package pt.pem.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/** Equivalentes em Kotlin dos scripts de .vscode/scripts. */
class Operations(private val runner: TaskRunner, private val wlRunner: TaskRunner) {

    private val wlConsole: LogPanel get() = wlRunner.console

    /** Chamado quando o processo iniciado por startWebLogic termina. */
    @Volatile var onWebLogicExit: () -> Unit = {}


    /** Lido a cada build: acrescenta -Dmaven.test.skip=true. */
    var skipTests: () -> Boolean = { false }

    /** Lido a cada build: acrescenta o perfil localMock, apenas ao modulo pem-common-domain. */
    var localMock: () -> Boolean = { false }

    @Volatile private var wlHandler: OSProcessHandler? = null

    val weblogicRunning: Boolean get() = wlHandler?.isProcessTerminated == false

    // ---- clean-targets.sh / clear_classpath.sh (passo 6) ----
    fun cleanTargets(p: Paths) = runner.run("Limpar pastas target/", 1) {
        step("Apagar target/ em ${p.workspace}") { deleteTargets(p.workspace) }
    }

    // ---- clear_classpath.sh ----
    fun clearClasspath(p: Paths) = runner.run("Limpar cache / classpath", 7) {
        val d = p.domain
        if (!Files.isDirectory(d)) {
            step("Validar dominio") { error("dominio nao encontrado em $d") }
            return@run
        }
        step("Staging explodido (_WL_user, _WL_internal)") {
            TaskRunner.clearDir(d.resolve("servers/AdminServer/tmp/_WL_user"))
            TaskRunner.clearDir(d.resolve("servers/AdminServer/tmp/_WL_internal"))
        }
        step("Cache interno do servidor") { TaskRunner.clearDir(d.resolve("servers/AdminServer/cache")) }
        step("Autodeploy (mantem ${Modules.autodeployKeep.joinToString()})") {
            val keep = Modules.autodeployKeep
            p.autodeploy.toFile().listFiles()?.forEach { f ->
                if (!f.name.startsWith(".") && f.name !in keep) f.deleteRecursively()
            }
        }
        step("liferay/deploy") { TaskRunner.clearDir(p.liferayDeploy) }
        step("liferay/data/osgi") { TaskRunner.clearDir(d.resolve("liferay/data/osgi")) }
        step("Apagar target/ em ${p.workspace}") { deleteTargets(p.workspace) }
        step("~/.m2/.../modules/pem (mantem ${Modules.m2Keep.joinToString()})") {
            val m2 = Path.of(System.getProperty("user.home"), ".m2/repository/pt/min-saude/spms/modules/pem")
            TaskRunner.clearDir(m2, Modules.m2Keep)
        }
        runner.log("Dados nao tocados: liferay/data/hsql, jackrabbit, lucene.")
    }

    private fun deleteTargets(root: Path) {
        val skip = setOf("node_modules", ".git")
        fun walk(dir: Path) {
            dir.toFile().listFiles { f -> f.isDirectory }?.forEach { f ->
                when {
                    f.name in skip -> {}
                    f.name == "target" -> { runner.log("Removendo: $f"); f.deleteRecursively() }
                    else -> walk(f.toPath())
                }
            }
        }
        walk(root)
    }

    // ---- kill-module-jars.sh ----
    fun killModuleJars() = runner.run("Encerrar processos java dos modulos", 1) {
        step("Procurar processos") {
            val self = ProcessHandle.current().pid()
            ProcessHandle.allProcesses().filter { it.pid() != self }.forEach { ph ->
                val cmd = ph.info().commandLine().orElse("")
                if (!cmd.contains("java")) return@forEach
                if (Modules.backendDirs.any { Regex(Regex.escape(it) + ".*\\.jar").containsMatchIn(cmd) }) {
                    runner.log("Encerrando processo ${ph.pid()}: ${cmd.take(160)}")
                    ph.destroy()
                    try { ph.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: Exception) { ph.destroyForcibly() }
                }
            }
        }
    }

    // ---- build-backend.sh / tasks Build Frontend ----
    fun buildBackend(p: Paths, profile: String) = runner.run("Build Backend", Modules.backendBuild().size) {
        Modules.backendBuild().forEach { runBuild(p, it, profile) }
    }

    fun buildFrontend(p: Paths, profile: String, fast: Boolean, threads: String = "1C") =
        runner.run(if (fast) "Build Frontend (rapido -T $threads)" else "Build Frontend", Modules.frontendBuild(fast, threads).size) {
            Modules.frontendBuild(fast, threads).forEach { runBuild(p, it, profile) }
        }

    /** Build de varios modulos (backend e/ou frontend) numa so tarefa, pela ordem recebida. */
    fun buildModules(p: Paths, entries: List<ModuleEntry>, profile: String) =
        runner.run(if (entries.size == 1) "Build ${entries[0].name}" else "Build de ${entries.size} modulos", entries.size) {
            for (e in entries) {
                val step = when (e.kind) {
                    Kind.BACKEND -> Modules.backendBuild().first { it.label == e.name }
                    Kind.FRONTEND -> Modules.frontendModuleBuild(e.name)
                }
                runBuild(p, step, profile)
            }
        }

    fun deployModules(p: Paths, entries: List<ModuleEntry>) =
        runner.run(if (entries.size == 1) "Deploy ${entries[0].name}" else "Deploy de ${entries.size} modulos", entries.size) {
            for (e in entries) when (e.kind) {
                Kind.BACKEND -> deployBackendOne(p, e.name)
                Kind.FRONTEND -> deployFrontendOne(p, e.name)
            }
        }

    private fun TaskRunner.Steps.runBuild(p: Paths, b: BuildStep, profile: String) {
        val profiles = profile.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (b.dir == LOCAL_MOCK_MODULE && localMock() && LOCAL_MOCK_PROFILE !in profiles) profiles += LOCAL_MOCK_PROFILE
        val prof = if (b.withProfile && profiles.isNotEmpty()) " -P${profiles.joinToString(",")}" else ""
        val skip = if (b.withProfile && skipTests()) " -Dmaven.test.skip=true" else ""
        shell(b.label, p.workspace.resolve(b.dir), "mvn ${b.goals}$prof$skip")
    }

    // ---- deploy_backend_lnx.sh ----
    fun deployBackend(p: Paths, modules: List<String>) = runner.run("Deploy Backend", modules.size) {
        for (m in modules) deployBackendOne(p, m)
    }

    private fun TaskRunner.Steps.deployBackendOne(p: Paths, m: String) = step("Deploy $m") {
        val target = p.workspace.resolve(m).resolve("target")
        if (m == Modules.JAR_MODULE) {
            TaskRunner.copy(findArtifact(target, m, "jar"), p.lib)
        } else {
            val war = findArtifact(target, m, "war")
            TaskRunner.deleteTree(p.wlUser.resolve(m))
            TaskRunner.copy(war, p.upload)
        }
    }

    // ---- deploy_frontend_lnx.sh ----
    fun deployFrontend(p: Paths, modules: List<String>) = runner.run("Deploy Frontend", modules.size) {
        for (m in modules) deployFrontendOne(p, m)
    }

    private fun TaskRunner.Steps.deployFrontendOne(p: Paths, m: String) = step("Deploy $m") {
        val sub = Modules.frontendDeploy.getValue(m)
        val war = findArtifact(p.workspace.resolve("spms-plugins/$sub/$m/target"), m, "war")
        TaskRunner.deleteTree(p.autodeploy.resolve(m))
        TaskRunner.copy(war, p.liferayDeploy)
    }

    /** O mais recente `<modulo>-*.<ext>` em target/, ignorando sources/javadoc/tests. */
    private fun findArtifact(target: Path, module: String, ext: String): Path {
        val found = target.toFile().listFiles { f ->
            f.isFile && f.name.startsWith("$module-") && f.name.endsWith(".$ext") &&
                listOf("sources", "javadoc", "tests").none { f.name.contains(it) }
        }?.maxByOrNull { it.lastModified() }
        return found?.toPath() ?: error("$module: nenhum .$ext em $target (corre o build primeiro)")
    }

    // ---- WebLogic ----
    fun startWebLogic(p: Paths) {
        if (weblogicRunning) {
            wlConsole.print("WebLogic ja esta a correr (iniciado por este plugin).\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        val ext = if (SystemInfo.isWindows) "cmd" else "sh"
        val script = p.domain.resolve("bin/startWebLogic.$ext")
        if (!Files.isRegularFile(script)) {
            wlConsole.print("Nao encontrado: $script\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }
        val cmd = TaskRunner.shellCommandLine(TaskRunner.scriptCommand(script))
            .withWorkDirectory(TaskRunner.scriptCwd(script, p.domain).toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .also { TaskRunner.applyJavaEnv(it) }
        val handler = OSProcessHandler(cmd)
        wlHandler = handler
        wlConsole.attachToProcess(handler)
        handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) = onWebLogicExit()
        })
        handler.startNotify()
    }

    /** Stop: tenta o script de paragem (max. 60s) e depois FORCA a terminacao do que restar. */
    fun stopWebLogic(p: Paths) = wlRunner.run("Stop WebLogic", 2) {
        val ext = if (SystemInfo.isWindows) "cmd" else "sh"
        val script = p.domain.resolve("bin/stopWebLogic.$ext")
        shell("stopWebLogic.$ext (gracioso)", TaskRunner.scriptCwd(script, p.domain), TaskRunner.scriptCommand(script),
            timeoutSec = 60, allowFail = true)
        step("Forcar paragem dos processos WebLogic") { forceKill(p.domain) }
    }

    /** Termina logo (sem tentar o stop gracioso) todos os processos do WebLogic deste dominio. */
    fun killWebLogic(p: Paths) = wlRunner.run("Kill WebLogic", 1) {
        step("Forcar paragem dos processos WebLogic") { forceKill(p.domain) }
    }

    private fun forceKill(domain: Path) {
        wlHandler?.takeIf { !it.isProcessTerminated }?.destroyProcess()

        fun cmdLine(ph: ProcessHandle): String =
            (ph.info().commandLine().orElse(null) ?: ph.info().arguments().map { it.joinToString(" ") }.orElse(""))
                .lowercase().replace('\\', '/')

        val needle = domain.toString().lowercase().replace('\\', '/')
        val self = ProcessHandle.current().pid()
        val roots = ProcessHandle.allProcesses()
            .filter { it.pid() != self }
            .filter { val c = cmdLine(it); c.contains("weblogic.server") && c.contains(needle) }
            .toList()
        if (roots.isEmpty()) {
            wlRunner.log("Nenhum processo WebLogic do dominio $domain em execucao.")
            return
        }
        val all = roots.flatMap { r -> r.descendants().toList() + r }.distinctBy { it.pid() }
        all.forEach { wlRunner.log("Terminando PID ${it.pid()}") ; it.destroy() }
        all.forEach { ph ->
            try { ph.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: Exception) {
                wlRunner.log("Forcando PID ${ph.pid()}")
                ph.destroyForcibly()
            }
        }
        wlConsole.print("[plugin] WebLogic terminado.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private companion object {
        const val LOCAL_MOCK_MODULE = "pem-common-domain"
        const val LOCAL_MOCK_PROFILE = "localMock"
    }
}
