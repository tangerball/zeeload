package com.zeeload.carappcenter.intercept.net

import com.zeeload.carappcenter.intercept.ArmMode
import com.zeeload.carappcenter.intercept.DownloadReplacer
import com.zeeload.carappcenter.intercept.StoreRewriter
import com.zeeload.carappcenter.intercept.dynamic.InterceptPlugin
import com.zeeload.carappcenter.intercept.dynamic.PluginContext
import com.zeeload.carappcenter.intercept.dynamic.RewriteRules
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** 拦截运行时需要知道的一切（由 ArmRepository 提供，引擎只依赖这个接口）。 */
interface InterceptRules {
    fun armed(): Boolean
    fun targetPackage(): String
    fun mode(): ArmMode
    /** 要装的那个 APK 的内容（懒加载 + 缓存）。 */
    fun apkBytes(): ByteArray?
    /** UPDATE 模式返回 APK 自身的 versionCode/versionName；NEW 返回 null（走哈希派生）。 */
    fun versionOverride(): Pair<Long?, String?>?
    fun apkSha256(): String
    fun apkMd5(): String
    fun apkSize(): Long
    /** 学习到商店返回的应用条目（供界面显示、判断能否升级）。 */
    fun onStoreJson(json: String)
    fun onEvent(kind: String, detail: String)
}

/** 动态能力来源：局域网下发的规则 + 插件（ArmRepository 实现）。 */
interface DynamicProvider {
    fun rewriteRules(): RewriteRules
    fun plugin(): InterceptPlugin?
    fun pluginContext(): PluginContext
}

/** 上游 TLS 连接：与真实服务器握手（信任任意证书——我们自己就是中间人）。 */
private class Upstream(private val host: String) : Closeable {
    val socket = Socket()
    private val tls = TlsClientHello(host)
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val buf = ByteArray(16 * 1024)

    fun connect(timeoutMs: Int = 8000) {
        socket.connect(InetSocketAddress(host, 443), timeoutMs)
        socket.soTimeout = 15000
        input = socket.getInputStream()
        output = socket.getOutputStream()
        var out = tls.start()
        var guard = 0
        while (!tls.isHandshakeDone()) {
            if (guard++ > 32) throw IOException("TLS 握手轮次过多")
            if (out.isNotEmpty()) {
                output.write(out)
                output.flush()
            }
            val n = input.read(buf)
            if (n <= 0) throw IOException("握手期上游断开")
            out = tls.feedCipher(buf.copyOf(n))
        }
        if (out.isNotEmpty()) {
            output.write(out)
            output.flush()
        }
    }

    fun writePlain(bytes: ByteArray) {
        output.write(tls.seal(bytes))
        output.flush()
    }

    /** 读到一段明文；上游关闭返回 null。 */
    fun readPlainBlocking(): ByteArray? {
        val already = tls.readPlain()
        if (already.isNotEmpty()) return already
        while (true) {
            val n = input.read(buf)
            if (n <= 0) return null
            val toSend = tls.feedCipher(buf.copyOf(n))
            if (toSend.isNotEmpty()) {
                output.write(toSend)
                output.flush()
            }
            val plain = tls.readPlain()
            if (plain.isNotEmpty()) return plain
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * 一条被解密的连接上跑 HTTP/1.1：每个请求独立处理，处理完关连接。
 *  · 下载请求 + 已武装 → 不上游，直接用本地 APK 回应（支持 Range）；
 *  · 商店 JSON → 上游取回后改写（注入新版本）；
 *  · 其它 → 原样流式透传。
 */
class MitmSession(
    private val host: String,
    private val rules: InterceptRules,
    private val dynamic: DynamicProvider,
    private val emitPlain: (ByteArray) -> Unit,
    private val closeAfterResponse: () -> Unit,
) {
    private val pending = ByteArrayOutputStream()
    private var done = false

    fun onPlain(data: ByteArray) {
        if (done) return
        pending.write(data)
        var guard = 0
        while (guard++ < 8) {
            val all = pending.toByteArray()
            val parsed = HttpCodec.parseRequest(all) ?: return
            val (req, consumed) = parsed
            pending.reset()
            if (consumed < all.size) pending.write(all, consumed, all.size - consumed)
            handle(req)
            if (done) return
        }
    }

    private fun handle(req: HttpRequest) {
        val rulesNow = runCatching { dynamic.rewriteRules() }.getOrDefault(RewriteRules.default())
        val plugin = dynamic.plugin()
        if (rules.armed() && rulesNow.matchesHost(host)) {
            val hijack = hijackDownload(req, rulesNow, plugin)
            if (hijack) return
        }
        forward(req)
        done = true
        closeAfterResponse()
    }

    /** 是否把这个下载请求就地接管（换成本地 APK）。 */
    private fun hijackDownload(
        req: HttpRequest,
        rulesNow: RewriteRules,
        plugin: InterceptPlugin?,
    ): Boolean {
        val headerMap = req.headers.associate { it.first to it.second }
        val pluginSays = runCatching {
            plugin?.onDownloadRequest(req.method, req.pathOnly, headerMap, dynamic.pluginContext()) ?: false
        }.getOrDefault(false)
        val ruleSays = rulesNow.isDownload(req.pathOnly, req.header("Accept"))
        if (!pluginSays && !ruleSays) return false
        val apk = rules.apkBytes() ?: return false
        val range = req.header("Range")
        emitPlain(LocalResponses.apk(apk, range))
        rules.onEvent(
            "replace",
            "${req.pathOnly} Range=${range ?: "无"} (本地 ${LocalResponses.formatMb(apk.size.toLong())} MB" +
                if (pluginSays) " · 插件" else " · 规则",
        )
        done = true
        closeAfterResponse()
        return true
    }

    private fun forward(req: HttpRequest) {
        try {
            Upstream(host).use { upstream ->
                upstream.connect()
                var outgoing = req.serialize(listOf("Connection" to "close"))
                val plugin = dynamic.plugin()
                if (plugin != null) {
                    val override = runCatching {
                        plugin.onUpstreamRequest(
                            req.method, req.pathOnly,
                            req.headers.associate { it.first to it.second },
                            req.body, dynamic.pluginContext(),
                        )
                    }.getOrNull()
                    if (override != null) {
                        val patched = HttpRequest(
                            method = override.method.ifBlank { req.method },
                            target = override.path.ifBlank { req.target },
                            version = req.version,
                            headers = if (override.headers.isEmpty()) req.headers
                            else req.headers.toMutableList().also { list ->
                                override.headers.forEach { (k, v) ->
                                    list.removeAll { it.first.equals(k, true) }
                                    list.add(k to v)
                                }
                            },
                            body = if (override.replaceBody) override.body else req.body,
                        )
                        outgoing = patched.serialize(listOf("Connection" to "close"))
                        rules.onEvent("plugin", "改写上游请求 ${patched.method} ${patched.target}")
                    }
                }
                upstream.writePlain(outgoing)

                val headBuf = ByteArrayOutputStream()
                var respHead: HttpResponseHead? = null
                var leftover = ByteArray(0)
                while (respHead == null) {
                    val plain = upstream.readPlainBlocking() ?: break
                    headBuf.write(plain)
                    val all = headBuf.toByteArray()
                    if (all.size > 256 * 1024) {
                        emitPlain(LocalResponses.text(502, "响应头过大"))
                        return
                    }
                    val parsed = HttpCodec.parseResponseHead(all)
                    if (parsed != null) {
                        respHead = parsed.first
                        leftover = all.copyOfRange(parsed.second, all.size)
                    }
                }
                val head = respHead ?: run {
                    emitPlain(LocalResponses.text(502, "上游无响应"))
                    return
                }

                val isStoreJson = head.header("Content-Type")?.contains("json", true) == true
                val contentLength = head.header("Content-Length")?.toIntOrNull()
                val chunked = head.header("Transfer-Encoding")?.contains("chunked", true) == true

                if (isStoreJson && !chunked && (contentLength ?: 0) in 1..(4 * 1024 * 1024)) {
                    val body = readBody(upstream, leftover, contentLength!!)
                    if (body == null) {
                        emitPlain(LocalResponses.text(502, "上游响应不完整"))
                        return
                    }
                    val text = String(body, Charsets.UTF_8)
                    rules.onStoreJson(text)
                    var outBody = body
                    val rulesNow = runCatching { dynamic.rewriteRules() }.getOrDefault(RewriteRules.default())
                    val pluginOut = if (rules.armed()) runCatching {
                        dynamic.plugin()?.onStoreJson(text, dynamic.pluginContext())
                    }.getOrNull() else null
                    if (pluginOut != null && pluginOut != text) {
                        outBody = pluginOut.toByteArray(Charsets.UTF_8)
                        rules.onEvent("inject", "${rules.targetPackage()} → 插件改写（${text.length} → ${outBody.size} 字节）")
                    } else if (rules.armed() && rulesNow.matchesHost(host) && rulesNow.looksLikeStore(text)) {
                        val injection = StoreRewriter.inject(
                            body = text,
                            targetPackage = rules.targetPackage(),
                            sha256 = rules.apkSha256(),
                            md5 = rules.apkMd5(),
                            sizeBytes = rules.apkSize(),
                            versionCode = rules.versionOverride()?.first,
                            versionName = rules.versionOverride()?.second,
                            rules = rulesNow,
                        )
                        if (injection.injected) {
                            outBody = injection.body.toByteArray(Charsets.UTF_8)
                            rules.onEvent(
                                "inject",
                                "${rules.targetPackage()} → v${injection.version} (${injection.matched} 条 / ${injection.urls.size} 个下载地址)"
                            )
                        }
                    }
                    val headers = head.headers
                        .filterNot { it.first.equals("content-length", true) }
                        .filterNot { it.first.equals("transfer-encoding", true) }
                        .filterNot { it.first.equals("connection", true) } +
                        listOf(
                            "Content-Length" to outBody.size.toString(),
                            "Connection" to "close",
                        )
                    emitPlain(HttpResponseHead(head.status, head.reason, headers).serialize() + outBody)
                } else {
                    val headers = head.headers
                        .filterNot { it.first.equals("connection", true) } +
                        listOf("Connection" to "close")
                    emitPlain(HttpResponseHead(head.status, head.reason, headers).serialize())
                    if (leftover.isNotEmpty()) emitPlain(leftover)
                    while (true) {
                        val plain = upstream.readPlainBlocking() ?: break
                        emitPlain(plain)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { emitPlain(LocalResponses.text(502, "上游失败：${e.javaClass.simpleName}")) }
            rules.onEvent("error", "上游 $host${req.pathOnly}: ${e.message}")
        }
    }

    private fun readBody(upstream: Upstream, initial: ByteArray, want: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        out.write(initial)
        var guard = 0
        while (out.size() < want && guard++ < 65536) {
            val plain = upstream.readPlainBlocking() ?: return null
            out.write(plain)
        }
        val all = out.toByteArray()
        return if (all.size >= want) all.copyOfRange(0, want) else null
    }
}
