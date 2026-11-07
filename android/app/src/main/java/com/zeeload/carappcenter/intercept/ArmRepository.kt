package com.zeeload.carappcenter.intercept

import android.content.Context
import com.zeeload.carappcenter.intercept.dynamic.InterceptPlugin
import com.zeeload.carappcenter.intercept.dynamic.PluginContext
import com.zeeload.carappcenter.intercept.dynamic.RewriteRules
import com.zeeload.carappcenter.intercept.net.DynamicProvider
import com.zeeload.carappcenter.intercept.net.InterceptRules
import com.zeeload.carappcenter.CarApp
import com.zeeload.carappcenter.InterceptEventRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条拦截事件（界面上实时滚动显示）。 */
data class InterceptEvent(val time: String, val kind: String, val detail: String)

/**
 * 车机端「武装状态」的唯一持有者：持久化 + 给网络引擎提供规则 + 给界面提供状态。
 * 电脑端控制台的能力（选目标 / 换包 / 解除 / 学商店应用）在这里等价实现。
 */
class ArmRepository(context: Context) : InterceptRules, DynamicProvider {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val inspector = ApkInspector(appContext)

    private val _state = MutableStateFlow(ArmState.fromProperties(prefs.getString(KEY_STATE, null)))
    val state: StateFlow<ArmState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<InterceptEvent>>(emptyList())
    val events: StateFlow<List<InterceptEvent>> = _events.asStateFlow()

    private val _knownApps = MutableStateFlow<Map<String, RewriteRules.KnownApp>>(emptyMap())
    val knownApps: StateFlow<Map<String, RewriteRules.KnownApp>> = _knownApps.asStateFlow()

    /** 局域网下发的规则（默认 = 内置规则）；换规则立即生效，不用重装、不用重启拦截。 */
    @Volatile private var dynamicRules: RewriteRules = RewriteRules.default()
    @Volatile private var dynamicPlugin: InterceptPlugin? = null

    @Volatile private var apkCache: ByteArray? = null
    @Volatile private var apkCachePath: String = ""

    fun inspect(file: File): ApkMeta = inspector.inspect(file)

    fun installedVersionCode(packageName: String): Long? = inspector.installedVersionCode(packageName)
    fun installedSigner(packageName: String): String? = inspector.installedSigner(packageName)

    /**
     * 武装：auto 自动判升级/新装，并在升级前做版本与签名体检。
     * @throws IllegalArgumentException 不能升级时抛出（消息直接给用户看）
     */
    @Synchronized
    fun arm(
        file: File,
        requested: ArmMode,
        shellPackage: String,
        ttlSeconds: Long = 3600,
        markets: Set<String> = _state.value.marketPackages,
        installedVersionOverride: Long? = null,
    ): ArmState {
        val meta = inspector.inspect(file)
        val known = _knownApps.value[meta.packageName]
        val installedVc = installedVersionOverride
            ?: known?.versionCode
            ?: inspector.installedVersionCode(meta.packageName)
        val installedSigner = inspector.installedSigner(meta.packageName)
        val decision = ArmPlanner.decide(
            requested = requested,
            apk = meta,
            storeKnowsApkPackage = known != null || installedVc != null,
            shellPackage = shellPackage,
            installedVersionCode = installedVc,
            installedSignerSha256 = installedSigner,
            ttlSeconds = ttlSeconds,
        )
        val next = decision.state.copy(marketPackages = markets)
        apkCache = null
        apkCachePath = meta.path
        persist(next)
        onEvent(
            "arm",
            "${next.mode.name} ${next.targetPackage} ← ${meta.label.ifBlank { meta.packageName }} " +
                "vc=${meta.versionCode} (${meta.sha256.take(12)}…)"
        )
        next.warnings.forEach { onEvent("warn", it) }
        return next
    }

    @Synchronized
    fun disarm(): ArmState {
        val next = _state.value.copy(enabled = false, armedAt = 0)
        apkCache = null
        persist(next)
        onEvent("disarm", "已解除武装，下载按原样透传")
        return next
    }

    @Synchronized
    fun setMarkets(packages: Set<String>): ArmState {
        val next = _state.value.copy(marketPackages = packages)
        persist(next)
        onEvent("market", if (packages.isEmpty()) "未选择应用市场" else "拦截 ${packages.size} 个市场：${packages.joinToString()}")
        return next
    }

    /** 装上动态模块（规则 + 可选插件）；plugin 为 null 时只用规则。 */
    fun applyDynamic(rules: RewriteRules, plugin: InterceptPlugin?, label: String) {
        dynamicRules = rules
        dynamicPlugin = plugin
        onEvent("module", "$label · 规则 v${rules.version}" + (if (plugin != null) " + 插件 ${plugin.id()}" else ""))
    }

    // ---------------------------------------------------------------- DynamicProvider

    override fun rewriteRules(): RewriteRules = dynamicRules

    override fun plugin(): InterceptPlugin? = dynamicPlugin

    override fun pluginContext(): PluginContext {
        val s = _state.value
        return PluginContext(
            armed = s.fresh(),
            mode = s.mode.name,
            targetPackage = s.targetPackage,
            apkPackage = s.apk.packageName,
            apkVersionCode = s.apk.versionCode,
            apkVersionName = s.apk.versionName,
            apkSha256 = s.apk.sha256,
            apkMd5 = s.apk.md5,
            apkSize = s.apk.size,
            log = { msg -> onEvent("plugin", msg) },
        )
    }

    fun clearKnownApps() {
        _knownApps.value = emptyMap()
    }

    private fun persist(state: ArmState) {
        _state.value = state
        prefs.edit().putString(KEY_STATE, state.toProperties()).apply()
    }

    // ---------------------------------------------------------------- InterceptRules

    override fun armed(): Boolean = _state.value.fresh()

    override fun targetPackage(): String = _state.value.targetPackage

    override fun mode(): ArmMode = _state.value.mode

    override fun apkBytes(): ByteArray? {
        val state = _state.value
        if (!state.enabled) return null
        val path = state.apk.path
        if (path.isBlank()) return null
        val cached = apkCache
        if (cached != null && apkCachePath == path) return cached
        return runCatching {
            File(path).readBytes().also {
                apkCache = it
                apkCachePath = path
            }
        }.getOrNull()
    }

    override fun versionOverride(): Pair<Long?, String?>? {
        val state = _state.value
        if (state.mode != ArmMode.UPDATE) return null
        val vc = state.apk.versionCode.takeIf { it > 0 }
        val vn = state.apk.versionName.takeIf { it.isNotBlank() }
        return if (vc == null && vn == null) null else vc to vn
    }

    override fun apkSha256(): String = _state.value.apk.sha256

    override fun apkMd5(): String = _state.value.apk.md5

    override fun apkSize(): Long = _state.value.apk.size

    override fun onStoreJson(json: String) {
        val parsed = dynamicRules.knownApps(json)
        if (parsed.isEmpty()) return
        val merged = _knownApps.value.toMutableMap()
        parsed.forEach { merged[it.packageName] = it }
        _knownApps.value = merged
    }

    override fun onEvent(kind: String, detail: String) {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val next = (listOf(InterceptEvent(stamp, kind, detail)) + _events.value).take(200)
        _events.value = next
        // 持久化：跨重启也能在「安装历史 → 这条是怎么来的」里关联上
        persistScope.launch {
            runCatching {
                val db = (appContext as? CarApp)?.services?.db ?: return@runCatching
                db.interceptEvents().add(InterceptEventRecord(ts = now, kind = kind, detail = detail))
                val limit = runCatching { (appContext as? CarApp)?.services?.settings?.interceptEventLimit }
                    .getOrNull() ?: MAX_PERSISTED
                val count = db.interceptEvents().count()
                if (count > limit) db.interceptEvents().deleteOldest(count - limit)
                db.interceptEvents().deleteBefore(now - 7L * 24 * 3600 * 1000)
            }
        }
    }

    /** 清空内存里的环形事件（库里由调用方清）。 */
    fun clearInMemoryEvents() {
        _events.value = emptyList()
    }

    /** 从库里取时间窗内的事件（历史页关联用）。 */
    suspend fun eventsInWindow(from: Long, to: Long, limit: Int = 20): List<InterceptEventRecord> =
        runCatching {
            (appContext as? CarApp)?.services?.db?.interceptEvents()?.inWindow(from, to, limit)
                ?: emptyList()
        }.getOrDefault(emptyList())

    companion object {
        private const val PREFS = "zeeload-intercept"
        private const val KEY_STATE = "arm-state"
        private const val MAX_PERSISTED = 2000
        @Volatile private var instance: ArmRepository? = null

        fun get(context: Context): ArmRepository =
            instance ?: synchronized(this) {
                instance ?: ArmRepository(context).also { instance = it }
            }
    }
}
