package orastudio

import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

sealed class NavNode : DefaultMutableTreeNode() {
    var loaded = false
}

class RootNode : NavNode() {
    override fun toString() = "Ligações"
}

class MessageNode(val text: String, val error: Boolean = false, val detail: String? = null) : NavNode() {
    init { allowsChildren = false }
    override fun toString() = text
}

class ConnNode(val handle: ConnectionHandle) : NavNode() {
    override fun toString() = handle.name
}

/** «Jobs agendados»: DBMS_SCHEDULER + DBMS_JOB da ligacao; [filter] filtra os filhos por nome (sem nova ida a base). */
class JobsNode(val handle: ConnectionHandle) : NavNode() {
    var all: List<JobInfo> = emptyList()
    var filter: String = ""
    override fun toString() = "Jobs agendados" + if (filter.isNotBlank()) "  [filtro: ${filter.trim()}]" else ""
}

class JobNode(val handle: ConnectionHandle, val job: JobInfo) : NavNode() {
    init { allowsChildren = false }
    override fun toString() = "${job.owner}.${job.name}"
}

class SchemaNode(val handle: ConnectionHandle, val name: String, val current: Boolean) : NavNode() {
    override fun toString() = name
}

class CategoryNode(val handle: ConnectionHandle, val owner: String, val type: ObjectType) : NavNode() {
    override fun toString() = type.plural
}

class ObjectNode(val handle: ConnectionHandle, val ref: ObjectRef, val valid: Boolean) : NavNode() {
    init { allowsChildren = false }
    override fun toString() = ref.name
}

/** Menu de accoes sobre ligacoes, implementado pela janela principal. */
interface NavActions {
    fun connect(node: ConnNode)
    fun disconnect(node: ConnNode)
    fun edit(node: ConnNode)
    fun duplicate(node: ConnNode)
    fun remove(node: ConnNode)
    fun passwordFor(handle: ConnectionHandle): Boolean
}

/** Arvore: Ligacao > Schema > Categoria > Objecto, com carregamento preguicoso em background. */
class Navigator(private val app: AppContext, private val actions: NavActions) : JTree() {

    private val root = RootNode()
    private val model = DefaultTreeModel(root, true) // nos sem filhos (ainda por carregar) continuam expansiveis

    init {
        setModel(model)
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(e: TreeExpansionEvent) {
                (e.path.lastPathComponent as? NavNode)?.let { ensureLoaded(it) }
            }

            override fun treeWillCollapse(e: TreeExpansionEvent) {}
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                    val node = getPathForLocation(e.x, e.y)?.lastPathComponent
                    if (node is ObjectNode) app.openObject(node.handle, node.ref)
                    if (node is JobNode) app.openJob(node.handle, node.job)
                    if (node is MessageNode && node.detail != null) ErrorDialog.show(this@Navigator, "Erro", node.detail)
                }
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) (selectionPath?.lastPathComponent as? ObjectNode)?.let { app.openObject(it.handle, it.ref) }
                if (e.keyCode == KeyEvent.VK_ENTER) (selectionPath?.lastPathComponent as? JobNode)?.let { app.openJob(it.handle, it.job) }
                if (e.keyCode == KeyEvent.VK_F5) (selectionPath?.lastPathComponent as? NavNode)?.let { refresh(it) }
            }
        })
        toolTipText = ""
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val n = getPathForLocation(e.x, e.y)?.lastPathComponent
        return when (n) {
            is ConnNode -> n.handle.config.effectiveUrl()
            is ObjectNode -> n.ref.toString() + if (n.valid) "" else "  — INVÁLIDO"
            is JobNode -> n.job.let { "${it.owner}.${it.name} [${if (it.kind == "scheduler") "DBMS_SCHEDULER" else "DBMS_JOB"}] ${it.state} — agenda: ${it.schedule} — próxima: ${it.nextRun.ifBlank { "—" }}" }
            is MessageNode -> if (n.detail != null) "Duplo clique: ver o erro completo" else null
            else -> null
        }
    }

    // ---- ligacoes ----

    fun setConnections(list: List<ConnectionHandle>) {
        root.removeAllChildren()
        list.forEach { root.add(ConnNode(it)) }
        model.reload()
    }

    fun nodeFor(handle: ConnectionHandle): ConnNode? = (0 until root.childCount).map { root.getChildAt(it) as ConnNode }.firstOrNull { it.handle === handle }

    fun selectedConn(): ConnNode? {
        var n = selectionPath?.lastPathComponent as? NavNode ?: return null
        while (n !is ConnNode) n = (n.parent as? NavNode) ?: return null
        return n
    }

    fun reloadNode(n: NavNode) {
        model.nodeChanged(n)
    }

    fun collapseConn(n: ConnNode) {
        collapsePath(TreePath(n.path))
        n.removeAllChildren()
        n.loaded = false
        model.nodeStructureChanged(n)
    }

    fun refresh(n: NavNode) {
        if (n is ObjectNode || n is MessageNode) return
        n.removeAllChildren()
        n.loaded = false
        model.nodeStructureChanged(n)
        if (isExpanded(TreePath(n.path)) || n is ConnNode) { expandPath(TreePath(n.path)) }
    }

    // ---- carregamento ----

    private fun ensureLoaded(n: NavNode) {
        if (n.loaded) return
        when (n) {
            is ConnNode -> {
                if (!actions.passwordFor(n.handle)) { collapsePath(TreePath(n.path)); return }
                loadAsync(n, { loadSchemas(n.handle) })
            }
            is JobsNode -> loadAsync(n, { n.all = n.handle.metadata.jobs(); jobChildren(n) })
            is SchemaNode -> {
                n.loaded = true
                ObjectType.browsable.forEach { n.add(CategoryNode(n.handle, n.name, it)) }
                model.nodeStructureChanged(n)
            }
            is CategoryNode -> loadAsync(n, { loadObjects(n) })
            else -> n.loaded = true
        }
    }

    private fun loadAsync(n: NavNode, work: () -> List<NavNode>) {
        n.loaded = true
        n.removeAllChildren()
        n.add(MessageNode("A carregar…"))
        model.nodeStructureChanged(n)
        Bg.run(work, { err ->
            n.removeAllChildren()
            n.add(MessageNode(err.shortMessage().lineSequence().first() + if (n is ConnNode) "  (duplo clique: erro completo)" else "", error = true, detail = fullErrorText(err)))
            n.loaded = false // permite tentar de novo ao expandir
            model.nodeStructureChanged(n)
            if (n is ConnNode) {
                app.status("Falha a ligar: ${err.shortMessage().lineSequence().first()}")
                collapsePath(TreePath(n.path))
                ErrorDialog.show(this, "Falha a ligar a ${n.handle.name}", fullErrorText(err))
            }
        }) { children ->
            n.removeAllChildren()
            if (children.isEmpty()) n.add(MessageNode("(vazio)")) else children.forEach { n.add(it) }
            model.nodeStructureChanged(n)
            if (n is ConnNode) app.status("Ligado a ${n.handle.name}")
        }
    }

    private fun loadSchemas(h: ConnectionHandle): List<NavNode> {
        h.metaSession()
        val schemas = h.metadata.listSchemas()
        val cur = h.currentSchema
        return listOf<NavNode>(JobsNode(h)) + schemas.sortedWith(compareBy({ it != cur }, { it })).map { SchemaNode(h, it, it == cur) }
    }

    private fun jobChildren(n: JobsNode): List<NavNode> {
        val ok = JobFilter.matcher(n.filter)
        val shown = n.all.filter(ok)
        return when {
            n.all.isEmpty() -> listOf(MessageNode("(sem jobs visíveis para este utilizador)"))
            shown.isEmpty() -> listOf(MessageNode("(nenhum job com «${n.filter.trim()}»)"))
            else -> shown.map { JobNode(n.handle, it) }
        }
    }

    /** Aplica (ou limpa) o filtro por nome sem voltar a pedir os jobs a base. */
    fun setJobFilter(n: JobsNode, text: String) {
        n.filter = text
        n.removeAllChildren()
        jobChildren(n).forEach { n.add(it) }
        n.loaded = true
        model.nodeStructureChanged(n)
        expandPath(TreePath(n.path))
    }

    private fun loadObjects(n: CategoryNode): List<NavNode> =
        n.handle.metadata.listObjects(n.owner, n.type).map { ObjectNode(n.handle, ObjectRef(n.owner, it.name, n.type), it.valid) }

    // ---- menu de contexto ----

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = getPathForLocation(e.x, e.y) ?: return
        selectionPath = path
        val node = path.lastPathComponent as? NavNode ?: return
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit) = menu.add(JMenuItem(text).apply { addActionListener { action() } })
        when (node) {
            is ConnNode -> {
                if (node.handle.connected) item("Desligar") { actions.disconnect(node) } else item("Ligar") { expandPath(path) }
                item("Nova consola SQL") { app.openConsole(node.handle) }
                item("Atualizar") { refresh(node) }
                menu.addSeparator()
                item("Editar…") { actions.edit(node) }
                item("Duplicar") { actions.duplicate(node) }
                item("Remover") { actions.remove(node) }
            }
            is SchemaNode -> {
                item("Nova consola SQL") { app.openConsole(node.handle, "ALTER SESSION SET CURRENT_SCHEMA = ${ObjectRef.quote(node.name)};\n\n") }
                item("Atualizar") { refresh(node) }
            }
            is JobsNode -> {
                item("Filtrar por nome…") {
                    val v = javax.swing.JOptionPane.showInputDialog(this, "Nome do job (texto, * curinga ou /regex/):", node.filter)
                    if (v != null) setJobFilter(node, v)
                }
                if (node.filter.isNotBlank()) item("Limpar filtro") { setJobFilter(node, "") }
                item("Atualizar") { refresh(node) }
            }
            is JobNode -> {
                item("Abrir") { app.openJob(node.handle, node.job) }
                item("Copiar nome") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("${node.job.owner}.${node.job.name}"), null) }
            }
            is CategoryNode -> item("Atualizar") { refresh(node) }
            is ObjectNode -> {
                item("Abrir") { app.openObject(node.handle, node.ref) }
                if (node.ref.type.relation) item("SELECT * numa consola") { app.openConsole(node.handle, "SELECT *\nFROM ${node.ref.quoted()}\nWHERE ROWNUM <= 100;\n") }
                item("Copiar nome") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(node.ref.qualified), null) }
            }
            else -> return
        }
        menu.show(this, e.x, e.y)
    }

    private inner class Renderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(t: JTree, value: Any?, sel: Boolean, exp: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
            val c = super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus) as DefaultTreeCellRenderer
            c.font = t.font
            when (value) {
                is ConnNode -> {
                    c.text = value.handle.name
                    c.icon = if (value.handle.connected) Icons.connectionOn else Icons.connectionOff
                }
                is SchemaNode -> {
                    c.text = value.name
                    c.icon = if (value.current) Icons.schemaCurrent else Icons.schema
                    if (value.current) c.font = t.font.deriveFont(Font.BOLD)
                }
                is JobsNode -> {
                    c.text = value.toString()
                    c.icon = Icons.jobs
                }
                is JobNode -> {
                    val j = value.job
                    val bad = j.failures > 0 || j.state.contains("FAIL", true) || j.state.contains("BROKEN", true)
                    c.text = "${j.owner}.${j.name}  —  ${if (j.enabled) "ativo" else "desligado"}"
                    c.icon = if (bad) Icons.jobBad else if (j.enabled) Icons.jobOn else Icons.jobOff
                    if (bad && !sel) c.foreground = Color(0xEF5350)
                }
                is CategoryNode -> {
                    c.text = value.type.plural
                    c.icon = Icons.of(value.type)
                }
                is ObjectNode -> {
                    c.text = value.ref.name
                    c.icon = Icons.of(value.ref.type)
                    if (!value.valid && !sel) c.foreground = Color(0xEF5350)
                }
                is MessageNode -> {
                    c.text = value.text
                    c.icon = null
                    if (!sel) c.foreground = if (value.error) Color(0xEF5350) else Color.GRAY
                    c.font = t.font.deriveFont(Font.ITALIC)
                }
            }
            return c
        }
    }
}
