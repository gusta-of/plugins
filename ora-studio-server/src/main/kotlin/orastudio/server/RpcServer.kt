package orastudio.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import orastudio.AppLog
import orastudio.AppPaths
import orastudio.ColumnMeta
import orastudio.ConfigStore
import orastudio.ConnectionConfig
import orastudio.ConnectionHandle
import orastudio.CompletionEngine
import orastudio.DbSession
import orastudio.DriverConfig
import orastudio.Drivers
import orastudio.ErrorOutcome
import orastudio.ObjectRef
import orastudio.ObjectType
import orastudio.Outcome
import orastudio.QueryOutcome
import orastudio.ResultCursor
import orastudio.RunListener
import orastudio.SqlRunner
import orastudio.SqlSplitter
import orastudio.Stmt
import orastudio.UpdateOutcome
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class ConsoleSession(val id: String, val connId: String, val session: DbSession)
private class CursorEntry(val ownerId: String, val session: DbSession, val cursor: ResultCursor)

/**
 * Protocolo: pedidos `{"id":1,"method":"x","params":{...}}` -> `{"id":1,"result":...}` ou `{"id":1,"error":{"message":"..."}}`;
 * notificacoes do servidor `{"method":"run/query","params":{...}}`.
 */
class RpcServer(private val input: InputStream, output: OutputStream) {

    private val gson = GsonBuilder().serializeNulls()
        .registerTypeAdapter(ObjectType::class.java, object : TypeAdapter<ObjectType?>() {
            override fun write(out: JsonWriter, v: ObjectType?) { if (v == null) out.nullValue() else out.value(v.sqlName) }
            override fun read(r: JsonReader): ObjectType? = if (r.peek() == com.google.gson.stream.JsonToken.NULL) { r.nextNull(); null } else ObjectType.of(r.nextString())
        })
        .create()

    private val out = PrintWriter(output.bufferedWriter(Charsets.UTF_8), false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "rpc").apply { isDaemon = true } }
    private val handles = ConcurrentHashMap<String, ConnectionHandle>()
    private val sessions = ConcurrentHashMap<String, ConsoleSession>()
    private val cursors = ConcurrentHashMap<String, CursorEntry>()
    private val dataSessions = ConcurrentHashMap<String, DbSession>()

    fun serve() {
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val msg = try { gson.fromJson(line, JsonObject::class.java) } catch (e: Exception) { AppLog.error("JSON invalido: $line", e); continue }
                if (msg.get("method")?.asString == "shutdown") break
                pool.execute { handleRequest(msg) }
            }
        }
        shutdown()
    }

    private fun shutdown() {
        sessions.values.forEach { try { it.session.close() } catch (_: Exception) {} }
        dataSessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        handles.values.forEach { try { it.disconnect() } catch (_: Exception) {} }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- transporte ----

    private fun send(obj: Map<String, Any?>) {
        val line = gson.toJson(obj)
        synchronized(out) {
            out.println(line)
            out.flush()
        }
    }

    private fun notify(method: String, params: Map<String, Any?>) = send(mapOf("method" to method, "params" to params))

    private fun handleRequest(msg: JsonObject) {
        val id = msg.get("id")
        val method = msg.get("method")?.asString ?: return
        val params = msg.getAsJsonObject("params") ?: JsonObject()
        try {
            val result = dispatch(method, params)
            send(mapOf("id" to id, "result" to result))
        } catch (t: Throwable) {
            val e = orastudio.DbError.of(t)
            AppLog.error("Erro no pedido $method", t)
            send(mapOf("id" to id, "error" to mapOf("message" to e.message.ifBlank { t.toString() }, "code" to e.code, "stack" to t.stackTraceToString())))
        }
    }

    // ---------------------------------------------------------------- helpers ----

    private fun JsonObject.str(k: String): String = optStr(k) ?: error("parametro em falta: $k")
    private fun JsonObject.optStr(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
    private fun JsonObject.int(k: String, def: Int): Int = get(k)?.takeIf { !it.isJsonNull }?.asInt ?: def
    private fun JsonObject.bool(k: String, def: Boolean): Boolean = get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: def
    private fun JsonObject.ref(k: String = "ref"): ObjectRef = gson.fromJson(get(k), ObjectRef::class.java)
    private fun JsonObject.strList(k: String): List<String> = getAsJsonArray(k)?.map { it.asString }.orEmpty()

    private fun handleFor(id: String): ConnectionHandle = handles.getOrPut(id) {
        val cfg = ConfigStore.connections.firstOrNull { it.id == id } ?: error("Ligacao desconhecida: $id")
        ConnectionHandle(cfg)
    }

    private fun dto(c: ConnectionConfig): Map<String, Any?> = mapOf(
        "id" to c.id, "name" to c.name, "driverId" to c.driverId, "type" to c.type, "host" to c.host, "port" to c.port,
        "database" to c.database, "user" to c.user, "savePassword" to c.savePassword, "url" to c.url,
        "autoCommit" to c.autoCommit, "rowLimit" to c.rowLimit, "connectTimeoutSec" to c.connectTimeoutSec,
        "initSql" to c.initSql, "hasPassword" to (c.passwordEnc != null), "effectiveUrl" to c.effectiveUrl(),
    )

    private fun connections() = ConfigStore.connections.map(::dto)

    // ---------------------------------------------------------------- metodos ----

    private fun dispatch(method: String, p: JsonObject): Any? = when (method) {
        "ping" -> mapOf("version" to "1.0.0", "java" to System.getProperty("java.version"))

        // ---- configuracao (partilhada com o programa Swing e o plugin do IntelliJ) ----
        "config.get" -> mapOf("connections" to connections(), "drivers" to ConfigStore.drivers, "configDir" to AppPaths.dir.toString())
        "connection.save" -> {
            val c = gson.fromJson(p.getAsJsonObject("connection"), ConnectionConfig::class.java).normalized()
            val existing = ConfigStore.connections.firstOrNull { it.id == c.id }
            c.passwordEnc = existing?.passwordEnc
            if (p.has("password")) c.setPassword(p.optStr("password"))
            if (!c.savePassword) c.passwordEnc = null
            if (existing != null) ConfigStore.connections[ConfigStore.connections.indexOf(existing)] = c else ConfigStore.connections.add(c)
            ConfigStore.saveConnections()
            handles.remove(c.id)?.disconnect()
            connections()
        }
        "connection.delete" -> {
            val id = p.str("id")
            ConfigStore.connections.removeAll { it.id == id }
            ConfigStore.saveConnections()
            handles.remove(id)?.disconnect()
            sessions.values.filter { it.connId == id }.forEach { closeSession(it.id) }
            connections()
        }
        "connection.test" -> {
            val c = gson.fromJson(p.getAsJsonObject("connection"), ConnectionConfig::class.java).normalized()
            val pwd = p.optStr("password") ?: ConfigStore.connections.firstOrNull { it.id == c.id }?.password()
            val conn = Drivers.connect(c, pwd)
            try {
                val md = conn.metaData
                mapOf("ok" to true, "version" to md.databaseProductVersion.lineSequence().first(), "driver" to md.driverVersion)
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
        }
        "driver.save" -> {
            val list = gson.fromJson(p.getAsJsonArray("drivers"), Array<DriverConfig>::class.java).toList()
            ConfigStore.drivers.clear(); ConfigStore.drivers.addAll(list)
            ConfigStore.saveDrivers()
            ConfigStore.drivers
        }
        "driver.detect" -> Drivers.detectAllJars().map { it.absolutePath }
        "driver.download" -> Drivers.downloadOjdbc8().absolutePath

        // ---- ligacao e metadados ----
        "connect" -> {
            val h = handleFor(p.str("id"))
            p.optStr("password")?.let { h.password = it }
            val s = h.metaSession()
            mapOf("currentSchema" to h.currentSchema, "version" to s.conn.metaData.databaseProductVersion.lineSequence().first())
        }
        "connection.needsPassword" -> handleFor(p.str("id")).needsPassword()
        "disconnect" -> {
            val id = p.str("id")
            handles[id]?.disconnect()
            sessions.values.filter { it.connId == id }.forEach { closeSession(it.id) }
            true
        }
        "meta.schemas" -> handleFor(p.str("id")).metadata.listSchemas()
        "meta.objects" -> handleFor(p.str("id")).metadata.listObjects(p.str("owner"), ObjectType.of(p.str("type")) ?: error("tipo invalido"))
        "meta.info" -> handleFor(p.str("id")).metadata.info(p.ref())
        "meta.columns" -> handleFor(p.str("id")).metadata.columns(p.ref())
        "meta.constraints" -> handleFor(p.str("id")).metadata.constraints(p.ref())
        "meta.indexes" -> handleFor(p.str("id")).metadata.indexes(p.ref())
        "meta.triggers" -> handleFor(p.str("id")).metadata.triggersOn(p.ref())
        "meta.uses" -> handleFor(p.str("id")).metadata.uses(p.ref())
        "meta.usedBy" -> handleFor(p.str("id")).metadata.usedBy(p.ref())
        "meta.ddl" -> handleFor(p.str("id")).metadata.ddl(p.ref(), p.bool("body", false))
        "meta.hasBody" -> handleFor(p.str("id")).metadata.hasBody(p.ref())
        "meta.synonymTarget" -> handleFor(p.str("id")).metadata.synonymTarget(p.ref())?.let { mapOf("owner" to it.first, "name" to it.second) }
        "meta.findExact" -> handleFor(p.str("id")).metadata.findExact(p.str("owner"), p.str("name"))
        "meta.search" -> handleFor(p.str("id")).metadata.search(p.str("text"), p.optStr("preferOwner"), p.int("limit", 200))
        "meta.currentSchema" -> { val h = handleFor(p.str("id")); h.metaSession(); h.currentSchema }

        // ---- "ir para a definicao" e autocompletar ----
        "resolve" -> {
            val h = handleFor(p.str("id"))
            val owners = p.strList("owners")
            p.getAsJsonArray("items").map { el ->
                val o = el.asJsonObject
                h.metaSession()
                h.resolver.resolveNow(owners, o.optStr("qualifier"), o.str("name"))
            }
        }
        "complete" -> complete(p)

        // ---- consolas SQL ----
        "session.open" -> {
            val h = handleFor(p.str("id"))
            val s = h.openSession("console")
            val sid = UUID.randomUUID().toString()
            s.sync {
                s.conn.autoCommit = p.bool("autoCommit", h.config.autoCommit)
                p.optStr("schema")?.takeIf { it.isNotBlank() }?.let { applySchema(s, it) }
            }
            sessions[sid] = ConsoleSession(sid, h.config.id, s)
            mapOf("sessionId" to sid)
        }
        "session.close" -> { closeSession(p.str("sessionId")); true }
        "session.cancel" -> { sessions[p.str("sessionId")]?.session?.cancelCurrent(); true }
        "session.schema" -> {
            val s = session(p).session
            val schema = p.str("schema")
            s.sync { applySchema(s, schema) }
            true
        }
        "session.autoCommit" -> {
            val s = session(p).session
            s.sync { s.conn.autoCommit = p.bool("value", true) }
            true
        }
        "session.commit" -> { val s = session(p).session; s.sync { s.conn.commit() }; true }
        "session.rollback" -> { val s = session(p).session; s.sync { s.conn.rollback() }; true }
        "session.run" -> run(p)
        "cursor.fetch" -> {
            val e = cursors[p.str("cursorId")] ?: error("cursor fechado")
            val rows = e.session.sync { e.cursor.fetch(p.int("n", 500)) }
            if (e.cursor.exhausted) cursors.remove(p.str("cursorId"))
            mapOf("rows" to rows, "hasMore" to !e.cursor.exhausted)
        }
        "cursor.close" -> { cursors.remove(p.str("cursorId"))?.let { e -> e.session.submit { e.cursor.close() } }; true }
        "table.data" -> tableData(p)

        else -> error("metodo desconhecido: $method")
    }

    private fun session(p: JsonObject): ConsoleSession = sessions[p.str("sessionId")] ?: error("sessao fechada")

    private fun closeSession(sid: String) {
        val cs = sessions.remove(sid) ?: return
        cursors.entries.filter { it.value.ownerId == sid }.forEach { cursors.remove(it.key) }
        cs.session.close()
    }

    private fun Stmt.shifted(by: Int) = Stmt(start + by, end + by, text, exec, plsql)

    private fun rowsOf(cols: List<ColumnMeta>) = cols.map { mapOf("name" to it.name, "typeName" to it.typeName, "numeric" to it.numeric) }

    /** Executa instrucoes numa sessao: devolve ja o plano e envia os resultados como notificacoes run/start, run/query, .... */
    private fun run(p: JsonObject): Any? {
        val cs = session(p)
        val runId = p.str("runId")
        val text = p.str("sql")
        val base = p.int("baseOffset", 0)
        val rowLimit = p.int("rowLimit", 500).coerceIn(1, 100_000)
        val stopOnError = p.bool("stopOnError", true)
        val stmts = (if (p.optStr("mode") == "statement") listOfNotNull(SqlSplitter.statementAt(text, p.int("caret", 0))) else SqlSplitter.split(text)).map { it.shifted(base) }
        // cursores anteriores desta sessao: libertar
        cursors.entries.filter { it.value.ownerId == cs.id }.forEach { e -> cursors.remove(e.key); e.value.session.submit { e.value.cursor.close() } }
        val plan = stmts.map { mapOf("start" to it.start, "end" to it.end, "text" to it.text) }
        if (stmts.isEmpty()) {
            notify("run/done", mapOf("runId" to runId, "cancelled" to false))
            return mapOf("runId" to runId, "statements" to plan)
        }
        val h = handleFor(cs.connId)
        val listener = object : RunListener {
            private fun head(st: Stmt) = mapOf("runId" to runId, "start" to st.start, "end" to st.end, "text" to st.text)

            override fun onStart(index: Int, total: Int, stmt: Stmt) = notify("run/start", head(stmt) + mapOf("index" to index, "total" to total))

            override fun onOutcome(outcome: Outcome) {
                val base0 = head(outcome.stmt) + mapOf("elapsedMs" to outcome.elapsedMs)
                when (outcome) {
                    is QueryOutcome -> {
                        val cid = UUID.randomUUID().toString()
                        if (!outcome.cursor.exhausted) cursors[cid] = CursorEntry(cs.id, cs.session, outcome.cursor)
                        notify("run/query", base0 + mapOf("cursorId" to cid, "columns" to rowsOf(outcome.cursor.columns), "rows" to outcome.rows, "hasMore" to !outcome.cursor.exhausted))
                    }
                    is UpdateOutcome -> {
                        if (Regex("^(create|alter|drop|truncate|rename|grant|revoke|comment)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outcome.stmt.exec.trimStart())) h.catalog.invalidateObjects()
                        notify("run/update", base0 + mapOf("message" to outcome.message, "notes" to outcome.notes))
                    }
                    is ErrorOutcome -> notify("run/error", base0 + mapOf(
                        "message" to outcome.error.message, "code" to outcome.error.code,
                        "position" to (if (outcome.error.position >= 0) outcome.stmt.scriptOffset(outcome.error.position) else -1),
                    ))
                }
            }

            override fun onOutput(lines: List<String>) = notify("run/output", mapOf("runId" to runId, "lines" to lines))
            override fun onDone(cancelled: Boolean) = notify("run/done", mapOf("runId" to runId, "cancelled" to cancelled))
        }
        cs.session.submit { SqlRunner.run(cs.session, stmts, rowLimit, stopOnError, listener) }.whenComplete { _, err ->
            if (err != null) {
                notify("run/error", mapOf("runId" to runId, "index" to -1, "start" to 0, "end" to 0, "text" to "", "elapsedMs" to 0,
                    "message" to (err.message ?: err.toString()), "code" to 0, "position" to -1))
                notify("run/done", mapOf("runId" to runId, "cancelled" to false))
            }
        }
        return mapOf("runId" to runId, "statements" to plan)
    }

    /** Dados de uma tabela/view/MV: sessao dedicada por ligacao (nao bloqueia o resto). */
    private fun tableData(p: JsonObject): Any? {
        val h = handleFor(p.str("id"))
        val ref = p.ref()
        val where = p.optStr("where")?.trim().orEmpty()
        val limit = p.int("limit", 500)
        val sql = "SELECT * FROM ${ref.quoted()}" + if (where.isEmpty()) "" else
            if (Regex("^(order|group|fetch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(where)) " $where" else " WHERE $where"
        val key = h.config.id
        val s = dataSessions[key]?.takeIf { it.isValid() } ?: h.openSession("data").also { dataSessions[key] = it }
        cursors.entries.filter { it.value.ownerId == "data:$key" }.forEach { e -> cursors.remove(e.key); e.value.session.submit { e.value.cursor.close() } }
        val outcome = s.sync { SqlRunner.runOne(s, SqlSplitter.split(sql).first(), limit) }
        return when (outcome) {
            is QueryOutcome -> {
                val cid = UUID.randomUUID().toString()
                if (!outcome.cursor.exhausted) cursors[cid] = CursorEntry("data:$key", s, outcome.cursor)
                mapOf("cursorId" to cid, "columns" to rowsOf(outcome.cursor.columns), "rows" to outcome.rows, "hasMore" to !outcome.cursor.exhausted, "elapsedMs" to outcome.elapsedMs, "sql" to sql)
            }
            is ErrorOutcome -> error(outcome.error.message)
            else -> error("resultado inesperado")
        }
    }

    /** Autocompletar: se a cache ainda esta a carregar espera pelos dados (ate 4 s) (para a lista vir completa). */
    private fun complete(p: JsonObject): Any? {
        val h = handleFor(p.str("id"))
        val sql = p.str("sql")
        val caret = p.int("caret", sql.length)
        val owners = p.strList("owners").ifEmpty { listOfNotNull(h.currentSchema) }
        val engine = CompletionEngine(h.catalog, { owners })
        var r = engine.complete(sql, caret)
        // cada carga (schemas, objectos, colunas...) acorda-nos; repete ate nao faltar nada (max. 4 s)
        val deadline = System.currentTimeMillis() + 4000
        while (r.loading && System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            val l = { latch.countDown() }
            h.catalog.onChange(l)
            try { latch.await(500, TimeUnit.MILLISECONDS) } finally { h.catalog.removeListener(l) }
            r = engine.complete(sql, caret)
        }
        return mapOf("prefixStart" to r.prefixStart, "prefix" to r.prefix, "loading" to r.loading, "items" to r.items)
    }
}

/** ALTER SESSION SET CURRENT_SCHEMA: os nomes sem prefixo passam a ser resolvidos neste schema. */
private fun applySchema(s: DbSession, schema: String) {
    val id = if (Regex("^[A-Z][A-Z0-9_\\$#]*$").matches(schema)) schema else "\"" + schema.replace("\"", "\"\"") + "\""
    s.conn.createStatement().use { it.execute("ALTER SESSION SET CURRENT_SCHEMA = $id") }
}
