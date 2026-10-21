package orastudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionEngineTest {

    private class FakeCatalog(var ready: Boolean = true) : Catalog {
        override fun schemas() = if (ready) listOf("DEMO", "PEM", "SYS") else null
        override fun objects(owner: String) = if (!ready) null else when (owner) {
            "PEM" -> listOf(CatalogObject("ACCOUNT_", ObjectType.TABLE), CatalogObject("ALERGIA", ObjectType.TABLE), CatalogObject("V_ALERGIA", ObjectType.VIEW), CatalogObject("PK_UTIL", ObjectType.PACKAGE))
            "DEMO" -> listOf(CatalogObject("EMP", ObjectType.TABLE), CatalogObject("DEPT", ObjectType.TABLE), CatalogObject("Mixed", ObjectType.TABLE))
            else -> emptyList()
        }
        override fun columns(owner: String, name: String) = if (!ready) null else when ("$owner.$name") {
            "DEMO.EMP" -> listOf(CatalogColumn("ID", "NUMBER"), CatalogColumn("NOME", "VARCHAR2"), CatalogColumn("DEPT_ID", "NUMBER"))
            "DEMO.DEPT" -> listOf(CatalogColumn("ID", "NUMBER"), CatalogColumn("DNOME", "VARCHAR2"))
            "PEM.ALERGIA" -> listOf(CatalogColumn("CODIGO", "VARCHAR2"))
            else -> emptyList()
        }
        override fun members(owner: String, pkg: String) = if (!ready) null else if ("$owner.$pkg" == "PEM.PK_UTIL") listOf("CALC_PRECO", "FORMATA") else emptyList()
    }

    private fun engine(c: Catalog = FakeCatalog()) = CompletionEngine(c) { listOf("DEMO") }

    private fun at(sql: String): CompletionResult {
        val caret = sql.indexOf('|')
        return engine().complete(sql.replace("|", ""), caret)
    }

    private fun texts(r: CompletionResult) = r.items.map { it.text }

    @Test fun `schema ponto lista os objectos do schema (como no DataGrip)`() {
        val r = at("select * from pem.|")
        assertEquals("", r.prefix)
        assertEquals(listOf("ACCOUNT_", "ALERGIA", "PK_UTIL", "V_ALERGIA").sorted(), texts(r).sorted())
        assertTrue(r.items.all { it.kind == ItemKind.OBJECT })
        assertEquals("(PEM)", r.items.first().detail)
    }

    @Test fun `prefixo filtra por inicio`() {
        val r = at("select * from pem.al|")
        assertEquals("al", r.prefix)
        assertEquals("ALERGIA", texts(r).first(), "quem comeca pelo prefixo vem primeiro")
        assertTrue("ACCOUNT_" !in texts(r))
    }

    @Test fun `depois de FROM sugere objectos e schemas, sem keywords nem colunas`() {
        val r = at("select * from |")
        assertTrue(texts(r).containsAll(listOf("EMP", "DEPT", "PEM", "DEMO")))
        assertTrue(r.items.none { it.kind == ItemKind.KEYWORD || it.kind == ItemKind.COLUMN })
    }

    @Test fun `alias ponto lista as colunas da tabela do alias`() {
        val r = at("select e.| from emp e join dept d on d.id = e.dept_id")
        assertEquals(listOf("ID", "NOME", "DEPT_ID").sorted(), texts(r).sorted())
        assertTrue(r.items.all { it.kind == ItemKind.COLUMN })
        assertEquals("NUMBER", r.items.first { it.text == "ID" }.detail)
    }

    @Test fun `alias com AS e segundo alias de JOIN`() {
        assertEquals(listOf("DNOME", "ID"), texts(at("select d.| from emp as e, dept as d")).sorted())
        assertEquals(listOf("DNOME", "ID"), texts(at("select * from emp e inner join dept d on 1=1 where d.|")).sorted())
    }

    @Test fun `nome de tabela sem alias tambem funciona`() {
        assertEquals(listOf("DNOME", "ID"), texts(at("select dept.| from dept")).sorted())
    }

    @Test fun `schema ponto tabela ponto lista colunas`() {
        assertEquals(listOf("CODIGO"), texts(at("select pem.alergia.| from dual")))
    }

    @Test fun `package ponto lista os membros`() {
        val r = at("begin pem.pk_util.|")
        assertEquals(listOf("CALC_PRECO", "FORMATA"), texts(r).sorted())
        assertTrue(r.items.all { it.kind == ItemKind.MEMBER })
    }

    @Test fun `sem qualificador mistura colunas das tabelas da instrucao, objectos, schemas e keywords`() {
        val r = at("select n| from emp e")
        assertTrue("NOME" in texts(r), texts(r).toString())
        assertTrue(texts(r).any { it.equals("NVL", ignoreCase = true) })
        assertEquals(ItemKind.COLUMN, r.items.first().kind, "colunas primeiro")
    }

    @Test fun `keywords seguem a caixa do que o utilizador escreveu`() {
        assertTrue("select" in texts(at("sel|")))
        assertTrue("SELECT" in texts(at("SEL|")))
    }

    @Test fun `nada dentro de strings e comentarios`() {
        assertTrue(at("select 'abc pem.|' from dual").items.isEmpty())
        assertTrue(at("select 1 -- emp.|\n").items.isEmpty())
        assertTrue(at("select /* pem.| */ 1").items.isEmpty())
    }

    @Test fun `depois do fim de uma string ja volta a sugerir`() {
        assertTrue(at("select 'x' from pem.|").items.isNotEmpty())
    }

    @Test fun `nomes com maiusculas e minusculas ficam entre aspas`() {
        assertTrue("\"Mixed\"" in texts(at("select * from demo.|")))
    }

    @Test fun `dados ainda a carregar sinalizam loading`() {
        val r = CompletionEngine(FakeCatalog(ready = false)) { listOf("DEMO") }.complete("select * from pem.", 18)
        assertTrue(r.loading)
        assertTrue(r.items.isEmpty())
    }

    @Test fun `parser de aliases`() {
        val m = AliasParser.parse("select * from demo.emp e, dept d left outer join pem.alergia a on a.x = e.y where 1=1")
        assertEquals(TableRef("DEMO", "EMP"), m["E"])
        assertEquals(TableRef(null, "DEPT"), m["D"])
        assertEquals(TableRef("PEM", "ALERGIA"), m["A"])
        assertFalse(m.containsKey("WHERE"))
        assertFalse(m.containsKey("ON"))
    }

    @Test fun `update e insert into`() {
        assertEquals(TableRef(null, "EMP"), AliasParser.parse("update emp set nome = 'x'")["EMP"])
        assertEquals(TableRef("DEMO", "DEPT"), AliasParser.parse("insert into demo.dept (id) values (1)")["DEPT"])
    }
}
