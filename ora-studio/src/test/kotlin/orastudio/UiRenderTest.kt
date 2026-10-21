package orastudio

import com.formdev.flatlaf.FlatDarkLaf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Monta os paineis reais contra um Oracle e desenha-os em PNG (precisa de DISPLAY, mas nao abre janelas). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
class UiRenderTest {
    private val jar = System.getenv("ORA_TEST_JAR").orEmpty()
    private val url = System.getenv("ORA_TEST_URL").orEmpty()
    private val shots = File("build/ui-shots").apply { mkdirs() }
    private lateinit var handle: ConnectionHandle

    private class FakeApp : AppContext {
        val opened = mutableListOf<ObjectRef>()
        val consoles = mutableListOf<String?>()
        override fun openObject(handle: ConnectionHandle, ref: ObjectRef) { opened += ref }
        override fun openConsole(handle: ConnectionHandle, text: String?) { consoles += text }
        override fun status(msg: String) {}
    }

    private class FakeActions : NavActions {
        override fun connect(node: ConnNode) {}
        override fun disconnect(node: ConnNode) {}
        override fun edit(node: ConnNode) {}
        override fun duplicate(node: ConnNode) {}
        override fun remove(node: ConnNode) {}
        override fun passwordFor(handle: ConnectionHandle) = true
    }

    @BeforeAll fun setup() {
        assumeTrue(jar.isNotBlank() && url.isNotBlank())
        FlatDarkLaf.setup()
        UiEnv.isDark = { com.formdev.flatlaf.FlatLaf.isLafDark() }
        UiEnv.scale = { com.formdev.flatlaf.util.UIScale.scale(it) }
        ConfigStore.overrideDrivers(listOf(DriverConfig(Drivers.DEFAULT_ID, "Oracle", jars = mutableListOf(jar))))
        handle = ConnectionHandle(ConnectionConfig(name = "XE local (DEMO)", user = "DEMO", url = url)).also { it.password = "demo_test" }
        handle.metaSession()
    }

    private fun layoutTree(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach { layoutTree(it) }
    }

    private fun snapshot(c: JComponent, w: Int, h: Int, name: String) {
        SwingUtilities.invokeAndWait {
            // janela com "peer" mas nunca mostrada (nada aparece no ecra): o RSyntaxTextArea precisa de getGraphics()
            val frame = javax.swing.JFrame()
            frame.contentPane.add(c)
            frame.setSize(w, h)
            frame.addNotify()
            frame.contentPane.setSize(w, h)
            c.setBounds(0, 0, w, h)
            layoutTree(c)
            layoutTree(c)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = c.background
            g.fillRect(0, 0, w, h)
            c.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(shots, "$name.png"))
            frame.contentPane.remove(c)
            frame.dispose()
        }
    }

    private fun waitFor(timeoutMs: Long = 20000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(cond(), "timeout")
    }

    @Test fun `01 arvore de navegacao`() {
        val nav = Navigator(FakeApp(), FakeActions())
        SwingUtilities.invokeAndWait { nav.setConnections(listOf(handle)) }
        val conn = nav.nodeFor(handle)!!
        SwingUtilities.invokeAndWait { nav.expandPath(TreePath(conn.path)) }
        waitFor { conn.childCount > 0 && conn.getChildAt(0) is SchemaNode }
        val demo = (0 until conn.childCount).map { conn.getChildAt(it) }.filterIsInstance<SchemaNode>().first { it.name == "DEMO" }
        assertTrue(demo.current)
        assertEquals("DEMO", (conn.getChildAt(0) as SchemaNode).name, "schema actual primeiro")
        SwingUtilities.invokeAndWait { nav.expandPath(TreePath(demo.path)) }
        waitFor { demo.childCount == ObjectType.browsable.size }
        val tables = (0 until demo.childCount).map { demo.getChildAt(it) }.filterIsInstance<CategoryNode>().first { it.type == ObjectType.TABLE }
        SwingUtilities.invokeAndWait { nav.expandPath(TreePath(tables.path)) }
        waitFor { tables.childCount > 0 && tables.getChildAt(0) is ObjectNode }
        snapshot(javax.swing.JScrollPane(nav), 300, 520, "01-arvore")
    }

    private fun viewer(ref: ObjectRef, tab: String? = null, name: String, w: Int = 1000, h: Int = 560): ObjectViewerPanel {
        val v = ObjectViewerPanel(FakeApp(), handle, ref)
        waitFor { v.isLoaded }
        if (tab != null) SwingUtilities.invokeAndWait { v.selectTab(tab) }
        waitFor { v.isIdle() }
        Thread.sleep(200)
        snapshot(v, w, h, name)
        return v
    }

    @Test fun `02 viewer da materialized view mostra DDL com select`() {
        val v = viewer(ObjectRef("DEMO", "MV_EMP", ObjectType.MATERIALIZED_VIEW), name = "02-mv")
        assertTrue(v.tabTitles().containsAll(listOf("Colunas", "DDL", "Dados", "Dependências")), v.tabTitles().toString())
    }

    @Test fun `03 viewer de tabela`() {
        val v = viewer(ObjectRef("DEMO", "EMP", ObjectType.TABLE), "Colunas", "03-tabela-colunas")
        assertTrue(v.tabTitles().containsAll(listOf("Colunas", "Constraints", "Índices", "Triggers", "DDL", "Dados", "Dependências")), v.tabTitles().toString())
        viewer(ObjectRef("DEMO", "EMP", ObjectType.TABLE), "Constraints", "03-tabela-constraints")
        viewer(ObjectRef("DEMO", "EMP", ObjectType.TABLE), "DDL", "03-tabela-ddl")
        viewer(ObjectRef("DEMO", "EMP", ObjectType.TABLE), "Dados", "03-tabela-dados")
    }

    @Test fun `04 viewer de package e trigger e view`() {
        val p = viewer(ObjectRef("DEMO", "PK_EMP", ObjectType.PACKAGE), "Corpo", "04-package-corpo")
        assertTrue(p.tabTitles().containsAll(listOf("Especificação", "Corpo", "Dependências")))
        viewer(ObjectRef("DEMO", "TRG_EMP_BI", ObjectType.TRIGGER), null, "04-trigger")
        viewer(ObjectRef("DEMO", "V_EMP", ObjectType.VIEW), "Dependências", "04-view-deps")
    }

    @Test fun `05 ctrl+clique - links no editor, gemeos de MV e navegacao`() {
        val app = FakeApp()
        val ed = SqlEditor(handle, { listOf("DEMO") }, { app.openObject(handle, it) })
        val sql = "select *\n  from DEMO.MV_EMP m\n  join dept d on d.id = m.id\n where f_dobro(1) > 0 -- emp\n"
        SwingUtilities.invokeAndWait { ed.text = sql }
        handle.resolver.prefetch(sql, listOf("DEMO"))
        waitFor { handle.resolver.cached(listOf("DEMO"), "DEMO", "MV_EMP") != null && handle.resolver.cached(listOf("DEMO"), null, "F_DOBRO") != null }
        fun linkAt(needle: String, delta: Int = 1): Boolean {
            var found = false
            SwingUtilities.invokeAndWait {
                val off = sql.indexOf(needle) + delta
                val r = ed.area.linkGenerator.isLinkAtOffset(ed.area, off)
                found = r != null
                r?.execute()
            }
            return found
        }
        assertTrue(linkAt("MV_EMP", 2), "MV_EMP deve ser um link")
        waitFor(3000) { app.opened.isNotEmpty() }
        assertEquals(ObjectRef("DEMO", "MV_EMP", ObjectType.MATERIALIZED_VIEW), app.opened.first(), "MV primeiro (a tabela gemea vem a seguir)")
        app.opened.clear()
        assertTrue(linkAt("dept"))
        waitFor(3000) { app.opened.isNotEmpty() }
        assertEquals(ObjectRef("DEMO", "DEPT", ObjectType.TABLE), app.opened.single())
        assertTrue(linkAt("f_dobro"))
        assertTrue(!linkAt("select", 2), "palavra reservada nao e link")
        assertTrue(!linkAt("-- emp", 4), "comentario nao e link")
        assertTrue(!linkAt("d.id", 2), "alias.coluna nao e link para a coluna")
        snapshot(ed.scroll, 700, 160, "05-editor")
    }

    @Test fun `06 consola SQL executa script e mostra resultados`() {
        val app = FakeApp()
        val c = SqlConsolePanel(app, handle, "select id, nome, salario from emp where rownum <= 5 order by id;\nselect * from dept;\nbegin dbms_output.put_line('ola'); end;\n/\nselect * from tabela_inexistente;\n")
        SwingUtilities.invokeAndWait { c.runScript() }
        waitFor { !c.busy && c.resultTabCount() >= 1 && c.logText().contains("com erro") }
        assertEquals(2, c.resultTabCount())
        val log = c.logText()
        assertTrue(log.contains("ORA-00942"), log)
        assertTrue(log.contains("ola"), log)
        snapshot(c, 1100, 700, "06-consola")
        SwingUtilities.invokeAndWait { c.showResults() }
        snapshot(c, 1100, 700, "06-consola-resultados")
        c.dispose()
    }

    @Test fun `06b autocompletar - popup leve colado ao cursor`() {
        val ed = SqlEditor(handle, { listOf("DEMO") }, { })
        val popup = ed.completion!!
        // o editor tem de estar numa janela (so com peer, nunca mostrada) para existir a camada de popups
        lateinit var frame: javax.swing.JFrame
        SwingUtilities.invokeAndWait {
            frame = javax.swing.JFrame()
            frame.contentPane.add(ed.component)
            frame.setSize(900, 500)
            frame.addNotify()
            frame.layeredPane.setBounds(0, 0, 900, 500)
            frame.contentPane.setBounds(0, 0, 900, 500)
            layoutTree(frame.contentPane); layoutTree(frame.contentPane)
            ed.area.text = "select * from demo."
            ed.area.caretPosition = ed.area.text.length
        }
        waitFor(20000) {
            SwingUtilities.invokeAndWait { popup.refresh(manual = true) }
            popup.isVisible
        }
        var caretRect: java.awt.geom.Rectangle2D? = null
        SwingUtilities.invokeAndWait { caretRect = ed.area.modelToView2D(ed.area.caretPosition) }
        assertTrue(popup.items().any { it.text == "EMP" })
        val below = SwingUtilities.convertPoint(ed.area, 0, (caretRect!!.y + caretRect!!.height).toInt(), frame.layeredPane)
        assertTrue(popup.bounds().y >= below.y, "o popup fica por baixo da linha do cursor (y=${popup.bounds().y} >= ${below.y})")
        assertTrue(popup.bounds().y - below.y < 40, "...e logo a seguir ao texto (nao no meio do ecra)")
        SwingUtilities.invokeAndWait {
            val img = BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            frame.layeredPane.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(shots, "06b-popup.png"))
        }
        // escrever filtra; aceitar substitui a palavra escrita
        SwingUtilities.invokeAndWait {
            ed.area.document.insertString(ed.area.caretPosition, "e", null)
            popup.refresh(manual = false)
        }
        assertTrue(popup.isVisible && popup.items().all { it.text.uppercase().contains("E") })
        SwingUtilities.invokeAndWait { popup.accept() }
        assertEquals("select * from demo.EMP", ed.area.text)
        assertTrue(!popup.isVisible, "fecha ao aceitar")
        // colunas por alias
        SwingUtilities.invokeAndWait {
            ed.area.text = "select e. from emp e"
            ed.area.caretPosition = "select e.".length
        }
        waitFor(20000) {
            SwingUtilities.invokeAndWait { popup.refresh(manual = true) }
            popup.isVisible
        }
        assertTrue(popup.items().map { it.text }.containsAll(listOf("ID", "NOME", "SALARIO")), popup.items().toString())
        SwingUtilities.invokeAndWait { popup.hide(); frame.dispose() }
    }

    @Test fun `07 janela principal, abas com nome e dialogos`() {
        val cfg = ConnectionConfig(name = "XE local (DEMO)", user = "DEMO", url = url)
        cfg.setPassword("demo_test")
        ConfigStore.connections.clear(); ConfigStore.connections.add(cfg)
        var frame: MainFrame? = null
        SwingUtilities.invokeAndWait { frame = MainFrame() }
        val f = frame!!
        val h = f.panel.handleOf(cfg.id)!!
        h.password = "demo_test"
        SwingUtilities.invokeAndWait {
            f.panel.openObject(h, ObjectRef("DEMO", "MV_EMP", ObjectType.MATERIALIZED_VIEW))
            f.panel.openObject(h, ObjectRef("DEMO", "EMP", ObjectType.TABLE))
            f.panel.openConsole(h, "select * from emp where rownum < 5;")
            f.panel.openObject(h, ObjectRef("DEMO", "EMP", ObjectType.TABLE)) // repetido: reutiliza o separador
        }
        assertEquals(4, f.panel.tabCount(), "inicio + MV + EMP + consola (a repeticao reaproveita)")
        assertEquals(listOf("Início", "MV_EMP", "EMP", "Console XE local (DEMO)"), f.panel.tabTitles(), "todas as abas tem nome")
        SwingUtilities.invokeAndWait { f.panel.openConsole(h, "select 1 from dual;") }
        assertEquals("Console XE local (DEMO) (2)", f.panel.tabTitles().last(), "a segunda consola e numerada")
        Thread.sleep(1500)
        SwingUtilities.invokeAndWait {
            f.addNotify()
            val fw = f.width; val fh = f.height - px(40)
            f.contentPane.setBounds(0, 0, fw, fh)
            layoutTree(f.contentPane); layoutTree(f.contentPane)
            val img = BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            f.contentPane.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(shots, "07-janela.png"))
        }
        SwingUtilities.invokeAndWait {
            val dlg = ConnectionsDialog(null, cfg)
            dlg.addNotify()
            val dw = dlg.width; val dh = dlg.height - px(30)
            dlg.contentPane.setBounds(0, 0, dw, dh)
            layoutTree(dlg.contentPane); layoutTree(dlg.contentPane)
            val img = BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            dlg.contentPane.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(shots, "07-dialogo-ligacoes.png"))
            dlg.dispose()
            val dd = DriversDialog(f)
            dd.addNotify()
            val ww = dd.width; val wh = dd.height - px(30)
            dd.contentPane.setBounds(0, 0, ww, wh)
            layoutTree(dd.contentPane); layoutTree(dd.contentPane)
            val img2 = BufferedImage(ww, wh, BufferedImage.TYPE_INT_RGB)
            val g2 = img2.createGraphics()
            dd.contentPane.paint(g2)
            g2.dispose()
            ImageIO.write(img2, "png", File(shots, "07-dialogo-drivers.png"))
            dd.dispose()
        }
        SwingUtilities.invokeAndWait { f.panel.dispose(); f.dispose() }
    }

    @Test fun `08 o x no titulo da aba (IntelliJ) mostra-se e fecha a aba`() {
        val cfg = ConnectionConfig(name = "XE local (DEMO)", user = "DEMO", url = url).also { it.setPassword("demo_test") }
        ConfigStore.connections.clear(); ConfigStore.connections.add(cfg)
        var p: MainPanel? = null
        SwingUtilities.invokeAndWait { p = MainPanel(closeInTitle = true) }
        val m = p!!
        val h = m.handleOf(cfg.id)!!
        h.password = "demo_test"
        SwingUtilities.invokeAndWait {
            m.openObject(h, ObjectRef("DEMO", "EMP", ObjectType.TABLE))
            m.openConsole(h, "select 1 from dual;")
        }
        assertEquals(listOf("Início", "EMP", "Console XE local (DEMO)"), m.tabTitles())
        assertTrue(m.tabTitlesRaw().all { it.endsWith("×") }, m.tabTitlesRaw().toString())
        SwingUtilities.invokeAndWait { m.clickCloseOf(1) }
        assertEquals(listOf("Início", "Console XE local (DEMO)"), m.tabTitles(), "o clique no x fechou a aba EMP")
        SwingUtilities.invokeAndWait { m.dispose() }
    }

    @Test fun `09 buscar dentro do DDL - contagem, navegacao, regex e realce`() {
        val h = ConnectionHandle(ConnectionConfig(name = "x", user = "x", url = "jdbc:oracle:thin:@localhost:1:X"))
        lateinit var ed: SqlEditor
        SwingUtilities.invokeAndWait {
            ed = SqlEditor(h, { listOf("DEMO") }, { }, editable = false)
            ed.area.text = "CREATE TABLE emp (\n  id NUMBER,\n  nome VARCHAR2(10),\n  Emp_Id NUMBER\n);\n-- EMP e emp"
            assertTrue(ed.findBar.isVisible, "no DDL (so leitura) a barra esta sempre visivel")
        }
        SwingUtilities.invokeAndWait {
            assertEquals(4, ed.findBar.search("emp"), "ignora maiusculas por omissao: emp, Emp_Id, EMP, emp")
            assertEquals("1/4", ed.findBar.statusText())
            assertEquals("emp", ed.area.selectedText.lowercase().take(3), "a ocorrencia fica seleccionada")
            ed.findBar.step(1); ed.findBar.step(1)
            assertEquals("3/4", ed.findBar.statusText())
            ed.findBar.step(1); ed.findBar.step(1)
            assertEquals("1/4", ed.findBar.statusText(), "volta ao inicio")
            ed.findBar.step(-1)
            assertEquals("4/4", ed.findBar.statusText(), "e para tras")
            assertEquals(2, ed.findBar.search("emp", caseSensitive = true), "so 'emp' minusculo")
            assertEquals(3, ed.findBar.search("""\bnumber\b|varchar2""", useRegex = true), "regex")
            assertEquals(0, ed.findBar.search("nao existe"))
            assertEquals("regex inválida", run { ed.findBar.search("(", useRegex = true); ed.findBar.statusText() })
        }
        snapshot(ed.component, 700, 220, "09-buscar")
    }

    @Test fun `10 desacoplar a aba para uma janela independente e voltar a acopla-la`() {
        val cfg = ConnectionConfig(name = "XE local (DEMO)", user = "DEMO", url = url).also { it.setPassword("demo_test") }
        ConfigStore.connections.clear(); ConfigStore.connections.add(cfg)
        var p: MainPanel? = null
        SwingUtilities.invokeAndWait { p = MainPanel() }
        val m = p!!
        val h = m.handleOf(cfg.id)!!
        h.password = "demo_test"
        SwingUtilities.invokeAndWait {
            m.openObject(h, ObjectRef("DEMO", "EMP", ObjectType.TABLE))
            m.openConsole(h, "select 1 from dual;")
            m.openObject(h, ObjectRef("DEMO", "DEPT", ObjectType.TABLE))
        }
        assertEquals(listOf("Início", "EMP", "Console XE local (DEMO)", "DEPT"), m.tabTitles())
        // desacopla a consola (indice 2) sem mostrar a janela
        SwingUtilities.invokeAndWait { m.detachTab(2, show = false) }
        assertEquals(listOf("Início", "EMP", "DEPT"), m.tabTitles(), "a aba saiu do painel de abas")
        assertEquals(listOf("Console XE local (DEMO)"), m.detachedTitles(), "...e esta numa janela independente")
        // abrir o mesmo objecto de novo nao cria outra aba (traz a janela para a frente)
        SwingUtilities.invokeAndWait { m.openObject(h, ObjectRef("DEMO", "EMP", ObjectType.TABLE)) }
        assertEquals(3, m.tabCount())
        // fechar a janela volta a acoplar no sitio original
        SwingUtilities.invokeAndWait { m.redockAll() }
        assertEquals(0, m.detachedCount())
        assertEquals(listOf("Início", "EMP", "Console XE local (DEMO)", "DEPT"), m.tabTitles(), "voltou ao mesmo sitio")
        // desacoplar duas e fechar o painel nao deixa janelas penduradas
        SwingUtilities.invokeAndWait { m.detachTab(1, show = false); m.detachTab(1, show = false) }
        assertEquals(2, m.detachedCount())
        SwingUtilities.invokeAndWait { m.dispose() }
        assertEquals(0, m.detachedCount())
    }
}
