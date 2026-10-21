package orastudio

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AppPaths {
    val dir: Path = run {
        val override = System.getProperty("orastudio.home") ?: System.getenv("ORASTUDIO_HOME")
        when {
            !override.isNullOrBlank() -> Path.of(override)
            System.getProperty("os.name").lowercase().contains("win") ->
                Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "OraStudio")
            else -> Path.of(System.getProperty("user.home"), ".config", "orastudio")
        }
    }

    fun ensure(): Path = dir.also { Files.createDirectories(it) }
    fun file(name: String): File = ensure().resolve(name).toFile()
}

/** Cifra as palavras-passe guardadas (AES-GCM, chave local em secret.key com permissoes 600). */
object Secrets {
    private val random = SecureRandom()

    private fun key(): SecretKeySpec {
        val f = AppPaths.file("secret.key")
        if (!f.exists()) {
            // chave apagada por engano: repoe a ultima copia (senao as palavras-passe guardadas ficariam ilegiveis)
            val saved = Backups.latest("secret.key")
            if (saved != null) saved.copyTo(f) else f.writeBytes(ByteArray(32).also { random.nextBytes(it) })
            try { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
        }
        if (Backups.latest("secret.key") == null) Backups.save(f) // garante que ha sempre uma copia da chave
        return SecretKeySpec(f.readBytes(), "AES")
    }

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val enc = Base64.getEncoder()
        return enc.encodeToString(iv) + ":" + enc.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String? = try {
        val (iv, data) = stored.split(':', limit = 2)
        val dec = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec.decode(iv))) }
        String(c.doFinal(dec.decode(data)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

enum class ConnType(val label: String) {
    SID("SID"), SERVICE_NAME("Service name"), URL("URL");

    override fun toString() = label
}

data class ConnectionConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var driverId: String = Drivers.DEFAULT_ID,
    var type: String = ConnType.SID.name,
    var host: String = "",
    var port: Int = 1521,
    var database: String = "",
    var user: String = "",
    var passwordEnc: String? = null,
    var savePassword: Boolean = true,
    /** So guardado quando o utilizador editou o URL a mao (sobrepoe os campos acima). */
    var url: String? = null,
    var autoCommit: Boolean = true,
    var rowLimit: Int = 500,
    var connectTimeoutSec: Int = 15,
    var initSql: String? = null,
) {
    val connType: ConnType get() = ConnType.entries.firstOrNull { it.name == type } ?: ConnType.SID

    fun generatedUrl(): String = when (connType) {
        ConnType.SID -> "jdbc:oracle:thin:@$host:$port:$database"
        ConnType.SERVICE_NAME -> "jdbc:oracle:thin:@//$host:$port/$database"
        ConnType.URL -> url.orEmpty()
    }

    fun effectiveUrl(): String = url?.takeIf { it.isNotBlank() } ?: generatedUrl()

    fun password(): String? = passwordEnc?.let { Secrets.decrypt(it) }

    fun setPassword(p: String?) {
        passwordEnc = if (p.isNullOrEmpty()) null else Secrets.encrypt(p)
    }

    fun copyAs(newName: String) = copy(id = UUID.randomUUID().toString(), name = newName)

    /** Preenche valores em falta (o Gson nao corre os inicializadores de campos ausentes). */
    fun normalized(): ConnectionConfig = apply {
        if (type.isNullOrBlank()) type = ConnType.SID.name
        if (port <= 0) port = 1521
        if (rowLimit <= 0) rowLimit = 500
        if (connectTimeoutSec <= 0) connectTimeoutSec = 15
        if (driverId.isNullOrBlank()) driverId = Drivers.DEFAULT_ID
        if (name == null) name = ""
        if (host == null) host = ""
        if (database == null) database = ""
        if (user == null) user = ""
    }
}

data class DriverConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var className: String = "oracle.jdbc.OracleDriver",
    var jars: MutableList<String> = mutableListOf(),
)

data class Prefs(
    var windowX: Int = -1,
    var windowY: Int = -1,
    var windowW: Int = 0,
    var windowH: Int = 0,
    var dividerLocation: Int = 0,
    var theme: String = "dark",
    var lastDir: String? = null,
)

/** Copias de seguranca automaticas dos ficheiros de configuracao (~/.config/orastudio/backups). */
object Backups {
    private const val KEEP = 15
    private val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS")

    private fun dir(): File = AppPaths.ensure().resolve("backups").toFile().also { it.mkdirs() }

    /** Copia [f] para backups/<nome>.<data>. */
    fun save(f: File, suffix: String = ""): File? {
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val target = File(dir(), "${f.name}$suffix.${stamp.format(java.util.Date())}")
            f.copyTo(target, overwrite = true)
            try { Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
            prune(f.name)
            target
        } catch (e: Exception) {
            AppLog.error("Falha a criar copia de seguranca de ${f.name}", e)
            null
        }
    }

    /** Copias normais de [name], da mais recente para a mais antiga. */
    fun all(name: String): List<File> =
        dir().listFiles { x -> x.name.startsWith("$name.") && !x.name.contains(".corrupt.") }?.sortedByDescending { it.name }.orEmpty()

    fun latest(name: String): File? = all(name).firstOrNull()

    private fun prune(name: String) {
        all(name).drop(KEEP).forEach { it.delete() }
    }
}

object ConfigStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    var connections: MutableList<ConnectionConfig> = mutableListOf()
        private set
    var drivers: MutableList<DriverConfig> = mutableListOf()
        private set
    var prefs: Prefs = Prefs()
        private set

    @Volatile private var loaded = false

    /** Carrega a configuracao (so uma vez por JVM: varias janelas do IDE partilham a mesma lista). */
    @Synchronized
    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        connections = read("connections.json", object : TypeToken<MutableList<ConnectionConfig>>() {}) ?: mutableListOf()
        connections.forEach { it.normalized() }
        drivers = read("drivers.json", object : TypeToken<MutableList<DriverConfig>>() {}) ?: mutableListOf()
        drivers.forEach { if (it.jars == null) it.jars = mutableListOf() }
        prefs = read("prefs.json", object : TypeToken<Prefs>() {}) ?: Prefs()
        if (drivers.none { it.id == Drivers.DEFAULT_ID }) {
            drivers.add(0, DriverConfig(Drivers.DEFAULT_ID, "Oracle", jars = Drivers.detectDefaultJars().toMutableList()))
            saveDrivers()
        }
    }

    /** Para testes: substitui a lista de drivers sem tocar no disco. */
    internal fun overrideDrivers(list: List<DriverConfig>) {
        drivers = list.toMutableList()
    }

    fun driver(id: String): DriverConfig? = drivers.firstOrNull { it.id == id } ?: drivers.firstOrNull()

    fun saveConnections() = write("connections.json", connections)
    fun saveDrivers() = write("drivers.json", drivers)
    fun savePrefs() = write("prefs.json", prefs)

    /**
     * Le [name]. Se o ficheiro existir mas estiver ilegivel, guarda uma copia ".corrupt" (nunca se perde)
     * e recupera da copia de seguranca mais recente que ainda seja valida.
     */
    private fun <T> read(name: String, type: TypeToken<T>): T? {
        val f = AppPaths.file(name)
        if (!f.exists()) return Backups.latest(name)?.let { tryParse(it, type) }?.also {
            AppLog.error("$name em falta: recuperado da copia de seguranca ${Backups.latest(name)?.name}")
            f.writeText(gson.toJson(it))
        }
        val parsed = tryParse(f, type)
        if (parsed != null || f.length() == 0L) return parsed
        AppLog.error("$name ilegivel: a recuperar da copia de seguranca")
        Backups.save(f, suffix = ".corrupt")
        for (b in Backups.all(name)) {
            val v = tryParse(b, type) ?: continue
            AppLog.error("$name recuperado de ${b.name}")
            f.writeText(gson.toJson(v))
            return v
        }
        return null
    }

    private fun <T> tryParse(f: File, type: TypeToken<T>): T? = try {
        gson.fromJson<T>(f.readText(), type.type)
    } catch (e: Exception) {
        null
    }

    private fun write(name: String, data: Any) {
        try {
            val f = AppPaths.file(name)
            val text = gson.toJson(data)
            // copia de seguranca da versao anterior (so quando o conteudo muda)
            if (f.isFile && f.readText() != text && name != "prefs.json") Backups.save(f)
            val tmp = File(f.parentFile, "$name.tmp")
            tmp.writeText(text)
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            try { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
        } catch (e: Exception) {
            AppLog.error("Falha a gravar $name", e)
        }
    }
}
