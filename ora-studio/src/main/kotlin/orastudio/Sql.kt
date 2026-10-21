package orastudio

/**
 * Uma instrucao do script. [start]/[end] sao offsets no texto original (end exclusivo, inclui o
 * terminador `;` ou `/`); [exec] e o texto a enviar ao driver (sem `;` final, excepto blocos PL/SQL).
 */
data class Stmt(val start: Int, val end: Int, val text: String, val exec: String, val plsql: Boolean) {
    /** Offset (no script) a que corresponde a posicao [pos] do texto enviado ao driver. */
    fun scriptOffset(pos: Int): Int = start + leadingOffset + pos.coerceAtLeast(0)

    internal var leadingOffset: Int = 0
}

object SqlSplitter {

    fun split(script: String): List<Stmt> {
        val out = mutableListOf<Stmt>()
        val n = script.length
        var i = 0

        fun atLineStart(pos: Int): Boolean {
            var k = pos - 1
            while (k >= 0 && script[k] != '\n') {
                if (!script[k].isWhitespace()) return false
                k--
            }
            return true
        }

        fun slashLineAt(pos: Int): Boolean {
            if (script[pos] != '/' || !atLineStart(pos)) return false
            var k = pos + 1
            while (k < n && script[k] != '\n') {
                if (!script[k].isWhitespace()) return false
                k++
            }
            return true
        }

        while (i < n) {
            // salta espacos, comentarios e "/" soltos antes da instrucao
            while (i < n) {
                val c = script[i]
                when {
                    c.isWhitespace() -> i++
                    c == '-' && i + 1 < n && script[i + 1] == '-' -> i = endOfLine(script, i)
                    c == '/' && i + 1 < n && script[i + 1] == '*' -> i = endOfBlockComment(script, i)
                    c == '/' && slashLineAt(i) -> i++
                    c == ';' -> i++
                    else -> break
                }
            }
            if (i >= n) break

            val start = i
            val kind = classify(script, start)
            val plsql = kind != Kind.SQL
            var depth = 0
            var sawBegin = false
            var nestedSubprogram = false
            var end = -1
            var execEnd = -1

            while (i < n) {
                val c = script[i]
                when {
                    c == '-' && i + 1 < n && script[i + 1] == '-' -> i = endOfLine(script, i)
                    c == '/' && i + 1 < n && script[i + 1] == '*' -> i = endOfBlockComment(script, i)
                    c == '\'' -> i = endOfString(script, i)
                    c == '"' -> i = endOfQuoted(script, i)
                    (c == 'q' || c == 'Q') && i + 2 < n && script[i + 1] == '\'' && !prevIsIdent(script, i) -> i = endOfQQuote(script, i)
                    c == '/' && slashLineAt(i) -> {
                        end = i + 1
                        execEnd = i
                        break
                    }
                    c == ';' && (!plsql || (kind.depthEnds && sawBegin && depth == 0 && !nestedSubprogram)) -> {
                        end = i + 1
                        execEnd = if (plsql) i + 1 else i
                        break
                    }
                    isIdentStart(c) && !prevIsIdent(script, i) -> {
                        var k = i + 1
                        while (k < n && isIdentPart(script[k])) k++
                        val w = script.substring(i, k).uppercase()
                        if (plsql) {
                            when (w) {
                                "BEGIN" -> { depth++; sawBegin = true }
                                "CASE" -> depth++
                                "END" -> {
                                    val next = nextWord(script, k)
                                    if (next != "IF" && next != "LOOP") depth--
                                }
                                "PROCEDURE", "FUNCTION" -> if (kind == Kind.DECLARE && !sawBegin) nestedSubprogram = true
                            }
                        }
                        i = k
                    }
                    else -> i++
                }
            }
            if (end < 0) {
                end = n
                execEnd = n
            }
            val raw = script.substring(start, execEnd)
            val trimmed = raw.trimEnd()
            val exec = if (plsql) trimmed else trimmed.trimEnd(';').trimEnd()
            if (exec.isNotBlank()) {
                out += Stmt(start, end, trimmed, exec, plsql)
            }
            i = end
        }
        return out
    }

    /** Instrucao sob o cursor: a que contem [caret], ou a mais proxima (anterior, na mesma zona). */
    fun statementAt(script: String, caret: Int): Stmt? {
        val all = split(script)
        if (all.isEmpty()) return null
        all.firstOrNull { caret >= it.start && caret <= it.end }?.let { return it }
        val prev = all.lastOrNull { it.end <= caret }
        if (prev != null) {
            val gap = script.substring(prev.end, caret.coerceAtMost(script.length))
            if (gap.count { it == '\n' } < 2) return prev
        }
        return all.firstOrNull { it.start >= caret } ?: prev
    }

    private enum class Kind(val depthEnds: Boolean) {
        SQL(false), ANON(true), DECLARE(true), UNIT(true), UNIT_NO_DEPTH(false)
    }

    private val createHeader = Regex(
        """^create\s+(or\s+replace\s+)?(and\s+(resolve|compile)\s+)?(noforce\s+|force\s+)?(editionable\s+|noneditionable\s+)?(procedure|function|package|trigger|type|library|java)\b(\s+body\b)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun classify(s: String, start: Int): Kind {
        val head = s.substring(start, minOf(s.length, start + 400))
        val first = Regex("""^[A-Za-z]+""").find(head)?.value?.uppercase() ?: return Kind.SQL
        if (first == "BEGIN") return Kind.ANON
        if (first == "DECLARE") return Kind.DECLARE
        if (first == "CREATE") {
            val m = createHeader.find(head) ?: return Kind.SQL
            val what = m.groupValues[6].lowercase()
            val body = m.groupValues[7].isNotBlank()
            return when (what) {
                "package" -> Kind.UNIT_NO_DEPTH
                "type" -> {
                    if (body) return Kind.UNIT_NO_DEPTH
                    val rest = head.substring(m.range.last + 1)
                    if (Regex("""\b(as|is)\s+(table|varray|varying)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)) Kind.SQL
                    else if (Regex("""\bunder\b|\b(as|is)\s+object\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)) Kind.UNIT_NO_DEPTH
                    else Kind.SQL
                }
                else -> Kind.UNIT
            }
        }
        return Kind.SQL
    }

    private fun nextWord(s: String, from: Int): String {
        var k = from
        while (k < s.length && s[k].isWhitespace()) k++
        val st = k
        while (k < s.length && isIdentPart(s[k])) k++
        return s.substring(st, k).uppercase()
    }

    private fun prevIsIdent(s: String, i: Int) = i > 0 && isIdentPart(s[i - 1])

    fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '#'

    private fun endOfLine(s: String, from: Int): Int {
        val k = s.indexOf('\n', from)
        return if (k < 0) s.length else k + 1
    }

    private fun endOfBlockComment(s: String, from: Int): Int {
        val k = s.indexOf("*/", from + 2)
        return if (k < 0) s.length else k + 2
    }

    private fun endOfString(s: String, from: Int): Int {
        var k = from + 1
        while (k < s.length) {
            if (s[k] == '\'') {
                if (k + 1 < s.length && s[k + 1] == '\'') k += 2 else return k + 1
            } else k++
        }
        return s.length
    }

    private fun endOfQuoted(s: String, from: Int): Int {
        val k = s.indexOf('"', from + 1)
        return if (k < 0) s.length else k + 1
    }

    /** q'[ ... ]' , q'{ ... }' , q'( ... )' , q'< ... >' , q'! ... ! */
    private fun endOfQQuote(s: String, from: Int): Int {
        if (from + 2 >= s.length) return s.length
        val open = s[from + 2]
        val close = when (open) { '[' -> ']'; '{' -> '}'; '(' -> ')'; '<' -> '>'; else -> open }
        val k = s.indexOf("$close'", from + 3)
        return if (k < 0) s.length else k + 2
    }
}
