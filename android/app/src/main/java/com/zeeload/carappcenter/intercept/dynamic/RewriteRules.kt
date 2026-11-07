package com.zeeload.carappcenter.intercept.dynamic

import com.zeeload.carappcenter.intercept.Json
import com.zeeload.carappcenter.intercept.J

/**
 * 声明式改写规则（不写代码就能改行为）——**协议换了只改这一份 JSON**。
 *
 * 设计目标：商店把字段名从 apkVersion 改成 version、把下载地址从 apkUrl 改成 downloadUrl、
 * 甚至把接口路径从 /app/appList 改成 /v3/apps，都只需要在局域网里下发改写规则，
 * 车机上的 App 不用重装（这点很关键：应用只能升级、不能重装）。
 */
data class RewriteRules(
    val version: Int = 1,
    /** 带这些字段的对象被当作「商店里的一个应用」 */
    val storeKeys: List<String> = listOf("apkPackageName", "packageName"),
    val versionFields: List<String> = listOf("apkVersion", "versionCode", "version"),
    val versionNameFields: List<String> = listOf("apkVersionName", "versionName"),
    val hashFields: List<String> = listOf("sha256", "apkSha256", "hash"),
    val md5Fields: List<String> = listOf("apkMd5", "md5"),
    val sizeFields: List<String> = listOf("apkSize", "size"),
    val signFields: List<String> = listOf("apkSign", "sign"),
    val urlFields: List<String> = listOf("apkUrl", "downloadUrl", "url", "apkDownloadUrl", "fileUrl", "packageUrl"),
    /** 命中这些正则的路径视为「下载 APK」 */
    val downloadPathPatterns: List<String> = listOf("(?i)\\.apk($|\\?)", "(?i)getapk", "(?i)/download"),
    val downloadContentTypes: List<String> = listOf(
        "application/vnd.android.package-archive",
        "application/octet-stream",
    ),
    /** 只拦这些域名（* = 全部）；用于「后期定向劫持某一家市场」 */
    val hosts: List<String> = listOf("*"),
    /** 是否给元数据条目补齐 sign 字段（有些商店没有这个字段，凭空加会被校验拒绝） */
    val addSignField: Boolean = true,
) {
    data class RewriteOutcome(
        val body: String,
        val matched: Int,
        val urls: List<String>,
        val version: Long?,
    ) {
        val changed: Boolean get() = matched > 0
    }

    fun matchesHost(host: String): Boolean {
        if (hosts.isEmpty() || hosts.contains("*")) return true
        val h = host.lowercase()
        return hosts.any { pattern ->
            val p = pattern.lowercase()
            h == p || h.endsWith(".$p") || globMatch(p, h)
        }
    }

    fun looksLikeStore(json: String): Boolean = storeKeys.any { json.contains("\"$it\"") }

    fun isDownload(path: String, contentType: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("json") || ct.contains("text/")) return false
        if (downloadContentTypes.any { ct.contains(it) }) return true
        val p = path.lowercase()
        return downloadPathPatterns.any { pattern -> runCatching { Regex(pattern).containsMatchIn(p) }.getOrDefault(false) }
    }

    fun rewrite(
        json: String,
        targetPackage: String,
        sha256: String,
        md5: String,
        sizeBytes: Long,
        versionCode: Long? = null,
        versionName: String? = null,
    ): RewriteOutcome {
        val root = Json.parse(json) ?: return RewriteOutcome(json, 0, emptyList(), null)
        var matched = 0
        val urls = ArrayList<String>()
        var version: Long? = null
        for (entry in entries(root)) {
            val pkg = packageOf(entry) ?: continue
            if (targetPackage != "ALL" && targetPackage != "*" && pkg != targetPackage) continue
            matched++
            val ver = versionCode ?: derivedVersion(sha256)
            version = ver
            put(entry, versionFields, J.Num(ver.toString()))
            put(entry, versionNameFields, J.Str(versionName ?: "9.9.${ver % 1000}"))
            if (sha256.length == 64) put(entry, hashFields, J.Str(sha256))
            if (md5.length == 32) put(entry, md5Fields, J.Str(md5))
            if (sizeBytes > 0) put(entry, sizeFields, J.Num(mbString(sizeBytes)))
            if (addSignField && signFields.isNotEmpty()) put(entry, signFields, J.Str("sign"))
            for (key in urlFields) {
                val url = entry.map[key]?.string() ?: continue
                if (url.isNotEmpty()) urls.add(pathOf(url))
            }
        }
        if (matched == 0) return RewriteOutcome(json, 0, emptyList(), null)
        return RewriteOutcome(Json.write(root), matched, urls.distinct(), version)
    }

    /** 商店已知应用摘要，供界面展示与「能否升级」判断。 */
    data class KnownApp(
        val packageName: String,
        val label: String,
        val versionCode: Long?,
        val versionName: String,
        val url: String,
    )

    fun knownApps(json: String): List<KnownApp> {
        val root = Json.parse(json) ?: return emptyList()
        return entries(root).mapNotNull { entry ->
            val pkg = packageOf(entry) ?: return@mapNotNull null
            KnownApp(
                packageName = pkg,
                label = entry.map["apkName"]?.string()
                    ?: entry.map["appName"]?.string()
                    ?: entry.map["name"]?.string().orEmpty(),
                versionCode = versionFields.firstNotNullOfOrNull { entry.map[it]?.long() },
                versionName = versionNameFields.firstNotNullOfOrNull { entry.map[it]?.string() }.orEmpty(),
                url = urlFields.firstNotNullOfOrNull { entry.map[it]?.string() }.orEmpty(),
            )
        }
    }

    fun entries(root: J): List<J.Obj> {
        val out = ArrayList<J.Obj>()
        fun walk(node: J) {
            when (node) {
                is J.Obj -> {
                    if (storeKeys.any { node.map.containsKey(it) }) out.add(node)
                    node.map.values.forEach { walk(it) }
                }
                is J.Arr -> node.list.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun packageOf(entry: J.Obj): String? =
        storeKeys.firstNotNullOfOrNull { entry.map[it]?.string() }?.takeIf { it.isNotBlank() }

    private fun put(entry: J.Obj, fields: List<String>, value: J) {
        fields.forEach { entry.map[it] = value }
    }

    companion object {
        fun default(): RewriteRules = RewriteRules()

        /** 给 Java 插件用（`default` 是 Java 关键字，不能直接调用）。 */
        @JvmStatic
        fun builtin(): RewriteRules = RewriteRules()

        fun fromJson(text: String): RewriteRules {
            val root = Json.parseObject(text) ?: return default()
            fun list(key: String, fallback: List<String>): List<String> {
                val arr = root.map[key] as? J.Arr ?: return fallback
                return arr.list.mapNotNull { it.string() }.ifEmpty { fallback }
            }
            return RewriteRules(
                version = root.map["version"]?.long()?.toInt() ?: 1,
                storeKeys = list("storeKeys", default().storeKeys),
                versionFields = list("versionFields", default().versionFields),
                versionNameFields = list("versionNameFields", default().versionNameFields),
                hashFields = list("hashFields", default().hashFields),
                md5Fields = list("md5Fields", default().md5Fields),
                sizeFields = list("sizeFields", default().sizeFields),
                signFields = list("signFields", default().signFields),
                urlFields = list("urlFields", default().urlFields),
                downloadPathPatterns = list("downloadPathPatterns", default().downloadPathPatterns),
                downloadContentTypes = list("downloadContentTypes", default().downloadContentTypes),
                hosts = list("hosts", default().hosts),
                addSignField = root.map["addSignField"]?.let { (it as? J.Bool)?.v } ?: true,
            )
        }

        fun derivedVersion(sha256: String): Long =
            if (sha256.length == 64) 1_800_000_000L + (sha256.take(7).toLong(16) % 300_000_000L)
            else 1_999_999_999L

        fun pathOf(url: String): String {
            val noQuery = url.substringBefore('?')
            if (!noQuery.startsWith("http")) return noQuery
            val afterScheme = noQuery.substringAfter("://")
            return if (afterScheme.contains('/')) "/" + afterScheme.substringAfter('/') else "/"
        }

        fun mbString(bytes: Long): String =
            String.format(java.util.Locale.US, "%.3f", bytes / 1048576.0)

        private fun globMatch(pattern: String, text: String): Boolean {
            if (!pattern.contains('*')) return false
            val body = pattern.split('*').joinToString(".*") { java.util.regex.Pattern.quote(it) }
            return runCatching { Regex("^$body$").matches(text) }.getOrDefault(false)
        }
    }
}
