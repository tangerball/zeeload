package com.zeeload.carappcenter.intercept

/**
 * 极简 JSON 解析/序列化：不依赖 org.json，保持键顺序与数字原文，
 * 这样改写结果和电脑端 Python 版（json.loads + 紧凑 dumps）逐字节一致，便于对拍。
 */
sealed class J {
    class Obj(val map: LinkedHashMap<String, J> = LinkedHashMap()) : J()
    class Arr(val list: MutableList<J> = ArrayList()) : J()
    class Str(val v: String) : J()
    /** 数字保留原文（避免 42 变成 42.0） */
    class Num(val raw: String) : J()
    class Bool(val v: Boolean) : J()
    object Null : J()

    fun obj(): Obj? = this as? Obj
    fun arr(): Arr? = this as? Arr
    fun string(): String? = (this as? Str)?.v
    fun long(): Long? = when (this) {
        is Num -> raw.toLongOrNull()
        is Str -> v.toLongOrNull()
        else -> null
    }
    fun double(): Double? = (this as? Num)?.raw?.toDoubleOrNull()
}

object Json {
    fun parse(text: String): J? = runCatching { Parser(text).parseValue() }.getOrNull()
    fun parseObject(text: String): J.Obj? = parse(text)?.obj()

    fun write(value: J): String = StringBuilder().also { writeInto(value, it) }.toString()

    private fun writeInto(value: J, out: StringBuilder) {
        when (value) {
            is J.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.map) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k, out)
                    out.append(':')
                    writeInto(v, out)
                }
                out.append('}')
            }
            is J.Arr -> {
                out.append('[')
                value.list.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeInto(v, out)
                }
                out.append(']')
            }
            is J.Str -> writeString(value.v, out)
            is J.Num -> out.append(value.raw)
            is J.Bool -> out.append(if (value.v) "true" else "false")
            J.Null -> out.append("null")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): J {
            skipWs()
            require(i < s.length) { "空 JSON" }
            return when (val c = s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> J.Str(parseString())
                't' -> literal("true", J.Bool(true))
                'f' -> literal("false", J.Bool(false))
                'n' -> literal("null", J.Null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("非法字符 '$c' @$i")
            }
        }

        private fun literal(word: String, value: J): J {
            require(s.startsWith(word, i)) { "非法字面量 @$i" }
            i += word.length
            return value
        }

        private fun parseObj(): J.Obj {
            i++ // {
            val obj = J.Obj()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return obj }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(i < s.length && s[i] == ':') { "缺少 ':' @$i" }
                i++
                obj.map[key] = parseValue()
                skipWs()
                require(i < s.length) { "对象未闭合" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return obj }
                    else -> error("对象内非法字符 '${s[i]}' @$i")
                }
            }
        }

        private fun parseArr(): J.Arr {
            i++ // [
            val arr = J.Arr()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return arr }
            while (true) {
                arr.list.add(parseValue())
                skipWs()
                require(i < s.length) { "数组未闭合" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return arr }
                    else -> error("数组内非法字符 '${s[i]}' @$i")
                }
            }
        }

        private fun parseString(): String {
            require(s[i] == '"') { "字符串应以引号开始 @$i" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "转义不完整" }
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "\\u 转义不完整" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("字符串未闭合")
        }

        private fun parseNumber(): J.Num {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return J.Num(s.substring(start, i))
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
