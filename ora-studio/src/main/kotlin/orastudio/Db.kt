package orastudio

import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Erro de BD legivel: codigo ORA, mensagem e (se o driver o der) posicao do erro no SQL. */
class DbError(val code: Int, val message: String, val position: Int, val cause: Throwable?) {
    val ora: String get() = if (code > 0) "ORA-%05d".format(code) else ""

    override fun toString() = message

    companion object {
        fun of(t: Throwable): DbError {
            var e: Throwable? = t
            while (e != null && e !is SQLException) e = e.cause
            val sql = e as? SQLException
            val msg = (sql?.message ?: t.message ?: t.toString()).trim()
            return DbError(sql?.errorCode ?: 0, msg, errorPosition(t), t)
        }

        /** Posicao (0-based, em chars) do erro dentro do SQL enviado; -1 se o driver nao a fornecer. */
        private fun errorPosition(t: Throwable): Int {
            var e: Throwable? = t
            var depth = 0
            while (e != null && depth++ < 6) {
                try {
                    val m = e.javaClass.methods.firstOrNull { it.name == "getErrorPosition" && it.parameterCount == 0 }
                    if (m != null) {
                        val v = (m.invoke(e) as? Number)?.toInt() ?: -1
                        if (v >= 0) return v
                    }
                } catch (_: Exception) {}
                e = e.cause
            }
            return -1
        }
    }
}

/**
 * Uma sessao JDBC. Todo o acesso passa pela sua thread unica ([submit]/[sync]), por isso nunca ha
 * dois pedidos em simultaneo na mesma Connection. [cancelCurrent] pode ser chamado de qualquer thread.
 */
class DbSession(val conn: Connection, val label: String) : AutoCloseable {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "db-$label").apply { isDaemon = true } }

    @Volatile var running: Statement? = null

    /** Pedido de cancelamento do utilizador: o executor de scripts pare apos a instrucao corrente. */
    @Volatile var cancelRequested = false

    fun <T> submit(task: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        exec.execute {
            try { f.complete(task()) } catch (t: Throwable) { f.completeExceptionally(t) }
        }
        return f
    }

    /** Bloqueia ate terminar. NUNCA chamar a partir da propria thread da sessao. */
    fun <T> sync(task: () -> T): T = try {
        submit(task).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /**
     * Pede ao servidor para cancelar a instrucao corrente. Se ao fim de [graceMs] ela ainda estiver a
     * correr (p.ex. o "break" do Oracle nao atravessa NAT/proxies), fecha a ligacao a forca.
     */
    fun cancelCurrent(graceMs: Long = 4000) {
        cancelRequested = true
        val st = running ?: return
        try { st.cancel() } catch (_: Exception) {}
        Thread({
            try { Thread.sleep(graceMs) } catch (_: InterruptedException) { return@Thread }
            if (running === st) {
                try { conn.close() } catch (_: Exception) {}
            }
        }, "cancel-watchdog-$label").apply { isDaemon = true }.start()
    }

    fun isValid(): Boolean = try { !conn.isClosed && conn.isValid(3) } catch (_: Exception) { false }

    override fun close() {
        cancelCurrent()
        exec.execute { try { conn.close() } catch (_: Exception) {} }
        exec.shutdown()
    }
}

/**
 * Uma ligacao configurada (no navegador). Tem uma sessao de metadados partilhada (arvore, viewers,
 * resolucao de links) e cria sessoes novas para cada consola SQL.
 */
class ConnectionHandle(val config: ConnectionConfig) {
    @Volatile var password: String? = null
    @Volatile var currentSchema: String? = null

    private var meta: DbSession? = null

    val metadata = MetadataService(this)
    val resolver = ObjectResolver(this)

    /** Nomes para o autocompletar (cache, carregada em background numa sessao propria). */
    val catalog: ServerCatalog by lazy { ServerCatalog(this) }

    private var completion: DbSession? = null

    val connected: Boolean get() = meta?.let { !it.conn.isClosed } == true

    val name: String get() = config.name.ifBlank { config.effectiveUrl() }

    fun needsPassword(): Boolean = password == null && config.password() == null

    fun effectivePassword(): String? = password ?: config.password()

    /** Sessao de metadados, (re)ligando se preciso. Pode demorar: chamar fora da EDT. */
    @Synchronized
    fun metaSession(): DbSession {
        val cur = meta
        if (cur != null && cur.isValid()) return cur
        cur?.close()
        val s = openSession("meta")
        meta = s
        currentSchema = s.sync { MetadataService.queryCurrentSchema(s.conn) }
        return s
    }

    @Synchronized
    fun completionSession(): DbSession {
        val cur = completion
        if (cur != null && cur.isValid()) return cur
        cur?.close()
        return openSession("complete").also { completion = it }
    }

    fun openSession(label: String): DbSession {
        val conn = Drivers.connect(config, effectivePassword())
        val s = DbSession(conn, "${config.name}-$label")
        try {
            conn.autoCommit = if (label == "meta") true else config.autoCommit
            config.initSql?.takeIf { it.isNotBlank() }?.let { init ->
                conn.createStatement().use { st -> SqlSplitter.split(init).forEach { st.execute(it.exec) } }
            }
        } catch (t: Throwable) {
            s.close()
            throw t
        }
        return s
    }

    fun disconnect() {
        meta?.close()
        meta = null
        completion?.close()
        completion = null
        resolver.clear()
        catalog.clear()
    }
}
