package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MavenCommandsTest {
    private val domain = BuildStep("pem-common-domain", "pem-common-domain", "clean install -rf :pem-common-domain war:manifest war:war")
    private val other = BuildStep("pem-fh-domain", "pem-fh-domain", "clean install")

    @Test fun `pular testes ativa o perfil fast-build e as flags (o pom fixa skipTests=false)`() {
        assertEquals("mvn clean install -rf :pem-common-domain war:manifest war:war -Pdev,localMock,fast-build -DskipTests -DskipITs -Dmaven.test.skip=true",
            MavenCommands.build(domain, "dev", localMock = true, skipTests = true))
        assertEquals("mvn clean install -Pdev,fast-build -DskipTests -DskipITs -Dmaven.test.skip=true", MavenCommands.build(other, "dev", localMock = true, skipTests = true))
    }

    @Test fun `sem perfil escolhido pular testes ativa na mesma o fast-build, e nao duplica`() {
        assertEquals("mvn clean install -Pfast-build -DskipTests -DskipITs -Dmaven.test.skip=true", MavenCommands.build(other, "", false, true))
        assertEquals("mvn clean install -Pdev,fast-build -DskipTests -DskipITs -Dmaven.test.skip=true", MavenCommands.build(other, "dev,fast-build", false, true))
    }

    @Test fun `sem pular testes o comando fica como estava`() {
        assertEquals("mvn clean install -Pdev", MavenCommands.build(other, "dev", false, false))
        assertEquals("mvn clean install", MavenCommands.build(other, "", false, false))
    }

    @Test fun `passos sem perfil (allure) nao levam perfil nem flags`() {
        val allure = BuildStep("Test report backend", "pem", "allure:aggregate", withProfile = false)
        val cmd = MavenCommands.build(allure, "dev", false, true)
        assertEquals("mvn allure:aggregate", cmd)
        assertFalse(cmd.contains("fast-build"))
    }

    @Test fun `parametros extra do mvn - todos e por modulo, no fim do comando`() {
        val args = mapOf("*" to "-Dmaven.gitcommitid.skip=true", "pem-fh-domain" to "-U\n-o")
        assertEquals("mvn clean install -Pdev -Dmaven.gitcommitid.skip=true -U -o", MavenCommands.build(other, "dev", false, false, MavenArgs.forStep("pem-fh-domain", args)))
        assertEquals("mvn clean install -Pdev -Dmaven.gitcommitid.skip=true", MavenCommands.build(other, "dev", false, false, MavenArgs.forStep("outro", args)))
        assertEquals("mvn clean install -Pdev", MavenCommands.build(other, "dev", false, false, MavenArgs.forStep("x", emptyMap())))
        assertEquals(true, MavenArgs.modules().any { it.name == "pem" }&& MavenArgs.modules().any { it.name == "pem-fh-domain" } && MavenArgs.modules().map { it.name }.distinct().size == MavenArgs.modules().size)
    }

    @Test fun `diagnostico - Missing tree do git-commit-id-plugin e reconhecido, outros erros nao`() {
        val out = "[ERROR] Failed to execute goal pl.project13.maven:git-commit-id-plugin:2.2.6:revision (git-info) on project pem-bkf-domain: Missing tree e30276702beb7db51751a4b9a78f59c0acfeaa46 -> [Help 1]"
        assertEquals("e30276702beb7db51751a4b9a78f59c0acfeaa46", BuildDiagnosis.diagnose(out)?.hash)
        assertEquals(null, BuildDiagnosis.diagnose("[ERROR] COMPILATION ERROR"))
        assertEquals(null, BuildDiagnosis.diagnose("Missing tree abcdef1234 noutro sitio"))
        val skip = BuildDiagnosis.GIT_COMMIT_ID_SKIP
        assertEquals(mapOf("*" to skip), BuildDiagnosis.withGlobalArg(emptyMap(), skip))
        assertEquals(mapOf("*" to "-U $skip", "x" to "y"), BuildDiagnosis.withGlobalArg(mapOf("*" to "-U", "x" to "y"), skip))
        assertEquals(mapOf("*" to skip), BuildDiagnosis.withGlobalArg(mapOf("*" to skip), skip))
    }
}
