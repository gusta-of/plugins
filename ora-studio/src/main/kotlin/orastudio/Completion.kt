package orastudio

/** Decide o que sugerir numa posicao do texto SQL. Sem Swing: testavel com um [Catalog] falso. */
class CompletionEngine(private val catalog: Catalog, private val owners: () -> List<String>) {

    fun complete(sql: String, caret: Int): CompletionResult {
        val pos = caret.coerceIn(0, sql.length)
        if (inLiteralOrComment(sql, pos)) return CompletionResult(pos, "", emptyList(), false)

        var s = pos
        while (s > 0 && SqlSplitter.isIdentPart(sql[s - 1])) s--
        val prefix = sql.substring(s, pos)
        var loading = false

        // qualificadores: a.  /  s.t.
        var q1: String? = null
        var q2: String? = null
        if (s > 0 && sql[s - 1] == '.') {
            val (n1, st1) = identBefore(sql, s - 1)
            q1 = n1
            if (n1 != null && st1 > 0 && sql[st1 - 1] == '.') q2 = identBefore(sql, st1 - 1).first
        }

        val stmt = SqlSplitter.statementAt(sql, pos)
        val aliases = if (stmt != null) AliasParser.parse(stmt.text) else emptyMap()
        // so os alias escritos de facto (nao o nome da tabela usado como chave): `from pem.` nao e um alias
        val explicit = if (stmt != null) AliasParser.parse(stmt.text, explicitOnly = true) else emptyMap()

        val items = ArrayList<Item>()
        val defaultOwners = owners()

        fun addColumns(owner: String, name: String, label: String): Boolean {
            val cols = catalog.columns(owner, name)
            if (cols == null) { loading = true; return false }
            cols.forEach { items += Item(ident(it.name), ItemKind.COLUMN, null, it.type, label) }
            return cols.isNotEmpty()
        }

        fun addObjects(owner: String) {
            val objs = catalog.objects(owner)
            if (objs == null) { loading = true; return }
            objs.forEach { items += Item(ident(it.name), ItemKind.OBJECT, it.type, "($owner)", it.type.label.lowercase()) }
        }

        fun addPackageMembers(pkg: String) {
            for (o in defaultOwners) {
                val m = catalog.members(o, pkg)
                if (m == null) { loading = true; return }
                if (m.isNotEmpty()) { m.forEach { items += Item(ident(it), ItemKind.MEMBER, null, "($pkg)", "membro") }; return }
            }
        }

        fun columnsInOwners(name: String, label: String): Boolean {
            for (o in defaultOwners) if (addColumns(o, name, label)) return true
            return false
        }

        when {
            q1 != null && q2 != null -> {
                // schema.tabela.  ou  schema.package.
                if (!addColumns(q2, q1, q1)) {
                    val m = catalog.members(q2, q1)
                    if (m == null) loading = true else m.forEach { items += Item(ident(it), ItemKind.MEMBER, null, "($q1)", "membro") }
                }
            }
            q1 != null -> {
                val alias = explicit[q1]
                val schemaList = catalog.schemas()
                when {
                    alias != null -> {
                        val ok = if (alias.owner != null) addColumns(alias.owner, alias.name, alias.name) else columnsInOwners(alias.name, alias.name)
                        if (!ok && alias.owner == null) addPackageMembers(alias.name)
                    }
                    schemaList == null -> loading = true
                    q1 in schemaList -> addObjects(q1)
                    else -> {
                        // nome de tabela/view (da instrucao ou do schema por omissao) ou de package
                        val t = aliases[q1]
                        val ok = if (t?.owner != null) addColumns(t.owner, t.name, t.name) else columnsInOwners(q1, q1)
                        if (!ok) addPackageMembers(q1)
                    }
                }
            }
            else -> {
                val prev = previousWord(sql, s)
                val expectObject = prev in OBJECT_CONTEXT
                if (!expectObject) {
                    aliases.values.distinct().take(8).forEach { t ->
                        if (t.owner != null) addColumns(t.owner, t.name, t.name) else columnsInOwners(t.name, t.name)
                    }
                }
                defaultOwners.forEach { addObjects(it) }
                catalog.schemas()?.forEach { items += Item(ident(it), ItemKind.SCHEMA, null, "", "schema") } ?: run { loading = true }
                if (!expectObject) {
                    val lower = prefix.isNotEmpty() && prefix.none { it.isUpperCase() }
                    KEYWORDS.forEach { items += Item(if (lower) it.lowercase() else it, ItemKind.KEYWORD, null, "", "") }
                }
            }
        }
        return CompletionResult(s, prefix, rank(items, prefix), loading)
    }

    private fun rank(items: List<Item>, prefix: String): List<Item> {
        val p = prefix.uppercase()
        val kindOrder = mapOf(ItemKind.COLUMN to 0, ItemKind.MEMBER to 0, ItemKind.OBJECT to 1, ItemKind.SCHEMA to 2, ItemKind.KEYWORD to 3)
        val starts = ArrayList<Item>()
        val contains = ArrayList<Item>()
        val seen = HashSet<String>()
        for (it in items) {
            val t = it.text.trim('"').uppercase()
            val key = it.kind.name + "|" + it.text + "|" + it.detail
            if (!seen.add(key)) continue
            if (p.isEmpty() || t.startsWith(p)) starts += it else if (p.length >= 2 && t.contains(p)) contains += it
        }
        val cmp = compareBy<Item>({ kindOrder.getValue(it.kind) }, { it.text.length }, { it.text })
        return (starts.sortedWith(cmp) + contains.sortedWith(cmp)).take(500)
    }

    companion object {
        private val OBJECT_CONTEXT = setOf("FROM", "JOIN", "UPDATE", "INTO", "TABLE", "TRUNCATE", "ANALYZE", "DESCRIBE", "DESC", "VIEW", "SEQUENCE", "TRIGGER", "PACKAGE", "PROCEDURE", "FUNCTION", "SYNONYM", "ON", "REFERENCES")

        val KEYWORDS = listOf(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS", "BETWEEN", "LIKE", "AS", "DISTINCT", "UNION", "UNION ALL", "MINUS", "INTERSECT",
            "GROUP BY", "ORDER BY", "HAVING", "JOIN", "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "CROSS JOIN", "ON", "USING", "CASE", "WHEN", "THEN", "ELSE", "END",
            "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM", "MERGE INTO", "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SEQUENCE", "TRIGGER",
            "PROCEDURE", "FUNCTION", "PACKAGE", "COMMIT", "ROLLBACK", "SAVEPOINT", "GRANT", "REVOKE", "WITH", "CONNECT BY", "START WITH", "PRIOR", "LEVEL", "ROWNUM", "ROWID",
            "SYSDATE", "SYSTIMESTAMP", "DUAL", "ASC", "DESC", "NULLS FIRST", "NULLS LAST", "OVER", "PARTITION BY", "BEGIN", "DECLARE", "EXCEPTION", "LOOP", "IF", "ELSIF",
            "NVL", "NVL2", "COALESCE", "DECODE", "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "TRUNC", "ROUND", "SUBSTR", "INSTR", "LENGTH", "UPPER", "LOWER", "TRIM", "REPLACE",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG", "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "ADD_MONTHS", "MONTHS_BETWEEN", "LAST_DAY", "CAST", "REGEXP_LIKE", "REGEXP_SUBSTR",
        )

        /** Nome para inserir: maiusculas simples sem aspas; o resto entre aspas. */
        fun ident(name: String): String = if (Regex("[A-Z][A-Z0-9_$#]*").matches(name)) name else "\"$name\""

        fun inLiteralOrComment(sql: String, pos: Int): Boolean {
            var i = 0
            while (i < pos) {
                val c = sql[i]
                when {
                    c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                        val e = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                        if (pos <= e) return true
                        i = e + 1
                    }
                    c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                        val e = sql.indexOf("*/", i + 2)
                        if (e < 0 || pos <= e + 1) return true
                        i = e + 2
                    }
                    c == '\'' -> {
                        var k = i + 1
                        var closed = false
                        while (k < sql.length) {
                            if (sql[k] == '\'') { if (k + 1 < sql.length && sql[k + 1] == '\'') k += 2 else { closed = true; break } } else k++
                        }
                        if (!closed || pos <= k) return true
                        i = k + 1
                    }
                    else -> i++
                }
            }
            return false
        }

        /** Identificador imediatamente antes da posicao do ponto [dot]: (nome normalizado, inicio). */
        fun identBefore(sql: String, dot: Int): Pair<String?, Int> {
            val e = dot
            if (e > 0 && sql[e - 1] == '"') {
                val open = sql.lastIndexOf('"', e - 2)
                if (open < 0) return null to dot
                return sql.substring(open + 1, e - 1) to open
            }
            var s = e
            while (s > 0 && SqlSplitter.isIdentPart(sql[s - 1])) s--
            if (s == e) return null to dot
            return sql.substring(s, e).uppercase() to s
        }

        fun previousWord(sql: String, before: Int): String {
            var e = before
            while (e > 0 && sql[e - 1].isWhitespace()) e--
            var s = e
            while (s > 0 && SqlSplitter.isIdentPart(sql[s - 1])) s--
            return sql.substring(s, e).uppercase()
        }
    }
}
