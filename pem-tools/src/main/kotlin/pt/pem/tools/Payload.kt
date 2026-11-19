package pt.pem.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Payload (JSON ou SOAP/XML) encontrado nos logs; [ref] e a linha onde ele COMECA (e ai que aparece o botao). */
data class PayloadEvent(val ref: Long, val kind: String, val text: String)

/**
 * Deteta JSON e SOAP (Envelope) nas linhas dos logs, mesmo repartidos por varias linhas.
 * Nao e thread-safe: use um por consola e sincronize.
 */
class PayloadDetector {
    private class XmlMode(val ref: Long) { val lines = ArrayList<String>(); var chars = 0 }
    private class JsonMode(val ref: Long) {
        val lines = ArrayList<String>(); var chars = 0
        var depth = 0; var inStr = false; var esc = false
    }
    private class Scan(val end: Int, val depth: Int, val inStr: Boolean, val esc: Boolean)

    private var xml: XmlMode? = null
    private var json: JsonMode? = null

    fun reset() { xml = null; json = null }

    fun push(line: String, ref: Long): List<PayloadEvent> {
        xml?.let { return continueXml(it, line) }
        json?.let { return continueJson(it, line) }
        val x = ENV_OPEN.find(line)
        if (x != null) {
            val text = line.substring(x.range.first)
            val c = ENV_CLOSE.find(text)
            if (c != null) return listOf(PayloadEvent(ref, "xml", text.substring(0, c.range.last + 1)))
            xml = XmlMode(ref).also { it.lines += text; it.chars = text.length }
            return emptyList()
        }
        return startJson(line, ref)
    }

    private fun continueXml(m: XmlMode, line: String): List<PayloadEvent> {
        m.lines += line; m.chars += line.length
        val c = ENV_CLOSE.find(line)
        if (c != null) {
            m.lines[m.lines.size - 1] = line.substring(0, c.range.last + 1)
            xml = null
            return listOf(PayloadEvent(m.ref, "xml", m.lines.joinToString("\n")))
        }
        if (m.lines.size > MAX_LINES || m.chars > MAX_CHARS) xml = null
        return emptyList()
    }

    private fun scan(text: String, from: Int, depth0: Int, inStr0: Boolean, esc0: Boolean): Scan {
        var depth = depth0; var inStr = inStr0; var esc = esc0
        for (i in from until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return Scan(i, 0, inStr, esc); if (depth < 0) return Scan(-1, -1, inStr, esc) }
            }
        }
        return Scan(-1, depth, inStr, esc)
    }

    private fun startJson(line: String, ref: Long): List<PayloadEvent> {
        var tried = 0
        var i = 0
        while (i < line.length && tried < 6) {
            val ch = line[i]
            if (ch != '{' && ch != '[') { i++; continue }
            tried++
            val r = scan(line, i, 0, false, false)
            if (r.end >= 0) {
                val sub = line.substring(i, r.end + 1)
                if (sub.length >= 4 && validJson(sub)) return listOf(PayloadEvent(ref, "json", sub))
                i++
                continue
            }
            // nao fecha nesta linha: so abre bloco multi-linha se parecer inicio de JSON (e nao "[INFO]")
            val next = line.substring(i + 1).trimStart().firstOrNull()
            val looksJson = if (ch == '{') next == null || next == '"' || next == '}'
            else next == null || next == '{' || next == '"' || next == '[' || next.isDigit() || next == '-'
            if (looksJson) {
                json = JsonMode(ref).also { it.lines += line.substring(i); it.chars = line.length - i; it.depth = r.depth; it.inStr = r.inStr; it.esc = r.esc }
            }
            return emptyList()
        }
        return emptyList()
    }

    private fun continueJson(m: JsonMode, line: String): List<PayloadEvent> {
        m.lines += line; m.chars += line.length
        val r = scan(line, 0, m.depth, m.inStr, m.esc)
        if (r.end >= 0) {
            m.lines[m.lines.size - 1] = line.substring(0, r.end + 1)
            val text = m.lines.joinToString("\n")
            json = null
            return if (validJson(text)) listOf(PayloadEvent(m.ref, "json", text)) else emptyList()
        }
        m.depth = r.depth; m.inStr = r.inStr; m.esc = r.esc
        if (r.depth < 0 || m.lines.size > MAX_LINES || m.chars > MAX_CHARS) json = null
        return emptyList()
    }

    companion object {
        private const val MAX_LINES = 600
        private const val MAX_CHARS = 400_000
        private val ENV_OPEN = Regex("<(\\w[\\w.-]*:)?Envelope\\b", RegexOption.IGNORE_CASE)
        private val ENV_CLOSE = Regex("</(\\w[\\w.-]*:)?Envelope\\s*>", RegexOption.IGNORE_CASE)

        /** JSON estrito (como o JSON.parse): objeto/array nao vazio. O Gson aceita `{0}`, chavetas sem aspas, etc., por isso ha um validador proprio. */
        fun validJson(s: String): Boolean {
            val v = StrictJson(s)
            return try { v.parseTop() } catch (_: Exception) { false }
        }

        private class StrictJson(private val s: String) {
            private var i = 0
            private var nonEmpty = false
            private fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
            private fun fail(): Nothing = throw IllegalStateException()
            fun parseTop(): Boolean {
                ws()
                if (i >= s.length || (s[i] != '{' && s[i] != '[')) return false
                value(0)
                ws()
                return i == s.length && nonEmpty
            }
            private fun value(depth: Int) {
                if (depth > 200) fail()
                ws()
                if (i >= s.length) fail()
                when (s[i]) {
                    '{' -> {
                        i++; ws()
                        if (s.getOrNull(i) == '}') { i++; return }
                        nonEmpty = true
                        while (true) {
                            ws(); string(); ws()
                            if (s.getOrNull(i) != ':') fail(); i++
                            value(depth + 1); ws()
                            when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return }; else -> fail() }
                        }
                    }
                    '[' -> {
                        i++; ws()
                        if (s.getOrNull(i) == ']') { i++; return }
                        nonEmpty = true
                        while (true) {
                            value(depth + 1); ws()
                            when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return }; else -> fail() }
                        }
                    }
                    '"' -> string()
                    't' -> literal("true")
                    'f' -> literal("false")
                    'n' -> literal("null")
                    else -> number()
                }
            }
            private fun literal(w: String) { if (!s.startsWith(w, i)) fail(); i += w.length }
            private fun string() {
                if (s.getOrNull(i) != '"') fail()
                i++
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '"' -> { i++; return }
                        c == '\\' -> { i++; if (i >= s.length) fail(); if (s[i] == 'u') { if (i + 4 >= s.length || !s.substring(i + 1, i + 5).all { it.isLetterOrDigit() }) fail(); i += 5 } else { if (s[i] !in "\"\\/bfnrt") fail(); i++ } }
                        c < ' ' -> fail()
                        else -> i++
                    }
                }
                fail()
            }
            private fun number() {
                val m = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?").matchAt(s, i) ?: fail()
                i = m.range.last + 1
            }
        }
    }
}

object PayloadFormat {
    fun prettyJson(text: String): String = try {
        GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(text))
    } catch (_: Exception) { text }

    /** Indenta XML sem bibliotecas (aceita fragmentos e SOAP com prefixos). */
    fun prettyXml(text: String): String {
        val tokens = Regex("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?\\]\\]>|<[^>]+>|[^<]+").findAll(text).map { it.value }.toList()
        fun isOpen(t: String) = Regex("^<[^/!?]").containsMatchIn(t) && !t.endsWith("/>")
        fun isClose(t: String) = t.startsWith("</")
        val out = ArrayList<String>()
        var depth = 0
        fun pad() = "  ".repeat(maxOf(depth, 0))
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.startsWith("<")) { val s = t.trim(); if (s.isNotEmpty()) out += pad() + s; i++; continue }
            if (isClose(t)) { depth--; out += pad() + t; i++; continue }
            if (isOpen(t)) {
                val n1 = tokens.getOrNull(i + 1); val n2 = tokens.getOrNull(i + 2)
                if (n1 != null && !n1.startsWith("<") && n2 != null && isClose(n2)) { out += pad() + t + n1.trim() + n2; i += 3; continue }
                if (n1 != null && isClose(n1)) { out += pad() + t + n1; i += 2; continue }
                out += pad() + t
                depth++
                i++
                continue
            }
            out += pad() + t
            i++
        }
        return out.joinToString("\n")
    }

    fun format(kind: String, text: String) = if (kind == "json") prettyJson(text) else prettyXml(text)
}

/** Regioes colapsaveis de um JSON/XML ja indentado (ver `foldRegions` em media/payload.js, o equivalente do VS Code). */
object PayloadFold {
    private val CLOSER = Regex("^(\\}|\\]|</)")
    private fun indent(l: String) = l.length - l.trimStart().length

    /** Para cada linha: indice da ultima linha do bloco que ela abre (com a linha de fecho, se existir) ou -1. */
    fun regions(lines: List<String>): IntArray {
        val n = lines.size
        val ends = IntArray(n) { -1 }
        val blank = BooleanArray(n) { lines[it].isBlank() }
        val ind = IntArray(n) { indent(lines[it]) }
        for (i in 0 until n) {
            if (blank[i]) continue
            var k = i
            var j = i + 1
            while (j < n && (blank[j] || ind[j] > ind[i])) { if (!blank[j]) k = j; j++ }
            if (k == i) continue
            val closes = k + 1 < n && !blank[k + 1] && ind[k + 1] == ind[i] && CLOSER.containsMatchIn(lines[k + 1].trim())
            ends[i] = if (closes) k + 1 else k
        }
        return ends
    }

    /** Texto mostrado depois do ... quando o bloco da linha [i] esta recolhido: o fecho ou a contagem de linhas. */
    fun tail(lines: List<String>, i: Int, end: Int): String {
        val last = lines[end].trim()
        return if (end > i && CLOSER.containsMatchIn(last) && indent(lines[end]) == indent(lines[i])) last else "(${end - i} linhas)"
    }
}
