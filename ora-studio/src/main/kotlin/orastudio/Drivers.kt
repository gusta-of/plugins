package orastudio

import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Carrega o driver JDBC a partir de JARs escolhidos pelo utilizador (ojdbc6, ojdbc8, ojdbc11, ...),
 * cada driver no seu proprio ClassLoader. Nao usa o DriverManager.
 */
object Drivers {
    const val DEFAULT_ID = "oracle"

    /** Versao descarregavel do Maven Central (ojdbc8 19.x liga a Oracle 11.2 e superiores). */
    const val DOWNLOAD_COORDS = "com/oracle/database/jdbc/ojdbc8/19.25.0.0/ojdbc8-19.25.0.0.jar"

    private val cache = ConcurrentHashMap<String, Driver>()

    /** JARs ojdbc encontrados no ~/.m2 e na pasta de drivers da aplicacao, mais recentes/novos primeiro. */
    fun detectAllJars(): List<File> {
        val roots = listOf(
            File(System.getProperty("user.home"), ".m2/repository/com/oracle"),
            AppPaths.dir.resolve("drivers").toFile(),
        )
        val found = mutableListOf<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            try {
                Files.walk(root.toPath(), 8).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .map { it.toFile() }
                        .filter { Regex("""ojdbc\d+.*\.jar""", RegexOption.IGNORE_CASE).matches(it.name) }
                        .filter { n -> listOf("sources", "javadoc", "tests").none { n.name.contains(it) } }
                        .forEach { found += it }
                }
            } catch (_: Exception) {}
        }
        return found.sortedWith(compareBy({ rank(it) }, { it.name }))
    }

    private fun rank(f: File): Int = when {
        f.name.startsWith("ojdbc8") -> 0
        f.name.startsWith("ojdbc11") -> 1
        f.name.startsWith("ojdbc10") -> 2
        f.name.startsWith("ojdbc7") -> 3
        f.name.startsWith("ojdbc6") -> 4
        else -> 5
    }

    fun detectDefaultJars(): List<String> = detectAllJars().take(1).map { it.absolutePath }

    fun load(d: DriverConfig): Driver {
        val jars = d.jars.filter { it.isNotBlank() }
        if (jars.isEmpty()) error("O driver \"${d.name}\" nao tem JARs. Abre Ficheiro > Drivers e adiciona o ojdbc.")
        val missing = jars.filter { !File(it).isFile }
        if (missing.isNotEmpty()) error("JAR do driver nao encontrado: ${missing.joinToString()}")
        val key = d.className + "|" + jars.joinToString("|")
        return cache.getOrPut(key) {
            val loader = URLClassLoader(jars.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
            Class.forName(d.className, true, loader).getDeclaredConstructor().newInstance() as Driver
        }
    }

    fun connect(cfg: ConnectionConfig, password: String?): Connection {
        val d = ConfigStore.driver(cfg.driverId) ?: error("Driver \"${cfg.driverId}\" nao configurado")
        val driver = load(d)
        val props = Properties().apply {
            setProperty("user", cfg.user)
            setProperty("password", password.orEmpty())
            setProperty("oracle.net.CONNECT_TIMEOUT", (cfg.connectTimeoutSec * 1000).toString())
            setProperty("v\$session.program", "OraStudio")
            setProperty("defaultRowPrefetch", "100")
            // "break" dentro do fluxo normal (o OOB nao atravessa NAT/proxies/docker): permite cancelar consultas
            setProperty("oracle.net.disableOob", "true")
        }
        val thread = Thread.currentThread()
        val old = thread.contextClassLoader
        thread.contextClassLoader = driver.javaClass.classLoader
        try {
            return driver.connect(cfg.effectiveUrl(), props) ?: error("O driver nao aceita o URL: ${cfg.effectiveUrl()}")
        } finally {
            thread.contextClassLoader = old
        }
    }

    /** Descarrega o ojdbc8 do Maven Central para a pasta de drivers da aplicacao. */
    fun downloadOjdbc8(): File {
        val target = AppPaths.ensure().resolve("drivers").also { Files.createDirectories(it) }
            .resolve(DOWNLOAD_COORDS.substringAfterLast('/')).toFile()
        if (target.isFile && target.length() > 1_000_000) return target
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val req = HttpRequest.newBuilder(URI.create("https://repo1.maven.org/maven2/$DOWNLOAD_COORDS")).GET().build()
        val tmp = File(target.parentFile, target.name + ".part")
        val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        if (resp.statusCode() != 200) {
            tmp.delete()
            error("Download falhou (HTTP ${resp.statusCode()})")
        }
        Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }
}
