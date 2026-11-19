package pt.pem.tools

import java.nio.file.Path

/** Um passo de build Maven: [dir] relativo a workspace, [goals] sem o perfil. */
/** Comando `mvn` de cada passo de build (puro, para poder ser testado). */
object MavenCommands {
    const val LOCAL_MOCK_MODULE = "pem-common-domain"
    const val LOCAL_MOCK_PROFILE = "localMock"
    const val FAST_BUILD_PROFILE = "fast-build"

    /**
     * O pem/pom.xml fixa `<skipTests>false</skipTests>` no surefire/failsafe, o que ganha a qualquer -D da linha de
     * comandos: so o perfil `fast-build` (desliga surefire, failsafe e jacoco) garante que os testes nao correm.
     */
    fun build(b: BuildStep, profile: String, localMock: Boolean, skipTests: Boolean, extra: String = ""): String {
        val profiles = profile.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (b.dir == LOCAL_MOCK_MODULE && localMock && LOCAL_MOCK_PROFILE !in profiles) profiles += LOCAL_MOCK_PROFILE
        val skipping = b.withProfile && skipTests
        if (skipping && FAST_BUILD_PROFILE !in profiles) profiles += FAST_BUILD_PROFILE
        val prof = if (b.withProfile && profiles.isNotEmpty()) " -P${profiles.joinToString(",")}" else ""
        val skip = if (skipping) " -DskipTests -DskipITs -Dmaven.test.skip=true" else ""
        return "mvn ${b.goals}$prof$skip" + if (extra.isBlank()) "" else " $extra"
    }
}

/** Parametros extra do mvn: a chave `*` vale para todos os builds, as outras sao o nome do modulo. */
object MavenArgs {
    const val ALL = "*"

    fun clean(s: String?): String = (s ?: "").replace(Regex("[\\r\\n]+"), " ").trim()

    fun forStep(label: String, args: Map<String, String>): String =
        listOf(clean(args[ALL]), clean(args[label])).filter { it.isNotEmpty() }.joinToString(" ")

    data class PomModule(val name: String, val dir: String, val kind: Kind)

    /** Modulos com pom.xml que tem build (backend e frontend), sem repeticoes. */
    fun modules(): List<PomModule> {
        val seen = HashSet<String>()
        val out = ArrayList<PomModule>()
        fun add(n: String, d: String, k: Kind) { if (seen.add(n)) out += PomModule(n, d, k) }
        Modules.backendBuild().forEach { add(it.label, it.dir, Kind.BACKEND) }
        Modules.frontendBuild(false).forEach { add(it.label, it.dir, Kind.FRONTEND) }
        Modules.frontendDeploy.forEach { (n, sub) -> add(n, "spms-plugins/$sub/$n", Kind.FRONTEND) }
        return out
    }
}

data class BuildStep(val label: String, val dir: String, val goals: String, val withProfile: Boolean = true)

object Modules {

    // Ordem identica a build-backend.sh
    private val backendGroups = listOf("pem-common", "pem-shared", "pem-med", "pem-dm", "pem-crd", "pem-fh", "pem-mcdt", "pem-ocl", "esp-mcdt")

    fun backendBuild(): List<BuildStep> = buildList {
        add(BuildStep("pem-common-beans", "pem-common-beans", "clean install -rf :pem-common-beans"))
        add(BuildStep("pem", "pem", "clean install -U -Dmaven.test.skip=true"))
        add(BuildStep("spms-plugins-common", "spms-plugins-common", "clean install -rf :spms-plugins-common"))
        for (g in backendGroups) {
            add(BuildStep("$g-domain", "$g-domain", "clean install -rf :$g-domain war:manifest war:war"))
            add(BuildStep("$g-app", "$g-app", "clean install -rf :$g-app war:war"))
            if (g == "pem-common") add(BuildStep("pem-common", "pem-common", "clean install -rf :pem-common"))
        }
        add(BuildStep("pem-bkf-app", "pem-bkf-app", "clean install -rf :pem-bkf-app war:war"))
        add(BuildStep("Test report backend", "pem", "allure:aggregate", withProfile = false))
    }

    /**
     * Passos do backend com os modulos ordenados pelas dependencias dos pom.xml de [workspace]
     * (a ordem fixa de build-backend.sh estava errada p.ex. para pem-common-app / spms-plugins-common).
     * Os dois primeiros passos (pem-common-beans e o reactor «pem») e o relatorio final mantem-se nas pontas.
     */
    fun backendBuild(workspace: Path, onCycle: (List<String>) -> Unit = {}): List<BuildStep> {
        val fixed = backendBuild()
        val head = fixed.take(2)
        val tail = fixed.filter { !it.withProfile }
        val middle = fixed.drop(2).filter { it.withProfile }
        val deps = BuildOrder.dependencies(workspace, middle.map { it.dir })
        val order = BuildOrder.sort(middle.map { it.dir }, deps, onCycle)
        return head + order.map { d -> middle.first { it.dir == d } } + tail
    }

    /** Ordena a selecao do utilizador: modulos do backend pela ordem de dependencias, depois os do frontend (ordem recebida). */
    fun orderEntries(workspace: Path, entries: List<ModuleEntry>): List<ModuleEntry> {
        val steps = backendBuild(workspace).map { it.label }
        val backend = entries.filter { it.kind == Kind.BACKEND }.sortedBy { e -> steps.indexOf(e.name).let { if (it < 0) Int.MAX_VALUE else it } }
        return backend + entries.filter { it.kind != Kind.BACKEND }
    }

    fun frontendBuild(fast: Boolean, threads: String = "1C"): List<BuildStep> = listOf(
        BuildStep("spms-plugins-common", "spms-plugins-common", "clean install -rf :spms-plugins-common"),
        BuildStep("spms-plugins", "spms-plugins", if (fast) "clean install -T $threads" else "clean install"),
    )

    fun frontendModuleBuild(module: String): BuildStep =
        BuildStep(module, "spms-plugins/${frontendDeploy.getValue(module)}/$module", "clean install -rf :$module")

    /** Modulos de build isolado: backend (sem o relatorio de testes) e frontend. */
    fun buildEntries(): List<ModuleEntry> =
        backendBuild().filter { it.withProfile }.map { ModuleEntry(it.label, Kind.BACKEND) } +
            frontendDeploy.keys.map { ModuleEntry(it, Kind.FRONTEND) }

    fun deployEntries(): List<ModuleEntry> =
        backendDeploy.map { ModuleEntry(it, Kind.BACKEND) } + frontendDeploy.keys.map { ModuleEntry(it, Kind.FRONTEND) }

    /** Modulos usados por clean-targets.sh / kill-module-jars.sh */
    val backendDirs = listOf(
        "pem-common-beans", "pem-common-rdata", "pem-common-notifications", "pem-common-domain", "pem-common",
        "pem-common-app", "pem-bkf-domain", "pem-shared-domain", "pem-med-domain", "pem-dm-domain",
        "pem-crd-domain", "pem-fh-domain", "pem-bkf-app", "pem-shared-app", "pem-med-app", "pem-dm-app",
        "pem-fh-app", "pem-crd-app", "pem-ocl-domain", "pem-ocl-app", "pem-mcdt-domain", "pem-mcdt-app",
        "esp-mcdt-domain", "esp-mcdt-app",
    )

    const val JAR_MODULE = "pem-common-beans"

    val backendDeploy: List<String> = listOf(
        "esp-mcdt-app", "esp-mcdt-domain", "pem-common-app", "pem-common-domain", "pem-crd-app", "pem-crd-domain",
        "pem-dm-app", "pem-dm-domain", "pem-fh-app", "pem-fh-domain", "pem-mcdt-app", "pem-mcdt-domain",
        "pem-med-app", "pem-med-domain", "pem-ocl-app", "pem-ocl-domain", "pem-shared-app", "pem-shared-domain",
        "pem-bkf-app", JAR_MODULE,
    )

    /** modulo -> subpasta dentro de spms-plugins */
    val frontendDeploy: Map<String, String> = linkedMapOf(
        "backoffice-portlet" to "portlets", "backoffice-theme" to "themes", "esp-mcdt-portlet" to "portlets",
        "header-portlet" to "portlets", "integration-portlet" to "portlets", "patient-portlet" to "portlets",
        "pem-base-theme" to "themes", "pem-crd-portlet" to "portlets", "pem-dm-portlet" to "portlets",
        "pem-fh-portlet" to "portlets", "pem-hook" to "hooks", "pem-lang-hook" to "hooks",
        "pem-mcdt-portlet" to "portlets", "pem-med-portlet" to "portlets", "pem-ocl-portlet" to "portlets",
        "portal-portlet" to "portlets", "portal-theme" to "themes",
    )

    /** Nunca apagar do autodeploy (nao sao reconstruidos pelo build do PEM) */
    val autodeployKeep = setOf("marketplace-portlet", "pem-layouttpl")

    /** Nunca apagar do ~/.m2/.../modules/pem */
    val m2Keep = setOf("fonts")

    val threadOptions = listOf("1C", "0.5C", "2C", "1", "2", "4", "6", "8", "12", "16")

    /** Aceita N, NC ou N.NC (ex.: 4, 1C, 0.5C); qualquer outra coisa cai no padrao 1C. */
    fun sanitizeThreads(raw: String): String = raw.trim().takeIf { Regex("""\d+(\.\d+)?C?""").matches(it) } ?: "1C"

    val mavenProfiles = listOf("", "dev", "qua", "fast-build", "prd.priv1", "prd.priv2", "prd1", "prd2")
}
