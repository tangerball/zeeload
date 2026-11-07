package com.zeeload.carappcenter.intercept

/** 下载替换：把商店下载到的原包换成我们的 APK，并按客户端 Range 返回正确切片。 */
object DownloadReplacer {
    data class Slice(val bytes: ByteArray, val status: Int, val contentRange: String?, val total: Long)

    /** 只解析 `bytes=start-end` / `bytes=start-`；不合法或越界时按整包处理。 */
    fun parseRange(header: String?, total: Long): LongRange? {
        if (header.isNullOrBlank() || total <= 0) return null
        val m = Regex("""bytes=(\d+)-(\d*)""").find(header.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val endRaw = m.groupValues[2]
        val end = if (endRaw.isEmpty()) total - 1 else (endRaw.toLongOrNull() ?: return null)
        if (start < 0 || start >= total) return null
        return start..minOf(end, total - 1)
    }

    fun slice(full: ByteArray, header: String?): Slice {
        val total = full.size.toLong()
        val range = parseRange(header, total)
        if (range == null) {
            return Slice(full, 200, null, total)
        }
        val start = range.first.toInt()
        val end = range.last.toInt()
        return Slice(
            bytes = full.copyOfRange(start, end + 1),
            status = 206,
            contentRange = "bytes $start-$end/$total",
            total = total,
        )
    }

    /**
     * 判定该响应是否「可能是商店的 APK 下载」。
     * 商店把下载地址写成 query 形态（/v2/getApk?id=…）也认；JSON 元数据永远不替换。
     */
    fun looksLikeApkDownload(path: String, contentType: String?, contentDisposition: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("json") || ct.contains("text/html") || ct.contains("xml")) return false
        val p = path.substringBefore('?').lowercase()
        if (p.endsWith(".apk") || p.contains("apk")) return true
        if (ct.contains("package-archive")) return true
        if (contentDisposition?.lowercase()?.contains("attachment") == true) return true
        return false
    }
}
