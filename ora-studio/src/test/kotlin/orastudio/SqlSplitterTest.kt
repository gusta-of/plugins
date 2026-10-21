package orastudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlSplitterTest {

    private fun execs(s: String) = SqlSplitter.split(s).map { it.exec }

    @Test fun `separa por ponto e virgula e ignora comentarios e strings`() {
        val sql = """
            -- comentario; com ponto e virgula
            SELECT 'a;b' AS x FROM dual;
            /* outro; comentario */
            UPDATE t SET c = 'it''s;' WHERE id = 1;
            SELECT "col;x" FROM t
        """.trimIndent()
        assertEquals(listOf("SELECT 'a;b' AS x FROM dual", "UPDATE t SET c = 'it''s;' WHERE id = 1", "SELECT \"col;x\" FROM t"), execs(sql))
    }

    @Test fun `bloco anonimo termina no END ponto e virgula sem barra`() {
        val sql = "BEGIN\n  IF 1=1 THEN\n    NULL;\n  END IF;\n  FOR i IN 1..2 LOOP NULL; END LOOP;\nEND;\nSELECT 1 FROM dual;"
        val r = SqlSplitter.split(sql)
        assertEquals(2, r.size)
        assertTrue(r[0].plsql)
        assertTrue(r[0].exec.endsWith("END;"))
        assertEquals("SELECT 1 FROM dual", r[1].exec)
    }

    @Test fun `CASE dentro de bloco nao fecha o bloco`() {
        val sql = "BEGIN\n  x := CASE WHEN a=1 THEN 1 ELSE 2 END;\n  y := 3;\nEND;\n"
        val r = SqlSplitter.split(sql)
        assertEquals(1, r.size)
        assertTrue(r[0].exec.endsWith("y := 3;\nEND;"))
    }

    @Test fun `create procedure termina na barra e mantem o ponto e virgula`() {
        val sql = """
            CREATE OR REPLACE PROCEDURE p IS
              v NUMBER;
            BEGIN
              v := 1;
              NULL;
            END p;
            /
            SELECT 2 FROM dual;
        """.trimIndent()
        val r = SqlSplitter.split(sql)
        assertEquals(2, r.size)
        assertTrue(r[0].plsql)
        assertTrue(r[0].exec.trimEnd().endsWith("END p;"))
        assertEquals("SELECT 2 FROM dual", r[1].exec)
    }

    @Test fun `package spec e body precisam da barra`() {
        val sql = """
            CREATE OR REPLACE PACKAGE pk AS
              PROCEDURE a;
              FUNCTION b RETURN NUMBER;
            END pk;
            /
            CREATE OR REPLACE PACKAGE BODY pk AS
              PROCEDURE a IS BEGIN NULL; END;
              FUNCTION b RETURN NUMBER IS BEGIN RETURN 1; END;
            END pk;
            /
        """.trimIndent()
        val r = SqlSplitter.split(sql)
        assertEquals(2, r.size)
        assertTrue(r[0].exec.contains("FUNCTION b RETURN NUMBER;"))
        assertTrue(r[1].exec.contains("END pk;"))
    }

    @Test fun `create trigger com corpo`() {
        val sql = "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW\nBEGIN\n  :new.id := 1;\nEND;\n/\nSELECT 1 FROM dual;"
        val r = SqlSplitter.split(sql)
        assertEquals(2, r.size)
        assertTrue(r[0].exec.contains(":new.id := 1;"))
    }

    @Test fun `create type table termina no ponto e virgula`() {
        val r = SqlSplitter.split("CREATE TYPE tt AS TABLE OF NUMBER;\nSELECT 1 FROM dual;")
        assertEquals(2, r.size)
        assertEquals("CREATE TYPE tt AS TABLE OF NUMBER", r[0].exec)
    }

    @Test fun `q-quote nao parte a instrucao`() {
        val r = SqlSplitter.split("SELECT q'[it's; here]' FROM dual; SELECT 2 FROM dual;")
        assertEquals(2, r.size)
    }

    @Test fun `statementAt escolhe a instrucao sob o cursor`() {
        val sql = "SELECT 1 FROM dual;\n\nSELECT 2 FROM dual;\nSELECT 3 FROM dual;"
        assertEquals("SELECT 1 FROM dual", SqlSplitter.statementAt(sql, 3)!!.exec)
        assertEquals("SELECT 1 FROM dual", SqlSplitter.statementAt(sql, sql.indexOf(';') + 1)!!.exec) // logo apos o ;
        assertEquals("SELECT 2 FROM dual", SqlSplitter.statementAt(sql, sql.indexOf("2"))!!.exec)
        assertEquals("SELECT 3 FROM dual", SqlSplitter.statementAt(sql, sql.length)!!.exec)
    }

    @Test fun `offsets do script`() {
        val sql = "SELECT 1 FROM dual;\nSELECT 2 FROM dual;"
        val r = SqlSplitter.split(sql)
        assertEquals(sql.indexOf("SELECT 2"), r[1].start)
        assertEquals(sql.indexOf("SELECT 2") + 7, r[1].scriptOffset(7))
    }

    @Test fun `script vazio`() {
        assertTrue(SqlSplitter.split("  \n -- so comentario\n ").isEmpty())
    }
}
