package com.microfoods.core

/** Tiny JSON reader/writer, so the core has no dependencies and runs the same on the phone and in tests. */
object Json {
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        if (p.i != s.length) p.err("trailing characters")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObj(s: String): Map<String, Any?> = parse(s) as? Map<String, Any?> ?: error("JSON is not an object")

    fun write(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> num(sb, v)
            is Float -> num(sb, v.toDouble())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (x == null) continue
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString()); sb.append(':'); w(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    w(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> w(sb, v.toList())
            else -> str(sb, v.toString())
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("0"); return }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong().toString()) else sb.append(d.toString())
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun err(m: String): Nothing = throw IllegalArgumentException("Bad JSON at $i: $m")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) err("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else err("unexpected '$c'")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) err("expected $word")
            i += word.length
            return v
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err("expected key")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') err("expected ':'")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) err("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> err("expected ',' or '}'")
                }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) err("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> err("expected ',' or ']'")
                }
            }
        }
        fun string(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) err("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) err("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> err("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble()
            else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

typealias JMap = Map<String, Any?>

fun JMap.str(k: String): String? = this[k] as? String
fun JMap.dbl(k: String): Double? = (this[k] as? Number)?.toDouble()
fun JMap.int(k: String): Int? = (this[k] as? Number)?.toInt()
fun JMap.lng(k: String): Long? = (this[k] as? Number)?.toLong()
fun JMap.bool(k: String): Boolean? = this[k] as? Boolean
fun JMap.list(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()
fun JMap.strs(k: String): List<String> = list(k).mapNotNull { it as? String }
@Suppress("UNCHECKED_CAST")
fun JMap.obj(k: String): JMap? = this[k] as? JMap
@Suppress("UNCHECKED_CAST")
fun JMap.objs(k: String): List<JMap> = list(k).mapNotNull { it as? JMap }
