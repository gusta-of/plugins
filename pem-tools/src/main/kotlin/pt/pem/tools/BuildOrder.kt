package pt.pem.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Ordem de build dos modulos do backend a partir das dependencias REAIS dos pom.xml (e nao de uma lista fixa):
 * um modulo so e construido depois de todos os modulos do workspace de que depende.
 */
object BuildOrder {
    private const val GROUP = "pt.min-saude.spms.modules.pem"
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val DEPENDENCY = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
    private val GROUP_ID = Regex("<groupId>\\s*([^<\\s]+)\\s*</groupId>")
    private val ARTIFACT_ID = Regex("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>")

    /** dir -> pastas (de [dirs]) de que o pom dessa pasta depende. Pastas sem pom.xml ficam sem dependencias. */
    fun dependencies(workspace: Path, dirs: Collection<String>): Map<String, Set<String>> {
        val known = dirs.toSet()
        return dirs.associateWith { dir ->
            val pom = workspace.resolve(dir).resolve("pom.xml")
            if (!Files.isRegularFile(pom)) return@associateWith emptySet<String>()
            val xml = COMMENT.replace(Files.readString(pom), "")
            DEPENDENCY.findAll(xml).mapNotNull { m ->
                val g = GROUP_ID.find(m.groupValues[1])?.groupValues?.get(1)
                val a = ARTIFACT_ID.find(m.groupValues[1])?.groupValues?.get(1)
                a.takeIf { g == GROUP && it != dir && it in known }
            }.toSet()
        }
    }

    /**
     * Ordenacao topologica estavel: em cada passo escolhe, pela ordem de [base], o primeiro modulo cujas
     * dependencias ja estao todas colocadas. Num ciclo (nao devia haver) segue a ordem de [base] e regista em [onCycle].
     */
    fun sort(base: List<String>, deps: Map<String, Set<String>>, onCycle: (List<String>) -> Unit = {}): List<String> {
        val remaining = base.toMutableList()
        val done = LinkedHashSet<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { m -> (deps[m] ?: emptySet()).all { it in done || it !in remaining } }
            if (next == null) {
                onCycle(remaining.toList())
                done += remaining
                break
            }
            remaining.remove(next)
            done += next
        }
        return done.toList()
    }
}
