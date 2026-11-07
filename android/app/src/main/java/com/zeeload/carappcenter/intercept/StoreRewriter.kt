package com.zeeload.carappcenter.intercept

import com.zeeload.carappcenter.intercept.dynamic.RewriteRules

/**
 * 内置改写规则的瘦门面：真正实现在 [RewriteRules]（可被局域网下发的规则替换）。
 * 保留这层是为了让旧调用点与测试保持稳定。
 */
object StoreRewriter {
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    data class Injection(
        val body: String,
        val injected: Boolean,
        val matched: Int,
        val urls: List<String>,
        val version: Long?,
    )

    fun looksLikeStore(body: String): Boolean = RewriteRules.default().looksLikeStore(body)

    fun inject(
        body: String,
        targetPackage: String,
        sha256: String,
        md5: String,
        sizeBytes: Long,
        versionCode: Long? = null,
        versionName: String? = null,
        rules: RewriteRules = RewriteRules.default(),
    ): Injection {
        val out = rules.rewrite(body, targetPackage, sha256, md5, sizeBytes, versionCode, versionName)
        return Injection(out.body, out.changed, out.matched, out.urls, out.version)
    }

    fun knownApps(body: String, rules: RewriteRules = RewriteRules.default()): List<RewriteRules.KnownApp> =
        rules.knownApps(body)

    fun derivedVersion(sha256: String): Long = RewriteRules.derivedVersion(sha256)

    fun pathOf(url: String): String = RewriteRules.pathOf(url)
}
