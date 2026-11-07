package com.zeeload.carappcenter.intercept.dynamic

import android.content.Context
import com.zeeload.carappcenter.intercept.ArmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 局域网模块管理（车机端入口）：
 *
 *   车机上填 Mac 的地址（http://192.168.2.1:8770） → 「拉取」
 *     → index.json（清单+签名）→ plugin.dex + rules.json
 *     → 校验签名与 sha256（公钥固化在 APK 的 assets/module-pubkey.txt）
 *     → 落盘缓存 → DexClassLoader 加载 → 立即生效（不用重装 App，也不用重启拦截）
 *
 * 失败时保留上一版可用模块（原子切换 + 指针文件），随时可回滚。
 */
class ModuleManager private constructor(
    private val context: Context,
    private val repo: ArmRepository,
) {
    data class Status(
        val baseUrl: String = "",
        val enabled: Boolean = false,
        val moduleId: String = "",
        val version: String = "",
        val rulesVersion: Int = 1,
        val note: String = "",
        val pluginLoaded: Boolean = false,
        val cached: List<String> = emptyList(),
        val message: String = "",
        val error: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())

    val status: StateFlow<Status> = _status.asStateFlow()

    private val prefs = context.getSharedPreferences("zeeload-module", Context.MODE_PRIVATE)

    init {
        _status.value = _status.value.copy(baseUrl = prefs.getString("module-url", "").orEmpty())
    }
    private val cacheRoot = File(context.filesDir, "modules-cache")
    private val pluginHost = PluginHost()
    private var loaded: PluginHost.LoadedPlugin? = null

    private val verifier: ModuleVerifier?
        get() = runCatching {
            val text = context.assets.open("module-pubkey.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            ModuleVerifier.loadPublicKey(text)?.let { ModuleVerifier(it) }
        }.getOrNull()

    private fun repository(): ModuleRepository {
        val v = verifier ?: throw IllegalStateException(
            "缺少模块公钥：把签名公钥放到 assets/module-pubkey.txt（Base64 或 PEM）"
        )
        return ModuleRepository(
            cacheRoot = cacheRoot,
            verifier = v,
            fetch = ::httpGet,
            log = { msg -> _status.value = _status.value.copy(message = msg) },
        )
    }

    /** 局域网拉取并按需启用。 */
    @Synchronized
    fun pull(baseUrl: String): Status {
        val url = baseUrl.trim().ifBlank { prefs.getString(KEY_URL, "").orEmpty() }
        if (url.isBlank()) return fail("先填局域网模块地址，例如 http://192.168.2.1:8770")
        prefs.edit().putString(KEY_URL, url).apply()
        return try {
            val installed = repository().install(url)
            activate(installed, url)
            _status.value = _status.value.copy(
                message = "已加载 ${installed.manifest.id}@${installed.manifest.version}",
                error = false,
                cached = repository().listInstalled().map { "${it.first}@${it.second}" },
            )
            _status.value
        } catch (e: Throwable) {
            fail("拉取失败：${e.message}")
        }
    }

    /** 启动时用缓存里的模块（断网也能用）。 */
    @Synchronized
    fun restore(): Status {
        val url = prefs.getString(KEY_URL, "").orEmpty()
        return try {
            val installed = repository().current()
            if (installed == null) {
                _status.value = _status.value.copy(baseUrl = url)
                return _status.value
            }
            activate(installed, url)
            _status.value = _status.value.copy(message = "已恢复缓存模块 ${installed.manifest.id}@${installed.manifest.version}")
            _status.value
        } catch (e: Throwable) {
            fail("恢复缓存模块失败：${e.message}")
        }
    }

    @Synchronized
    fun rollback(version: String): Status {
        return try {
            val repository = repository()
            val id = repository.listInstalled().firstOrNull { it.second == version }?.first
                ?: return fail("没有这个版本：$version")
            val installed = repository.activate(id, version) ?: return fail("该版本文件不完整")
            activate(installed, prefs.getString(KEY_URL, "").orEmpty())
            _status.value.copy(message = "已回滚到 $id@$version")
        } catch (e: Throwable) {
            fail("回滚失败：${e.message}")
        }
    }

    @Synchronized
    fun disable(): Status {
        loaded = null
        repo.applyDynamic(RewriteRules.default(), null, "已停用动态模块，回到内置规则")
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        return _status.value.copy(enabled = false, pluginLoaded = false, moduleId = "", version = "")
    }

    private fun activate(installed: ModuleRepository.Installed, url: String): Status {
        val plugin = runCatching {
            pluginHost.load(installed, PluginHost.optDirFor(context.filesDir, installed.manifest.id, installed.manifest.version))
        }.onFailure {
            repo.onEvent("error", "插件加载失败：${it.message}（规则仍然生效）")
        }.getOrNull()
        loaded = plugin
        repo.applyDynamic(
            installed.rules,
            plugin?.instance,
            "动态模块 ${installed.manifest.id}@${installed.manifest.version} 已生效" +
                (if (plugin == null) "（仅规则）" else "（规则 + 插件）"),
        )
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _status.value = _status.value.copy(
            baseUrl = url,
            enabled = true,
            moduleId = installed.manifest.id,
            version = installed.manifest.version,
            rulesVersion = installed.rules.version,
            note = installed.manifest.note,
            pluginLoaded = plugin != null,
            cached = runCatching { repository().listInstalled().map { "${it.first}@${it.second}" } }
                .getOrDefault(emptyList()),
            error = false,
        )
        return _status.value
    }

    private fun fail(message: String): Status {
        repo.onEvent("error", message)
        _status.value = _status.value.copy(message = message, error = true)
        return _status.value
    }

    private fun httpGet(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "ZEEload-ModuleLoader/1")
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }

    companion object {
        private const val KEY_URL = "module-url"
        private const val KEY_ENABLED = "module-enabled"

        @Volatile private var instance: ModuleManager? = null

        fun get(context: Context): ModuleManager =
            instance ?: synchronized(this) {
                instance ?: ModuleManager(context.applicationContext, ArmRepository.get(context)).also { instance = it }
            }
    }
}
