package pt.pem.tools

/** Um passo de build Maven: [dir] relativo a workspace, [goals] sem o perfil. */
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
