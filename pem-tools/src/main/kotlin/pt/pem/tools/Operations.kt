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

    /** Arranque do WebLogic (para acompanhar os artefactos) e cada linha que ele escreve. */
    @Volatile var onWebLogicStart: (Paths) -> Unit = {}
    @Volatile var onWebLogicLine: (String) -> Unit = {}


    /** Lido a cada build: acrescenta -Dmaven.test.skip=true. */
    var skipTests: () -> Boolean = { false }

    /** Lido a cada build: acrescenta o perfil localMock, apenas ao modulo pem-common-domain. */
    var localMock: () -> Boolean = { false }

    /** Lido a cada build: parametros extra do mvn (`*` = todos, ou o nome do modulo). */
    var mavenArgs: () -> Map<String, String> = { emptyMap() }

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

    // ---- git ----

    /**
     * Volta a empacotar o repositorio do workspace em packs `pack-*` normais (que o JGit do git-commit-id-plugin le)
     * e remove o multi-pack-index. Nao apaga historico: so junta os objetos e tira os packs redundantes.
     */
    fun repairGit(p: Paths, hash: String? = null) {
        val verify = hash?.takeIf { Regex("^[0-9a-f]{7,40}$").matches(it) }
        runner.run("Reparar repositorio git (repack)", if (verify != null) 4 else 3) {
            shell("git repack -a -d", p.workspace, "git repack -a -d")
            step("Remover multi-pack-index") {
                val rel = gitOut(p.workspace, "rev-parse", "--git-path", "objects/pack/multi-pack-index")
                val f = p.workspace.resolve(rel).normalize()
                Files.deleteIfExists(f)
                runner.log("Removido (se existia): $f")
            }
            shell("git prune-packed", p.workspace, "git prune-packed")
            if (verify != null) shell("Verificar o objeto ${verify.take(10)}", p.workspace, "git cat-file -t $verify")
            runner.log("Se o git maintenance voltar a criar packs «loose-*», repete esta operacao (ou usa «Parametros Maven» com ${BuildDiagnosis.GIT_COMMIT_ID_SKIP}).")
        }
    }

    private fun gitOut(cwd: Path, vararg args: String): String {
        val pr = ProcessBuilder(listOf("git") + args).directory(cwd.toFile()).redirectErrorStream(true).start()
        val out = pr.inputStream.bufferedReader().readText().trim()
        check(pr.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    // ---- build-backend.sh / tasks Build Frontend ----
    fun buildBackend(p: Paths, profile: String): Unit {
        val steps = Modules.backendBuild(p.workspace) { runner.log("AVISO: ciclo de dependencias entre ${it.joinToString()}; a usar a ordem fixa.", ConsoleViewContentType.ERROR_OUTPUT) }
        runner.run("Build Backend", steps.size) {
            runner.log("Ordem de build (pelas dependencias dos pom.xml): ${steps.filter { it.withProfile }.joinToString(" → ") { it.label }}")
            steps.forEach { runBuild(p, it, profile) }
        }
    }

    fun buildFrontend(p: Paths, profile: String, fast: Boolean, threads: String = "1C") =
        runner.run(if (fast) "Build Frontend (rapido -T $threads)" else "Build Frontend", Modules.frontendBuild(fast, threads).size) {
            Modules.frontendBuild(fast, threads).forEach { runBuild(p, it, profile) }
        }

    /** Backend (ordenado pelas dependencias) e a seguir o frontend, numa so tarefa; para no primeiro erro. */
    fun buildAll(p: Paths, profile: String) {
        val be = Modules.backendBuild(p.workspace) { runner.log("AVISO: ciclo de dependencias entre ${it.joinToString()}; a usar a ordem fixa.", ConsoleViewContentType.ERROR_OUTPUT) }
        val fe = Modules.frontendBuild(false)
        runner.run("Build Backend + Frontend", be.size + fe.size) {
            runner.log("Backend (pelas dependencias dos pom.xml): ${be.filter { it.withProfile }.joinToString(" → ") { it.label }}")
            be.forEach { runBuild(p, it, profile) }
            runner.log("Backend concluido — a seguir o frontend.")
            fe.forEach { runBuild(p, it, profile) }
        }
    }

    /** Build de varios modulos (backend e/ou frontend) numa so tarefa, pela ordem recebida. */
    fun buildModules(p: Paths, entries: List<ModuleEntry>, profile: String) {
        // o que o utilizador escolhe pode vir em qualquer ordem: o backend e' ordenado pelas dependencias dos pom.xml
        val ordered = Modules.orderEntries(p.workspace, entries)
        runner.run(if (ordered.size == 1) "Build ${ordered[0].name}" else "Build de ${ordered.size} modulos", ordered.size) {
            if (ordered.size > 1) runner.log("Ordem de build (pelas dependencias): ${ordered.joinToString(" → ") { it.name }}")
            for (e in ordered) {
                val step = when (e.kind) {
                    Kind.BACKEND -> Modules.backendBuild().first { it.label == e.name }
                    Kind.FRONTEND -> Modules.frontendModuleBuild(e.name)
                }
                runBuild(p, step, profile)
            }
        }
    }

    /** Modulos de deploy que ja tem artefacto (war/jar) no target/ e os que ficam de fora. */
    fun deployableAll(p: Paths): Pair<List<ModuleEntry>, List<String>> {
        val ok = ArrayList<ModuleEntry>()
        val skipped = ArrayList<String>()
        for (e in Modules.deployEntries()) {
            try {
                if (e.kind == Kind.BACKEND) findArtifact(p.workspace.resolve(e.name).resolve("target"), e.name, if (e.name == Modules.JAR_MODULE) "jar" else "war")
                else findArtifact(p.workspace.resolve("spms-plugins/${Modules.frontendDeploy.getValue(e.name)}/${e.name}/target"), e.name, "war")
                ok += e
            } catch (_: Exception) { skipped += e.name }
        }
        return ok to skipped
    }

    /** Deploy de tudo o que foi construido: backend e depois frontend. */
    fun deployAll(p: Paths, entries: List<ModuleEntry>, skipped: List<String>) =
        runner.run("Deploy Backend + Frontend (${entries.size} modulos)", entries.size) {
            if (skipped.isNotEmpty()) runner.log("Sem artefacto (nao construidos), ignorados: ${skipped.joinToString()}")
            for (e in entries) when (e.kind) {
                Kind.BACKEND -> deployBackendOne(p, e.name)
                Kind.FRONTEND -> deployFrontendOne(p, e.name)
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
        shell(b.label, p.workspace.resolve(b.dir), MavenCommands.build(b, profile, localMock(), skipTests(), MavenArgs.forStep(b.label, mavenArgs())))
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
        onWebLogicStart(p)
        val partial = StringBuilder()
        handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                partial.append(event.text)
                var i = partial.indexOf("\n")
                while (i >= 0) {
                    onWebLogicLine(partial.substring(0, i).trimEnd('\r'))
                    partial.delete(0, i + 1)
                    i = partial.indexOf("\n")
                }
            }
        })
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

}
