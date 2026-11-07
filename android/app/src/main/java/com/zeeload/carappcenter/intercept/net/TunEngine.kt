package com.zeeload.carappcenter.intercept.net

import android.os.ParcelFileDescriptor
import com.zeeload.carappcenter.intercept.CaMaterial
import com.zeeload.carappcenter.intercept.CertMinter
import com.zeeload.carappcenter.intercept.ServerCert
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机上的最小网络栈：处理 IPv4 + TCP（转发/解密）+ UDP 53（DNS 转发）。
 *
 * 只有被 VpnService 允许的应用（= 用户在界面上选的应用市场）的流量会进来，
 * 所以「动态选择拦截哪个市场」就是在 VpnService 层按包名下发。
 */
class TunEngine(
    private val tunFd: ParcelFileDescriptor,
    private val rules: InterceptRules,
    private val dynamic: DynamicProvider,
    private val ca: CaMaterial?,
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val minter: CertMinter? = ca?.let { runCatching { CertMinter(it) }.getOrElse { e ->
        log("证书铸造不可用：${e.message}"); null
    } }
    private val flows = ConcurrentHashMap<Int, TcpFlow>()
    private val dnsPool = Executors.newFixedThreadPool(2)
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var seqSalt: Int = 0x5A17_0000

    fun start() {
        if (!running.compareAndSet(false, true)) return
        input = FileInputStream(tunFd.fileDescriptor)
        output = FileOutputStream(tunFd.fileDescriptor)
        Thread({ readLoop() }, "zeeload-tun-read").apply { isDaemon = true }.start()
        Thread({ retransmitLoop() }, "zeeload-tun-timer").apply { isDaemon = true }.start()
        log("隧道已启动（MTU=1500）")
    }

    fun stop() {
        running.set(false)
        flows.values.forEach { runCatching { it.close("tunnel stop") } }
        flows.clear()
        runCatching { input?.close() }
        runCatching { output?.close() }
        dnsPool.shutdownNow()
        runCatching { tunFd.close() }
        log("隧道已停止")
    }

    // ---------------------------------------------------------------- 读循环

    private fun readLoop() {
        val buf = ByteArray(32767)
        val inStream = input ?: return
        while (running.get()) {
            val n = try {
                inStream.read(buf)
            } catch (e: IOException) {
                if (running.get()) log("TUN 读取结束：${e.message}")
                break
            }
            if (n <= 0) continue
            try {
                dispatch(buf, n)
            } catch (e: Exception) {
                log("解析包失败：${e.message}")
            }
        }
    }

    private fun dispatch(buf: ByteArray, len: Int) {
        if (len < 20) return
        val version = (buf[0].toInt() shr 4) and 0x0F
        if (version != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val totalLen = u16(buf, 2)
        if (totalLen < ihl || totalLen > len) return
        val proto = buf[9].toInt() and 0xFF
        val src = ip(buf, 12)
        val dst = ip(buf, 16)
        when (proto) {
            6 -> handleTcp(buf, ihl, totalLen, src, dst)
            17 -> handleUdp(buf, ihl, totalLen, src, dst)
            else -> Unit
        }
    }

    private fun handleUdp(buf: ByteArray, ihl: Int, totalLen: Int, src: ByteArray, dst: ByteArray) {
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return
        val payload = buf.copyOfRange(ihl + 8, totalLen)
        dnsPool.execute {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 4000
                    sock.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(dst), 53))
                    val respBuf = ByteArray(4096)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    sock.receive(resp)
                    val data = resp.data.copyOfRange(0, resp.length)
                    emitUdp(src, dst, srcPort, dstPort, data)
                }
            } catch (e: Exception) {
                log("DNS 转发失败：${e.message}")
            }
        }
    }

    private fun handleTcp(buf: ByteArray, ihl: Int, totalLen: Int, src: ByteArray, dst: ByteArray) {
        val off = ihl
        if (off + 20 > totalLen) return
        val srcPort = u16(buf, off)
        val dstPort = u16(buf, off + 2)
        val seq = u32(buf, off + 4)
        val ack = u32(buf, off + 8)
        val dataOffset = ((buf[off + 12].toInt() shr 4) and 0x0F) * 4
        val flags = buf[off + 13].toInt() and 0xFF
        val payload = buf.copyOfRange(off + dataOffset, totalLen)
        val key = flowKey(src, srcPort, dst, dstPort)
        val flow = flows[key]
        val isSyn = flags and 0x02 != 0
        val isAck = flags and 0x10 != 0
        if (isSyn && !isAck) {
            if (flow != null) runCatching { flow.close("重复 SYN") }
            val newFlow = TcpFlow(key, src, srcPort, dst, dstPort, seq)
            flows[key] = newFlow
            newFlow.acceptSyn()
            return
        }
        if (flow == null) return
        flow.onSegment(seq, ack.toLong(), flags, payload)
    }

    // ---------------------------------------------------------------- 写包

    private fun emitUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray) {
        val tcpLen = 8 + payload.size
        val total = 20 + tcpLen
        val pkt = ByteArray(total)
        writeIpHeader(pkt, total, 17, dst, src)
        pkt[20] = (srcPort ushr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort ushr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = ((tcpLen) ushr 8).toByte()
        pkt[25] = (tcpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        writeTun(pkt)
    }

    private fun writeIpHeader(pkt: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        pkt[0] = 0x45
        pkt[1] = 0
        pkt[2] = (total ushr 8).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[4] = 0; pkt[5] = 0
        pkt[6] = 0x40.toByte(); pkt[7] = 0
        pkt[8] = 64
        pkt[9] = proto.toByte()
        pkt[10] = 0; pkt[11] = 0
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        val sum = checksum(pkt, 0, 20, 0)
        pkt[10] = (sum ushr 8).toByte()
        pkt[11] = (sum and 0xFF).toByte()
    }

    private fun writeTun(pkt: ByteArray) {
        val out = output ?: return
        synchronized(writeLock) {
            runCatching {
                out.write(pkt)
                out.flush()
            }
        }
    }

    // ---------------------------------------------------------------- 重传

    private fun retransmitLoop() {
        while (running.get()) {
            Thread.sleep(400)
            val now = System.currentTimeMillis()
            flows.values.forEach { flow -> runCatching { flow.tick(now) } }
            flows.entries.removeIf { it.value.isDead(now) }
        }
    }

    private fun nextSeq(): Int {
        seqSalt += 0x9E37
        return seqSalt
    }

    // ---------------------------------------------------------------- 单条 TCP 流

    private inner class TcpFlow(
        val key: Int,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int,
        private val initialSeq: Long,
    ) {
        private val sndIss: Long = (nextSeq().toLong() and 0xFFFFFFFFL)
        private var sndNxt: Long = sndIss + 1
        private var sndUna: Long = sndIss + 1
        private var rcvNxt: Long = initialSeq + 1
        private val pending = LinkedHashMap<Long, Segment>()
        private var closed = false
        private var finSent = false
        private var deadAt = 0L
        private var sni: String? = null
        private var tls: TlsServerHello? = null
        private var mitm: MitmSession? = null
        private val helloBuf = ByteArrayOutputStream()
        private var rawSocket: Socket? = null
        private var rawWriter: OutputStream? = null
        private var clientHandshakeSeen = 0

        private inner class Segment(val seq: Long, val data: ByteArray, val flags: Int) {
            var sentAt: Long = System.currentTimeMillis()
            var retries: Int = 0
        }

        fun acceptSyn() {
            // SYN 用 ISS 本身作为序列号，之后的数据从 ISS+1 开始
            sendSegment(sndIss, ByteArray(0), FLAG_SYN or FLAG_ACK)
            sndNxt = sndIss + 1
            sndUna = sndIss + 1
            log("TCP 连接 ${ipToString(clientIp)}:$clientPort → ${ipToString(serverIp)}:$serverPort")
        }

        fun onSegment(seq: Long, ack: Long, flags: Int, payload: ByteArray) {
            if (closed) return
            if (flags and FLAG_RST != 0) {
                close("客户端 RST")
                return
            }
            if (flags and FLAG_ACK != 0 && ack > sndUna) {
                sndUna = ack
                pending.entries.removeIf { it.key + it.value.data.size <= ack }
            }
            if (payload.isNotEmpty()) {
                if (seq == rcvNxt) {
                    rcvNxt += payload.size
                    emit(ByteArray(0), FLAG_ACK)
                    onClientData(payload)
                } else {
                    emit(ByteArray(0), FLAG_ACK)
                }
            }
            if (flags and FLAG_FIN != 0 && !finSent) {
                rcvNxt += 1
                emit(ByteArray(0), FLAG_ACK)
                close("客户端 FIN")
            }
        }

        private fun onClientData(data: ByteArray) {
            if (mitm == null && rawSocket == null) {
                helloBuf.write(data)
                val buffered = helloBuf.toByteArray()
                if (!SniParser.isComplete(buffered) && buffered.size < 16 * 1024) return
                val host = SniParser.parse(buffered)
                clientHandshakeSeen++
                if (host == null && clientHandshakeSeen < 2) return
                startIntercept(host ?: ipToString(serverIp), buffered)
                helloBuf.reset()
                return
            }
            if (mitm != null) {
                feedTls(data)
            } else {
                runCatching { rawWriter?.write(data); rawWriter?.flush() }
            }
        }

        private fun startIntercept(host: String, buffered: ByteArray) {
            val cert: ServerCert? = minter?.let { m ->
                runCatching { m.forHost(host) }.getOrElse { e ->
                    log("签证书失败($host)：${e.message}"); null
                }
            }
            if (cert == null) {
                startRawRelay(buffered)
                return
            }
            sni = host
            val server = TlsServerHello(cert)
            tls = server
            mitm = MitmSession(
                host = host,
                rules = rules,
                dynamic = dynamic,
                emitPlain = { plain -> emitTls(plain) },
                closeAfterResponse = { markDead(1500) },
            )
            log("解密 ${host}（${ipToString(serverIp)}:$serverPort）")
            feedTls(buffered)
        }

        private fun feedTls(cipher: ByteArray) {
            val server = tls ?: return
            val toClient = runCatching { server.feedCipher(cipher) }.getOrElse { e ->
                log("TLS 处理失败：${e.message}"); ByteArray(0)
            }
            if (toClient.isNotEmpty()) emit(toClient, FLAG_ACK)
            val plain = runCatching { server.readPlain() }.getOrDefault(ByteArray(0))
            if (plain.isNotEmpty()) {
                runCatching { mitm?.onPlain(plain) }.onFailure { log("HTTP 处理失败：${it.message}") }
            }
        }

        private fun emitTls(plain: ByteArray) {
            val server = tls ?: return
            val cipher = runCatching { server.seal(plain) }.getOrElse { e ->
                log("加密失败：${e.message}"); ByteArray(0)
            }
            if (cipher.isNotEmpty()) emit(cipher, FLAG_ACK)
        }

        private fun startRawRelay(buffered: ByteArray) {
            runCatching {
                val sock = Socket()
                sock.connect(InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort), 8000)
                rawSocket = sock
                rawWriter = sock.getOutputStream()
                rawWriter?.write(buffered)
                rawWriter?.flush()
                Thread({ rawReaderLoop(sock) }, "zeeload-raw-${clientPort}").apply { isDaemon = true }.start()
                log("明文转发（无法解密）→ ${ipToString(serverIp)}:$serverPort")
            }.onFailure {
                log("上游连接失败：${it.message}")
                emit(ByteArray(0), FLAG_RST)
                close("上游不可达")
            }
        }

        private fun rawReaderLoop(sock: Socket) {
            val buf = ByteArray(16 * 1024)
            try {
                while (running.get() && !closed) {
                    val n = sock.getInputStream().read(buf)
                    if (n <= 0) break
                    emit(buf.copyOf(n), FLAG_ACK)
                }
            } catch (_: Exception) {
            } finally {
                markDead(500)
            }
        }

        private fun emit(data: ByteArray, flags: Int) {
            if (closed) return
            val mss = 1460
            var offset = 0
            if (data.isEmpty()) {
                sendSegment(sndNxt, ByteArray(0), flags)
                if (flags and FLAG_SYN != 0) sndNxt += 1
                if (flags and FLAG_FIN != 0) { sndNxt += 1; finSent = true }
                return
            }
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(data.size, offset + mss))
                val seq = sndNxt
                sendSegment(seq, chunk, FLAG_ACK or (flags and FLAG_PSH))
                pending[seq] = Segment(seq, chunk, flags)
                sndNxt += chunk.size
                offset += chunk.size
            }
        }

        private fun sendSegment(seq: Long, payload: ByteArray, flags: Int) {
            val tcpLen = 20 + payload.size
            val total = 20 + tcpLen
            val pkt = ByteArray(total)
            writeIpHeader(pkt, total, 6, serverIp, clientIp)
            pkt[20] = (serverPort ushr 8).toByte()
            pkt[21] = (serverPort and 0xFF).toByte()
            pkt[22] = (clientPort ushr 8).toByte()
            pkt[23] = (clientPort and 0xFF).toByte()
            writeU32(pkt, 24, seq)
            writeU32(pkt, 28, rcvNxt)
            pkt[32] = 0x50.toByte() // data offset 5
            pkt[33] = flags.toByte()
            pkt[34] = 0xFF.toByte(); pkt[35] = 0xFF.toByte() // window 65535
            pkt[36] = 0; pkt[37] = 0
            pkt[38] = 0; pkt[39] = 0
            System.arraycopy(payload, 0, pkt, 40, payload.size)
            val pseudo = pseudoHeader(serverIp, clientIp, tcpLen)
            val sum = checksum(pkt, 20, tcpLen, pseudo)
            pkt[36] = (sum ushr 8).toByte()
            pkt[37] = (sum and 0xFF).toByte()
            writeTun(pkt)
        }

        fun tick(now: Long) {
            if (closed) return
            for (segment in pending.values) {
                if (now - segment.sentAt < 700) continue
                if (segment.retries > 5) {
                    log("重传超限，断开 ${ipToString(serverIp)}:$serverPort")
                    emit(ByteArray(0), FLAG_RST)
                    close("重传超限")
                    return
                }
                segment.retries++
                segment.sentAt = now
                sendSegment(segment.seq, segment.data, segment.flags)
            }
        }

        fun isDead(now: Long): Boolean = closed && (deadAt == 0L || now - deadAt > 3000)

        fun markDead(delayMs: Long) {
            deadAt = System.currentTimeMillis() - 3000 + delayMs
        }

        fun close(reason: String) {
            if (closed) return
            closed = true
            deadAt = System.currentTimeMillis()
            runCatching { mitm = null }
            runCatching { rawSocket?.close() }
            flows.remove(key)
            log("连接关闭（$reason）")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun flowKey(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Int {
        var h = 17
        for (b in src) h = h * 31 + b
        h = h * 31 + srcPort
        for (b in dst) h = h * 31 + b
        h = h * 31 + dstPort
        return h
    }

    private fun pseudoHeader(src: ByteArray, dst: ByteArray, length: Int): Int {
        var sum = 0
        sum += u16(src, 0); sum += u16(src, 2)
        sum += u16(dst, 0); sum += u16(dst, 2)
        sum += 6
        sum += length
        return sum
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var i = offset
        while (i + 1 < offset + length) {
            sum += u16(buf, i)
            i += 2
        }
        if (i < offset + length) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun writeU32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = ((value shr 24) and 0xFF).toByte()
        buf[off + 1] = ((value shr 16) and 0xFF).toByte()
        buf[off + 2] = ((value shr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
    }

    private fun ip(buf: ByteArray, off: Int): ByteArray = buf.copyOfRange(off, off + 4)

    private fun ipToString(ip: ByteArray): String = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
    }
}
