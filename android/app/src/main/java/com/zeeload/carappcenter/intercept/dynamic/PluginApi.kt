package com.zeeload.carappcenter.intercept.dynamic

/**
 * 局域网热加载插件的对外契约（dex 里的实现类必须实现这个接口）。
 *
 * 目的：App 只能升级、不能重装（车机策略），而商店协议随时会变。
 * 所以「注入 / 绕过 / 劫持」的逻辑做成可热更新插件：
 *   · 规则变化 → 只换 rules.json（连代码都不用写）；
 *   · 逻辑变化（新协议、新签名字段、下载分片方式）→ 换 plugin.dex。
 *
 * 所有方法都可能被网络线程调用，实现必须线程安全、不能抛异常（抛了会被隔离并停用）。
 */
interface InterceptPlugin {
    fun id(): String

    /**
     * 看到一段商店 JSON（已确认含应用条目）。
     * 返回改写后的 JSON；返回 null 表示「我不处理」，交回内置规则。
     */
    fun onStoreJson(json: String, ctx: PluginContext): String?

    /**
     * 收到一个疑似 APK 下载请求。
     * 返回 true = 用本地 APK 直接回应（支持 Range）；false = 正常转发到上游。
     */
    fun onDownloadRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        ctx: PluginContext,
    ): Boolean

    /**
     * 转发前可改写请求（换域名/路径/头/体）——用于「绕过」类改动，例如
     * 把新版接口改回旧版、把 sign 请求换成不带参数的形态。
     * 返回 null = 不做改动。
     */
    fun onUpstreamRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
        ctx: PluginContext,
    ): UpstreamOverride?
}

/** 插件拿到的这次拦截的上下文（只读快照，避免插件误改内部状态）。 */
data class PluginContext(
    val armed: Boolean,
    val mode: String,
    val targetPackage: String,
    val apkPackage: String,
    val apkVersionCode: Long,
    val apkVersionName: String,
    val apkSha256: String,
    val apkMd5: String,
    val apkSize: Long,
    val log: (String) -> Unit,
) {
    /** 给 Java 插件用的日志入口（避免直接碰 Kotlin 函数类型）。 */
    fun logLine(message: String) {
        log(message)
    }
}

/** 请求改写结果；空字符串/空数组表示「这一项不改」。 */
data class UpstreamOverride(
    val method: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val replaceBody: Boolean = false,
)
