package com.zeeload.carappcenter.intercept.net

import com.zeeload.carappcenter.intercept.ServerCert
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** 用指定（现签的）叶子证书做服务端：给客户端看到的是「车机信任的 CA 签的证书」。 */
class TlsServerHello(private val cert: ServerCert) {
    private val ctx: SSLContext = SSLContext.getInstance("TLS").apply {
        val km = LeafKeyManager(cert)
        val kms: Array<javax.net.ssl.KeyManager> = arrayOf(km)
        init(kms, null, java.security.SecureRandom())
    }
    private val engine: SSLEngine = ctx.createSSLEngine().apply {
        useClientMode = false
        wantClientAuth = false
    }
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize * 2)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
    private var closed = false

    init {
        engine.beginHandshake()
    }

    /** 把客户端密文喂进来，返回需要回给客户端的密文（可能为空）。 */
    fun feedCipher(cipher: ByteArray): ByteArray {
        if (cipher.isNotEmpty()) {
            if (netIn.remaining() < cipher.size) {
                val bigger = ByteBuffer.allocate(netIn.position() + cipher.size + 4096)
                netIn.flip(); bigger.put(netIn); netIn = bigger
            }
            netIn.put(cipher)
        }
        netIn.flip()
        produce()
        netIn.compact()
        return drainNetOut()
    }

    /** 把明文加密成密文发给客户端。 */
    fun seal(plain: ByteArray): ByteArray {
        var offset = 0
        while (offset < plain.size) {
            val chunk = minOf(engine.session.applicationBufferSize / 2, plain.size - offset)
            var src = ByteBuffer.wrap(plain, offset, chunk)
            while (src.hasRemaining()) {
                val res = engine.wrap(src, netOut)
                if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) growNetOut()
                if (res.status == SSLEngineResult.Status.CLOSED) { closed = true; break }
            }
            offset += chunk
        }
        return drainNetOut()
    }

    /** 解密后的应用层数据。 */
    fun readPlain(): ByteArray {
        if (appIn.position() == 0) return ByteArray(0)
        appIn.flip()
        val out = ByteArray(appIn.remaining())
        appIn.get(out)
        appIn.compact()
        return out
    }

    fun isClosed(): Boolean = closed || engine.isInboundDone

    fun closeOutbound(): ByteArray {
        engine.closeOutbound()
        netOut.flip()
        val out = ByteArray(netOut.remaining())
        netOut.get(out)
        netOut.compact()
        return out
    }

    private fun produce() {
        var guard = 0
        while (guard++ < 64) {
            val res: SSLEngineResult = when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> { runTasks(); continue }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> engine.wrap(EMPTY, netOut)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (netIn.remaining() <= 0) return
                    engine.unwrap(netIn, appIn)
                }
                else -> {
                    if (netIn.remaining() > 0) engine.unwrap(netIn, appIn) else return
                }
            }
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                growNetOut()
                if (appIn.remaining() < engine.session.applicationBufferSize) {
                    // appIn 不够，扩一次
                    val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip(); bigger.put(appIn); appIn = bigger
                }
            }
            if (res.status == SSLEngineResult.Status.CLOSED) { closed = true; return }
            if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return
            if (!netIn.hasRemaining() && engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) return
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    private fun growNetOut() {
        val bigger = ByteBuffer.allocate(netOut.capacity() * 2 + 4096)
        netOut.flip(); bigger.put(netOut); netOut = bigger
    }

    private fun drainNetOut(): ByteArray {
        netOut.flip()
        val out = ByteArray(netOut.remaining())
        netOut.get(out)
        netOut.compact()
        return out
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}

/** 上游客户端：信任任意证书（我们自己就是中间人，握手成功即可）。 */
class TlsClientHello(host: String) {
    private val engine: SSLEngine
    private var netIn: ByteBuffer
    private var netOut: ByteBuffer
    private var appIn: ByteBuffer

    init {
        val ctx = SSLContext.getInstance("TLS").apply {
            val tms: Array<TrustManager> = arrayOf(TRUST_ALL)
            init(null, tms, java.security.SecureRandom())
        }
        engine = ctx.createSSLEngine(host, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
                endpointIdentificationAlgorithm = null
            }
        }
        netIn = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
        netOut = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
        appIn = ByteBuffer.allocate(engine.session.applicationBufferSize * 2)
        engine.beginHandshake()
    }

    /** 需要发给服务器的第一段握手数据。 */
    fun start(): ByteArray {
        pump()
        return drain()
    }

    fun isHandshakeDone(): Boolean = engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING

    fun feedCipher(cipher: ByteArray): ByteArray {
        if (cipher.isNotEmpty()) {
            if (netIn.remaining() < cipher.size) {
                val bigger = ByteBuffer.allocate(netIn.position() + cipher.size + 4096)
                netIn.flip(); bigger.put(netIn); netIn = bigger
            }
            netIn.put(cipher)
        }
        netIn.flip()
        pump()
        netIn.compact()
        return drain()
    }

    fun seal(plain: ByteArray): ByteArray {
        var src = ByteBuffer.wrap(plain)
        while (src.hasRemaining()) {
            if (engine.wrap(src, netOut).status == SSLEngineResult.Status.BUFFER_OVERFLOW) grow()
        }
        return drain()
    }

    fun readPlain(): ByteArray {
        if (appIn.position() == 0) return ByteArray(0)
        appIn.flip()
        val out = ByteArray(appIn.remaining())
        appIn.get(out)
        appIn.compact()
        return out
    }

    private fun pump() {
        var guard = 0
        while (guard++ < 64) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> { runTasks(); continue }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> engine.wrap(ByteBuffer.allocate(0), netOut)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (netIn.remaining() <= 0) return
                    val r = engine.unwrap(netIn, appIn)
                    if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) grow()
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return
                }
                else -> {
                    if (netIn.remaining() > 0) {
                        val r = engine.unwrap(netIn, appIn)
                        if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return
                    }
                    return
                }
            }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    private fun grow() {
        val bigger = ByteBuffer.allocate(netOut.capacity() * 2 + 4096)
        netOut.flip(); bigger.put(netOut); netOut = bigger
    }

    private fun drain(): ByteArray {
        netOut.flip()
        val out = ByteArray(netOut.remaining())
        netOut.get(out)
        netOut.compact()
        return out
    }

    companion object {
        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}

private class LeafKeyManager(private val cert: ServerCert) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?,
    ): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> = arrayOf(ALIAS)
    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?,
    ): String = ALIAS

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = cert.chain
    override fun getPrivateKey(alias: String?): java.security.PrivateKey = cert.keyPair.private

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        engine: SSLEngine?,
    ): String = ALIAS

    companion object { const val ALIAS = "zeeload-leaf" }
}

/** 从 ClientHello 里取 SNI（决定给哪张证书 / 是否需要拦截）。 */
object SniParser {
    fun parse(data: ByteArray): String? {
        if (data.size < 5 || data[0] != 0x16.toByte()) return null // TLS handshake
        var i = 5 // record header
        if (i + 4 > data.size) return null
        val handshakeType = data[i].toInt() and 0xFF
        if (handshakeType != 0x01) return null
        i += 4 // type + length(3)
        i += 2 // client_version
        i += 32 // random
        if (i >= data.size) return null
        val sessionIdLen = data[i].toInt() and 0xFF
        i += 1 + sessionIdLen
        if (i + 2 > data.size) return null
        val cipherLen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2 + cipherLen
        if (i >= data.size) return null
        val compLen = data[i].toInt() and 0xFF
        i += 1 + compLen
        if (i + 2 > data.size) return null
        val extTotal = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2
        val extEnd = minOf(data.size, i + extTotal)
        while (i + 4 <= extEnd) {
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            if (type == 0x00 && len > 5) {
                // server_name: list_len(2) name_type(2) name_len(2) name
                var p = i + 2 + 2
                val nameLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                p += 2
                if (p + nameLen <= data.size) {
                    return String(data, p, nameLen, Charsets.US_ASCII)
                }
            }
            i += len
        }
        return null
    }

    /** ClientHello 是否已收全（同一条 TLS record 完整到达）。 */
    fun isComplete(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val len = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        return data.size >= 5 + len
    }

    fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(a.size + b.size)
        out.write(a); out.write(b)
        return out.toByteArray()
    }
}
