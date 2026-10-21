package orastudio

/** Referencia a um identificador no texto: `qualifier.name` (ja normalizados: maiusculas, ou exactos se entre aspas). */
data class IdentRef(val start: Int, val end: Int, val qualifier: String?, val name: String)

object SqlTokens {

    private val keywords = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "AS", "IN", "IS", "ON", "BY", "GROUP", "ORDER", "HAVING", "UNION", "ALL",
        "DISTINCT", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "USING", "CASE", "WHEN", "THEN", "ELSE", "END",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "SEQUENCE",
        "TRIGGER", "PROCEDURE", "FUNCTION", "PACKAGE", "BODY", "TYPE", "REPLACE", "OR", "BEGIN", "DECLARE", "EXCEPTION", "RETURN", "RETURNS",
        "IF", "ELSIF", "LOOP", "WHILE", "FOR", "EXIT", "CURSOR", "OPEN", "CLOSE", "FETCH", "COMMIT", "ROLLBACK", "SAVEPOINT", "GRANT", "REVOKE",
        "BETWEEN", "LIKE", "EXISTS", "ANY", "SOME", "WITH", "CONNECT", "START", "PRIOR", "LEVEL", "ROWNUM", "ROWID", "SYSDATE", "DUAL",
        "ASC", "DESC", "OVER", "PARTITION", "ROWS", "RANGE", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "UNIQUE", "CHECK",
        "DEFAULT", "VARCHAR2", "NUMBER", "DATE", "TIMESTAMP", "CLOB", "BLOB", "INTEGER", "REFRESH", "COMPLETE", "DEMAND", "MATERIALIZED",
        "OF", "TO", "BULK", "COLLECT", "FORALL", "OUT", "INOUT", "IMMEDIATE", "EXECUTE", "PRAGMA", "AUTONOMOUS_TRANSACTION", "NEW", "OLD",
        "BEFORE", "AFTER", "EACH", "ROW", "TRUE", "FALSE", "MINUS", "INTERSECT", "NOCOPY", "LIMIT", "OFFSET", "ONLY", "NEXT", "FIRST", "LAST",
    )

    fun norm(raw: String): String = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) raw.substring(1, raw.length - 1) else raw.uppercase()

    /**
     * Todos os pares (qualificador, nome) candidatos a objectos de BD no texto, ignorando strings e
     * comentarios e palavras reservadas. Usado para pre-resolver links em lote.
     */
    fun extractRefs(text: String): Set<Pair<String?, String>> {
        val out = LinkedHashSet<Pair<String?, String>>()
        val n = text.length
        var i = 0
        var prevName: String? = null
        var prevDot = false
        while (i < n) {
            val c = text[i]
            when {
                c == '-' && i + 1 < n && text[i + 1] == '-' -> { i = text.indexOf('\n', i).let { if (it < 0) n else it + 1 }; prevName = null; prevDot = false }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> { i = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }; prevName = null; prevDot = false }
                c == '\'' -> {
                    var k = i + 1
                    while (k < n) { if (text[k] == '\'') { if (k + 1 < n && text[k + 1] == '\'') k += 2 else { k++; break } } else k++ }
                    i = k; prevName = null; prevDot = false
                }
                c == '"' -> {
                    val k = text.indexOf('"', i + 1).let { if (it < 0) n else it + 1 }
                    val raw = text.substring(i, k)
                    out += (if (prevDot) prevName else null) to norm(raw)
                    prevName = norm(raw); prevDot = false
                    i = k
                }
                SqlSplitter.isIdentStart(c) -> {
                    var k = i + 1
                    while (k < n && SqlSplitter.isIdentPart(text[k])) k++
                    val w = text.substring(i, k).uppercase()
                    if (prevDot || w !in keywords) out += (if (prevDot) prevName else null) to w
                    prevName = w; prevDot = false
                    i = k
                }
                c == '.' -> { prevDot = prevName != null; i++ }
                c.isWhitespace() -> { i++ }
                else -> { prevName = null; prevDot = false; i++ }
            }
        }
        return out
    }

    /** Identificador em [col] de [line] (com qualificador `X.` imediatamente antes, se existir). */
    fun identifierAt(line: String, col: Int): IdentRef? {
        if (line.isEmpty()) return null
        var c = col.coerceIn(0, line.length)
        if (c == line.length || !(SqlSplitter.isIdentPart(line[c]) || line[c] == '"')) c-- // cursor logo depois da palavra
        if (c < 0) return null
        val (s, e) = wordBounds(line, c) ?: return null
        val raw = line.substring(s, e)
        if (raw[0].isDigit()) return null
        var qualifier: String? = null
        if (s > 0 && line[s - 1] == '.') {
            val qb = wordBounds(line, s - 2)
            if (qb != null) qualifier = norm(line.substring(qb.first, qb.second))
        }
        return IdentRef(s, e, qualifier, norm(raw))
    }

    private fun wordBounds(line: String, pos: Int): Pair<Int, Int>? {
        if (pos < 0 || pos >= line.length) return null
        val before = line.substring(0, pos).count { it == '"' }
        if (line[pos] == '"' || before % 2 == 1) {
            val open = if (line[pos] == '"') (if (before % 2 == 0) pos else line.lastIndexOf('"', pos - 1)) else line.lastIndexOf('"', pos)
            if (open < 0) return null
            val close = line.indexOf('"', open + 1)
            if (close < 0) return null
            return open to close + 1
        }
        if (!SqlSplitter.isIdentPart(line[pos])) return null
        var s = pos
        var e = pos + 1
        while (s > 0 && SqlSplitter.isIdentPart(line[s - 1])) s--
        while (e < line.length && SqlSplitter.isIdentPart(line[e])) e++
        return s to e
    }
}
