package orastudio

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Corre contra um Oracle real: ORA_TEST_JAR, ORA_TEST_URL, ORA_TEST_USER, ORA_TEST_PASSWORD (senao e ignorado). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName::class)
class OracleIntegrationTest {
    private val jar = System.getenv("ORA_TEST_JAR").orEmpty()
    private val url = System.getenv("ORA_TEST_URL").orEmpty()
    private val user = System.getenv("ORA_TEST_USER").orEmpty()
    private val pass = System.getenv("ORA_TEST_PASSWORD").orEmpty()
    private lateinit var handle: ConnectionHandle

    @BeforeAll fun setup() {
        assumeTrue(jar.isNotBlank() && url.isNotBlank())
        ConfigStore.overrideDrivers(listOf(DriverConfig(Drivers.DEFAULT_ID, "Oracle", jars = mutableListOf(jar))))
        val cfg = ConnectionConfig(name = "teste", user = user, url = url)
        handle = ConnectionHandle(cfg).also { it.password = pass }
    }

    private class Collect : RunListener {
        val outcomes = mutableListOf<Outcome>()
        val output = mutableListOf<String>()
        override fun onOutcome(outcome: Outcome) { outcomes += outcome }
        override fun onOutput(lines: List<String>) { output += lines }
    }

    private fun runScript(script: String, stopOnError: Boolean = false, rowLimit: Int = 500): Collect {
        val s = handle.openSession("test")
        try {
            val c = Collect()
            s.sync { SqlRunner.run(s, SqlSplitter.split(script), rowLimit, stopOnError, c) }
            return c
        } finally {
            s.close()
        }
    }

    @Test fun `01 liga e le a versao e o schema`() {
        val s = handle.metaSession()
        assertEquals(user.uppercase(), handle.currentSchema)
        assertTrue(handle.connected)
        println("Ligado como ${handle.currentSchema} com driver ${s.conn.metaData.driverName} ${s.conn.metaData.driverVersion} a ${s.conn.metaData.databaseProductVersion.lineSequence().first()}")
    }

    @Test fun `02 cria objectos com script (ddl, plsql, comandos)`() {
        runScript(
            """
            BEGIN FOR r IN (SELECT object_name n, object_type t FROM user_objects WHERE object_type IN ('MATERIALIZED VIEW')) LOOP EXECUTE IMMEDIATE 'DROP MATERIALIZED VIEW ' || r.n; END LOOP; END;
            /
            BEGIN FOR r IN (SELECT object_name n, object_type t FROM user_objects WHERE object_type IN ('VIEW','TABLE','PROCEDURE','FUNCTION','PACKAGE','SEQUENCE','TYPE','SYNONYM')) LOOP
              BEGIN EXECUTE IMMEDIATE 'DROP ' || r.t || ' ' || r.n || CASE WHEN r.t = 'TABLE' THEN ' CASCADE CONSTRAINTS' ELSE '' END; EXCEPTION WHEN OTHERS THEN NULL; END;
            END LOOP; END;
            /
            """.trimIndent(),
        )
        val c = runScript(
            """
            CREATE TABLE dept (id NUMBER(5) PRIMARY KEY, nome VARCHAR2(50 CHAR) NOT NULL, criado DATE DEFAULT SYSDATE);
            COMMENT ON TABLE dept IS 'Departamentos';
            COMMENT ON COLUMN dept.nome IS 'Nome do departamento';
            CREATE TABLE emp (id NUMBER PRIMARY KEY, dept_id NUMBER(5) REFERENCES dept(id), nome VARCHAR2(100), salario NUMBER(10,2), CONSTRAINT ck_sal CHECK (salario >= 0));
            CREATE INDEX ix_emp_nome ON emp(nome);
            CREATE SEQUENCE seq_emp START WITH 100;
            CREATE VIEW v_emp AS SELECT e.id, e.nome, d.nome AS dept FROM emp e JOIN dept d ON d.id = e.dept_id;
            CREATE MATERIALIZED VIEW mv_emp REFRESH COMPLETE ON DEMAND AS SELECT * FROM v_emp;
            CREATE OR REPLACE FUNCTION f_dobro(p NUMBER) RETURN NUMBER IS BEGIN RETURN p * 2; END;
            /
            CREATE OR REPLACE PACKAGE pk_emp AS
              PROCEDURE contratar(p_nome VARCHAR2, p_dept NUMBER);
            END pk_emp;
            /
            CREATE OR REPLACE PACKAGE BODY pk_emp AS
              PROCEDURE contratar(p_nome VARCHAR2, p_dept NUMBER) IS
              BEGIN
                INSERT INTO emp (id, dept_id, nome, salario) VALUES (seq_emp.NEXTVAL, p_dept, p_nome, f_dobro(1000));
                DBMS_OUTPUT.PUT_LINE('contratado ' || p_nome);
              END;
            END pk_emp;
            /
            CREATE OR REPLACE TRIGGER trg_emp_bi BEFORE INSERT ON emp FOR EACH ROW
            BEGIN
              IF :new.nome IS NULL THEN :new.nome := 'SEM NOME'; END IF;
            END;
            /
            CREATE SYNONYM syn_emp FOR emp;
            INSERT INTO dept (id, nome) VALUES (1, 'TI');
            BEGIN pk_emp.contratar('Ana', 1); END;
            /
            COMMIT;
            """.trimIndent(),
            stopOnError = true,
        )
        val errors = c.outcomes.filterIsInstance<ErrorOutcome>()
        assertTrue(errors.isEmpty(), "erros: " + errors.joinToString { it.error.message })
        assertTrue(c.output.any { it.contains("contratado Ana") }, "DBMS_OUTPUT: ${c.output}")
        assertEquals(16, c.outcomes.size)
    }

    @Test fun `03 select com limite e carregar mais`() {
        runScript("BEGIN FOR i IN 1..250 LOOP INSERT INTO emp (id, dept_id, nome, salario) VALUES (1000 + i, 1, 'x' || i, i); END LOOP; COMMIT; END;")
        val s = handle.openSession("q")
        try {
            val c = Collect()
            s.sync { SqlRunner.run(s, SqlSplitter.split("SELECT id, nome, salario, SYSDATE d FROM emp ORDER BY id"), 100, true, c) }
            val q = c.outcomes.single() as QueryOutcome
            assertEquals(100, q.rows.size)
            assertFalse(q.cursor.exhausted)
            val more = s.sync { q.cursor.fetch(1000) }
            assertEquals(251 - 100, more.size) // 250 + a Ana
            assertTrue(q.cursor.exhausted)
            assertEquals(listOf("ID", "NOME", "SALARIO", "D"), q.cursor.columns.map { it.name })
        } finally { s.close() }
    }

    @Test fun `04 erros sao capturados com codigo ORA e script continua`() {
        val c = runScript("SELECT * FROM nao_existe;\nSELECT 1 FROM dual;\nSELEC 1 FROM dual;", stopOnError = false)
        assertEquals(3, c.outcomes.size)
        val e1 = c.outcomes[0] as ErrorOutcome
        assertEquals(942, e1.error.code)
        assertTrue(e1.error.message.contains("ORA-00942"))
        assertTrue(c.outcomes[1] is QueryOutcome)
        assertEquals(900, (c.outcomes[2] as ErrorOutcome).error.code)
    }

    @Test fun `05 stop on error para no primeiro erro`() {
        val c = runScript("SELECT * FROM nao_existe;\nSELECT 1 FROM dual;", stopOnError = true)
        assertEquals(1, c.outcomes.size)
    }

    @Test fun `06 erros de compilacao plsql sao reportados`() {
        val c = runScript("CREATE OR REPLACE PROCEDURE p_mau IS BEGIN nao_existe_xpto(1); END;\n/")
        val u = c.outcomes.single()
        assertTrue(u is UpdateOutcome && u.notes.any { it.contains("PLS-00201") || it.contains("compila", true) })
        runScript("DROP PROCEDURE p_mau")
    }

    @Test fun `07 metadados - listas, colunas, constraints, indices`() {
        val md = handle.metadata
        assertTrue(md.listSchemas().containsAll(listOf("DEMO", "OTHERS", "SYS")))
        val tables = md.listObjects("DEMO", ObjectType.TABLE).map { it.name }
        assertEquals(listOf("DEPT", "EMP"), tables) // MV_EMP nao aparece como tabela
        assertEquals(listOf("MV_EMP"), md.listObjects("DEMO", ObjectType.MATERIALIZED_VIEW).map { it.name })
        assertEquals(listOf("V_EMP"), md.listObjects("DEMO", ObjectType.VIEW).map { it.name })
        assertEquals(listOf("PK_EMP"), md.listObjects("DEMO", ObjectType.PACKAGE).map { it.name })
        assertEquals(listOf("TRG_EMP_BI"), md.listObjects("DEMO", ObjectType.TRIGGER).map { it.name })

        val cols = md.columns(ObjectRef("DEMO", "DEPT", ObjectType.TABLE))
        assertEquals(listOf("ID", "NOME", "CRIADO"), cols.map { it.name })
        assertEquals("NUMBER(5)", cols[0].type)
        assertEquals("VARCHAR2(50 CHAR)", cols[1].type)
        assertFalse(cols[1].nullable)
        assertEquals("SYSDATE", cols[2].default)
        assertEquals("Nome do departamento", cols[1].comment)

        val cons = md.constraints(ObjectRef("DEMO", "EMP", ObjectType.TABLE))
        val fk = cons.single { it.type == "FOREIGN KEY" }
        assertEquals("DEPT_ID", fk.columns)
        assertEquals(ObjectRef("DEMO", "DEPT", ObjectType.TABLE), fk.refTable)
        assertEquals("ID", fk.refColumns)
        assertTrue(cons.any { it.type == "CHECK" && it.condition?.contains("salario >= 0") == true })
        assertTrue(cons.any { it.type == "PRIMARY KEY" })

        val idx = md.indexes(ObjectRef("DEMO", "EMP", ObjectType.TABLE))
        assertTrue(idx.any { it.name == "IX_EMP_NOME" && it.columns == "NOME" && !it.unique })

        val trg = md.triggersOn(ObjectRef("DEMO", "EMP", ObjectType.TABLE))
        assertEquals("TRG_EMP_BI", trg.single().ref.name)
        assertEquals("VALID", md.info(ObjectRef("DEMO", "EMP", ObjectType.TABLE)).status)
    }

    @Test fun `08 ddl de cada tipo de objecto`() {
        val md = handle.metadata
        val mv = md.ddl(ObjectRef("DEMO", "MV_EMP", ObjectType.MATERIALIZED_VIEW))
        assertTrue(mv.uppercase().contains("CREATE MATERIALIZED VIEW"))
        assertTrue(mv.uppercase().contains("V_EMP"), "o select da MV deve referir V_EMP")
        assertTrue(md.ddl(ObjectRef("DEMO", "V_EMP", ObjectType.VIEW)).uppercase().contains("JOIN"))
        assertTrue(md.ddl(ObjectRef("DEMO", "EMP", ObjectType.TABLE)).uppercase().contains("CREATE TABLE"))
        assertTrue(md.ddl(ObjectRef("DEMO", "TRG_EMP_BI", ObjectType.TRIGGER)).uppercase().contains("TRIGGER"))
        assertTrue(md.ddl(ObjectRef("DEMO", "F_DOBRO", ObjectType.FUNCTION)).uppercase().contains("RETURN"))
        val spec = md.ddl(ObjectRef("DEMO", "PK_EMP", ObjectType.PACKAGE))
        val body = md.ddl(ObjectRef("DEMO", "PK_EMP", ObjectType.PACKAGE), body = true)
        assertTrue(spec.uppercase().contains("PACKAGE"), spec)
        assertTrue(body.uppercase().contains("SEQ_EMP.NEXTVAL"), body)
        assertTrue(md.hasBody(ObjectRef("DEMO", "PK_EMP", ObjectType.PACKAGE)))
        assertTrue(md.ddl(ObjectRef("DEMO", "SEQ_EMP", ObjectType.SEQUENCE)).uppercase().contains("SEQUENCE"))
        assertTrue(md.ddl(ObjectRef("DEMO", "SYN_EMP", ObjectType.SYNONYM)).uppercase().contains("SYNONYM"))
        assertEquals("DEMO" to "EMP", md.synonymTarget(ObjectRef("DEMO", "SYN_EMP", ObjectType.SYNONYM)))
    }

    @Test fun `09 dependencias`() {
        val md = handle.metadata
        val uses = md.uses(ObjectRef("DEMO", "V_EMP", ObjectType.VIEW)).map { it.ref.name }
        assertTrue(uses.containsAll(listOf("EMP", "DEPT")), uses.toString())
        val usedBy = md.usedBy(ObjectRef("DEMO", "V_EMP", ObjectType.VIEW)).map { it.ref.name }
        assertTrue("MV_EMP" in usedBy, usedBy.toString())
        val pkUses = md.uses(ObjectRef("DEMO", "PK_EMP", ObjectType.PACKAGE)).map { it.ref.name }
        assertTrue(pkUses.containsAll(listOf("EMP", "F_DOBRO", "SEQ_EMP")), pkUses.toString())
    }

    @Test fun `10 pesquisa de objectos`() {
        val hits = handle.metadata.search("emp", "DEMO")
        assertTrue(hits.any { it.ref == ObjectRef("DEMO", "EMP", ObjectType.TABLE) })
        assertEquals("DEMO", hits.first().ref.owner)
    }

    @Test fun `11 resolver de links - qualificado, nao qualificado e gemeos de MV`() {
        val r = handle.resolver
        val owners = listOf("DEMO")
        r.prefetch("SELECT * FROM mv_emp m JOIN dept d ON 1=1 WHERE f_dobro(1) > 0 -- comentario TABELA_FANTASMA\n AND demo.emp.id = 1 /* so emp */ ", owners)
        val deadline = System.currentTimeMillis() + 15000
        while (r.cached(owners, null, "MV_EMP") == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val mv = r.cached(owners, null, "MV_EMP")
        assertNotNull(mv)
        assertEquals(listOf(ObjectType.MATERIALIZED_VIEW, ObjectType.TABLE), mv.map { it.type }, "MV primeiro, depois a tabela gemea (como no DataGrip)")
        assertEquals(ObjectType.TABLE, r.cached(owners, null, "DEPT")!!.single().type)
        assertEquals(ObjectType.FUNCTION, r.cached(owners, null, "F_DOBRO")!!.single().type)
        assertEquals(emptyList(), r.cached(owners, null, "M"))
        assertNull(r.cached(owners, null, "TABELA_FANTASMA"))
    }

    @Test fun `12 schema qualificado de outro utilizador`() {
        val r = handle.resolver
        val owners = listOf("OTHERS")
        r.prefetch("SELECT * FROM demo.dept", owners)
        val deadline = System.currentTimeMillis() + 15000
        while (r.cached(owners, "DEMO", "DEPT") == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(ObjectRef("DEMO", "DEPT", ObjectType.TABLE), r.cached(owners, "DEMO", "DEPT")!!.single())
    }

    @Test fun `13 cancelar uma consulta longa`() {
        val s = handle.openSession("cancel")
        try {
            val c = Collect()
            val f = s.submit {
                SqlRunner.run(s, SqlSplitter.split("SELECT COUNT(*) FROM all_objects a, all_objects b, all_objects c"), 10, true, c)
            }
            Thread.sleep(1500)
            s.cancelCurrent()
            f.get(30, java.util.concurrent.TimeUnit.SECONDS)
            val e = c.outcomes.single() as ErrorOutcome
            assertTrue(e.error.code == 1013 || e.error.message.contains("closed", true) || e.error.message.contains("fechad", true) || e.error.code == 17008, e.error.message)
        } finally { s.close() }
    }

    @Test fun `14 tabela estilo PEM (clob, timestamp, defaults, pk) - todas as partes carregam`() {
        runScript(
            """
            BEGIN EXECUTE IMMEDIATE 'DROP TABLE refdata CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;
            /
            CREATE TABLE refdata (
              id NUMBER(19) NOT NULL,
              creationdate TIMESTAMP(6),
              opt_lock NUMBER(19) DEFAULT 0,
              referencekey VARCHAR2(255 CHAR),
              referencevalue CLOB,
              valuetype VARCHAR2(255 CHAR),
              CONSTRAINT pk_refdata PRIMARY KEY (id)
            );
            CREATE UNIQUE INDEX ux_refdata_key ON refdata(referencekey, valuetype);
            CREATE OR REPLACE TRIGGER trg_refdata BEFORE UPDATE ON refdata FOR EACH ROW BEGIN :new.opt_lock := NVL(:old.opt_lock, 0) + 1; END;
            /
            COMMENT ON COLUMN refdata.referencekey IS 'chave';
            """.trimIndent(),
        )
        val ref = ObjectRef("DEMO", "REFDATA", ObjectType.TABLE)
        val md = handle.metadata
        val cols = md.columns(ref)
        assertEquals(6, cols.size)
        assertEquals("TIMESTAMP(6)", cols[1].type)
        assertEquals("0", cols[2].default)
        assertEquals("CLOB", cols[4].type)
        assertTrue(md.constraints(ref).any { it.type == "PRIMARY KEY" && it.columns == "ID" })
        assertTrue(md.indexes(ref).any { it.name == "UX_REFDATA_KEY" && it.unique && it.columns == "REFERENCEKEY, VALUETYPE" })
        assertEquals(1, md.triggersOn(ref).size)
        assertTrue(md.ddl(ref).uppercase().contains("CREATE TABLE"))
        md.uses(ref); md.usedBy(ref); md.info(ref)
        runScript("DROP TABLE refdata CASCADE CONSTRAINTS")
    }

    @Test fun `15 catalogo real do autocompletar - objectos, colunas, sinonimos e package`() {
        runScript(
            """
            CREATE TABLE emp2 (id NUMBER, nome VARCHAR2(20));
            CREATE MATERIALIZED VIEW mv_emp2 AS SELECT * FROM emp2;
            CREATE SYNONYM syn_emp2 FOR emp2;
            """.trimIndent(),
        )
        val cat = ServerCatalog(handle)
        fun <T> await(f: () -> T?): T {
            val end = System.currentTimeMillis() + 15000
            var v = f()
            while (v == null && System.currentTimeMillis() < end) { Thread.sleep(50); v = f() }
            return v ?: error("timeout a carregar o catalogo")
        }
        assertTrue("DEMO" in await { cat.schemas() })
        val objs = await { cat.objects("DEMO") }
        assertEquals(ObjectType.MATERIALIZED_VIEW, objs.single { it.name == "MV_EMP2" }.type, "a MV aparece uma so vez (nao tambem como tabela)")
        assertEquals(ObjectType.TABLE, objs.single { it.name == "EMP2" }.type)
        assertEquals(listOf("ID", "NOME"), await { cat.columns("DEMO", "EMP2") }.map { it.name })
        assertEquals(listOf("ID", "NOME"), await { cat.columns("DEMO", "SYN_EMP2") }.map { it.name }, "synonym -> colunas da tabela destino")
        assertEquals(listOf("CONTRATAR"), await { cat.members("DEMO", "PK_EMP") })

        val engine = CompletionEngine(cat) { listOf("DEMO") }
        val sql = "select e.n from emp2 e"
        val r = await { engine.complete(sql, sql.indexOf(" from")).takeIf { !it.loading } }
        assertEquals(listOf("NOME"), r.items.filter { it.kind == ItemKind.COLUMN }.map { it.text })
        runScript("DROP SYNONYM syn_emp2")
        runScript("DROP MATERIALIZED VIEW mv_emp2")
        runScript("DROP TABLE emp2 PURGE")
    }
}
