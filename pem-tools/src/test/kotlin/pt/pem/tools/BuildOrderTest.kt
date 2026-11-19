package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BuildOrderTest {
    private fun pom(ws: Path, dir: String, vararg deps: String, group: String = "pt.min-saude.spms.modules.pem") {
        Files.createDirectories(ws.resolve(dir))
        val body = deps.joinToString("") { "<dependency><groupId>$group</groupId><artifactId>$it</artifactId><version>0.0.1</version></dependency>" }
        Files.writeString(ws.resolve(dir).resolve("pom.xml"), "<project><artifactId>$dir</artifactId><dependencies>$body</dependencies></project>")
    }

    @Test fun `a ordem respeita as dependencias dos pom, mesmo com a lista fixa ao contrario`() {
        val ws = Files.createTempDirectory("ws")
        pom(ws, "pem-common", "pem-common-domain")
        pom(ws, "pem-common-domain")
        pom(ws, "pem-common-app", "pem-common", "pem-common-domain")
        pom(ws, "spms-plugins-common", "pem-common", "pem-common-domain")
        val deps = BuildOrder.dependencies(ws, listOf("spms-plugins-common", "pem-common-app", "pem-common", "pem-common-domain"))
        assertEquals(setOf("pem-common", "pem-common-domain"), deps["spms-plugins-common"])
        val order = BuildOrder.sort(listOf("spms-plugins-common", "pem-common-app", "pem-common", "pem-common-domain"), deps)
        assertEquals(listOf("pem-common-domain", "pem-common", "spms-plugins-common", "pem-common-app"), order)
    }

    @Test fun `grupo diferente, comentarios e o proprio modulo nao contam`() {
        val ws = Files.createTempDirectory("ws")
        pom(ws, "a", "b", group = "outro.grupo")
        Files.writeString(ws.resolve("a/pom.xml"), "<project><dependencies><!-- <dependency><groupId>pt.min-saude.spms.modules.pem</groupId><artifactId>b</artifactId></dependency> -->" +
            "<dependency><groupId>pt.min-saude.spms.modules.pem</groupId><artifactId>a</artifactId></dependency></dependencies></project>")
        pom(ws, "b")
        assertEquals(emptySet<String>(), BuildOrder.dependencies(ws, listOf("a", "b"))["a"])
    }

    @Test fun `ciclo nao bloqueia (segue a ordem de base) e e reportado`() {
        var cyc: List<String>? = null
        val order = BuildOrder.sort(listOf("x", "y"), mapOf("x" to setOf("y"), "y" to setOf("x")), { cyc = it })
        assertEquals(listOf("x", "y"), order)
        assertEquals(listOf("x", "y"), cyc)
    }

    @Test fun `no workspace real cada modulo vem depois de tudo aquilo de que depende`() {
        val ws = Path.of(System.getenv("PEM_WORKSPACE") ?: "/home/gustavo/workspace/PEM/PEM_Aplicacao")
        assumeTrue(Files.isDirectory(ws.resolve("pem-common")), "workspace do PEM nao encontrado")
        val steps = Modules.backendBuild(ws)
        val labels = steps.filter { it.withProfile }.map { it.dir }
        val deps = BuildOrder.dependencies(ws, labels)
        for ((i, dir) in labels.withIndex()) for (d in deps.getValue(dir)) {
            assertTrue(labels.indexOf(d) < i, "$dir depende de $d mas vem antes dele na ordem: $labels")
        }
        // os dois erros da ordem fixa antiga
        assertTrue(labels.indexOf("pem-common") < labels.indexOf("pem-common-app"))
        assertTrue(labels.indexOf("pem-common") < labels.indexOf("spms-plugins-common"))
        assertEquals(listOf("pem-common-beans", "pem"), steps.take(2).map { it.label })
        assertEquals("Test report backend", steps.last().label)
        // a selecao do utilizador e' reordenada
        val sel = Modules.orderEntries(ws, listOf(ModuleEntry("pem-med-app", Kind.BACKEND), ModuleEntry("pem-common-app", Kind.BACKEND), ModuleEntry("pem-shared-domain", Kind.BACKEND), ModuleEntry("pem-hook", Kind.FRONTEND)))
        assertEquals(listOf("pem-common-app", "pem-shared-domain", "pem-med-app", "pem-hook"), sel.map { it.name })
        println("ORDEM: ${labels.joinToString(" -> ")}")
    }
}
