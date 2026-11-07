package com.zeeload.carappcenter.intercept

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 最小 DER 编码器：只为「签一张叶子证书」服务。
 * 不引入 BouncyCastle —— 车机上少一个依赖，也方便在 JVM 单测里直接跑。
 */
object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray): ByteArray = tlv(0x30, items.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    fun seq(items: List<ByteArray>): ByteArray = tlv(0x30, items.fold(ByteArray(0)) { a, b -> a + b })

    fun set(vararg items: ByteArray): ByteArray = tlv(0x31, items.fold(ByteArray(0)) { a, b -> a + b })

    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xA0 or tagNumber, content)

    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))

    fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    fun bitString(bytes: ByteArray, unusedBits: Int = 0): ByteArray =
        tlv(0x03, byteArrayOf(unusedBits.toByte()) + bytes)

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun utf8(text: String): ByteArray = tlv(0x0C, text.toByteArray(Charsets.UTF_8))

    fun printableString(text: String): ByteArray = tlv(0x13, text.toByteArray(Charsets.US_ASCII))

    fun utcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID 至少两段：$dotted" }
        val out = ByteArrayOutputStream()
        out.write((40 * parts[0] + parts[1]).toInt())
        for (p in parts.drop(2)) {
            var v = p
            val stack = ArrayDeque<Int>()
            stack.addFirst((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                stack.addFirst(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            stack.forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> { out.write(0x81); out.write(length) }
            length <= 0xFFFF -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
    }
}
