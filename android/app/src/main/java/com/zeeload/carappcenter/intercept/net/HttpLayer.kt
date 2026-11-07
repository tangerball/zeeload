package com.zeeload.carappcenter.intercept.net

import com.zeeload.carappcenter.intercept.DownloadReplacer
import com.zeeload.carappcenter.intercept.StoreRewriter
import java.io.ByteArrayOutputStream
import java.util.Locale

data class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val pathOnly: String get() = target.substringBefore('?')

    fun serialize(extraHeaders: List<Pair<String, String>> = emptyList()): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(target).append(' ').append(version).append("\r\n")
        val skip = extraHeaders.map { it.first.lowercase() }.toSet()
        headers.filterNot { it.first.lowercase() in skip }.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + body
    }
}

data class HttpResponseHead(val status: Int, val reason: String, val headers: List<Pair<String, String>>) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun serialize(): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

object HttpCodec {
    private const val MAX_HEAD = 64 * 1024

    /** 返回 (请求, 已消费字节数)；数据不完整返回 null。 */
    fun parseRequest(data: ByteArray): Pair<HttpRequest, Int>? {
        val headEnd = indexOfHeadEnd(data) ?: return null
        if (headEnd > MAX_HEAD) return null
        val head = String(data, 0, headEnd, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size < 3) return null
        val headers = ArrayList<Pair<String, String>>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        val contentLength = headers.firstOrNull { it.first.equals("content-length", true) }
            ?.second?.toIntOrNull() ?: 0
        val transferChunked = headers.any {
            it.first.equals("transfer-encoding", true) && it.second.contains("chunked", true)
        }
        val bodyStart = headEnd + 4
        if (transferChunked) return null // 商店请求几乎不用 chunked；不支持就交回上游透传
        if (data.size < bodyStart + contentLength) return null
        val body = data.copyOfRange(bodyStart, bodyStart + contentLength)
        return HttpRequest(parts[0], parts[1], parts[2], headers, body) to (bodyStart + contentLength)
    }

    fun parseResponseHead(data: ByteArray): Pair<HttpResponseHead, Int>? {
        val headEnd = indexOfHeadEnd(data) ?: return null
        val head = String(data, 0, headEnd, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val statusParts = lines.firstOrNull()?.split(' ') ?: return null
        val status = statusParts.getOrNull(1)?.toIntOrNull() ?: return null
        val reason = statusParts.drop(2).joinToString(" ")
        val headers = ArrayList<Pair<String, String>>()
        lines.drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        return HttpResponseHead(status, reason, headers) to (headEnd + 4)
    }

    private fun indexOfHeadEnd(data: ByteArray): Int? {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) return i
        }
        return null
    }
}

object LocalResponses {
    fun build(
        status: Int,
        reason: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): ByteArray {
        val head = HttpResponseHead(status, reason, headers).serialize()
        return head + body
    }

    fun text(status: Int, message: String): ByteArray = build(
        status,
        if (status == 200) "OK" else "Error",
        listOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to message.toByteArray().size.toString(),
            "Connection" to "close",
        ),
        message.toByteArray(),
    )

    /** 用本地 APK 直接回应下载请求（含 Range），不经上游。 */
    fun apk(apk: ByteArray, rangeHeader: String?): ByteArray {
        val slice = DownloadReplacer.slice(apk, rangeHeader)
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Content-Type" to StoreRewriter.APK_CONTENT_TYPE)
        headers.add("Content-Length" to slice.bytes.size.toString())
        headers.add("Accept-Ranges" to "bytes")
        headers.add("Connection" to "close")
        slice.contentRange?.let { headers.add("Content-Range" to it) }
        return build(slice.status, if (slice.status == 206) "Partial Content" else "OK", headers, slice.bytes)
    }

    fun formatMb(bytes: Long): String = String.format(Locale.US, "%.2f", bytes / 1048576.0)
}
