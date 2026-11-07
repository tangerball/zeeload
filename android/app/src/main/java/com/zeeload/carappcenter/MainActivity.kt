package com.zeeload.carappcenter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeload.carappcenter.ui.CarTheme
import com.zeeload.carappcenter.ui.UiScale
import com.zeeload.carappcenter.ui.CarField
import androidx.compose.ui.platform.LocalConfiguration
import com.zeeload.carappcenter.ui.layoutFor
import com.zeeload.carappcenter.ui.CarPalette
import com.zeeload.carappcenter.ui.Card
import com.zeeload.carappcenter.ui.GhostButton
import com.zeeload.carappcenter.ui.KeyValue
import com.zeeload.carappcenter.ui.NavItem
import com.zeeload.carappcenter.ui.PageHeader
import com.zeeload.carappcenter.ui.Pill
import com.zeeload.carappcenter.ui.PrimaryButton
import com.zeeload.carappcenter.ui.StatusStrip
import com.zeeload.carappcenter.ui.Tone
import com.zeeload.carappcenter.ui.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Candidate(val apk: ApkInfo, val file: File, val sha256: String, val url: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as CarApp).services
        val presetUrl = intent?.getStringExtra("apk_url").orEmpty()
        setContent {
            var scale by remember { mutableStateOf(UiScale.of(services.settings.uiScale)) }
            val screenSpec = layoutFor(
                width = LocalConfiguration.current.screenWidthDp.dp,
                height = LocalConfiguration.current.screenHeightDp.dp,
            )
            CarTheme(UiScale.of(scale.value * screenSpec.fontBoost)) {
                CarScreen(
                    services = services,
                    presetUrl = presetUrl,
                    scale = scale,
                    onScaleChange = { next ->
                        scale = next
                        services.settings.uiScale = next.value
                    },
                    openPermissions = { name ->
                        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$name")
                        })
                    }
                )
            }
        }
    }
}

private val TABS = listOf("我的应用", "安装历史", "导入应用", "设置")

@Composable
private fun CarScreen(
    services: Services,
    presetUrl: String,
    scale: UiScale,
    onScaleChange: (UiScale) -> Unit,
    openPermissions: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val managed by services.db.apps().observe().collectAsState(initial = emptyList())
    val latestResult by services.db.apps().latestRecord().collectAsState(initial = null)
    val history by services.db.apps().observeHistory().collectAsState(initial = emptyList())

    var selected by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var address by remember { mutableStateOf(presetUrl) }
    var status by remember { mutableStateOf("就绪") }
    var statusTone by remember { mutableStateOf(Tone.Neutral) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var activeDownload by remember { mutableStateOf<Job?>(null) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var showSystem by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(0L) }
    var pendingLogs by remember { mutableStateOf(0) }
    var cacheLimit by remember { mutableStateOf(services.settings.cacheLimitMb.toString()) }
    var logAddress by remember { mutableStateOf(services.settings.logUrl) }
    var remoteEnabled by remember { mutableStateOf(services.settings.remoteEnabled) }
    var debugEnabled by remember { mutableStateOf(services.settings.debugUntil > System.currentTimeMillis()) }
    var armTimeout by remember { mutableStateOf(services.settings.armWatchTimeoutSec.toString()) }

    fun say(text: String, tone: Tone = Tone.Neutral) {
        status = text
        statusTone = tone
    }

    fun refreshStorage() {
        scope.launch(Dispatchers.IO) {
            val used = services.cache.sizeBytes()
            val pending = services.db.events().pendingCount()
            withContext(Dispatchers.Main) { cacheSize = used; pendingLogs = pending }
        }
    }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { services.packages.installed() }
        refreshStorage()
    }
    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 }

    LaunchedEffect(tab) {
        while (tab == 3) {
            debugEnabled = services.settings.debugUntil > System.currentTimeMillis()
            refreshStorage()
            delay(5_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = layoutFor(maxWidth, maxHeight)
        val sidebarWidth = layout.sidebarWidth
        val pagePadding = layout.pagePadding
        // 超宽屏（≥1500dp）时把内容限制在 contentMaxWidth 内并居中，避免卡片被拉成一条横带
        val contentWidth = maxWidth - sidebarWidth
        val sideInset = ((contentWidth - layout.contentMaxWidth) / 2).coerceAtLeast(0.dp)

        Row(Modifier.fillMaxSize().background(CarPalette.bg)) {
            // ------------------------------------------------ 侧栏（全站统一）
            Column(
                Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp, vertical = pagePadding),
            ) {
              Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), color = CarPalette.textStrong, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.app_subtitle),
                    color = CarPalette.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
                )
                TABS.forEachIndexed { index, label ->
                    NavItem(label, tab == index) { tab = index }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp)) {
                    Text("工具", color = CarPalette.dimmer, fontSize = 12.sp)
                }
                NavItem("动态拦截（应用市场）", false) {
                    ctx.startActivity(Intent(ctx, InterceptActivity::class.java))
                }
                NavItem("取得报告（扫码）", false) {
                    ctx.startActivity(Intent(ctx, com.zeeload.carappcenter.report.ReportActivity::class.java))
                }

              }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CarPalette.card)
                        .border(1.dp, CarPalette.line, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(status, color = statusTone.color(), fontSize = 13.sp, maxLines = 3)
                    latestResult?.let { record ->
                        // 只显示「谁 · 哪条路 · 谁装的」，完整结果在安装历史里
                        val channel = Regex("via=([a-z]+)").find(record.result)?.groupValues?.get(1)
                        val installer = Regex("installer=([^ ·]+)").find(record.result)?.groupValues?.get(1)
                        val compact = listOfNotNull(
                            record.packageName.substringAfterLast('.'),
                            channel?.let { "via=$it" },
                            installer,
                        ).joinToString(" · ")
                        Text(
                            "最近：$compact",
                            color = CarPalette.dimmer,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // ------------------------------------------------ 内容区
            Column(
                Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .border(0.5.dp, CarPalette.line)
                    .padding(
                        start = pagePadding + sideInset,
                        end = pagePadding + sideInset,
                        top = 14.dp,
                        bottom = 14.dp,
                    ),
            ) {
                when (tab) {
                    0 -> MyAppsTab(
                        layout = layout,
                        services = services,
                        managed = managed,
                        selected = selected,
                        onSelect = { record ->
                            selected = record.packageName
                            address = record.url
                            candidate?.let { services.cache.unpin(it.file) }
                            candidate = null
                        },
                        onAddNew = {
                            selected = ""
                            address = ""
                            candidate?.let { services.cache.unpin(it.file) }
                            candidate = null
                            say("填写 APK 地址后点「检查 APK」")
                        },
                        address = address,
                        onAddressChange = { address = it },
                        busy = busy,
                        progress = progress,
                        candidate = candidate,
                        permissionRefresh = permissionRefresh,
                        onPermissionRefresh = { permissionRefresh++ },
                        openPermissions = openPermissions,
                        onCheck = { record ->
                            candidate?.let { services.cache.unpin(it.file) }
                            candidate = null
                            busy = true
                            progress = "连接中"
                            say("正在下载并检查 APK…", Tone.Info)
                            activeDownload = scope.launch {
                                var downloadedFile: File? = null
                                try {
                                    if (record != null && record.url != address) {
                                        withContext(Dispatchers.IO) {
                                            services.db.apps().put(record.copy(url = address))
                                        }
                                    }
                                    val download = services.downloader.download(address) { done, total ->
                                        scope.launch(Dispatchers.Main) {
                                            progress = if (total > 0) "${done / 1024} / ${total / 1024} KB"
                                            else "${done / 1024} KB"
                                        }
                                    }
                                    downloadedFile = download.file
                                    val apk = withContext(Dispatchers.IO) {
                                        services.packages.fromArchive(download.file)
                                    } ?: error("文件不是可解析的单体 APK")
                                    val current = withContext(Dispatchers.IO) { services.packages.find(apk.packageName) }
                                    if (selected.isNotBlank() && selected != apk.packageName) {
                                        services.cache.unpin(download.file)
                                        error("包名与所选应用不同：${apk.packageName}")
                                    }
                                    if (current != null && apk.certificateSha256 != current.certificateSha256) {
                                        services.cache.unpin(download.file)
                                        error("签名不一致：已安装应用不会被覆盖")
                                    }
                                    if (current != null && apk.versionCode <= current.versionCode) {
                                        services.cache.unpin(download.file)
                                        error("版本未提高：已安装 ${current.versionCode}，下载 ${apk.versionCode}")
                                    }
                                    services.cache.pin(download.file)
                                    candidate = Candidate(apk, download.file, download.sha256, address)
                                    say("已检查：${apk.name} ${apk.versionName}，等待安装确认", Tone.Ok)
                                    services.logs.log("apk-checked", status, apk.packageName, address,
                                        apk.versionCode, download.httpStatus)
                                } catch (_: CancellationException) {
                                    say("下载已取消；可再次检查以续传", Tone.Warn)
                                } catch (error: Exception) {
                                    say(error.message ?: "检查更新失败", Tone.Danger)
                                    services.logs.log("download", status, selected, address,
                                        errorCode = error.javaClass.simpleName, level = "ERROR")
                                } finally {
                                    if (candidate == null) downloadedFile?.let {
                                        services.cache.unpin(it)
                                        services.cache.trim()
                                    }
                                    busy = false
                                    activeDownload = null
                                    refreshStorage()
                                }
                            }
                        },
                        onCancelDownload = { activeDownload?.cancel() },
                        onGoMarket = { ready ->
                            ctx.startActivity(
                                Intent(ctx, InterceptActivity::class.java).apply {
                                    putExtra("apk_path", ready.file.absolutePath)
                                    putExtra("apk_name", ready.apk.name)
                                    putExtra("mode", "UPDATE")
                                    putExtra("pkg", ready.apk.packageName)
                                }
                            )
                        },
                        onInstall = { ready ->
                            if (!services.installer.canInstall()) {
                                say("请先允许安装未知来源应用，然后返回点安装", Tone.Warn)
                                services.installer.openUnknownSources()
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        val id = withContext(Dispatchers.IO) {
                                            services.installer.install(ready.file, ready.apk, ready.url, ready.sha256)
                                        }
                                        say("安装会话 $id 已提交；等待系统结果", Tone.Info)
                                        services.cache.unpin(ready.file)
                                        candidate = null
                                    } catch (error: Exception) {
                                        say(error.message ?: "提交安装失败", Tone.Danger)
                                    } finally { busy = false }
                                }
                            }
                        },
                    )

                    1 -> HistoryScreen(history)

                    2 -> ImportTab(
                        installed = installed,
                        managed = managed,
                        showSystem = showSystem,
                        onToggleSystem = { showSystem = !showSystem },
                        onImport = { item ->
                            scope.launch(Dispatchers.IO) {
                                services.db.apps().put(
                                    ManagedApp(
                                        packageName = item.info.packageName,
                                        name = item.info.name,
                                        origin = "车机原有",
                                        versionCode = item.info.versionCode,
                                        versionName = item.info.versionName,
                                        certificateSha256 = item.info.certificateSha256,
                                        installedAt = System.currentTimeMillis()
                                    )
                                )
                                services.logs.log("import", "导入现有应用", item.info.packageName,
                                    versionCode = item.info.versionCode)
                                withContext(Dispatchers.Main) { say("已导入 ${item.info.name}", Tone.Ok) }
                            }
                        },
                    )

                    else -> SettingsTab(
                        services = services,
                        scale = scale,
                        onScaleChange = onScaleChange,
                        cacheSize = cacheSize,
                        pendingLogs = pendingLogs,
                        cacheLimit = cacheLimit,
                        onCacheLimitChange = { cacheLimit = it },
                        onSaveCacheLimit = {
                            services.settings.cacheLimitMb = cacheLimit.toIntOrNull() ?: 512
                            services.cache.trim()
                            refreshStorage()
                            say("缓存上限已保存", Tone.Ok)
                        },
                        onClearCache = {
                            val removed = services.cache.clear()
                            refreshStorage()
                            say("已清理 ${removed / 1048576} MB", Tone.Ok)
                        },
                        logAddress = logAddress,
                        onLogAddressChange = { logAddress = it },
                        onSaveLogAddress = {
                            if (logAddress.isBlank() ||
                                !(logAddress.startsWith("http://") || logAddress.startsWith("https://"))
                            ) {
                                say("请输入有效的 HTTP/HTTPS 地址", Tone.Warn)
                            } else {
                                services.settings.logUrl = logAddress
                                services.logs.scheduleOnce()
                                say("日志地址已保存", Tone.Ok)
                            }
                        },
                        remoteEnabled = remoteEnabled,
                        onRemoteChange = { on ->
                            if (on && services.settings.logUrl.isBlank()) {
                                say("请先保存日志接收地址", Tone.Warn)
                            } else if (on) {
                                remoteEnabled = true
                                services.settings.remoteEnabled = true
                                services.logs.scheduleOnce()
                                say("远程推送已开启", Tone.Ok)
                            } else {
                                remoteEnabled = false
                                services.logs.stopRemote()
                                debugEnabled = false
                                say("远程推送已关闭", Tone.Neutral)
                            }
                        },
                        debugEnabled = debugEnabled,
                        onDebugChange = { on ->
                            debugEnabled = on
                            services.settings.debugUntil =
                                if (on) System.currentTimeMillis() + 30L * 60 * 1000 else 0
                            services.logs.scheduleOnce()
                            say(if (on) "实时调试已开启 30 分钟" else "实时调试已关闭", Tone.Info)
                        },
                        armTimeout = armTimeout,
                        onArmTimeoutChange = { armTimeout = it },
                        onSaveArmTimeout = {
                            val v = armTimeout.toIntOrNull()
                            if (v == null || v !in 30..1800) {
                                say("拦截等待超时需在 30–1800 秒之间", Tone.Warn)
                            } else {
                                services.settings.armWatchTimeoutSec = v
                                say("拦截等待超时已设为 $v 秒", Tone.Ok)
                            }
                        },
                        lastUpload = services.settings.lastUpload,
                        onClearLogs = {
                            scope.launch(Dispatchers.IO) {
                                services.logs.clear()
                                withContext(Dispatchers.Main) {
                                    refreshStorage()
                                    say("本地诊断日志已清空", Tone.Ok)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 我的应用

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MyAppsTab(
    layout: com.zeeload.carappcenter.ui.LayoutSpec,
    services: Services,
    managed: List<ManagedApp>,
    selected: String,
    onSelect: (ManagedApp) -> Unit,
    onAddNew: () -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    busy: Boolean,
    progress: String,
    candidate: Candidate?,
    permissionRefresh: Int,
    onPermissionRefresh: () -> Unit,
    openPermissions: (String) -> Unit,
    onCheck: (ManagedApp?) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (Candidate) -> Unit,
    onGoMarket: (Candidate) -> Unit,
) {
    val record = managed.find { it.packageName == selected }
    PageHeader(
        title = "我的应用",
        subtitle = "通过地址检查并安装 APK；同包名更新要求签名一致、版本更高。",
    ) { Pill("${managed.size} 个已管理", Tone.Info) }
    Spacer(Modifier.height(12.dp))

    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.gap)) {
        // 左：应用列表 + 使用说明
        Column(
            Modifier
                .weight(if (layout.isExpanded) 0.34f else 0.42f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(title = "应用列表", subtitle = "选一个应用查看详情，或新增地址") {
                PrimaryButton("添加 APK 地址", Modifier.fillMaxWidth(), onClick = onAddNew)
                Column(Modifier.fillMaxWidth()) {
                    managed.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (item.packageName == selected) CarPalette.cardSoft.copy(alpha = 0.9f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(item) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    color = CarPalette.text,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.packageName,
                                    color = CarPalette.dim,
                                    fontSize = 12.sp,
                                    fontFamily = CarPalette.mono,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.origin,
                                    color = CarPalette.dimmer,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (item.packageName == selected) Pill("当前", Tone.Info)
                        }
                    }
                    if (managed.isEmpty()) {
                        Text("还没有管理的应用：点上面的按钮添加 APK 地址", color = CarPalette.dimmer, fontSize = 13.sp)
                    }
                }
            }

            Card(title = "怎么用", subtitle = "填地址 → 检查 → 安装") {
                Column {
                    Text(
                        "① 填地址 · APK 的 HTTP/HTTPS 直链\n" +
                            "② 检查 · 解析包名 / 版本 / 签名并校验\n" +
                            "③ 安装 · 提交系统安装会话，结果见状态卡",
                        color = CarPalette.text,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                    Text(
                        "同包名更新：签名一致 + 版本更高；装新包不限。",
                        color = CarPalette.dimmer,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // 右：安装 / 详情 / 权限
        Column(
            Modifier.weight(if (layout.isExpanded) 0.66f else 0.58f).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                title = if (record == null) "通过地址安装" else "检查更新",
                subtitle = if (record == null) "填入 APK 的 HTTP/HTTPS 地址"
                else "${record.packageName} · 已装 ${record.versionName} (${record.versionCode})",
            ) {
                CarField(
                    value = address,
                    onValueChange = onAddressChange,
                    label = "APK 在线地址（HTTP/HTTPS）",
                    mono = true,
                )
                Text(
                    "安装者：com.zeeload.carappcenter（本应用 · 第三方口径）—— 拿不到市场专属权限；" +
                        "需要市场权限请用「动态拦截 → UPDATE」让商店自己装。",
                    color = CarPalette.dim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = if (record == null) "检查 APK" else "检查更新",
                        enabled = !busy && address.isNotBlank(),
                    ) { onCheck(record) }
                    if (busy) GhostButton("取消下载") { onCancelDownload() }
                    candidate?.let { GhostButton("安装") { onInstall(it) } }
                    if (busy && progress.isNotBlank()) {
                        Text(progress, color = CarPalette.info, fontSize = 13.sp, fontFamily = CarPalette.mono)
                    }
                }
            }

            candidate?.let { c ->
                Card(title = "待安装包", subtitle = "已下载并校验，等待提交系统安装") {
                    KeyValue("应用", "${c.apk.name}")
                    KeyValue("包名", c.apk.packageName, mono = true)
                    KeyValue("安装者", "com.zeeload.carappcenter（本应用 · 非市场）", mono = true)
                    KeyValue("版本", "${c.apk.versionName} (${c.apk.versionCode})")
                    KeyValue("SHA-256", c.sha256.take(32) + "…", mono = true)
                    KeyValue("签名", c.apk.certificateSha256.ifBlank { "未读取" }.take(32), mono = true)
                    GhostButton("经市场安装（商店身份）") { onGoMarket(c) }
                    Text(
                        "由商店发起安装 → installer = 商店包名。点上面按钮会带这个包跳到「动态拦截」并自动按 UPDATE 武装；" +
                            "在那里勾选市场、启动拦截，再回车机商店点更新。",
                        color = CarPalette.dim, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }

            if (record != null) {
                val permissions = remember(record.packageName, permissionRefresh) {
                    services.packages.permissions(record.packageName)
                }
                Card(title = "权限与功能", subtitle = "系统权限仍由车机控制") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("打开系统权限设置") { openPermissions(record.packageName) }
                        GhostButton("刷新权限状态") { onPermissionRefresh() }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        permissions.forEach { p ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    p.name.substringAfterLast('.'),
                                    color = CarPalette.text,
                                    fontSize = 13.sp,
                                    fontFamily = CarPalette.mono,
                                )
                                Pill(
                                    p.status,
                                    when {
                                        p.status.contains("允许") || p.status.contains("granted", true) -> Tone.Ok
                                        p.status.contains("拒绝") -> Tone.Danger
                                        else -> Tone.Neutral
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 导入应用

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ImportTab(
    installed: List<InstalledApp>,
    managed: List<ManagedApp>,
    showSystem: Boolean,
    onToggleSystem: () -> Unit,
    onImport: (InstalledApp) -> Unit,
) {
    val visible = installed.filter { showSystem || !it.system }
    PageHeader(
        title = "导入应用",
        subtitle = "把车机里已有的应用加进管理列表；系统应用仅供查看。",
    ) {
        GhostButton(if (showSystem) "隐藏系统应用" else "显示系统应用") { onToggleSystem() }
    }
    Spacer(Modifier.height(12.dp))
    Card(
        title = "车机已安装 · ${visible.size}",
        subtitle = if (showSystem) "含系统应用" else "仅第三方应用",
        modifier = Modifier.weight(1f),
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(visible, key = { it.info.packageName }) { item ->
                val already = managed.any { it.packageName == item.info.packageName }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.info.name,
                            color = CarPalette.text,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.info.packageName} · ${item.info.versionName}",
                            color = CarPalette.dim,
                            fontSize = 12.sp,
                            fontFamily = CarPalette.mono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        already -> Pill("已管理", Tone.Ok)
                        item.system -> Pill("系统", Tone.Neutral)
                        else -> GhostButton("导入") { onImport(item) }
                    }
                }
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CarPalette.line),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 设置

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SettingsTab(
    services: Services,
    scale: UiScale,
    onScaleChange: (UiScale) -> Unit,
    cacheSize: Long,
    pendingLogs: Int,
    cacheLimit: String,
    onCacheLimitChange: (String) -> Unit,
    onSaveCacheLimit: () -> Unit,
    onClearCache: () -> Unit,
    logAddress: String,
    onLogAddressChange: (String) -> Unit,
    onSaveLogAddress: () -> Unit,
    remoteEnabled: Boolean,
    onRemoteChange: (Boolean) -> Unit,
    debugEnabled: Boolean,
    onDebugChange: (Boolean) -> Unit,
    armTimeout: String,
    onArmTimeoutChange: (String) -> Unit,
    onSaveArmTimeout: () -> Unit,
    lastUpload: String,
    onClearLogs: () -> Unit,
) {
    PageHeader(title = "设置", subtitle = "存储占用、日志接收与调试开关。") {
        Pill("${cacheSize / 1048576} MB 缓存", Tone.Neutral)
    }
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(title = "显示", subtitle = "车机屏幕远，字小了看不清——选一档字号，立即生效") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                UiScale.entries.forEach { option ->
                    if (option == scale) PrimaryButton(option.label) { onScaleChange(option) }
                    else GhostButton(option.label) { onScaleChange(option) }
                }
            }
            Text(
                "当前：${scale.label}（×${"%.2f".format(scale.value)}）· ${scale.hint}",
                color = CarPalette.dim,
                fontSize = 14.sp,
            )
        }

        Card(title = "存储", subtitle = "APK 缓存超限按最久未使用清理") {
            KeyValue("当前缓存", "${cacheSize / 1048576} MB")
            KeyValue("待上传事件", pendingLogs.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CarField(
                    value = cacheLimit,
                    onValueChange = onCacheLimitChange,
                    label = "缓存上限 MB（64-2048）",
                    modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                )
                PrimaryButton("保存上限") { onSaveCacheLimit() }
                GhostButton("清理缓存") { onClearCache() }
            }
        }

        Card(
            title = "动态拦截",
            subtitle = "武装后等商店安装的超时；等不到就自动解除武装，避免一直改商店元数据",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CarField(
                    value = armTimeout,
                    onValueChange = onArmTimeoutChange,
                    label = "等待超时（秒，30–1800）",
                    modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                )
                PrimaryButton("保存") { onSaveArmTimeout() }
                Text("默认 600 秒；下次武装生效", color = CarPalette.dim, fontSize = 12.sp)
            }

        }

        Card(title = "日志接收", subtitle = "把结构化事件推送到 HTTP/HTTPS 接收端") {
            CarField(
                value = logAddress,
                onValueChange = onLogAddressChange,
                label = "日志接收地址（HTTP/HTTPS）",
                mono = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("保存地址") { onSaveLogAddress() }
                SwitchRow("远程推送", remoteEnabled, onRemoteChange)
                SwitchRow("实时调试 30 分钟", debugEnabled, onDebugChange, enabled = remoteEnabled)
            }
            Text(
                "127.0.0.1 是车机本机；完整 URL 可能包含访问参数。",
                color = CarPalette.dimmer,
                fontSize = 12.sp,
            )
        }

        Card(
            title = "安装身份（决定能拿到哪些权限）",
            subtitle = "同一个 APK，走哪条路装进去，installerPackageName 完全不同",
        ) {
            IdentityRow("按地址安装（本页）", "com.zeeload.carappcenter", "第三方口径 · 无市场专属权限", Tone.Neutral)
            IdentityRow("动态拦截 → 商店自己装", "车机应用市场的包名", "官方渠道 · 权限跟着走", Tone.Ok)
            IdentityRow("adb pm install -i <市场>", "市场包名（仅记录）", "看厂商实现：白名单按 installer 才生效", Tone.Warn)
            Text(
                "伪装成市场不可行：SessionParams.setInstallerPackageName() 只是申请，createSession() 会校验" +
                    "「installer 包必须属于调用者 uid」，否则 SecurityException（Android 15 实测）。",
                color = CarPalette.dimmer,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Text(
                "核对命令：bash scripts/check-installer.sh <包名>   或   " +
                    "adb shell dumpsys package <包名> | grep installerPackageName",
                color = CarPalette.info,
                fontSize = 12.sp,
                fontFamily = CarPalette.mono,
                lineHeight = 18.sp,
            )
        }

        Card(title = "诊断", subtitle = "最近一次上传：${lastUpload.ifBlank { "尚未上传" }}") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("清空本地日志") { onClearLogs() }
            }
        }
    }
}

/** 「怎么用」里的步骤行：左序号徽标 + 右两行说明，避免右对齐换行。 */
@Composable
private fun StepRow(no: String, title: String, desc: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CarPalette.accent.copy(alpha = 0.16f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text(no, color = CarPalette.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = CarPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = CarPalette.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 三列一行的身份对照：路径 → installer → 权限口径。 */
@Composable
private fun IdentityRow(path: String, installer: String, perms: String, tone: Tone) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(path, color = CarPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Pill(
                when (tone) {
                    Tone.Ok -> "官方"
                    Tone.Warn -> "看实现"
                    else -> "第三方"
                },
                tone,
            )
        }
        Text(installer, color = CarPalette.dim, fontSize = 12.sp, fontFamily = CarPalette.mono)
        Text(perms, color = CarPalette.dimmer, fontSize = 12.sp)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = CarPalette.text, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CarPalette.accent,
                uncheckedTrackColor = CarPalette.line,
            ),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
