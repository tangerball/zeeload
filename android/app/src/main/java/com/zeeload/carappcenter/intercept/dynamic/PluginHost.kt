package com.zeeload.carappcenter.intercept.dynamic

import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 加载并托管局域网下发的插件：
 *  · DexClassLoader 从缓存目录加载 plugin.dex；
 *  · 反射实例化 entry 类，必须实现 InterceptPlugin；
 *  · 所有调用都包在隔离层里——插件抛异常只会让它自己被停用，不影响拦截主链路。
 */
class PluginHost(private val parentClassLoader: ClassLoader = PluginHost::class.java.classLoader!!) {

    class LoadedPlugin(
        val instance: InterceptPlugin,
        val classLoader: DexClassLoader,
        val dir: File,
    )

    fun load(installed: ModuleRepository.Installed, optDir: File): LoadedPlugin {
        val dex = installed.dexPath
        require(dex.isFile) { "插件 dex 不存在：${dex.path}" }
        if (dex.canWrite()) dex.setWritable(false, false)
        optDir.mkdirs()
        val loader = DexClassLoader(dex.path, optDir.path, null, parentClassLoader)
        val clazz = Class.forName(installed.manifest.entryClass, true, loader)
        val instance = clazz.getDeclaredConstructor().newInstance() as? InterceptPlugin
            ?: throw IllegalArgumentException("${installed.manifest.entryClass} 没有实现 InterceptPlugin")
        return LoadedPlugin(GuardedPlugin(instance), loader, installed.dir)
    }

    /** 隔离层：异常计数超阈值自动熔断，避免坏插件把拦截拖死。 */
    private class GuardedPlugin(private val delegate: InterceptPlugin) : InterceptPlugin {
        private var failures = 0

        override fun id(): String = runCatching { delegate.id() }.getOrDefault("plugin")

        override fun onStoreJson(json: String, ctx: PluginContext): String? = guard("onStoreJson") {
            delegate.onStoreJson(json, ctx)
        }

        override fun onDownloadRequest(
            method: String,
            path: String,
            headers: Map<String, String>,
            ctx: PluginContext,
        ): Boolean = guard("onDownloadRequest") {
            delegate.onDownloadRequest(method, path, headers, ctx)
        } ?: false

        override fun onUpstreamRequest(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray,
            ctx: PluginContext,
        ): UpstreamOverride? = guard("onUpstreamRequest") {
            delegate.onUpstreamRequest(method, path, headers, body, ctx)
        }

        private inline fun <T> guard(name: String, block: () -> T): T? {
            if (failures >= MAX_FAILURES) return null
            return try {
                block()
            } catch (t: Throwable) {
                failures++
                Log.w("ZEEloadPlugin", "插件 $name 抛异常（第 $failures 次）：${t}")
                null
            }
        }

        private companion object {
            const val MAX_FAILURES = 5
        }
    }

    companion object {
        fun optDirFor(root: File, id: String, version: String): File = File(root, "opt/$id-$version")
    }
}
