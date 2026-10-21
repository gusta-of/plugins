package pt.pem.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Caminhos lidos dos campos da UI no momento do clique. */
data class Paths(val workspace: Path, val domain: Path) {
    val upload: Path get() = domain.resolve("servers/AdminServer/upload")
    val lib: Path get() = domain.resolve("lib")
    val wlUser: Path get() = domain.resolve("servers/AdminServer/tmp/_WL_user")
    val autodeploy: Path get() = domain.resolve("autodeploy")
    val liferayDeploy: Path get() = domain.resolve("liferay/deploy")
}

/**
 * Executa sequencias de passos em background, escrevendo na consola. Um passo devolve true se correu bem.
 * Os passos nunca interrompem a sequencia (como o build-backend.sh); no fim imprime-se o resumo.
 */
class TaskRunner(private val project: Project, val console: LogPanel) {

    @Volatile private var current: OSProcessHandler? = null
    @Volatile var busy = false
        private set
    @Volatile private var cancelled = false

    /** (feitos, total, rotulo do passo atual). total == 0 -> indeterminado. Chamado fora da EDT. */
    /** Chamado (fora da EDT) quando uma tarefa termina, com sucesso ou nao. */
    @Volatile var onFinished: () -> Unit = {}

    @Volatile var onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }

    fun log(msg: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) =
        console.print(msg + "\n", type)

    fun cancel() {
        cancelled = true
        current?.destroyProcess()
    }

    fun run(title: String, total: Int, body: Steps.() -> Unit) {
        if (busy) {
            log("Ja existe uma tarefa em curso; cancela-a primeiro.", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        busy = true
        cancelled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val steps = Steps(total)
            onProgress(0, total, title)
            log("=== $title ===", ConsoleViewContentType.SYSTEM_OUTPUT)
            try {
                steps.body()
            } catch (e: Exception) {
                log("ERRO: ${e.message}", ConsoleViewContentType.ERROR_OUTPUT)
                steps.failed += "${e.message}"
            } finally {
                busy = false
                current = null
            }
            if (cancelled) log("Cancelado.", ConsoleViewContentType.ERROR_OUTPUT)
            else if (steps.failed.isEmpty()) log("=== $title: concluido com sucesso ===", ConsoleViewContentType.SYSTEM_OUTPUT)
            else {
                log("=== $title: concluido com falhas ===", ConsoleViewContentType.ERROR_OUTPUT)
                steps.failed.forEach { log(" - $it", ConsoleViewContentType.ERROR_OUTPUT) }
            }
            onProgress(if (steps.failed.isEmpty() && !cancelled) total else steps.done, total, "")
            onFinished()
        }
    }

    inner class Steps(private val total: Int) {
        val failed = mutableListOf<String>()
        var done = 0
            private set

        private fun finished(label: String) {
            done++
            onProgress(done, total, label)
        }

        /** Passo em Kotlin (copias, apagar pastas, ...). */
        fun step(label: String, action: () -> Unit) {
            if (cancelled) return
            log("==> $label")
            try {
                action()
                log("OK: $label")
            } catch (e: Exception) {
                log("FAILED: $label (${e.message})", ConsoleViewContentType.ERROR_OUTPUT)
                failed += label
            }
            finished(label)
        }

        /** Comando de shell (bash -lc) numa pasta. */
        fun shell(label: String, cwd: Path, command: String, timeoutSec: Int = 0, allowFail: Boolean = false) {
            if (cancelled) return
            log("==> $label")
            if (!Files.isDirectory(cwd)) {
                log("FAILED: $label (pasta inexistente: $cwd)", ConsoleViewContentType.ERROR_OUTPUT)
                failed += label
                finished(label)
                return
            }
            val code = execute(command, cwd, timeoutSec)
            if (code == 0) log("OK: $label")
            else if (!cancelled) {
                log("${if (allowFail) "AVISO" else "FAILED"}: $label (exit code $code)", ConsoleViewContentType.ERROR_OUTPUT)
                if (!allowFail) failed += "$label ($code)"
            }
            if (!cancelled) finished(label)
        }
    }

    private fun execute(command: String, cwd: Path, timeoutSec: Int): Int {
        val cmd = shellCommandLine(command)
            .withWorkDirectory(cwd.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .also { applyJavaEnv(it) }
        val handler = OSProcessHandler(cmd)
        current = handler
        console.attachToProcess(handler)
        handler.startNotify()
        if (timeoutSec > 0) {
            if (!handler.waitFor(timeoutSec * 1000L)) {
                log("Timeout de ${timeoutSec}s: a terminar o comando.", ConsoleViewContentType.ERROR_OUTPUT)
                handler.destroyProcess()
                handler.waitFor(5000)
                return -1
            }
        } else {
            handler.waitFor()
        }
        return handler.process.exitValue()
    }

    companion object {
        private const val DEFAULT_JAVA_HOME = "/opt/jdk1.8.0_202"
        private const val DEFAULT_MAVEN_BIN = "/opt/apache-maven-3.9.9/bin"

        /** Linux/macOS: bash -lc; Windows: cmd /c (mvn resolve para mvn.cmd no PATH). */
        fun shellCommandLine(command: String): GeneralCommandLine =
            if (SystemInfo.isWindows) GeneralCommandLine("cmd", "/c", command)
            else GeneralCommandLine("bash", "-lc", command)

        /**
         * Comando para correr um script do dominio. No Windows corre-se pelo NOME do ficheiro com a pasta
         * `bin` como diretoria de trabalho (ver [scriptCwd]): evita aspas dentro do `cmd /c`, que o
         * IntelliJ escapa mal e que quebram caminhos com espacos.
         */
        fun scriptCommand(script: Path): String =
            if (SystemInfo.isWindows) script.fileName.toString() else "bash '$script'"

        fun scriptCwd(script: Path, domain: Path): Path = if (SystemInfo.isWindows) script.parent else domain

        /** O IntelliJ lancado pelo desktop (Linux) nao herda o .bashrc: garante JAVA_HOME e mvn. */
        fun applyJavaEnv(cmd: GeneralCommandLine) {
            if (SystemInfo.isWindows) return
            val javaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_JAVA_HOME.takeIf { File(it).isDirectory }
            val path = System.getenv("PATH").orEmpty()
            val extra = buildList {
                if (javaHome != null) {
                    cmd.environment["JAVA_HOME"] = javaHome
                    add("$javaHome/bin")
                }
                if (File(DEFAULT_MAVEN_BIN).isDirectory) add(DEFAULT_MAVEN_BIN)
            }
            cmd.environment["PATH"] = (extra + path).joinToString(File.pathSeparator)
        }

        fun newConsole(): LogPanel = LogPanel()

        fun copy(src: Path, dstDir: Path) {
            Files.createDirectories(dstDir)
            Files.copy(src, dstDir.resolve(src.fileName), StandardCopyOption.REPLACE_EXISTING)
        }

        fun deleteTree(p: Path) {
            if (Files.exists(p)) p.toFile().deleteRecursively()
        }

        /** Apaga o conteudo de [dir] (mantem a pasta), poupando os nomes em [keep]. */
        fun clearDir(dir: Path, keep: Set<String> = emptySet()) {
            if (!Files.isDirectory(dir)) return
            dir.toFile().listFiles()?.forEach { f ->
                if (f.name !in keep) f.deleteRecursively()
            }
        }
    }
}
