package orastudio

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

/** Registo simples em ficheiro (~/.config/orastudio/orastudio.log) para diagnosticar problemas, sobretudo dentro do IntelliJ. */
object AppLog {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX = 1_000_000L

    @Synchronized
    fun info(msg: String) = write("INFO  $msg")

    @Synchronized
    fun error(msg: String, t: Throwable? = null) {
        val trace = t?.let { StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString() }.orEmpty()
        write("ERROR $msg${if (trace.isNotEmpty()) "\n$trace" else ""}")
    }

    private fun write(line: String) {
        try {
            val f = AppPaths.file("orastudio.log")
            if (f.length() > MAX) f.renameTo(File(f.parentFile, "orastudio.log.1"))
            f.appendText("${fmt.format(Date())} $line\n")
        } catch (_: Exception) {
        }
    }
}
