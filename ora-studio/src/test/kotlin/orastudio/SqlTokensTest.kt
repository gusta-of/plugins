package orastudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlTokensTest {

    @Test fun `identifierAt simples e qualificado`() {
        val line = "select * from INFARMED.MV_MEDICAMENTOS where x = 1"
        val a = SqlTokens.identifierAt(line, line.indexOf("MV_MED") + 3)!!
        assertEquals("MV_MEDICAMENTOS", a.name)
        assertEquals("INFARMED", a.qualifier)
        assertEquals("MV_MEDICAMENTOS", line.substring(a.start, a.end))
        val b = SqlTokens.identifierAt(line, line.indexOf("INFARMED") + 1)!!
        assertEquals("INFARMED", b.name)
        assertNull(b.qualifier)
    }

    @Test fun `identificadores entre aspas mantem a caixa`() {
        val line = "select \"MixedCase\" from \"Sch\".\"Tab\""
        val r = SqlTokens.identifierAt(line, line.indexOf("Tab") + 1)!!
        assertEquals("Tab", r.name)
        assertEquals("Sch", r.qualifier)
    }

    @Test fun `identifierAt em espaco ou numero devolve null`() {
        assertNull(SqlTokens.identifierAt("a  b", 2))
        assertNull(SqlTokens.identifierAt("x = 123", 5))
    }

    @Test fun `extractRefs ignora comentarios, strings e palavras reservadas`() {
        val refs = SqlTokens.extractRefs(
            "SELECT a.col, NVL(b, 'texto TABELA_X') FROM esq.tab1 a -- TABELA_Y\n JOIN \"Mix\" m /* TABELA_Z */ ON m.id = a.id",
        )
        val names = refs.map { it.second }.toSet()
        assertTrue("TAB1" in names)
        assertTrue("Mix" in names)
        assertTrue(refs.contains("ESQ" to "TAB1"))
        assertTrue("TABELA_X" !in names && "TABELA_Y" !in names && "TABELA_Z" !in names)
        assertTrue("SELECT" !in names && "FROM" !in names && "JOIN" !in names)
    }
}
