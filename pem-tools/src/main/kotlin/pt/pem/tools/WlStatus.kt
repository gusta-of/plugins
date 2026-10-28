package pt.pem.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class AppState { PENDING, LOADING, READY, FAILED }

class WlApp(val name: String, var state: AppState = AppState.PENDING, var detail: String = "", var error: String? = null)

data class WlCounts(val ready: Int, val loading: Int, val failed: Int, val total: Int)

/** Artefactos (app-deployment) declarados em config/config.xml do dominio. */
fun expectedApps(domain: Path): List<String> = try {
    val xml = Files.readString(domain.resolve("config/config.xml"))
    Regex("<app-deployment>([\\s\\S]*?)</app-deployment>").findAll(xml).mapNotNull { m ->
        Regex("<name>([^<]+)</name>").find(m.groupValues[1])?.groupValues?.get(1)?.trim()
    }.toList()
} catch (_: Exception) {
    emptyList()
}

/**
 * Acompanha o arranque do WebLogic: estado do servidor e de cada artefacto (a carregar / pronto / falhou), a partir
 * do stdout do startWebLogic e do ficheiro de log do servidor (os deployments em INFO so ai aparecem).
 */
class WlWatcher {
    private val apps = LinkedHashMap<String, WlApp>()
    @Volatile var serverState = ""
        private set
    @Volatile var active = false
        private set
    @Volatile var onChange: () -> Unit = {}

    private var scheduler: ScheduledExecutorService? = null
    private var file: Path? = null
    private var pos = 0L
    private var rest = ""
    private val capturing = HashMap<String, Pair<String, MutableList<String>>?>()

    /** Copia estavel dos artefactos (para a UI). */
    @Synchronized fun snapshot(): List<WlApp> = apps.values.map { WlApp(it.name, it.state, it.detail, it.error) }.sortedBy { it.name.lowercase() }

    @Synchronized fun errorOf(name: String): String? = apps[name]?.error

    @Synchronized fun counts(): WlCounts {
        var ready = 0; var loading = 0; var failed = 0
        for (a in apps.values) when (a.state) { AppState.READY -> ready++; AppState.FAILED -> failed++; else -> loading++ }
        return WlCounts(ready, loading, failed, apps.size)
    }

    fun start(domain: Path, serverName: String = "AdminServer") {
        stop(silent = true)
        synchronized(this) {
            active = true
            apps.clear()
            serverState = "STARTING"
            expectedApps(domain).forEach { apps[it] = WlApp(it) }
            file = domain.resolve("servers/$serverName/logs/$serverName.log")
            pos = try { Files.size(file!!) } catch (_: Exception) { 0L }
            rest = ""
            capturing.clear()
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pem-wl-watch").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ poll() }, 1, 1, TimeUnit.SECONDS) }
        onChange()
    }

    fun stop(silent: Boolean = false) {
        scheduler?.shutdownNow()
        scheduler = null
        val was = synchronized(this) {
            flushCapture("out"); flushCapture("file")
            val w = active
            active = false
            if (w && !silent) serverState = "PARADO"
            w
        }
        if (was && !silent) onChange()
    }

    private fun poll() {
        try {
            val f = file ?: return
            val size = try { Files.size(f) } catch (_: Exception) { return }
            val lines = synchronized(this) {
                if (size < pos) { pos = 0; rest = "" } // rodou (rotacao do log)
                if (size == pos) return
                java.io.RandomAccessFile(f.toFile(), "r").use { raf ->
                    raf.seek(pos)
                    val buf = ByteArray((size - pos).toInt())
                    raf.readFully(buf)
                    pos = size
                    val parts = (rest + String(buf, Charsets.UTF_8)).split(Regex("\r?\n")).toMutableList()
                    rest = parts.removeAt(parts.size - 1)
                    parts
                }
            }
            lines.forEach { feed(it, "file") }
        } catch (_: Exception) { /* ficheiro em rotacao: tenta de novo */ }
    }

    private fun app(name: String) = apps.getOrPut(name) { WlApp(name) }

    private fun flushCapture(src: String) {
        val c = capturing[src] ?: return
        capturing[src] = null
        val a = app(c.first)
        val text = c.second.joinToString("\n")
        if (a.error == null || text.length > a.error!!.length) a.error = text
    }

    /** Uma linha do stdout ("out") ou do ficheiro de log ("file"). */
    fun feed(line: String, src: String = "out") {
        var changed = false
        synchronized(this) {
            val cap = capturing[src]
            if (cap != null) {
                if (HEADER.containsMatchIn(line)) { flushCapture(src); changed = true }
                else { if (cap.second.size < MAX_ERROR_LINES) cap.second += line; return }
            }
            val st = SERVER_STATE.find(line)
            if (st != null) { serverState = st.groupValues[1]; changed = true }
            else {
                val tr = TRANSITION.find(line)
                if (tr != null) {
                    val a = app(tr.groupValues[2])
                    a.detail = "STATE_${tr.groupValues[4]}"
                    if (a.state != AppState.FAILED) a.state = if (tr.groupValues[4] == "ACTIVE") AppState.READY else AppState.LOADING
                    changed = true
                } else {
                    val ok = DEPLOYED.find(line)
                    if (ok != null) { val a = app(ok.groupValues[1]); if (a.state != AppState.FAILED) a.state = AppState.READY; changed = true }
                    else {
                        val fail = FAILED.find(line)
                        if (fail != null) {
                            val a = app(fail.groupValues[1])
                            a.state = AppState.FAILED
                            a.error = null
                            capturing[src] = fail.groupValues[1] to mutableListOf(line)
                            changed = true
                        }
                    }
                }
            }
        }
        if (changed) onChange()
    }

    private companion object {
        const val MAX_ERROR_LINES = 150
        val HEADER = Regex("^(?:####)?<\\d{1,2}/[^>]+>")
        val SERVER_STATE = Regex("Server state changed to ([A-Z_]+)")
        val TRANSITION = Regex("Module (\\S+) of application (\\S+) (?:\\[[^\\]]*\\] )?(?:successfully )?transitioned from STATE_(\\w+) to STATE_(\\w+)")
        val DEPLOYED = Regex("Successfully completed deployment task: .*?(?:deploy|redeploy|start) application (\\S+?) on ", RegexOption.IGNORE_CASE)
        val FAILED = Regex("Failed to (?:initialize|deploy|prepare|activate|start) the application \"([^\"]+)\"")
    }
}
