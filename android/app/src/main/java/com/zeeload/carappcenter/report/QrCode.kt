package com.zeeload.carappcenter.report

/**
 * 纯 Kotlin 的最小 QR 编码器（字节模式，纠错等级 M，版本 1–10）。
 *
 * 为什么要自己写：车机页面必须离线生成二维码，不能依赖 Google Charts 之类的在线服务，
 * 也不想为一张二维码引入 zxing（体积 + 版本兼容）。这里只实现页面需要的子集：
 *  · 模式：8bit byte（URL/验证码都是 ASCII）；
 *  · 纠错：M（15%）；
 *  · 版本：1–10（最多 213 字节，足够 `http://10.255.255.255:8888/r?c=123456`）；
 *  · 掩码：8 种全算，按标准罚分选最优。
 *
 * 正确性由单元测试比对参考实现（python `qrcode`）的矩阵来保证。
 */
class QrMatrix(val size: Int) {
    private val cells = ByteArray(size * size) // 0 = 浅色, 1 = 深色

    fun get(x: Int, y: Int): Boolean = cells[y * size + x].toInt() == 1

    internal fun set(x: Int, y: Int, dark: Boolean) {
        cells[y * size + x] = if (dark) 1 else 0
    }

    internal fun flip(x: Int, y: Int) {
        cells[y * size + x] = (1 - cells[y * size + x]).toByte()
    }

    /** 按模块渲染需要的一行数据（未含 quiet zone）。 */
    fun row(y: Int): BooleanArray = BooleanArray(size) { get(it, y) }
}

object QrCode {
    private const val MAX_VERSION = 10

    /** 版本 → 数据码字数（纠错等级 M） */
    private val DATA_CODEWORDS_M = intArrayOf(0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216)

    /** 版本 → 每块纠错码字数（纠错等级 M） */
    private val EC_PER_BLOCK_M = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

    /** 版本 → 块数（纠错等级 M） */
    private val BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    /** 版本 → 对齐图形中心坐标 */
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    fun encode(text: String): QrMatrix = encode(text, forcedMask = null)

    /** 仅供调试/单测：返回最终码字序列（数据 + 纠错，已交织）。 */
    internal fun debugCodewords(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.ISO_8859_1)
        val version = pickVersion(payload.size)
        val bits = BitBuffer()
        bits.append(0b0100, 4)
        bits.append(payload.size, if (version <= 9) 8 else 16)
        for (b in payload) bits.append(b.toInt() and 0xFF, 8)
        bits.append(0, minOf(4, DATA_CODEWORDS_M[version] * 8 - bits.size))
        while (bits.size % 8 != 0) bits.append(0, 1)
        val capacity = DATA_CODEWORDS_M[version]
        val padded = ByteArray(capacity)
        bits.toBytes().copyInto(padded)
        var pad = 0xEC
        for (i in bits.toBytes().size until capacity) {
            padded[i] = pad.toByte()
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return interleave(splitBlocks(padded, version), splitBlocks(padded, version).map { reedSolomon(it, EC_PER_BLOCK_M[version]) })
    }

    /** forcedMask 仅供单测比对参考实现使用；正常调用用 [encode]。 */
    internal fun encode(text: String, forcedMask: Int?): QrMatrix {
        val payload = text.toByteArray(Charsets.ISO_8859_1)
        val version = pickVersion(payload.size)
        val dataCapacityBits = DATA_CODEWORDS_M[version] * 8
        val bits = BitBuffer()
        bits.append(0b0100, 4)                       // byte mode
        bits.append(payload.size, if (version <= 9) 8 else 16)
        for (b in payload) bits.append(b.toInt() and 0xFF, 8)
        val terminator = minOf(4, dataCapacityBits - bits.size)
        bits.append(0, terminator)
        while (bits.size % 8 != 0) bits.append(0, 1)
        val data = bits.toBytes()
        val capacity = DATA_CODEWORDS_M[version]
        require(data.size <= capacity) { "内容过长：${data.size} > $capacity" }
        val padded = ByteArray(capacity)
        data.copyInto(padded)
        var padByte = 0xEC
        for (i in data.size until capacity) {
            padded[i] = padByte.toByte()
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        val blocks = splitBlocks(padded, version)
        val ecBlocks = blocks.map { reedSolomon(it, EC_PER_BLOCK_M[version]) }
        val codewords = interleave(blocks, ecBlocks)

        val matrix = QrMatrix(version * 4 + 17)
        drawFunctionPatterns(matrix, version)
        drawCodewords(matrix, codewords)
        val bestMask: Int
        if (forcedMask != null) {
            bestMask = forcedMask
        } else {
            var candidate = 0
            var bestScore = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(matrix, mask)
                drawFormatInfo(matrix, mask)
                val score = penalty(matrix)
                if (score < bestScore) {
                    bestScore = score
                    candidate = mask
                }
                applyMask(matrix, mask) // 异或两次还原
            }
            bestMask = candidate
        }
        applyMask(matrix, bestMask)
        drawFormatInfo(matrix, bestMask)
        return matrix
    }

    private fun pickVersion(bytes: Int): Int {
        for (v in 1..MAX_VERSION) {
            val overheadBits = 4 + if (v <= 9) 8 else 16
            val need = overheadBits + bytes * 8
            if (need <= DATA_CODEWORDS_M[v] * 8) return v
        }
        throw IllegalArgumentException("内容过长（${bytes} 字节），最大支持 $MAX_VERSION 版")
    }

    // ---------------------------------------------------------------- 数据编码

    private class BitBuffer {
        private val out = ArrayList<Int>()
        val size: Int get() = out.size

        fun append(value: Int, bits: Int) {
            var v = value
            for (i in bits - 1 downTo 0) out.add((v ushr i) and 1)
        }

        fun toBytes(): ByteArray {
            val bytes = ByteArray((out.size + 7) / 8)
            out.forEachIndexed { i, bit -> if (bit == 1) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (7 - i % 8))).toByte() }
            return bytes
        }
    }

    private fun splitBlocks(data: ByteArray, version: Int): List<ByteArray> {
        val count = BLOCKS_M[version]
        val shortLen = data.size / count
        val longCount = data.size % count
        val blocks = ArrayList<ByteArray>(count)
        var offset = 0
        for (i in 0 until count) {
            val len = if (i >= count - longCount) shortLen + 1 else shortLen
            blocks.add(data.copyOfRange(offset, offset + len))
            offset += len
        }
        return blocks
    }

    private fun interleave(dataBlocks: List<ByteArray>, ecBlocks: List<ByteArray>): ByteArray {
        val out = ArrayList<Int>()
        val maxData = dataBlocks.maxOf { it.size }
        for (i in 0 until maxData) {
            dataBlocks.forEach { block -> if (i < block.size) out.add(block[i].toInt()) }
        }
        val maxEc = ecBlocks.maxOf { it.size }
        for (i in 0 until maxEc) {
            ecBlocks.forEach { block -> if (i < block.size) out.add(block[i].toInt()) }
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    private fun initGf() {
        if (gfExp[1] != 0) return
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        initGf()
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun generatorPoly(degree: Int): IntArray {
        initGf()
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until result.size) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun reedSolomon(data: ByteArray, ecLen: Int): ByteArray {
        val divisor = generatorPoly(ecLen)
        val result = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (j in 0 until result.size) result[j] = result[j] xor gfMul(divisor[j], factor)
        }
        return ByteArray(ecLen) { result[it].toByte() }
    }

    // ---------------------------------------------------------------- 图形绘制

    private fun drawFunctionPatterns(m: QrMatrix, version: Int) {
        val size = m.size
        // 定时图形
        for (i in 0 until size) {
            m.set(6, i, i % 2 == 0)
            m.set(i, 6, i % 2 == 0)
        }
        // 三个定位图形（含分隔符）
        for (corner in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
            val (cx, cy) = corner
            for (dy in -1..7) for (dx in -1..7) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val dark = (dx in 0..6 && dy in 0..6) &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                m.set(x, y, dark)
            }
        }
        // 对齐图形
        val positions = ALIGNMENT[version]
        for (cy in positions) for (cx in positions) {
            if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue
            for (dy in -2..2) for (dx in -2..2) {
                val dark = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1
                m.set(cx + dx, cy + dy, dark)
            }
        }
        // 格式信息占位（先占位，稍后正式写入）
        drawFormatInfo(m, 0)
        // 版本信息（版本 ≥ 7）
        if (version >= 7) {
            val bits = versionInfoBits(version)
            for (i in 0 until 18) {
                val dark = (bits shr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                m.set(a, b, dark)
                m.set(b, a, dark)
            }
        }
    }

    private fun versionInfoBits(version: Int): Int {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (version shl 12) or (rem and 0xFFF)
    }

    private fun maskApplies(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    private fun applyMask(m: QrMatrix, mask: Int) {
        val size = m.size
        for (y in 0 until size) for (x in 0 until size) {
            if (isFunctionModule(x, y, size)) continue
            if (maskApplies(mask, x, y)) m.flip(x, y)
        }
    }

    private fun isFunctionModule(x: Int, y: Int, size: Int): Boolean {
        if (x == 6 || y == 6) return true
        if (x < 9 && y < 9) return true
        if (x >= size - 8 && y < 9) return true      // 右上定位图形 + 格式信息
        if (x < 9 && y >= size - 8) return true      // 左下定位图形 + 格式信息
        // 对齐图形
        val positions = ALIGNMENT[(size - 17) / 4]
        for (cy in positions) for (cx in positions) {
            if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue
            if (x in cx - 2..cx + 2 && y in cy - 2..cy + 2) return true
        }
        // 版本信息区
        if ((size - 17) / 4 >= 7) {
            if (x >= size - 11 && y < 6) return true
            if (y >= size - 11 && x < 6) return true
        }
        return false
    }

    private fun drawFormatInfo(m: QrMatrix, mask: Int) {
        val data = (0b00 shl 3) or mask // EC 等级 M = 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or (rem and 0x3FF)) xor 0x5412
        val size = m.size
        for (i in 0 until 15) {
            val dark = (bits shr i) and 1 == 1
            // 第一份：竖排 8 列 + 横排
            when {
                i < 6 -> m.set(8, i, dark)
                i == 6 -> m.set(8, 7, dark)
                i == 7 -> m.set(8, 8, dark)
                i == 8 -> m.set(7, 8, dark)
                else -> m.set(14 - i, 8, dark)
            }
            // 第二份：右上横排 + 左下竖排
            if (i < 8) m.set(size - 1 - i, 8, dark)
            else m.set(8, size - 15 + i, dark)
        }
        m.set(8, size - 8, true) // 固定深色模块
    }

    private fun drawCodewords(m: QrMatrix, codewords: ByteArray) {
        val size = m.size
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = (right + 1) and 2 == 0
            for (i in 0 until size) {
                val y = if (upward) size - 1 - i else i
                for (dx in 0..1) {
                    val x = right - dx
                    if (isFunctionModule(x, y, size)) continue
                    val dark = if (bitIndex < totalBits) {
                        (codewords[bitIndex / 8].toInt() shr (7 - bitIndex % 8)) and 1 == 1
                    } else false
                    m.set(x, y, dark)
                    bitIndex++
                }
            }
            right -= 2
        }
    }

    // ---------------------------------------------------------------- 掩码罚分

    private fun penalty(m: QrMatrix): Int {
        val size = m.size
        var score = 0
        // 规则 1：连续同色
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (m.get(x, y) == m.get(x - 1, y)) run++ else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (m.get(x, y) == m.get(x, y - 1)) run++ else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        // 规则 2：2x2 同色块
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m.get(x, y)
            if (c == m.get(x + 1, y) && c == m.get(x, y + 1) && c == m.get(x + 1, y + 1)) score += 3
        }
        // 规则 3：类似定位图形的 1:1:3:1:1
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        fun matches(get: (Int) -> Boolean, start: Int): Boolean {
            for (i in 0 until 7) if (get(start + i) != pattern[i]) return false
            // 前后需要 4 格浅色（越界按浅色算）
            val before = (start - 4 until start).count { it < 0 || !get(it) }
            val after = (start + 7 until start + 11).count { it >= size || !get(it) }
            return before == 4 || after == 4
        }
        for (y in 0 until size) {
            for (x in 0..size - 7) {
                if (matches({ i -> m.get(i, y) }, x)) score += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 7) {
                if (matches({ i -> m.get(x, i) }, y)) score += 40
            }
        }
        // 规则 4：深色比例偏离 50%
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (m.get(x, y)) dark++
        val percent = dark * 100 / (size * size)
        score += kotlin.math.abs(percent - 50) / 5 * 10
        return score
    }
}
