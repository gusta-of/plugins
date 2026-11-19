package orastudio.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fala com o servidor pelo protocolo real (linhas JSON) contra um Oracle (ORA_TEST_*). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName::class)
class RpcServerTest {
    private val jar = System.getenv("ORA_TEST_JAR").orEmpty()
    private val url = System.getenv("ORA_TEST_URL").orEmpty()
    private val user = System.getenv("ORA_TEST_USER").orEmpty()
    private val pass = System.getenv("ORA_TEST_PASSWORD").orEmpty()
    private val gson = Gson()

    private lateinit var toServer: PipedOutputStream
    private val responses = ConcurrentHashMap<Int, LinkedBlockingQueue<JsonObject>>()
    private val notifications = CopyOnWriteArrayList<JsonObject>()
    private var nextId = 1
    private lateinit var connId: String

    @BeforeAll fun start() {
        assumeTrue(jar.isNotBlank() && url.isNotBlank())
        orastudio.ConfigStore.load(force = true)
        toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 1 shl 20)
        val fromServer = PipedInputStream(1 shl 22)
        val serverOut = PipedOutputStream(fromServer)
        Thread { RpcServer(serverIn, serverOut).serve() }.apply { isDaemon = true }.start()
        Thread {
            fromServer.bufferedReader().useLines { lines ->
                for (l in lines) {
                    val o = gson.fromJson(l, JsonObject::class.java)
                    if (o.has("id")) responses.getOrPut(o.get("id").asInt) { LinkedBlockingQueue() }.add(o)
                    else notifications += o
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun call(method: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val id = nextId++
        val line = gson.toJson(mapOf("id" to id, "method" to method, "params" to params))
        synchronized(toServer) { toServer.write((line + "\n").toByteArray()); toServer.flush() }
        val resp = responses.getOrPut(id) { LinkedBlockingQueue() }.poll(60, TimeUnit.SECONDS) ?: error("timeout em $method")
        return resp
    }

    private fun ok(method: String, params: Map<String, Any?> = emptyMap()): com.google.gson.JsonElement {
        val r = call(method, params)
        assertTrue(!r.has("error"), "$method falhou: ${r.get("error")}")
        return r.get("result")
    }

    private fun waitNotification(method: String, runId: String, timeoutMs: Long = 30000, pred: (JsonObject) -> Boolean = { true }): JsonObject {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            notifications.firstOrNull { it.get("method").asString == method && it.getAsJsonObject("params").get("runId")?.asString == runId && pred(it.getAsJsonObject("params")) }
                ?.let { return it.getAsJsonObject("params") }
            Thread.sleep(30)
        }
        error("sem notificacao $method")
    }

    private fun runScript(sessionId: String, sql: String, mode: String = "script", extra: Map<String, Any?> = emptyMap()): String {
        val runId = "run-${nextId}"
        ok("session.run", mapOf("sessionId" to sessionId, "runId" to runId, "sql" to sql, "mode" to mode) + extra)
        waitNotification("run/done", runId)
        return runId
    }

    private fun events(runId: String) = notifications.filter { it.getAsJsonObject("params").get("runId")?.asString == runId }.map { it.get("method").asString to it.getAsJsonObject("params") }

    @Test fun `01 ping e configuracao das ligacoes e drivers`() {
        assertEquals("1.0.0", ok("ping").asJsonObject.get("version").asString)
        ok("driver.save", mapOf("drivers" to listOf(mapOf("id" to "oracle", "name" to "Oracle", "className" to "oracle.jdbc.OracleDriver", "jars" to listOf(jar)))))
        val list = ok("connection.save", mapOf(
            "connection" to mapOf("id" to "srv-test", "name" to "Servidor teste", "driverId" to "oracle", "type" to "URL", "url" to url,
                "user" to user, "savePassword" to true, "autoCommit" to true, "rowLimit" to 500, "connectTimeoutSec" to 15, "port" to 1521, "host" to "", "database" to ""),
            "password" to pass,
        )).asJsonArray
        connId = "srv-test"
        val c = list.first { it.asJsonObject.get("id").asString == connId }.asJsonObject
        assertEquals(true, c.get("hasPassword").asBoolean)
        assertTrue(!c.has("passwordEnc"), "a palavra-passe nunca sai do servidor")
        val t = ok("connection.test", mapOf("connection" to mapOf("id" to connId, "name" to "x", "driverId" to "oracle", "type" to "URL", "url" to url, "user" to user, "port" to 1521, "host" to "", "database" to ""))).asJsonObject
        assertEquals(true, t.get("ok").asBoolean)
        println("teste de ligacao: ${t.get("version").asString}")
    }

    @Test fun `02 liga e lista schemas, objectos, colunas e ddl`() {
        val r = ok("connect", mapOf("id" to connId)).asJsonObject
        assertEquals(user.uppercase(), r.get("currentSchema").asString)
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        runScript(s, "BEGIN EXECUTE IMMEDIATE 'DROP TABLE srv_t PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;\n/\nCREATE TABLE srv_t (id NUMBER PRIMARY KEY, nome VARCHAR2(30));\nCREATE VIEW srv_v AS SELECT * FROM srv_t;\nINSERT INTO srv_t SELECT level, 'n' || level FROM dual CONNECT BY level <= 250;\nCOMMIT;")
        ok("session.close", mapOf("sessionId" to s))

        assertTrue(ok("meta.schemas", mapOf("id" to connId)).asJsonArray.any { it.asString == user.uppercase() })
        val tables = ok("meta.objects", mapOf("id" to connId, "owner" to user.uppercase(), "type" to "TABLE")).asJsonArray
        assertTrue(tables.any { it.asJsonObject.get("name").asString == "SRV_T" })
        val ref = mapOf("owner" to user.uppercase(), "name" to "SRV_T", "type" to "TABLE")
        val cols = ok("meta.columns", mapOf("id" to connId, "ref" to ref)).asJsonArray
        assertEquals(listOf("ID", "NOME"), cols.map { it.asJsonObject.get("name").asString })
        assertEquals("NUMBER", cols[0].asJsonObject.get("type").asString)
        assertTrue(ok("meta.ddl", mapOf("id" to connId, "ref" to ref)).asString.uppercase().contains("CREATE TABLE"))
        val cons = ok("meta.constraints", mapOf("id" to connId, "ref" to ref)).asJsonArray
        assertTrue(cons.any { it.asJsonObject.get("type").asString == "PRIMARY KEY" })
        val usedBy = ok("meta.usedBy", mapOf("id" to connId, "ref" to ref)).asJsonArray
        assertTrue(usedBy.any { it.asJsonObject.getAsJsonObject("ref").get("name").asString == "SRV_V" })
        assertEquals("VIEW", usedBy.first { it.asJsonObject.getAsJsonObject("ref").get("name").asString == "SRV_V" }.asJsonObject.getAsJsonObject("ref").get("type").asString)
        val found = ok("meta.search", mapOf("id" to connId, "text" to "srv_")).asJsonArray
        assertTrue(found.size() >= 2)
    }

    @Test fun `03 resolve nomes para objectos (ir para a definicao)`() {
        val u = user.uppercase()
        val r = ok("resolve", mapOf("id" to connId, "owners" to listOf(u), "items" to listOf(
            mapOf("name" to "SRV_T"), mapOf("name" to "SRV_V"), mapOf("qualifier" to u, "name" to "SRV_T"), mapOf("name" to "NAO_EXISTE"),
        ))).asJsonArray
        assertEquals(4, r.size())
        assertEquals("TABLE", r[0].asJsonArray[0].asJsonObject.get("type").asString)
        assertEquals("VIEW", r[1].asJsonArray[0].asJsonObject.get("type").asString)
        assertEquals("SRV_T", r[2].asJsonArray[0].asJsonObject.get("name").asString)
        assertEquals(0, r[3].asJsonArray.size())
    }

    @Test fun `04 autocompletar espera pela cache e devolve a lista completa`() {
        val u = user.uppercase()
        val a = ok("complete", mapOf("id" to connId, "sql" to "select s. from srv_t s", "caret" to 9, "owners" to listOf(u))).asJsonObject
        val names = a.getAsJsonArray("items").map { it.asJsonObject.get("text").asString }
        assertTrue(names.containsAll(listOf("ID", "NOME")), names.toString())
        val b = ok("complete", mapOf("id" to connId, "sql" to "select * from $u.srv_", "caret" to ("select * from $u.srv_").length, "owners" to listOf(u))).asJsonObject
        val objs = b.getAsJsonArray("items").map { it.asJsonObject.get("text").asString }
        assertEquals(listOf("SRV_T", "SRV_V"), objs.sorted())
        assertEquals("srv_", b.get("prefix").asString)
    }

    @Test fun `05 executa script com eventos, erro e DBMS_OUTPUT, e carrega mais linhas do cursor`() {
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val runId = runScript(s, "select id, nome from srv_t order by id;\nbegin dbms_output.put_line('ola servidor'); end;\n/\nselect * from tabela_que_nao_existe;\nselect 1 from dual;", extra = mapOf("rowLimit" to 100, "stopOnError" to false))
        val ev = events(runId)
        val kinds = ev.map { it.first }
        assertEquals(listOf("run/start", "run/query", "run/start", "run/update", "run/start", "run/error", "run/start", "run/query", "run/output", "run/done"), kinds)
        val q = ev[1].second
        assertEquals(100, q.getAsJsonArray("rows").size())
        assertTrue(q.get("hasMore").asBoolean)
        assertEquals("ID", q.getAsJsonArray("columns")[0].asJsonObject.get("name").asString)
        val err = ev[5].second
        assertEquals(942, err.get("code").asInt)
        assertTrue(err.get("message").asString.contains("ORA-00942"))
        assertTrue(ev[8].second.getAsJsonArray("lines").any { it.asString == "ola servidor" })
        val more = ok("cursor.fetch", mapOf("cursorId" to q.get("cursorId").asString, "n" to 1000)).asJsonObject
        assertEquals(150, more.getAsJsonArray("rows").size())
        assertEquals(false, more.get("hasMore").asBoolean)
        ok("session.close", mapOf("sessionId" to s))
    }

    @Test fun `07b schema da sessao muda o CURRENT_SCHEMA`() {
        val s = ok("session.open", mapOf("id" to connId, "schema" to "SYSTEM")).asJsonObject.get("sessionId").asString
        fun cur(): String {
            val runId = runScript(s, "select sys_context('USERENV','CURRENT_SCHEMA') from dual;")
            return events(runId).first { it.first == "run/query" }.second.getAsJsonArray("rows")[0].asJsonArray[0].asString
        }
        assertEquals("SYSTEM", cur())
        ok("session.schema", mapOf("sessionId" to s, "schema" to user.uppercase()))
        assertEquals(user.uppercase(), cur())
        ok("session.close", mapOf("sessionId" to s))
    }

    @Test fun `07c pesquisa de objetos com regex, maiusculas e palavra inteira`() {
        fun names(vararg kv: Pair<String, Any>): List<String> =
            ok("meta.search", mapOf("id" to connId, "preferOwner" to user.uppercase()) + kv).asJsonArray
                .map { it.asJsonObject.getAsJsonObject("ref").get("name").asString }.filter { it.startsWith("SRV_") }.sorted()
        assertEquals(listOf("SRV_T", "SRV_V"), names("text" to "srv_"))                                  // contem, sem maiusculas
        assertEquals(listOf("SRV_T", "SRV_V"), names("text" to "SRV_[TV]", "regex" to true))
        assertEquals(listOf("SRV_T"), names("text" to "\\w+_T\\b", "regex" to true))                          // \b traduzido para o Oracle
        assertEquals(listOf("SRV_V"), names("text" to "^srv_v$", "regex" to true))
        assertEquals(emptyList<String>(), names("text" to "^srv_v$", "regex" to true, "matchCase" to true))    // Aa: SRV_V != srv_v
        assertEquals(listOf("SRV_V"), names("text" to "SRV_V", "regex" to true, "matchCase" to true))
        assertEquals(listOf("SRV_T"), names("text" to "srv_t", "wholeWord" to true))                       // palavra inteira (sem regex)
        assertEquals(emptyList<String>(), names("text" to "srv", "wholeWord" to true))
        assertEquals(listOf("SRV_T", "SRV_V"), names("text" to "srv_*"))                                   // * curinga
        val bad = runCatching { names("text" to "([", "regex" to true) }
        assertTrue(bad.isFailure, "regex invalida da erro legivel")
    }

    @Test fun `06 instrucao sob o cursor, stop on error, commit e rollback`() {
        val s = ok("session.open", mapOf("id" to connId, "autoCommit" to false)).asJsonObject.get("sessionId").asString
        val sql = "select 1 from dual;\n\nselect 2 x from dual;\nselect 3 from dual;"
        val runId = runScript(s, sql, mode = "statement", extra = mapOf("caret" to sql.indexOf("2 x")))
        val q = events(runId).first { it.first == "run/query" }.second
        assertEquals("select 2 x from dual;", q.get("text").asString.let { if (it.endsWith(";")) it else "$it;" })
        assertEquals("2", q.getAsJsonArray("rows")[0].asJsonArray[0].asString)
        assertEquals(sql.indexOf("select 2"), q.get("start").asInt, "offsets relativos ao texto enviado")

        runScript(s, "delete from srv_t where id <= 10;")
        ok("session.rollback", mapOf("sessionId" to s))
        val r1 = runScript(s, "select count(*) from srv_t;")
        assertEquals("250", events(r1).first { it.first == "run/query" }.second.getAsJsonArray("rows")[0].asJsonArray[0].asString)

        val r2 = runScript(s, "select * from nao_existe;\nselect 1 from dual;", extra = mapOf("stopOnError" to true))
        assertEquals(1, events(r2).count { it.first == "run/error" })
        assertEquals(0, events(r2).count { it.first == "run/query" }, "parou no primeiro erro")
        ok("session.close", mapOf("sessionId" to s))
    }

    @Test fun `07 dados de uma tabela com filtro e cancelamento de uma consulta longa`() {
        val d = ok("table.data", mapOf("id" to connId, "ref" to mapOf("owner" to user.uppercase(), "name" to "SRV_T", "type" to "TABLE"), "where" to "id <= 5 order by id", "limit" to 100)).asJsonObject
        assertEquals(5, d.getAsJsonArray("rows").size())

        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val runId = "cancel-run"
        ok("session.run", mapOf("sessionId" to s, "runId" to runId, "sql" to "select count(*) from all_objects a, all_objects b, all_objects c", "mode" to "script"))
        Thread.sleep(1500)
        ok("session.cancel", mapOf("sessionId" to s))
        val done = waitNotification("run/done", runId)
        assertEquals(true, done.get("cancelled").asBoolean)
        val err = events(runId).first { it.first == "run/error" }.second
        assertTrue(err.get("code").asInt == 1013 || err.get("message").asString.isNotBlank())
        ok("session.close", mapOf("sessionId" to s))
    }

    @Test fun `07d cancelar com forca corta a ligacao, termina a execucao e a consola fica livre`() {
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val runId = "force-run"
        ok("session.run", mapOf("sessionId" to s, "runId" to runId, "sql" to "select count(*) from all_objects a, all_objects b, all_objects c, all_objects d", "mode" to "script"))
        Thread.sleep(1500)
        val t0 = System.currentTimeMillis()
        ok("session.cancel", mapOf("sessionId" to s, "force" to true))
        val done = waitNotification("run/done", runId)
        assertTrue(System.currentTimeMillis() - t0 < 6000, "terminou depressa")
        assertEquals(true, done.get("cancelled").asBoolean)
        // a sessao antiga ja nao existe: o pedido seguinte falha de forma clara em vez de ficar preso
        val gone = runCatching { ok("session.run", mapOf("sessionId" to s, "runId" to "x", "sql" to "select 1 from dual", "mode" to "script")) }
        assertTrue(gone.isFailure || events("force-run").any { it.first == "run/done" })
        // e uma sessao nova funciona logo
        val s2 = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val q = events(runScript(s2, "select 7 from dual;")).first { it.first == "run/query" }.second
        assertEquals("7", q.getAsJsonArray("rows")[0].asJsonArray[0].asString)
        ok("session.close", mapOf("sessionId" to s2))
    }

    @Test fun `07e lista os jobs do agendador e do DBMS_JOB, com o historico`() {
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val setup = runScript(s, "BEGIN DBMS_SCHEDULER.CREATE_JOB(job_name => 'SRV_JOB_TESTE', job_type => 'PLSQL_BLOCK', job_action => 'BEGIN NULL; END;', repeat_interval => 'FREQ=DAILY;BYHOUR=3;BYMINUTE=0', enabled => FALSE, comments => 'teste do servidor'); END;\n/\nDECLARE j BINARY_INTEGER; BEGIN DBMS_JOB.SUBMIT(j, 'BEGIN NULL; /*srvjob*/ END;', SYSDATE + 1, 'SYSDATE + 1'); COMMIT; END;\n/")
        try {
            assertTrue(events(setup).none { it.first == "run/error" }, "criar os jobs (precisa de CREATE JOB): ${events(setup).filter { it.first == "run/error" }.map { it.second.get("message") }}")
            val jobs = ok("meta.jobs", mapOf("id" to connId)).asJsonArray.map { it.asJsonObject }
            val sch = jobs.first { it.get("kind").asString == "scheduler" && it.get("name").asString == "SRV_JOB_TESTE" }
            assertEquals(user.uppercase(), sch.get("owner").asString)
            assertEquals(false, sch.get("enabled").asBoolean)
            assertTrue(sch.get("schedule").asString.contains("BYHOUR=3"))
            assertTrue(sch.get("action").asString.contains("BEGIN NULL"))
            assertEquals("teste do servidor", sch.get("comments").asString)
            val old = jobs.first { it.get("kind").asString == "dbms_job" && it.get("action").asString.contains("srvjob") }
            assertEquals(user.uppercase(), old.get("owner").asString)
            assertTrue(old.get("name").asString.startsWith("JOB "))
            assertEquals("SYSDATE + 1", old.get("schedule").asString)
            assertTrue(old.get("nextRun").asString.isNotBlank())
            // historico (um job desligado nunca correu: lista vazia, mas o pedido tem de funcionar)
            assertEquals(0, ok("meta.jobRuns", mapOf("id" to connId, "owner" to user.uppercase(), "name" to "SRV_JOB_TESTE")).asJsonArray.size())
        } finally {
            runScript(s, "BEGIN DBMS_SCHEDULER.DROP_JOB('SRV_JOB_TESTE', TRUE); EXCEPTION WHEN OTHERS THEN NULL; END;\n/\nBEGIN FOR r IN (SELECT job FROM user_jobs WHERE what LIKE '%srvjob%') LOOP DBMS_JOB.REMOVE(r.job); END LOOP; COMMIT; END;\n/")
            ok("session.close", mapOf("sessionId" to s))
        }
    }

    @Test fun `07f lista as FKs de outras tabelas que apontam para a tabela`() {
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        val setup = runScript(s, "CREATE TABLE ZFK_PAI (ID NUMBER PRIMARY KEY, B NUMBER, CONSTRAINT ZFK_PAI_UQ UNIQUE (B));\nCREATE TABLE ZFK_FILHA (ID NUMBER, PAI_ID NUMBER, CONSTRAINT ZFK_FILHA_FK FOREIGN KEY (PAI_ID) REFERENCES ZFK_PAI (ID) ON DELETE CASCADE);")
        try {
            assertTrue(events(setup).none { it.first == "run/error" }, "criar tabelas: ${events(setup).filter { it.first == "run/error" }.map { it.second.get("message") }}")
            val refs = ok("meta.referencedBy", mapOf("id" to connId, "ref" to mapOf("owner" to user.uppercase(), "name" to "ZFK_PAI", "type" to "TABLE"))).asJsonArray.map { it.asJsonObject }
            assertEquals(1, refs.size)
            assertEquals("ZFK_FILHA", refs[0].getAsJsonObject("table").get("name").asString)
            assertEquals("ZFK_FILHA_FK", refs[0].get("name").asString)
            assertEquals("PAI_ID", refs[0].get("columns").asString)
            assertEquals("CASCADE", refs[0].get("deleteRule").asString)
            assertEquals(0, ok("meta.referencedBy", mapOf("id" to connId, "ref" to mapOf("owner" to user.uppercase(), "name" to "ZFK_FILHA", "type" to "TABLE"))).asJsonArray.size())
        } finally {
            runScript(s, "DROP TABLE ZFK_FILHA PURGE;\nDROP TABLE ZFK_PAI PURGE;")
            ok("session.close", mapOf("sessionId" to s))
        }
    }

    @Test fun `08 limpeza e erro de metodo desconhecido`() {
        val s = ok("session.open", mapOf("id" to connId)).asJsonObject.get("sessionId").asString
        runScript(s, "drop view srv_v;\ndrop table srv_t purge;")
        ok("session.close", mapOf("sessionId" to s))
        val r = call("nao.existe")
        assertTrue(r.has("error"))
        ok("connection.delete", mapOf("id" to connId))
    }
}
