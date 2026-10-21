package orastudio

import java.sql.Connection
import java.sql.PreparedStatement

enum class ObjectType(
    val sqlName: String,
    val label: String,
    val plural: String,
    /** tipo para DBMS_METADATA.GET_DDL */
    val ddlType: String,
    val relation: Boolean = false,
    val code: Boolean = false,
) {
    TABLE("TABLE", "Tabela", "Tabelas", "TABLE", relation = true),
    VIEW("VIEW", "View", "Views", "VIEW", relation = true),
    MATERIALIZED_VIEW("MATERIALIZED VIEW", "Materialized view", "Materialized views", "MATERIALIZED_VIEW", relation = true),
    PROCEDURE("PROCEDURE", "Procedure", "Procedures", "PROCEDURE", code = true),
    FUNCTION("FUNCTION", "Function", "Functions", "FUNCTION", code = true),
    PACKAGE("PACKAGE", "Package", "Packages", "PACKAGE_SPEC", code = true),
    TRIGGER("TRIGGER", "Trigger", "Triggers", "TRIGGER", code = true),
    TYPE("TYPE", "Type", "Types", "TYPE_SPEC", code = true),
    SEQUENCE("SEQUENCE", "Sequence", "Sequences", "SEQUENCE"),
    SYNONYM("SYNONYM", "Synonym", "Synonyms", "SYNONYM"),
    INDEX("INDEX", "Index", "Indexes", "INDEX");

    companion object {
        /** Categorias mostradas por schema na arvore. */
        val browsable = listOf(TABLE, VIEW, MATERIALIZED_VIEW, PROCEDURE, FUNCTION, PACKAGE, TRIGGER, TYPE, SEQUENCE, SYNONYM)

        fun of(sql: String): ObjectType? = entries.firstOrNull { it.sqlName == sql }
    }
}

data class ObjectRef(val owner: String, val name: String, val type: ObjectType) {
    val qualified: String get() = "$owner.$name"
    fun quoted(): String = "${quote(owner)}.${quote(name)}"
    override fun toString() = "$qualified [${type.label}]"

    companion object {
        fun quote(id: String): String = if (Regex("[A-Z][A-Z0-9_$#]*").matches(id)) id else "\"$id\""
    }
}

data class ObjectInfo(val status: String?, val created: String?, val lastDdl: String?)
data class ObjectName(val name: String, val valid: Boolean)

data class ColumnInfo(val id: Int, val name: String, val type: String, val nullable: Boolean, val default: String?, val comment: String?)

data class ConstraintInfo(
    val name: String,
    val type: String,
    val columns: String,
    val status: String,
    val refTable: ObjectRef?,
    val refColumns: String?,
    val deleteRule: String?,
    val condition: String?,
)

data class IndexInfo(val name: String, val type: String, val unique: Boolean, val columns: String, val status: String)
data class TriggerInfo(val ref: ObjectRef, val event: String, val type: String, val status: String)
data class DependencyInfo(val ref: ObjectRef)
data class SearchHit(val ref: ObjectRef)

class MetadataService(private val handle: ConnectionHandle) {

    private fun <T> run(block: (Connection) -> T): T {
        val s = handle.metaSession()
        return s.sync { block(s.conn) }
    }

    private inline fun <T> Connection.query(sql: String, setup: (PreparedStatement) -> Unit = {}, read: (java.sql.ResultSet) -> T): List<T> {
        prepareStatement(sql).use { ps ->
            ps.fetchSize = 200
            setup(ps)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += read(rs)
                return out
            }
        }
    }

    fun listSchemas(): List<String> = run { c -> c.query("SELECT username FROM all_users ORDER BY username") { it.getString(1) } }

    fun listObjects(owner: String, type: ObjectType): List<ObjectName> = run { c ->
        val notMv = if (type == ObjectType.TABLE) {
            "AND NOT EXISTS (SELECT 1 FROM all_mviews m WHERE m.owner = o.owner AND m.mview_name = o.object_name)"
        } else ""
        c.query(
            "SELECT o.object_name, o.status FROM all_objects o WHERE o.owner = ? AND o.object_type = ? " +
                "AND o.object_name NOT LIKE 'BIN\$%' $notMv ORDER BY o.object_name",
            { it.setString(1, owner); it.setString(2, type.sqlName) },
        ) { ObjectName(it.getString(1), it.getString(2) == "VALID") }
    }

    fun info(ref: ObjectRef): ObjectInfo = run { c ->
        c.query(
            "SELECT status, TO_CHAR(created, 'YYYY-MM-DD HH24:MI:SS'), TO_CHAR(last_ddl_time, 'YYYY-MM-DD HH24:MI:SS') " +
                "FROM all_objects WHERE owner = ? AND object_name = ? AND object_type = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name); it.setString(3, ref.type.sqlName) },
        ) { ObjectInfo(it.getString(1), it.getString(2), it.getString(3)) }.firstOrNull() ?: ObjectInfo(null, null, null)
    }

    fun columns(ref: ObjectRef): List<ColumnInfo> = run { c -> columnsOn(c, ref) }

    private fun columnsOn(c: Connection, ref: ObjectRef): List<ColumnInfo> =
        // data_default e LONG: tem de ser a ultima coluna lida
        c.query(
            "SELECT c.column_id, c.column_name, c.data_type, c.data_length, c.data_precision, c.data_scale, c.nullable, " +
                "c.char_used, c.char_length, cc.comments, c.data_default " +
                "FROM all_tab_columns c LEFT JOIN all_col_comments cc " +
                "ON cc.owner = c.owner AND cc.table_name = c.table_name AND cc.column_name = c.column_name " +
                "WHERE c.owner = ? AND c.table_name = ? ORDER BY c.column_id",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { rs ->
            val id = rs.getInt(1)
            val name = rs.getString(2)
            val dt = rs.getString(3)
            val len = rs.getInt(4)
            val prec = rs.getObject(5) as? Number
            val scale = rs.getObject(6) as? Number
            val nullable = rs.getString(7) == "Y"
            val charUsed = rs.getString(8)
            val charLen = rs.getInt(9)
            val comment = rs.getString(10)
            val default = rs.getString(11)?.trim()
            ColumnInfo(id, name, formatType(dt, len, prec?.toInt(), scale?.toInt(), charUsed, charLen), nullable, default, comment)
        }

    private fun formatType(dt: String, len: Int, prec: Int?, scale: Int?, charUsed: String?, charLen: Int): String = when {
        dt == "NUMBER" -> when {
            prec != null && scale != null && scale != 0 -> "NUMBER($prec,$scale)"
            prec != null -> "NUMBER($prec)"
            else -> "NUMBER"
        }
        dt.startsWith("VARCHAR") || dt == "NVARCHAR2" || dt == "CHAR" || dt == "NCHAR" ->
            "$dt(${if (charUsed == "C") "$charLen CHAR" else "$len"})"
        dt == "RAW" -> "RAW($len)"
        else -> dt
    }

    fun constraints(ref: ObjectRef): List<ConstraintInfo> = run { c -> constraintsOn(c, ref) }

    private fun constraintsOn(c: Connection, ref: ObjectRef): List<ConstraintInfo> {
        val cols = LinkedHashMap<String, MutableList<String>>()
        c.query(
            "SELECT constraint_name, column_name FROM all_cons_columns WHERE owner = ? AND table_name = ? ORDER BY constraint_name, position",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { cols.getOrPut(it.getString(1)) { mutableListOf() }.add(it.getString(2)) }

        class Raw(val name: String, val type: String, val status: String, val rOwner: String?, val rName: String?, val del: String?, val cond: String?)

        val raws = c.query(
            "SELECT constraint_name, constraint_type, status, r_owner, r_constraint_name, delete_rule, search_condition " +
                "FROM all_constraints WHERE owner = ? AND table_name = ? ORDER BY constraint_type, constraint_name",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { Raw(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)?.trim()) }

        return raws.map { r ->
            var refTable: ObjectRef? = null
            var refCols: String? = null
            if (r.type == "R" && r.rOwner != null && r.rName != null) {
                c.query(
                    "SELECT table_name FROM all_constraints WHERE owner = ? AND constraint_name = ?",
                    { it.setString(1, r.rOwner); it.setString(2, r.rName) },
                ) { it.getString(1) }.firstOrNull()?.let { t ->
                    refTable = ObjectRef(r.rOwner, t, ObjectType.TABLE)
                    refCols = c.query(
                        "SELECT column_name FROM all_cons_columns WHERE owner = ? AND constraint_name = ? ORDER BY position",
                        { it.setString(1, r.rOwner); it.setString(2, r.rName) },
                    ) { it.getString(1) }.joinToString(", ")
                }
            }
            ConstraintInfo(r.name, constraintLabel(r.type), cols[r.name]?.joinToString(", ").orEmpty(), r.status, refTable, refCols, r.del, r.cond)
        }
    }

    private fun constraintLabel(t: String) = when (t) {
        "P" -> "PRIMARY KEY"; "U" -> "UNIQUE"; "R" -> "FOREIGN KEY"; "C" -> "CHECK"; "V" -> "WITH CHECK OPTION"; "O" -> "READ ONLY"; else -> t
    }

    fun indexes(ref: ObjectRef): List<IndexInfo> = run { c -> indexesOn(c, ref) }

    private fun indexesOn(c: Connection, ref: ObjectRef): List<IndexInfo> {
        val cols = LinkedHashMap<String, MutableList<String>>()
        c.query(
            "SELECT index_name, column_name, descend FROM all_ind_columns WHERE table_owner = ? AND table_name = ? ORDER BY index_name, column_position",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { cols.getOrPut(it.getString(1)) { mutableListOf() }.add(it.getString(2) + if (it.getString(3) == "DESC") " DESC" else "") }
        return c.query(
            "SELECT index_name, index_type, uniqueness, status FROM all_indexes WHERE table_owner = ? AND table_name = ? ORDER BY index_name",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { IndexInfo(it.getString(1), it.getString(2), it.getString(3) == "UNIQUE", cols[it.getString(1)]?.joinToString(", ").orEmpty(), it.getString(4)) }
    }

    fun triggersOn(ref: ObjectRef): List<TriggerInfo> = run { c ->
        c.query(
            "SELECT owner, trigger_name, trigger_type, triggering_event, status FROM all_triggers WHERE table_owner = ? AND table_name = ? ORDER BY trigger_name",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { TriggerInfo(ObjectRef(it.getString(1), it.getString(2), ObjectType.TRIGGER), it.getString(4), it.getString(3), it.getString(5)) }
    }

    /** Objectos que [ref] usa. */
    fun uses(ref: ObjectRef): List<DependencyInfo> = run { c ->
        val types = if (ref.type == ObjectType.PACKAGE) listOf("PACKAGE", "PACKAGE BODY") else listOf(ref.type.sqlName, ref.type.sqlName)
        c.query(
            "SELECT DISTINCT referenced_owner, referenced_name, referenced_type FROM all_dependencies " +
                "WHERE owner = ? AND name = ? AND type IN (?, ?) AND referenced_type <> 'NON-EXISTENT' " +
                "ORDER BY referenced_type, referenced_owner, referenced_name",
            { it.setString(1, ref.owner); it.setString(2, ref.name); it.setString(3, types[0]); it.setString(4, types[1]); it.maxRows = 1000 },
        ) { rs -> depRef(rs.getString(1), rs.getString(2), rs.getString(3)) }.filterNotNull().map { DependencyInfo(it) }
    }

    /** Objectos que dependem de [ref]. */
    fun usedBy(ref: ObjectRef): List<DependencyInfo> = run { c ->
        val types = when (ref.type) {
            ObjectType.MATERIALIZED_VIEW -> listOf("MATERIALIZED VIEW", "TABLE")
            ObjectType.PACKAGE -> listOf("PACKAGE", "PACKAGE BODY")
            else -> listOf(ref.type.sqlName, ref.type.sqlName)
        }
        c.query(
            "SELECT DISTINCT owner, name, type FROM all_dependencies WHERE referenced_owner = ? AND referenced_name = ? " +
                "AND referenced_type IN (?, ?) ORDER BY type, owner, name",
            { it.setString(1, ref.owner); it.setString(2, ref.name); it.setString(3, types[0]); it.setString(4, types[1]); it.maxRows = 1000 },
        ) { rs -> depRef(rs.getString(1), rs.getString(2), rs.getString(3)) }.filterNotNull().map { DependencyInfo(it) }
    }

    private fun depRef(owner: String, name: String, type: String): ObjectRef? {
        val t = if (type == "PACKAGE BODY") ObjectType.PACKAGE else if (type == "TYPE BODY") ObjectType.TYPE else ObjectType.of(type)
        return t?.let { ObjectRef(owner, name, it) }
    }

    /** Destino de um synonym: owner.nome (pode ser de outro tipo; resolvido depois). */
    fun synonymTarget(ref: ObjectRef): Pair<String, String>? = run { c ->
        c.query(
            "SELECT table_owner, table_name FROM all_synonyms WHERE owner = ? AND synonym_name = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) { it.getString(1) to it.getString(2) }.firstOrNull()
    }

    /** Todos os objectos com esse owner e nome exactos (para resolver o destino de um synonym). */
    fun findExact(owner: String, name: String): List<ObjectRef> = run { c -> lookup(c, owner, name) }

    fun findExactOn(c: Connection, owner: String, name: String): List<ObjectRef> = lookup(c, owner, name)

    private fun lookup(c: Connection, owner: String, name: String): List<ObjectRef> =
        c.query(
            "SELECT owner, object_name, object_type FROM all_objects WHERE owner = ? AND object_name = ? AND object_type IN ($TYPES_IN)",
            { it.setString(1, owner); it.setString(2, name) },
        ) { ObjectType.of(it.getString(3))?.let { t -> ObjectRef(it.getString(1), it.getString(2), t) } }.filterNotNull()

    /** Texto de criacao do objecto (DBMS_METADATA, com fallback para os dicionarios). [body] para package/type body. */
    fun ddl(ref: ObjectRef, body: Boolean = false): String = run { c ->
        val ddlType = when {
            body && ref.type == ObjectType.PACKAGE -> "PACKAGE_BODY"
            body && ref.type == ObjectType.TYPE -> "TYPE_BODY"
            else -> ref.type.ddlType
        }
        try {
            dbmsMetadata(c, ddlType, ref)
        } catch (e: Exception) {
            try {
                fallbackSource(c, ref, body)
            } catch (e2: Exception) {
                throw e
            }
        }
    }

    fun hasBody(ref: ObjectRef): Boolean = run { c ->
        c.query(
            "SELECT 1 FROM all_objects WHERE owner = ? AND object_name = ? AND object_type = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name); it.setString(3, ref.type.sqlName + " BODY") },
        ) { 1 }.isNotEmpty()
    }

    private fun dbmsMetadata(c: Connection, ddlType: String, ref: ObjectRef): String {
        c.createStatement().use {
            it.execute(
                "BEGIN " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE); " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'PRETTY',TRUE); " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES',FALSE); " +
                    "END;",
            )
        }
        return c.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual").use { ps ->
            ps.setString(1, ddlType); ps.setString(2, ref.name); ps.setString(3, ref.owner)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getClob(1).let { clob -> clob.getSubString(1, clob.length().toInt()) }.trim()
            }
        }
    }

    private fun fallbackSource(c: Connection, ref: ObjectRef, body: Boolean): String = when (ref.type) {
        ObjectType.TABLE -> tableDdl(c, ref)
        ObjectType.SEQUENCE -> c.query(
            "SELECT min_value, max_value, increment_by, cycle_flag, order_flag, cache_size, last_number FROM all_sequences WHERE sequence_owner = ? AND sequence_name = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) {
            val min = it.getString(1); val max = it.getString(2); val inc = it.getString(3)
            "$FALLBACK_NOTE\nCREATE SEQUENCE ${ref.quoted()} MINVALUE $min MAXVALUE $max INCREMENT BY $inc START WITH ${it.getString(7)} " +
                "${if (it.getInt(6) > 0) "CACHE ${it.getInt(6)}" else "NOCACHE"} ${if (it.getString(4) == "Y") "CYCLE" else "NOCYCLE"} ${if (it.getString(5) == "Y") "ORDER" else "NOORDER"};"
        }.firstOrNull() ?: error("Sequence nao encontrada")
        ObjectType.SYNONYM -> c.query(
            "SELECT table_owner, table_name, db_link FROM all_synonyms WHERE owner = ? AND synonym_name = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) {
            val target = (it.getString(1)?.let { o -> ObjectRef.quote(o) + "." } ?: "") + ObjectRef.quote(it.getString(2)) + (it.getString(3)?.let { l -> "@$l" } ?: "")
            "$FALLBACK_NOTE\nCREATE OR REPLACE SYNONYM ${ref.quoted()} FOR $target;"
        }.firstOrNull() ?: error("Synonym nao encontrado")
        ObjectType.INDEX -> {
            val row = c.query(
                "SELECT table_owner, table_name, uniqueness FROM all_indexes WHERE owner = ? AND index_name = ?",
                { it.setString(1, ref.owner); it.setString(2, ref.name) },
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }.firstOrNull() ?: error("Indice nao encontrado")
            val cols = c.query(
                "SELECT column_name, descend FROM all_ind_columns WHERE index_owner = ? AND index_name = ? ORDER BY column_position",
                { it.setString(1, ref.owner); it.setString(2, ref.name) },
            ) { ObjectRef.quote(it.getString(1)) + if (it.getString(2) == "DESC") " DESC" else "" }
            "$FALLBACK_NOTE\nCREATE ${if (row.third == "UNIQUE") "UNIQUE " else ""}INDEX ${ref.quoted()} ON ${ObjectRef.quote(row.first)}.${ObjectRef.quote(row.second)} (${cols.joinToString(", ")});"
        }
        ObjectType.VIEW -> "$FALLBACK_NOTE\nCREATE OR REPLACE VIEW ${ref.quoted()} AS\n" +
            c.query("SELECT text FROM all_views WHERE owner = ? AND view_name = ?", { it.setString(1, ref.owner); it.setString(2, ref.name) }) { it.getString(1) }
                .firstOrNull().orEmpty()
        ObjectType.MATERIALIZED_VIEW -> c.query(
            "SELECT refresh_method, refresh_mode, build_mode, query FROM all_mviews WHERE owner = ? AND mview_name = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) {
            val method = it.getString(1); val mode = it.getString(2); val build = it.getString(3)
            val refresh = if (method == "NEVER") "NEVER REFRESH" else "REFRESH $method ON $mode"
            "$FALLBACK_NOTE\nCREATE MATERIALIZED VIEW ${ref.quoted()}\n  BUILD ${if (build == "DEFERRED") "DEFERRED" else "IMMEDIATE"}\n  $refresh\nAS\n" + it.getString(4).orEmpty()
        }.firstOrNull() ?: error("Materialized view nao encontrada")
        ObjectType.TRIGGER -> c.query(
            "SELECT description, when_clause, trigger_body FROM all_triggers WHERE owner = ? AND trigger_name = ?",
            { it.setString(1, ref.owner); it.setString(2, ref.name) },
        ) {
            val desc = it.getString(1).orEmpty().trim()
            val whenC = it.getString(2)?.trim()
            val bodyText = it.getString(3).orEmpty()
            "$FALLBACK_NOTE\nCREATE OR REPLACE TRIGGER $desc" + (if (whenC != null) "\nWHEN ($whenC)" else "") + "\n" + bodyText
        }.firstOrNull() ?: error("Trigger nao encontrado")
        else -> {
            val t = if (body) ref.type.sqlName + " BODY" else ref.type.sqlName
            val text = c.query(
                "SELECT text FROM all_source WHERE owner = ? AND name = ? AND type = ? ORDER BY line",
                { it.setString(1, ref.owner); it.setString(2, ref.name); it.setString(3, t) },
            ) { it.getString(1) }.joinToString("")
            if (text.isBlank()) error("Sem codigo-fonte visivel para ${ref.qualified}")
            "CREATE OR REPLACE $text"
        }
    }

    /** DDL de uma tabela montado a partir do dicionario (quando o DBMS_METADATA nao esta disponivel). */
    private fun tableDdl(c: Connection, ref: ObjectRef): String {
        val cols = columnsOn(c, ref)
        if (cols.isEmpty()) error("Tabela nao encontrada: ${ref.qualified}")
        val cons = constraintsOn(c, ref)
        val notNullOnly = Regex("""^"?[\w$#]+"? IS NOT NULL$""", RegexOption.IGNORE_CASE)
        val lines = ArrayList<String>()
        for (col in cols) {
            lines += "  " + ObjectRef.quote(col.name) + " " + col.type +
                (col.default?.let { " DEFAULT $it" } ?: "") + if (!col.nullable) " NOT NULL" else ""
        }
        val backed = HashSet<String>()
        for (k in cons) {
            val q = ObjectRef.quote(k.name)
            val cl = k.columns.split(", ").joinToString(", ") { ObjectRef.quote(it) }
            when (k.type) {
                "PRIMARY KEY", "UNIQUE" -> { backed += k.name; lines += "  CONSTRAINT $q ${k.type} ($cl)" }
                "FOREIGN KEY" -> k.refTable?.let { t ->
                    lines += "  CONSTRAINT $q FOREIGN KEY ($cl) REFERENCES ${t.quoted()} (${k.refColumns?.split(", ")?.joinToString(", ") { ObjectRef.quote(it) }})" +
                        if (k.deleteRule == "CASCADE") " ON DELETE CASCADE" else if (k.deleteRule == "SET NULL") " ON DELETE SET NULL" else ""
                }
                "CHECK" -> if (k.condition != null && !notNullOnly.matches(k.condition)) lines += "  CONSTRAINT $q CHECK (${k.condition})"
            }
        }
        val sb = StringBuilder(FALLBACK_NOTE).append("\nCREATE TABLE ${ref.quoted()} (\n").append(lines.joinToString(",\n")).append("\n);\n")
        for (ix in indexesOn(c, ref)) {
            if (ix.name in backed) continue
            sb.append("\nCREATE ${if (ix.unique) "UNIQUE " else ""}INDEX ${ObjectRef.quote(ref.owner)}.${ObjectRef.quote(ix.name)} ON ${ref.quoted()} (${ix.columns});")
        }
        c.query("SELECT comments FROM all_tab_comments WHERE owner = ? AND table_name = ?", { it.setString(1, ref.owner); it.setString(2, ref.name) }) { it.getString(1) }
            .firstOrNull()?.let { sb.append("\n\nCOMMENT ON TABLE ${ref.quoted()} IS '${it.replace("'", "''")}';") }
        cols.filter { !it.comment.isNullOrEmpty() }.forEach {
            sb.append("\nCOMMENT ON COLUMN ${ref.quoted()}.${ObjectRef.quote(it.name)} IS '${it.comment!!.replace("'", "''")}';")
        }
        return sb.toString().trimEnd()
    }

    fun search(text: String, preferOwner: String?, limit: Int = 200): List<SearchHit> = run { c ->
        val pattern = "%" + text.trim().uppercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        c.query(
            "SELECT * FROM (SELECT owner, object_name, object_type FROM all_objects " +
                "WHERE object_name LIKE ? ESCAPE '\\' AND object_type IN ($TYPES_IN) AND object_name NOT LIKE 'BIN\$%' " +
                "ORDER BY CASE WHEN owner = ? THEN 0 ELSE 1 END, object_name, owner) WHERE ROWNUM <= $limit",
            { it.setString(1, pattern); it.setString(2, preferOwner ?: "") },
        ) { rs -> ObjectType.of(rs.getString(3))?.let { SearchHit(ObjectRef(rs.getString(1), rs.getString(2), it)) } }.filterNotNull()
    }

    companion object {
        const val FALLBACK_NOTE = "-- Gerado a partir do dicionario de dados (DBMS_METADATA indisponivel neste servidor/utilizador)."
        const val TYPES_IN = "'TABLE','VIEW','MATERIALIZED VIEW','SYNONYM','PROCEDURE','FUNCTION','PACKAGE','TRIGGER','SEQUENCE','TYPE'"

        fun queryCurrentSchema(c: Connection): String =
            c.createStatement().use { st -> st.executeQuery("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual").use { rs -> rs.next(); rs.getString(1) } }
    }
}
