package com.zeeload.carappcenter

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeload.carappcenter.ui.CarPalette
import com.zeeload.carappcenter.ui.Card
import com.zeeload.carappcenter.ui.CarField
import com.zeeload.carappcenter.ui.GhostButton
import com.zeeload.carappcenter.ui.Pill
import com.zeeload.carappcenter.ui.Tone
import com.zeeload.carappcenter.ui.PrimaryButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.zeeload.carappcenter.CarApp
import com.zeeload.carappcenter.intercept.ArmMode
import com.zeeload.carappcenter.intercept.MarketInstallWatch
import com.zeeload.carappcenter.intercept.ArmRepository
import com.zeeload.carappcenter.intercept.InterceptEvent
import com.zeeload.carappcenter.intercept.MarketApp
import com.zeeload.carappcenter.intercept.MarketCatalog
import com.zeeload.carappcenter.intercept.ZeeloadVpnService
import com.zeeload.carappcenter.intercept.dynamic.ModuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 车机端「动态拦截」面板：选应用市场 → 选 APK → 武装 → 去商店点更新。
 * 全部在本机完成（VpnService + 本地 TLS 解密 + HTTP 改写），不依赖电脑。
 */
class InterceptActivity : ComponentActivity() {

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
        else repo.onEvent("error", "用户未授权 VPN")
    }

    private lateinit var repo: ArmRepository
    private lateinit var catalog: MarketCatalog
    private var presetApk: String = ""
    private var presetPkg: String = ""
    private var presetName: String = ""
    private var watchTimeoutSec: Long = 600
    private var presetMode: ArmMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ArmRepository.get(this)
        catalog = MarketCatalog(this)
        presetApk = intent?.getStringExtra("apk_path").orEmpty()
        presetPkg = intent?.getStringExtra("pkg").orEmpty()
        presetName = intent?.getStringExtra("apk_name").orEmpty()
        intent?.getStringExtra("markets")?.takeIf { it.isNotBlank() }?.let { raw ->
            val set = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (set.isNotEmpty()) repo.setMarkets(set)
        }
        val fromSettings = runCatching {
            com.zeeload.carappcenter.ArmSettings.timeoutSec(this)
        }.getOrDefault(600)
        watchTimeoutSec = (intent?.getIntExtra("watch_timeout_sec", 0) ?: 0)
            .takeIf { it > 0 }?.toLong() ?: fromSettings.toLong()
        presetMode = intent?.getStringExtra("mode")
            ?.let { raw -> runCatching { ArmMode.valueOf(raw.uppercase()) }.getOrNull() }
        // 支持命令行/脚本直接下发模块地址：am start … --es module_url http://<mac>:8770
        intent?.getStringExtra("module_url")?.takeIf { it.isNotBlank() }?.let { url ->
            Thread { runCatching { ModuleManager.get(this).pull(url) } }.apply { isDaemon = true }.start()
        }
        // 断网也能用：开机直接用缓存里的模块（没有就保持内置规则）
        Thread { runCatching { ModuleManager.get(this).restore() } }.apply { isDaemon = true }.start()
        setContent {
            val scale = runCatching {
                com.zeeload.carappcenter.ui.UiScale.of((application as CarApp).services.settings.uiScale)
            }.getOrDefault(com.zeeload.carappcenter.ui.UiScale.Large)
            com.zeeload.carappcenter.ui.CarTheme(scale) {
                Surface(modifier = Modifier.fillMaxSize(), color = CarPalette.bg) {
                    InterceptScreen(
                        onBack = { finish() },
                        repo = repo,
                        catalog = catalog,
                        presetApk = presetApk,
                        presetPkg = presetPkg,
                        presetName = presetName,
                        watchTimeoutSec = watchTimeoutSec,
                        presetMode = presetMode,
                        onToggleVpn = { wanted -> toggleVpn(wanted) },
                        onOpenStore = { pkg -> catalog.launchStore(pkg) },
                    )
                }
            }
        }
    }

    private fun toggleVpn(wanted: Boolean) {
        if (!wanted) {
            startService(Intent(this, ZeeloadVpnService::class.java).setAction(ZeeloadVpnService.ACTION_STOP))
            repo.onEvent("vpn", "已请求停止拦截")
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else startVpn()
    }

    private fun startVpn() {
        val intent = Intent(this, ZeeloadVpnService::class.java).apply {
            action = ZeeloadVpnService.ACTION_START
            putStringArrayListExtra(
                ZeeloadVpnService.EXTRA_MARKETS,
                ArrayList(repo.state.value.marketPackages),
            )
        }
        // 前台上下文里启动即可：不强制走「5 秒内必须 startForeground」的约束
        startService(intent)
    }
}

@Composable
private fun InterceptScreen(
    onBack: () -> Unit,
    repo: ArmRepository,
    catalog: MarketCatalog,
    presetApk: String,
    presetPkg: String,
    presetName: String,
    presetMode: ArmMode?,
    watchTimeoutSec: Long,
    onToggleVpn: (Boolean) -> Unit,
    onOpenStore: (String) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repo.state.collectAsState()
    val events by repo.events.collectAsState()
    val knownApps by repo.knownApps.collectAsState()
    val markets: List<MarketApp> = remember { catalog.candidates() }

    var apkPath by remember { mutableStateOf(presetApk.ifBlank { state.apk.path }) }
    var mode by remember { mutableStateOf(presetMode ?: state.mode) }
    var shellPkg by remember { mutableStateOf("") }
    var installedVc by remember { mutableStateOf("") }
    var inspecting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var vpnOn by remember { mutableStateOf(ZeeloadVpnService.activeMarkets.isNotEmpty()) }
    var marketsExpanded by remember { mutableStateOf(false) }
    var storePicker by remember { mutableStateOf(false) }
    val settings = (context.applicationContext as? CarApp)?.services?.settings
    // 候选：已勾选的市场优先，其次是检测到的全部候选（含系统商店），去重
    val storeChoices: List<MarketApp> = remember(markets, state.marketPackages) {
        val selected = markets.filter { it.packageName in state.marketPackages }
        val rest = markets.filterNot { it.packageName in state.marketPackages }
        (selected + rest).distinctBy { it.packageName }
    }

    // 武装之后盯住目标包：一旦商店把它装完，就写一条 via=market 的安装记录
    LaunchedEffect(state.armedAt, state.targetPackage, state.mode) {
        if (!state.fresh() || state.targetPackage.isBlank() || state.targetPackage == "ALL") return@LaunchedEffect
        val baseline = MarketInstallWatch.installedVersionCode(context, state.targetPackage) ?: 0L
        repo.onEvent("watch", "等待商店安装 ${state.targetPackage}（当前 vc=$baseline）")
        val armedAtSnapshot = state.armedAt
        val detected = MarketInstallWatch.watch(
            context = context,
            targetPackage = state.targetPackage,
            baseline = baseline,
            timeoutMs = watchTimeoutSec * 1000,
        )
        if (detected != null) {
            val text = MarketInstallWatch.record(context, detected)
            // 收尾：装完就解除武装并停掉拦截，别让它继续改商店流量
            repo.disarm()
            vpnOn = false
            onToggleVpn(false)
            message = "对账：$text（已自动解除武装并停止拦截）"
            repo.onEvent("install", text)
            repo.onEvent("done", "对账完成：已解除武装 + 停止拦截")
        } else if (repo.state.value.armedAt == armedAtSnapshot && repo.state.value.fresh()) {
            // 等不到商店安装：自动解除，别让"半武装"一直挂着（TTL 到期前也会一直改元数据）
            repo.disarm()
            val text = "等待 ${watchTimeoutSec} 秒未检测到商店安装，已自动解除武装（可重新武装）"
            message = text
            repo.onEvent("warn", text)
        }
    }

    // 从「应用中心 → 经市场安装」带过来时：市场勾选后自动武装（也可手动点「武装」）
    LaunchedEffect(presetApk, state.marketPackages) {
        if (presetApk.isBlank()) return@LaunchedEffect
        if (state.marketPackages.isEmpty()) {
            message = "已带入 APK：${File(presetApk).name}（${presetMode ?: ArmMode.UPDATE} 模式）→ 先在 ① 勾选应用市场，再点武装"
            return@LaunchedEffect
        }
        if (state.fresh() && state.apk.path == presetApk) {
            message = "已武装：${state.mode.name} → ${state.targetPackage}（去车机商店点更新）"
            return@LaunchedEffect
        }
        message = "正在武装（读取 APK 元信息）…"
        val armed = withContext(Dispatchers.IO) {
            runCatching {
                repo.arm(
                    file = File(presetApk),
                    requested = presetMode ?: ArmMode.UPDATE,
                    shellPackage = "",
                    markets = state.marketPackages,
                )
            }
        }
        armed.onSuccess { next ->
            message = "已带入并武装：${next.mode.name} → ${next.targetPackage}（接下来启动拦截，去商店点更新）"
        }.onFailure { error ->
            message = "自动武装失败：${error.message ?: error.javaClass.simpleName}"
            repo.onEvent("warn", "自动武装失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageSpec = com.zeeload.carappcenter.ui.layoutFor(maxWidth, maxHeight)
    Column(Modifier.fillMaxSize().padding(pageSpec.pagePadding)) {
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier
                .width(if (pageSpec.isExpanded) 780.dp else 560.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (presetApk.isNotBlank()) {
                HandoffBanner(
                    apkName = presetName.ifBlank { File(presetApk).name },
                    targetPkg = presetPkg,
                    mode = presetMode ?: ArmMode.UPDATE,
                    marketsChosen = state.marketPackages.isNotEmpty(),
                    armedNow = state.fresh(),
                )
            }
            com.zeeload.carappcenter.ui.BackBar(
                title = "动态拦截（车机本机）",
                subtitle = "只拦你勾选的应用市场；安装仍由商店完成，权限按官方渠道发放。",
            ) { onBack() }

            Section("① 选择应用市场（per-app VPN）") {
                Column(Modifier.fillMaxWidth()) {
                markets.take(if (marketsExpanded) 20 else 5).forEach { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (app.packageName in state.marketPackages) {
                                    state.marketPackages - app.packageName
                                } else {
                                    state.marketPackages + app.packageName
                                }
                                repo.setMarkets(next)
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = app.packageName in state.marketPackages,
                            onCheckedChange = null,
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text("${app.label}  ${if (app.isSystem) "· 系统" else ""}", color = CarPalette.text, fontSize = 16.sp)
                            Text(app.packageName, color = CarPalette.dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (markets.size > 5) {
                    GhostButton(
                        if (marketsExpanded) "收起（只看前 5 个）"
                        else "展开全部 ${markets.size} 个候选市场"
                    ) { marketsExpanded = !marketsExpanded }
                }
                }
            }

            Section("② 要装的 APK") {
                CarField(
                    value = apkPath,
                    onValueChange = { apkPath = it },
                    label = "APK 绝对路径",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(text = "检查 APK", onClick = {
                        inspecting = true
                            message = runCatching {
                                val meta = repo.inspect(File(apkPath.trim()))
                                buildString {
                                    append("包名 ${meta.packageName}\n")
                                    append("versionCode ${meta.versionCode} / ${meta.versionName}\n")
                                    append("签名 ${meta.signerSha256.take(24)}…\n")
                                    append("已装版本 ${repo.installedVersionCode(meta.packageName) ?: "未安装"}")
                                }
                            }.getOrElse { "读取失败：${it.message}" }
                        inspecting = false
                    })
                    if (inspecting) CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
                if (message.isNotBlank()) {
                    Text(message, color = CarPalette.info, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Section("③ 武装（决定注入目标与版本口径）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArmMode.values().forEach { m ->
                        if (m == mode) {
                            PrimaryButton(m.name) { mode = m }
                        } else {
                            GhostButton(m.name) { mode = m }
                        }
                    }
                }
                Text(
                    when (mode) {
                        ArmMode.AUTO -> "auto：商店已有该包 → 同包升级；否则 → 换包名新装"
                        ArmMode.UPDATE -> "update：同包升级（签名一致 + versionCode 更高，保留数据）"
                        ArmMode.NEW -> "new：换包名新装（下面填一个已装应用当外壳）"
                    },
                    color = CarPalette.dim, fontSize = 14.sp,
                )
                Text(
                    "安装由商店发起 → installerPackageName = 商店包名，权限按官方渠道发放" +
                        if (mode == ArmMode.NEW) "（外壳模式同样由商店安装）" else "",
                    color = CarPalette.info, fontSize = 13.sp, lineHeight = 19.sp,
                )
                if (mode != ArmMode.UPDATE) {
                    CarField(
                        value = shellPkg,
                        onValueChange = { shellPkg = it },
                        label = "外壳应用包名（留空 = ALL）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CarField(
                    value = installedVc,
                    onValueChange = { installedVc = it },
                    label = "已装 versionCode（可选，商店元数据滞后时用）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = "武装",
                        enabled = apkPath.isNotBlank(),
                        onClick = {
                            message = runCatching {
                                val next = repo.arm(
                                    file = File(apkPath.trim()),
                                    requested = mode,
                                    shellPackage = shellPkg.trim(),
                                    markets = state.marketPackages,
                                    installedVersionOverride = installedVc.trim().toLongOrNull(),
                                )
                                "已武装：${next.mode.name} → ${next.targetPackage}"
                            }.getOrElse { it.message ?: "武装失败" }
                        },
                    )
                    GhostButton("解除武装") { repo.disarm(); message = "已解除武装" }
                }
                Text(
                    if (state.fresh()) {
                        "当前：${state.mode.name} → ${state.targetPackage} · 剩余 ${state.remainingSeconds()}s"
                    } else {
                        "当前：未武装"
                    },
                    color = if (state.fresh()) CarPalette.accentSoft else CarPalette.dim,
                    fontSize = 15.sp,
                )
                state.warnings.forEach { Text("· $it", color = CarPalette.warn, fontSize = 13.sp) }
            }

            Section("④ 动态模块（局域网热加载）") {
                ModuleSection(repo)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("商店已知应用（拦截到的实时数据）") {
                if (knownApps.isEmpty()) {
                    Text("还没有数据：先在车机打开商店 → 检查更新 → 点开任意应用详情页", color = CarPalette.dim, fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(knownApps.values.toList()) { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(app.label.ifBlank { app.packageName }, color = CarPalette.text, fontSize = 14.sp)
                                Text(app.packageName, color = CarPalette.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("vc=${app.versionCode}", color = CarPalette.info, fontSize = 12.sp)
                                GhostButton("用它的包名") { shellPkg = app.packageName }
                            }
                        }
                    }
                }
            }
            Section("事件") {
                Column(Modifier.fillMaxWidth()) {
                    events.take(8).forEach { e: InterceptEvent ->
                        Text(
                            "${e.time}  ${e.kind}  ${e.detail}",
                            color = when (e.kind) {
                                "error" -> CarPalette.danger
                                "warn" -> CarPalette.warn
                                "inject", "replace", "arm", "install", "done" -> CarPalette.accentSoft
                                else -> CarPalette.dim
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Divider(color = CarPalette.line)
                    }
                }
            }
        }
    }

        if (storePicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { storePicker = false },
                title = { Text("选择要打开的应用市场", color = CarPalette.text, fontSize = 16.sp) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "车机换了市场/换了包名，直接在这里挑；下次沿用你的选择。",
                            color = CarPalette.dim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        storeChoices.forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        storePicker = false
                                        settings?.storePackage = app.packageName
                                        val ok = onOpenStore(app.packageName)
                                        message = if (ok) "已打开 ${app.label}" else "${app.packageName} 打不开，请手动进商店"
                                        if (!ok) repo.onEvent("warn", "无法自动打开 ${app.packageName}，请手动进入商店")
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.label.ifBlank { app.packageName },
                                        color = CarPalette.text,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        app.packageName,
                                        color = CarPalette.dim,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (app.packageName in state.marketPackages) Pill("已勾选", Tone.Ok)
                            }
                        }
                    }
                },
                confirmButton = {
                    GhostButton("关闭") { storePicker = false }
                },
            )
        }

        // 固定底部操作条：拦截开关永远可见
        Spacer(Modifier.height(10.dp))
        InterceptBar(
            state = state,
            vpnOn = vpnOn,
            onStart = { vpnOn = true; onToggleVpn(true) },
            message = message,
            onStoreOpenFailed = { pkg ->
                message = "$pkg 打不开，请手动进商店"
                repo.onEvent("warn", "无法自动打开 $pkg，请手动进入商店")
            },
            storeLabel = settings?.storePackage?.takeIf { it.isNotBlank() }
                ?.let { pkg -> markets.firstOrNull { it.packageName == pkg }?.label ?: pkg },
            onPickStore = { storePicker = true },
            onStop = { vpnOn = false; onToggleVpn(false) },
            onOpenStore = onOpenStore,
        )
    }
    }
}

@Composable
private fun ModuleSection(repo: ArmRepository) {
    val context = LocalContext.current
    val manager = remember { ModuleManager.get(context) }
    val status by manager.status.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(status.baseUrl.ifBlank { "http://192.168.2.1:8770" }) }
    LaunchedEffect(status.baseUrl) {
        if (status.baseUrl.isNotBlank()) url = status.baseUrl
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarField(
            value = url,
            onValueChange = { url = it },
            label = "局域网模块地址（Mac 上跑 tools/module-server/serve.py）",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("拉取并启用") {
                scope.launch { withContext(Dispatchers.IO) { manager.pull(url) } }
            }
            GhostButton("用缓存启用") {
                scope.launch { withContext(Dispatchers.IO) { manager.restore() } }
            }
            GhostButton("停用（回内置）") { manager.disable() }
        }
        Text(
            buildString {
                append(if (status.enabled) "已启用 ${status.moduleId}@${status.version}" else "未启用动态模块")
                if (status.enabled) append(" · 规则 v${status.rulesVersion} · 插件${if (status.pluginLoaded) "已加载" else "未加载"}")
                if (status.note.isNotBlank()) append("\n${status.note}")
            },
            color = if (status.enabled) CarPalette.accentSoft else CarPalette.dim,
            fontSize = 14.sp,
        )
        if (status.message.isNotBlank()) {
            Text(status.message, color = if (status.error) CarPalette.danger else CarPalette.info, fontSize = 13.sp)
        }
        if (status.cached.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.cached.takeLast(4).forEach { full ->
                    val version = full.substringAfter('@')
                    GhostButton("回滚 $full") {
                        scope.launch { withContext(Dispatchers.IO) { manager.rollback(version) } }
                    }
                }
            }
        }
        Text(
            "模块 = 规则(rules.json) + 可选插件(plugin.dex)，用固定公钥验签后加载；协议一变就在 Mac 上改包热更新，车机不重装。",
            color = CarPalette.dim, fontSize = 13.sp,
        )
    }
}

/** 底部固定操作条：拦截开关不随内容滚走。 */
@Composable
private fun InterceptBar(
    state: com.zeeload.carappcenter.intercept.ArmState,
    vpnOn: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenStore: (String) -> Boolean,
    onStoreOpenFailed: (String) -> Unit,
    onPickStore: () -> Unit,
    storeLabel: String? = null,
    message: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(CarPalette.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryButton(if (vpnOn) "重新启动拦截" else "启动拦截", enabled = state.marketPackages.isNotEmpty()) { onStart() }
        GhostButton("停止拦截") { onStop() }
        GhostButton(if (storeLabel != null) "打开商店：$storeLabel" else "打开商店") { onPickStore() }
        Text(
            when {
                message.isNotBlank() -> message
                state.marketPackages.isEmpty() -> "先在 ① 里勾选要拦截的应用市场"
                else -> "已选 ${state.marketPackages.size} 个市场" + if (vpnOn) " · 拦截中" else ""
            },
            color = when {
                message.contains("失败") || message.contains("无法") || message.contains("打不开") -> CarPalette.warn
                message.startsWith("已武装") || message.startsWith("对账") -> CarPalette.accentSoft
                else -> CarPalette.dim
            },
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 3,
        )
    }
}

private fun quote(text: String): String = "\"" + text
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

/** 顶部横幅：这次是从应用中心带包进来的，把上下文说全。 */
@Composable
private fun HandoffBanner(
    apkName: String,
    targetPkg: String,
    mode: ArmMode,
    marketsChosen: Boolean,
    armedNow: Boolean,
) {
    val statusText = when {
        armedNow -> "已武装 · 去车机商店点更新即可"
        marketsChosen -> "待武装 · 点下面「武装」"
        else -> "先勾选要拦截的应用市场"
    }
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(CarPalette.card)
            .border(
                1.dp,
                if (armedNow) CarPalette.accent.copy(alpha = 0.6f) else CarPalette.info.copy(alpha = 0.5f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("从应用中心带入", color = CarPalette.info, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                apkName,
                color = CarPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            buildString {
                if (targetPkg.isNotBlank()) append(targetPkg).append(" · ")
                append(mode.name).append(" · ").append(statusText)
            },
            color = if (armedNow) CarPalette.accentSoft else CarPalette.dim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(title = title) { content() }
}
