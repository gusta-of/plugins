package orastudio

import java.sql.CallableStatement
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

class ColumnMeta(val name: String, val typeName: String, val jdbcType: Int) {
    val numeric: Boolean get() = jdbcType in setOf(Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.FLOAT, Types.DOUBLE, Types.REAL, 100, 101)
}

/** Cursor aberto sobre um ResultSet; so pode ser usado na thread da sessao que o criou. */
class ResultCursor(val columns: List<ColumnMeta>, private val stmt: Statement, private var rs: ResultSet?, private val session: DbSession) {
    private var pending: Array<String?>? = null
    var exhausted = false
        private set

    fun fetch(max: Int): List<Array<String?>> {
        val out = ArrayList<Array<String?>>()
        val cur = rs
        if (cur == null) { exhausted = true; return out }
        pending?.let { out += it; pending = null }
        session.running = stmt
        try {
            while (out.size < max && cur.next()) out += SqlRunner.readRow(cur, columns)
            if (out.size >= max) {
                if (cur.next()) pending = SqlRunner.readRow(cur, columns) else exhausted = true
            } else exhausted = true
        } finally {
            session.running = null
        }
        if (exhausted) close()
        return out
    }

    fun close() {
        try { rs?.close() } catch (_: Exception) {}
        try { stmt.close() } catch (_: Exception) {}
        rs = null
    }
}

sealed class Outcome {
    abstract val stmt: Stmt
    abstract val elapsedMs: Long
}

class QueryOutcome(override val stmt: Stmt, override val elapsedMs: Long, val cursor: ResultCursor, val rows: List<Array<String?>>) : Outcome()
class UpdateOutcome(override val stmt: Stmt, override val elapsedMs: Long, val message: String, val notes: List<String>) : Outcome()
class ErrorOutcome(override val stmt: Stmt, override val elapsedMs: Long, val error: DbError) : Outcome()

interface RunListener {
    fun onStart(index: Int, total: Int, stmt: Stmt) {}
    fun onOutcome(outcome: Outcome) {}
    fun onOutput(lines: List<String>) {}
    fun onDone(cancelled: Boolean) {}
}

object SqlRunner {

    /** Executa [stmts] em sequencia. Chamar a partir da thread da sessao (DbSession.submit). */
    fun run(session: DbSession, stmts: List<Stmt>, rowLimit: Int, stopOnError: Boolean, listener: RunListener) {
        session.cancelRequested = false
        val conn = session.conn
        try { enableOutput(conn) } catch (_: Exception) {}
        for ((i, st) in stmts.withIndex()) {
            if (session.cancelRequested) break
            listener.onStart(i, stmts.size, st)
            val outcome = runOne(session, st, rowLimit)
            listener.onOutcome(outcome)
            if (outcome is ErrorOutcome && stopOnError) break
        }
        val lines = try { drainOutput(conn) } catch (_: Exception) { emptyList() }
        if (lines.isNotEmpty()) listener.onOutput(lines)
        listener.onDone(session.cancelRequested)
    }

    private val sqlPlusCommand = Regex("""^(set\s+(?!transaction|role|constraint|constraints)\w+|prompt\b|spool\b|whenever\b|exit\b|quit\b|define\b|undefine\b|rem\b|remark\b|clear\b|column\b|break\b|ttitle\b|btitle\b|connect\b|show\b|accept\b|pause\b|timing\b)""", RegexOption.IGNORE_CASE)

    fun runOne(session: DbSession, st: Stmt, rowLimit: Int): Outcome {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (sqlPlusCommand.containsMatchIn(st.exec.trimStart())) {
            return UpdateOutcome(st, 0, "Comando SQL*Plus ignorado", emptyList())
        }
        var stmt: Statement? = null
        try {
            stmt = session.conn.createStatement()
            stmt.fetchSize = 100
            session.running = stmt
            val isRs = stmt.execute(st.exec)
            if (isRs) {
                val rs = stmt.resultSet
                val md = rs.metaData
                val cols = (1..md.columnCount).map { ColumnMeta(md.getColumnLabel(it), md.getColumnTypeName(it), md.getColumnType(it)) }
                val cursor = ResultCursor(cols, stmt, rs, session)
                session.running = null
                val rows = cursor.fetch(rowLimit.coerceAtLeast(1))
                return QueryOutcome(st, ms(), cursor, rows)
            }
            val count = stmt.updateCount
            val notes = mutableListOf<String>()
            var w = stmt.warnings
            while (w != null) { notes += w.message.orEmpty().trim(); w = w.nextWarning }
            notes += compileErrors(session.conn, st)
            val msg = describe(st.exec, count)
            stmt.close()
            session.running = null
            return UpdateOutcome(st, ms(), msg, notes.filter { it.isNotBlank() })
        } catch (t: Throwable) {
            try { stmt?.close() } catch (_: Exception) {}
            session.running = null
            return ErrorOutcome(st, ms(), DbError.of(t))
        }
    }

    private fun describe(sql: String, count: Int): String {
        val verb = Regex("""^[A-Za-z]+""").find(sql.trimStart())?.value?.uppercase().orEmpty()
        return when (verb) {
            "INSERT", "UPDATE", "DELETE", "MERGE" -> "$count linha(s) afetada(s)"
            "BEGIN", "DECLARE" -> "Bloco PL/SQL executado com sucesso"
            "COMMIT" -> "Commit concluido"
            "ROLLBACK" -> "Rollback concluido"
            "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMENT", "RENAME", "ANALYZE", "AUDIT", "FLASHBACK", "PURGE" ->
                "$verb executado com sucesso"
            else -> if (count >= 0) "$count linha(s) afetada(s)" else "Comando executado com sucesso"
        }
    }

    private val unitHeader = Regex(
        """^create\s+(?:or\s+replace\s+)?(?:and\s+(?:resolve|compile)\s+)?(?:noforce\s+|force\s+)?(?:editionable\s+|noneditionable\s+)?(procedure|function|package\s+body|package|trigger|type\s+body|type)\s+(?:("?[\w${'$'}#]+"?)\.)?("?[\w${'$'}#]+"?)""",
        RegexOption.IGNORE_CASE,
    )

    /** Se a instrucao criou/alterou codigo PL/SQL, devolve os erros de compilacao registados (ALL_ERRORS). */
    private fun compileErrors(conn: Connection, st: Stmt): List<String> {
        val m = unitHeader.find(st.exec.trimStart()) ?: return emptyList()
        val type = m.groupValues[1].uppercase().replace(Regex("\\s+"), " ")
        val owner = m.groupValues[2].ifEmpty { null }?.let { SqlTokens.norm(it) }
        val name = SqlTokens.norm(m.groupValues[3])
        return try {
            val ownerName = owner ?: MetadataService.queryCurrentSchema(conn)
            conn.prepareStatement("SELECT line, position, text FROM all_errors WHERE owner = ? AND name = ? AND type = ? ORDER BY sequence").use { ps ->
                ps.setString(1, ownerName); ps.setString(2, name); ps.setString(3, type)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out += "Erro de compilacao ${rs.getInt(1)}:${rs.getInt(2)}  ${rs.getString(3).trim()}"
                    out
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- leitura de linhas ----

    fun readRow(rs: ResultSet, cols: List<ColumnMeta>): Array<String?> = Array(cols.size) { i -> cell(rs, i + 1, cols[i]) }

    private fun cell(rs: ResultSet, i: Int, col: ColumnMeta): String? = try {
        when (col.jdbcType) {
            Types.CLOB, Types.NCLOB -> rs.getClob(i)?.let { c -> c.getSubString(1, minOf(c.length(), 1_000_000L).toInt()) }
            Types.BLOB -> rs.getBlob(i)?.let { "[BLOB ${it.length()} bytes]" }
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rs.getBytes(i)?.let { hex(it) }
            -10 -> if (rs.getObject(i) == null) null else "[CURSOR]"
            Types.DATE, Types.TIMESTAMP -> rs.getString(i)?.removeSuffix(".0")
            else -> rs.getString(i)
        }
    } catch (e: Exception) {
        try { rs.getObject(i)?.toString() } catch (_: Exception) { "[${col.typeName}]" }
    }

    private fun hex(b: ByteArray): String {
        val max = minOf(b.size, 64)
        val s = b.take(max).joinToString("") { "%02X".format(it) }
        return if (b.size > max) "$s... (${b.size} bytes)" else s
    }

    // ---- DBMS_OUTPUT ----

    fun enableOutput(conn: Connection) {
        conn.prepareCall("BEGIN DBMS_OUTPUT.ENABLE(NULL); END;").use { it.execute() }
    }

    fun drainOutput(conn: Connection, limit: Int = 20000): List<String> {
        val out = ArrayList<String>()
        conn.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;").use { cs: CallableStatement ->
            cs.registerOutParameter(1, Types.VARCHAR)
            cs.registerOutParameter(2, Types.INTEGER)
            while (out.size < limit) {
                cs.execute()
                if (cs.getInt(2) != 0) break
                out += cs.getString(1).orEmpty()
            }
        }
        return out
    }
}
