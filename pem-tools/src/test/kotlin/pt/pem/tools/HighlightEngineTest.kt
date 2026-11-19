package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HighlightEngineTest {
    private val engine = HighlightEngine(HighlightDefaults.rules())

    @Test fun `linha de erro e aviso sao pintadas por inteiro`() {
        val e = engine.segments("[ERROR] COMPILATION ERROR")
        assertEquals(1, e.size)
        assertEquals("#7F1D1D", e[0].style!!.background)
        assertEquals("#7A5C00", engine.segments("2026 WARN something").single().style!!.background)
        assertNull(engine.segments("[INFO] tudo normal").single().style)
    }

    @Test fun `bloco do WebLogic Error vai ate a linha que acaba em maior que`() {
        val first = engine.segments("<21/out/2026 16H11m UTC> <Error> <Deployer> <BEA-149205> <Failed to initialize the application \"x\" due to error foo")
        assertEquals("#B71C1C", first.single().style!!.background)
        for (l in listOf("weblogic.application.ModuleException: Error: bar", "\tat weblogic.servlet.internal.WebAppModule.init(WebAppModule.java:284)", "\tTruncated. see log file for complete stacktrace", ">")) {
            assertEquals("#B71C1C", engine.segments(l).single().style!!.background, l)
        }
        // depois do bloco, volta ao normal
        assertNull(engine.segments("Loading zip:/opt/x.jar").single().style)
    }

    @Test fun `so o trecho que casa e pintado quando a regra nao e linha toda`() {
        val s = engine.segments("<Notice> <WebLogicServer> <BEA-000365> <Server state changed to STANDBY.>")
        val painted = s.filter { it.style != null }
        assertEquals(1, painted.size)
        assertEquals("Server state changed to STANDBY", painted[0].text)
        assertEquals("<Notice> <WebLogicServer> <BEA-000365> <", s[0].text)
        // o texto reconstruido e igual ao original
        assertEquals("<Notice> <WebLogicServer> <BEA-000365> <Server state changed to STANDBY.>", s.joinToString("") { it.text })
    }

    @Test fun `sucesso ganha ao estado do servidor (primeira regra manda)`() {
        val s = engine.segments("<Notice> <WebLogicServer> <BEA-000365> <Server state changed to RUNNING.>")
        assertEquals(1, s.size)
        assertEquals("#1B5E20", s[0].style!!.background)
    }

    @Test fun `texto simples (sem regex), maiusculas e regex invalida`() {
        val rules = listOf(
            HighlightRule(pattern = "a.b", regex = false, caseSensitive = true, background = "#111111"),
            HighlightRule(pattern = "([", background = "#222222"),
        )
        val en = HighlightEngine(rules)
        assertNotNull(en.segments("x a.b y").single().style)
        assertNull(en.segments("x aXb y").single().style)
        assertNull(en.segments("A.B").single().style)
    }

    @Test fun `modos contem comeca termina igual e curinga`() {
        fun hit(mode: String, pat: String, line: String) =
            HighlightEngine(listOf(HighlightRule(pattern = pat, mode = mode, background = "#111111"))).segments(line).any { it.style != null }
        assertEquals(true, hit("contains", "erro", "um ERRO aqui"))
        assertEquals(true, hit("starts", "<Error>", "<Error> x"))
        assertEquals(false, hit("starts", "<Error>", "x <Error>"))
        assertEquals(true, hit("ends", "falhou", "build falhou"))
        assertEquals(false, hit("ends", "falhou", "falhou depois"))
        assertEquals(true, hit("equals", ">", ">"))
        assertEquals(false, hit("equals", ">", "> x"))
        assertEquals(true, hit("contains", "Module * of application", "Module pem of application x"))
        assertEquals(true, hit("regex", "^\\d+$", "123"))
        // regras antigas (so com o booleano regex) continuam a funcionar
        assertEquals(true, HighlightEngine(listOf(HighlightRule(pattern = "a.c", regex = true, background = "#1"))).segments("abc").any { it.style != null })
        assertEquals(false, HighlightEngine(listOf(HighlightRule(pattern = "a.c", regex = false, background = "#1"))).segments("abc").any { it.style != null })
    }

    @Test fun `as regras guardam-se e lem-se em JSON`() {
        val json = com.google.gson.Gson().toJson(HighlightDefaults.rules())
        val back = HighlightStore.parse(json)!!
        assertEquals(HighlightDefaults.rules(), back)
        assertNull(HighlightStore.parse("isto nao e json"))
    }
}
