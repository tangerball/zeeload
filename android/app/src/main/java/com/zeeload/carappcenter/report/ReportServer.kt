package com.zeeload.carappcenter.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 车机上的报告服务：手机扫码后由它把报告递给手机。
 *
 * · 只监听局域网端口（默认 8888），只允许 GET；
 * · 报告用 6 位验证码换取（二维码里带 `?c=`，手输也能进）；
 * · 每次下载都带 Content-Length，手机端才能显示进度；
 * · 车机界面靠 [state] 显示「手机已连接 / 下载中 / 完成 / 中断」。
 */
class ReportServer(
    private val cache: ReportCache,
    private val port: Int = DEFAULT_PORT,
) {
    data class ServerState(
        val running: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val requestedPort: Int = DEFAULT_PORT,
        val portSwitched: Boolean = false,
        val code: String = "",
        val clientIp: String = "",
        val pageOpened: Boolean = false,
        val downloading: Boolean = false,
        val done: Boolean = false,
        val aborted: Boolean = false,
        val bytesSent: Long = 0,
        val totalBytes: Long = 0,
        val message: String = "",
        val lastError: String = "",
        val urls: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(ServerState(port = port))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private val lock = Any()
    private var lastDownloadAt = 0L
    private var preferredPort: Int = port

    val codeDisplay: String
        get() = formatCode(_state.value.code)

    /**
     * 启动服务。端口默认 8888，被占用时自动顺延（8889、8890…，最多试 [PORT_ATTEMPTS] 个），
     * 并在 [ServerState.portSwitched] 里标出来，车机界面会提示「8888 被占用，已改用 xxxx」。
     */
    fun start(html: String, fromPort: Int = preferredPort, attempts: Int = PORT_ATTEMPTS): ServerState {
        synchronized(lock) {
            if (server != null) return _state.value
            val code = newCode()
            var bound: ServerSocket? = null
            var actualPort = fromPort
            var lastFailure: String? = null
            for (candidate in fromPort until (fromPort + attempts)) {
                if (candidate !in 1024..65535) break
                try {
                    bound = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", candidate))
                    }
                    actualPort = candidate
                    break
                } catch (e: IOException) {
                    lastFailure = e.message
                }
            }
            val socket = bound
            if (socket == null) {
                _state.value = _state.value.copy(
                    running = false,
                    requestedPort = fromPort,
                    portSwitched = false,
                    lastError = "端口 $fromPort 起 $attempts 个都被占用（$lastFailure），请到设置里换端口",
                )
                return _state.value
            }
            val switched = actualPort != fromPort
            server = socket
            if (actualPort != preferredPort) preferredPort = actualPort
            _state.value = ServerState(
                running = true,
                port = actualPort,
                requestedPort = fromPort,
                portSwitched = switched,
                code = code,
                urls = localUrls(actualPort, code),
                message = if (switched) "$fromPort 被占用，已改用 $actualPort，等待手机扫码" else "等待手机扫码",
            )
            thread(name = "zeeload-report-accept", isDaemon = true) {
                while (server != null) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true) { handle(client, html) }
                }
            }
            return _state.value
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { server?.close() }
            server = null
            _state.value = _state.value.copy(running = false, downloading = false, message = "服务已停止")
        }
    }

    fun setHtmlCache(html: String) {
        // html 由 Activity 从 assets 读入后传入；这里不额外保存
    }

    fun markError(message: String) {
        _state.value = _state.value.copy(lastError = message, message = message)
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(client: Socket, html: String) {
        client.use { socket ->
            socket.soTimeout = 15000
            val input: InputStream = socket.getInputStream()
            val requestLine = readRequestLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                respond(socket, 405, "text/plain; charset=utf-8", "只支持 GET\n".toByteArray())
                return
            }
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val ip = (socket.inetAddress?.hostAddress ?: "").removePrefix("/")
            when (path) {
                "/", "/r" -> {
                    val code = query["c"].orEmpty()
                    if (code.isNotEmpty() && code != _state.value.code) {
                        respond(socket, 403, "text/html; charset=utf-8", forbiddenHtml().toByteArray(Charsets.UTF_8))
                        return
                    }
                    _state.value = _state.value.copy(
                        clientIp = ip,
                        pageOpened = true,
                        message = "手机已连接（$ip）",
                    )
                    respond(socket, 200, "text/html; charset=utf-8", renderPage(html, code).toByteArray(Charsets.UTF_8))
                }
                "/report" -> {
                    if (query["c"] != _state.value.code) {
                        respond(socket, 403, "text/plain; charset=utf-8", "验证码不对\n".toByteArray())
                        return
                    }
                    serveReport(socket, ip)
                }
                "/progress" -> {
                    val stage = query["stage"].orEmpty()
                    val bytes = query["bytes"]?.toLongOrNull() ?: 0L
                    _state.value = when (stage) {
                        "done" -> _state.value.copy(
                            done = true, downloading = false, bytesSent = bytes,
                            message = "下载完成（${formatBytes(bytes)}）",
                        )
                        "open" -> _state.value.copy(clientIp = ip, pageOpened = true, message = "手机已连接（$ip）")
                        else -> _state.value
                    }
                    respond(socket, 204, "text/plain", ByteArray(0))
                }
                "/health" -> respond(socket, 200, "text/plain; charset=utf-8", "ok\n".toByteArray())
                else -> respond(socket, 404, "text/plain; charset=utf-8", "not found\n".toByteArray())
            }
        }
    }

    private fun serveReport(socket: Socket, ip: String) {
        val entry = cache.current()
        if (entry == null) {
            respond(socket, 503, "text/plain; charset=utf-8", "报告还没生成\n".toByteArray())
            return
        }
        lastDownloadAt = System.currentTimeMillis()
        _state.value = _state.value.copy(
            clientIp = ip,
            downloading = true,
            done = false,
            aborted = false,
            bytesSent = 0,
            totalBytes = entry.size,
            message = "正在下载（${formatBytes(entry.size)}）",
        )
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/zip\r\n")
            append("Content-Length: ${entry.size}\r\n")
            append("Content-Disposition: attachment; filename=\"${entry.fileName}\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        var sent = 0L
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(head.toByteArray(Charsets.US_ASCII))
            entry.file.inputStream().use { file ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = file.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    sent += n
                    if (sent % (1024 * 1024) < n) {
                        _state.value = _state.value.copy(bytesSent = sent)
                    }
                }
            }
            out.flush()
            _state.value = _state.value.copy(
                downloading = false,
                done = sent == entry.size,
                aborted = sent != entry.size,
                bytesSent = sent,
                message = if (sent == entry.size) "下载完成（${formatBytes(sent)}）" else "下载中断（${formatBytes(sent)}）",
            )
        } catch (e: IOException) {
            _state.value = _state.value.copy(
                downloading = false,
                aborted = true,
                bytesSent = sent,
                message = "下载中断（${formatBytes(sent)}）",
            )
        }
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun readRequestLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (prev == '\r'.code && b == '\n'.code) break
            sb.append(b.toChar())
            if (sb.length > 2048) return null
            prev = b
        }
        // 读完剩余头部
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        return sb.toString().trim()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 4096) return null
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(text: String): String = runCatching {
        java.net.URLDecoder.decode(text, "UTF-8")
    }.getOrDefault(text)

    private fun renderPage(html: String, code: String): String {
        val entry = cache.current()
        val state = _state.value
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(entry?.createdAt ?: System.currentTimeMillis()))
        return html
            .replace("{{DEVICE}}", entry?.meta?.get("device")?.ifBlank { "车机本机报告" } ?: "车机本机报告")
            .replace("{{TS}}", stamp)
            .replace("{{SIZE}}", entry?.let { formatBytes(it.size) } ?: "—")
            .replace("{{SIZE_BYTES}}", (entry?.size ?: 0L).toString())
            .replace("{{EXC}}", entry?.meta?.get("exceptions")?.let { "$it 段" } ?: "见报告")
            .replace("{{LOGS}}", entry?.meta?.get("logs")?.let { "$it 行" } ?: "见报告")
            .replace("{{VERSION}}", entry?.meta?.get("version")?.ifBlank { "ZEEload" } ?: "ZEEload")
            .replace("{{FILE_NAME}}", entry?.fileName ?: "zeeload-report.zip")
            .replace("{{DOWNLOAD_PATH}}", "/report?c=$code")
            .replace("{{PROGRESS_PATH}}", "/progress")
    }

    private fun forbiddenHtml(): String = """
        <!doctype html><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <body style="background:#0b0f14;color:#e8eef6;font:16px -apple-system,sans-serif;padding:28px">
        <h2>验证码不匹配</h2>
        <p style="color:#93a1b3">请在车机页面上重新扫码，或手动输入车机显示的 6 位验证码。</p>
        <form action="/r" method="get">
          <input name="c" inputmode="numeric" placeholder="输入验证码" style="padding:12px;width:60%;background:#151b23;color:#fff;border:1px solid #232c38;border-radius:10px">
          <button style="padding:12px 18px;margin-left:8px;background:#2ea043;color:#fff;border:0;border-radius:10px">进入</button>
        </form></body>
    """.trimIndent()

    companion object {
        const val DEFAULT_PORT = 8888

        /** 8888 被占用时最多往后顺延几个端口 */
        const val PORT_ATTEMPTS = 10

        fun newCode(): String = (100000 + (Math.random() * 900000).toInt()).toString()

        fun formatCode(code: String): String =
            if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }

        /** 本机所有 IPv4 局域网地址，用来生成「扫码打不开就换这个地址」的提示。 */
        fun localUrls(port: Int, code: String): List<String> {
            val out = ArrayList<String>()
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .forEach { addr ->
                        val host = addr.hostAddress ?: return@forEach
                        if (host.startsWith("127.")) return@forEach
                        out.add("http://$host:$port/r?c=$code")
                    }
            }
            return out.ifEmpty { listOf("http://127.0.0.1:$port/r?c=$code") }
        }
    }
}
