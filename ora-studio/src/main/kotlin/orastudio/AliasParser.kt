package orastudio

/** Extrai de uma instrucao os pares alias -> tabela (FROM/JOIN/UPDATE/INTO). */
object AliasParser {
    private val stop = setOf("WHERE", "GROUP", "ORDER", "HAVING", "CONNECT", "START", "UNION", "MINUS", "INTERSECT", "ON", "USING", "SET", "VALUES", "SELECT", "FETCH", "FOR", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER", "LATERAL", "AS")
    private val clause = Regex("""\b(from|join|update|into)\s+""", RegexOption.IGNORE_CASE)
    private val ident = """(?:"[^"]+"|[A-Za-z_][\w${'$'}#]*)"""
    private val table = Regex("""^($ident)(?:\s*\.\s*($ident))?(?:\s+(?:as\s+)?($ident))?""", RegexOption.IGNORE_CASE)

    fun parse(stmt: String, explicitOnly: Boolean = false): Map<String, TableRef> {
        val out = LinkedHashMap<String, TableRef>()
        for (m in clause.findAll(stmt)) {
            var rest = stmt.substring(m.range.last + 1)
            // `FROM a x, b y, c` : le a lista separada por virgulas
            while (true) {
                val t = table.find(rest) ?: break
                val first = norm(t.groupValues[1])
                val second = t.groupValues[2].takeIf { it.isNotEmpty() }?.let { norm(it) }
                val aliasRaw = t.groupValues[3].takeIf { it.isNotEmpty() }
                val (owner, name) = if (second != null) first to second else null to first
                if (name.uppercase() in stop && second == null) break
                val alias = aliasRaw?.let { norm(it) }?.takeIf { it.uppercase() !in stop }
                val ref = TableRef(owner, name)
                if (alias != null) out[alias] = ref
                if (!explicitOnly) out.putIfAbsent(name, ref)
                // continua se a seguir ha uma virgula (so no FROM)
                val after = rest.substring(t.range.last + 1).trimStart()
                if (m.groupValues[1].equals("from", true) && after.startsWith(",")) {
                    rest = after.substring(1).trimStart()
                } else break
            }
        }
        return out
    }

    private fun norm(raw: String) = SqlTokens.norm(raw)
}
