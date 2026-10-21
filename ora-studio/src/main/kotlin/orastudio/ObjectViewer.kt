package orastudio

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.SwingConstants

/** Uma aba que so vai a BD quando e mostrada pela primeira vez (as consultas lentas nao bloqueiam as outras abas). */
private class LazyTab(val title: String, val panel: JPanel, val start: () -> Unit) {
    var started = false
}

/** Estrutura de um objecto: colunas, constraints, indices, triggers, DDL/codigo (com links), dados e dependencias. */
class ObjectViewerPanel(private val app: AppContext, val handle: ConnectionHandle, val ref: ObjectRef) : JPanel(BorderLayout()) {

    private val tabs = JTabbedPane()
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
    private val lazy = ArrayList<LazyTab>()
    private val pending = AtomicInteger(0)
    private var dataSession: DbSession? = null
    private var dataPanel: ResultsPanel? = null
    private val editors = mutableListOf<SqlEditor>()
    @Volatile private var ddlText: String = ""
    private var info: ObjectInfo? = null

    /** true quando nao ha nenhuma aba a carregar (usado nos testes). */
    fun isIdle(): Boolean = pending.get() == 0
    val isLoaded: Boolean get() = tabs.parent != null
    fun tabTitles(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
    fun selectTab(title: String) {
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title }?.let { tabs.selectedIndex = it; startTab(it) }
    }

    init {
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        tabs.addChangeListener { startTab(tabs.selectedIndex) }
        rebuild()
    }

    private fun owners() = listOfNotNull(ref.owner, handle.currentSchema).distinct()

    fun reload() {
        disposeData()
        rebuild()
    }

    private fun rebuild() {
        tabs.removeAll()
        lazy.clear()
        editors.clear()
        ddlText = ""
        buildHeader()
        skeleton()
        // separador inicial: DDL para views e MVs (e a estrutura que o utilizador quer ver); senao o primeiro
        val first = if (ref.type == ObjectType.VIEW || ref.type == ObjectType.MATERIALIZED_VIEW) tabTitles().indexOf("DDL").takeIf { it >= 0 } ?: 0 else 0
        tabs.selectedIndex = first
        startTab(first)
        Bg.run({ handle.metadata.info(ref) }, { }) { i ->
            info = i
            buildHeader()
        }
    }

    // ---- esqueleto das abas ----

    private fun skeleton() {
        val md = handle.metadata
        val t = ref.type
        val open: (ObjectRef) -> Unit = { app.openObject(handle, it) }

        fun ddlTab(title: String, body: Boolean = false) =
            lazy(title, { md.ddl(ref, body) }) { text ->
                if (!body) ddlText = text
                if (text.isBlank()) emptyLabel("Sem corpo / código-fonte") else editorFor(text)
            }

        when {
            t.relation -> {
                lazy("Colunas", { md.columns(ref) }) { cols ->
                    if (cols.isEmpty()) emptyLabel("Sem colunas visíveis para este utilizador") else
                        SimpleTable(listOf("#", "Coluna", "Tipo", "Nulo?", "Default", "Comentário"),
                            cols.map { listOf(it.id, it.name, it.type, if (it.nullable) "SIM" else "NÃO", it.default ?: "", it.comment ?: "") }, open)
                }
                if (t != ObjectType.TABLE) ddlTab("DDL")
                if (t == ObjectType.TABLE) {
                    lazy("Constraints", { md.constraints(ref) }) { list ->
                        if (list.isEmpty()) emptyLabel("Sem constraints") else
                            SimpleTable(listOf("Nome", "Tipo", "Colunas", "Referencia", "Colunas ref.", "On delete", "Condição", "Estado"),
                                list.map { c -> listOf(c.name, c.type, c.columns, c.refTable?.let { LinkCell(it) } ?: "", c.refColumns ?: "", c.deleteRule ?: "", c.condition ?: "", c.status) }, open)
                    }
                }
                if (t == ObjectType.TABLE || t == ObjectType.MATERIALIZED_VIEW) {
                    lazy("Índices", { md.indexes(ref) }) { list ->
                        if (list.isEmpty()) emptyLabel("Sem índices") else
                            SimpleTable(listOf("Nome", "Tipo", "Único", "Colunas", "Estado"),
                                list.map { listOf(it.name, it.type, if (it.unique) "SIM" else "NÃO", it.columns, it.status) }, open)
                    }
                }
                if (t == ObjectType.TABLE || t == ObjectType.VIEW) {
                    lazy("Triggers", { md.triggersOn(ref) }) { list ->
                        if (list.isEmpty()) emptyLabel("Sem triggers") else
                            SimpleTable(listOf("Trigger", "Evento", "Tipo", "Estado"),
                                list.map { listOf(LinkCell(it.ref, it.ref.qualified), it.event, it.type, it.status) }, open)
                    }
                }
                if (t == ObjectType.TABLE) ddlTab("DDL")
                custom("Dados") { dataTab() }
            }
            t == ObjectType.PACKAGE || t == ObjectType.TYPE -> {
                ddlTab("Especificação")
                lazy("Corpo", { if (md.hasBody(ref)) md.ddl(ref, body = true) else "" }) { text ->
                    if (text.isBlank()) emptyLabel("Sem corpo") else editorFor(text)
                }
            }
            t == ObjectType.SYNONYM -> {
                ddlTab("DDL")
                lazy("Destino", { md.synonymTarget(ref)?.let { (o, n) -> Triple(o, n, md.findExact(o, n)) } }) { r ->
                    targetPanel(r)
                }
            }
            else -> ddlTab(if (t.code) "Código" else "DDL")
        }
        lazy("Dependências", { md.uses(ref) to md.usedBy(ref) }) { (uses, usedBy) ->
            fun table(list: List<DependencyInfo>, title: String): JPanel = JPanel(BorderLayout()).apply {
                add(JLabel("  $title (${list.size})").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                add(if (list.isEmpty()) emptyLabel("—") else SimpleTable(listOf("Objecto", "Tipo"), list.map { listOf(LinkCell(it.ref), it.ref.type.label) }, open), BorderLayout.CENTER)
            }
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, table(uses, "Usa"), table(usedBy, "Usado por")).apply { resizeWeight = 0.5 }
        }
    }

    /** Aba cujo conteudo vem da BD ([fetch], fora da EDT) e e desenhado por [render] (na EDT). */
    private fun <T> lazy(title: String, fetch: () -> T, render: (T) -> JComponent) {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("A carregar…", SwingConstants.CENTER), BorderLayout.CENTER)
        val tab = LazyTab(title, panel) {
            pending.incrementAndGet()
            Bg.run({ fetch() }, { err ->
                panel.removeAll()
                panel.add(errorLabel(err), BorderLayout.CENTER)
                panel.revalidate(); panel.repaint()
                pending.decrementAndGet()
            }) { value ->
                panel.removeAll()
                panel.add(render(value), BorderLayout.CENTER)
                panel.revalidate(); panel.repaint()
                pending.decrementAndGet()
            }
        }
        lazy += tab
        tabs.addTab(title, panel)
    }

    /** Aba cujo conteudo e criado ao abrir (sem fetch previo). */
    private fun custom(title: String, build: () -> JComponent) {
        val panel = JPanel(BorderLayout())
        val tab = LazyTab(title, panel) { panel.add(build(), BorderLayout.CENTER); panel.revalidate(); panel.repaint() }
        lazy += tab
        tabs.addTab(title, panel)
    }

    private fun startTab(i: Int) {
        val t = lazy.getOrNull(i) ?: return
        if (t.started) return
        t.started = true
        try {
            t.start()
        } catch (e: Throwable) {
            AppLog.error("Falha a iniciar a aba ${t.title} de ${ref.qualified}", e)
            t.panel.removeAll(); t.panel.add(errorLabel(e), BorderLayout.CENTER)
        }
    }

    // ---- cabecalho ----

    private fun buildHeader() {
        header.removeAll()
        header.add(JLabel(Icons.of(ref.type)))
        header.add(JLabel(ref.qualified).apply { font = font.deriveFont(Font.BOLD, font.size + 2f) })
        header.add(JLabel("(${ref.type.label})"))
        info?.let { i ->
            i.status?.let { s -> header.add(JLabel(s).apply { foreground = if (s == "VALID") Color(0x4CAF50) else Color(0xEF5350) }) }
            if (i.created != null) header.add(JLabel("criado ${i.created}"))
            if (i.lastDdl != null) header.add(JLabel("alterado ${i.lastDdl}"))
        }
        header.add(smallButton("Atualizar", "Recarrega a estrutura") { reload() })
        header.add(smallButton("Copiar DDL", "Copia o DDL/código para a área de transferência") { copyDdl() })
        if (ref.type.relation) header.add(smallButton("Consola…", "Abre uma consola com SELECT * deste objecto") {
            app.openConsole(handle, "SELECT *\nFROM ${ref.quoted()}\nWHERE ROWNUM <= 100;\n")
        })
        header.revalidate(); header.repaint()
    }

    private fun copyDdl() {
        fun copy(s: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null)
        if (ddlText.isNotEmpty()) { copy(ddlText); return }
        Bg.run({ handle.metadata.ddl(ref) }, { showError(this, "Copiar DDL", it) }) { ddlText = it; copy(it) }
    }

    // ---- pecas ----

    private fun editorFor(text: String): JComponent {
        val ed = SqlEditor(handle, ::owners, { app.openObject(handle, it) }, editable = false)
        ed.text = text
        editors += ed
        return ed.component
    }

    private fun emptyLabel(text: String) = JLabel(text, SwingConstants.CENTER).apply { foreground = Color.GRAY }

    private fun errorLabel(t: Throwable?) = JLabel(
        "<html><div style='padding:12px'>Não foi possível carregar:<br><pre>${(t?.shortMessage() ?: "").replace("&", "&amp;").replace("<", "&lt;")}</pre></div>",
        SwingConstants.LEFT,
    ).apply { verticalAlignment = SwingConstants.TOP }

    private fun targetPanel(r: Triple<String, String, List<ObjectRef>>?): JComponent {
        val p = JPanel(FlowLayout(FlowLayout.LEFT))
        if (r == null) { p.add(JLabel("Destino do synonym não encontrado")); return p }
        val (owner, name, refs) = r
        p.add(JLabel("Aponta para:"))
        if (refs.isEmpty()) p.add(JLabel("$owner.$name (não encontrado ou sem acesso)"))
        refs.forEach { x -> p.add(JButton("${x.name} — ${x.type.label}").apply { addActionListener { app.openObject(handle, x) } }) }
        return p
    }

    // ---- dados (tabelas/views/MVs) ----

    private fun dataTab(): JPanel {
        val panel = JPanel(BorderLayout())
        val where = JTextField().apply { toolTipText = "Condição opcional (sem a palavra WHERE), ex.: ID > 10 ORDER BY NOME" }
        val limit = JComboBox(arrayOf(100, 500, 1000, 5000)).apply { selectedItem = 500 }
        val holder = JPanel(BorderLayout())
        val msg = JLabel("A carregar…", SwingConstants.CENTER)
        holder.add(msg, BorderLayout.CENTER)

        fun fetch() {
            val sql = "SELECT * FROM ${ref.quoted()}" + where.text.trim().takeIf { it.isNotEmpty() }?.let { c ->
                if (Regex("^(order|group|fetch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(c)) " $c" else " WHERE $c"
            }.orEmpty()
            msg.text = "A carregar…"
            holder.removeAll(); holder.add(msg, BorderLayout.CENTER); holder.revalidate(); holder.repaint()
            disposeData()
            val rowLimit = limit.selectedItem as Int
            pending.incrementAndGet()
            Bg.run({
                val s = dataSession?.takeIf { it.isValid() } ?: handle.openSession("data").also { dataSession = it }
                s to s.sync { SqlRunner.runOne(s, SqlSplitter.split(sql).first(), rowLimit) }
            }, { err ->
                msg.text = "<html>Erro: ${err.shortMessage().lineSequence().first().replace("<", "&lt;")}</html>"
                pending.decrementAndGet()
            }) { (s, outcome) ->
                holder.removeAll()
                when (outcome) {
                    is QueryOutcome -> ResultsPanel(s, outcome.cursor, outcome.rows, outcome.elapsedMs, rowLimit).also { dataPanel = it; holder.add(it, BorderLayout.CENTER) }
                    is ErrorOutcome -> holder.add(errorLabel(RuntimeException(outcome.error.message)), BorderLayout.CENTER)
                    else -> holder.add(JLabel(outcome.toString()), BorderLayout.CENTER)
                }
                holder.revalidate(); holder.repaint()
                pending.decrementAndGet()
            }
        }

        val bar = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            add(JLabel("WHERE / ORDER BY:"), BorderLayout.WEST)
            add(where, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Linhas:")); add(limit)
                add(JButton("Carregar").apply { addActionListener { fetch() } })
            }, BorderLayout.EAST)
        }
        where.addActionListener { fetch() }
        panel.add(bar, BorderLayout.NORTH)
        panel.add(holder, BorderLayout.CENTER)
        fetch()
        return panel
    }

    private fun disposeData() {
        dataPanel?.dispose()
        dataPanel = null
    }

    fun dispose() {
        disposeData()
        dataSession?.close()
        dataSession = null
    }
}
