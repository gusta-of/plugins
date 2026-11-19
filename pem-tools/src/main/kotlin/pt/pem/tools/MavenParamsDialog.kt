package pt.pem.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * Ecra com os modulos que tem build e um campo de parametros Maven extra para cada um (e para todos).
 * O que se escreve vai no fim do `mvn ...` desse modulo; a linha «Todos os modulos» vale para qualquer build.
 */
class MavenParamsDialog(project: Project?, initial: Map<String, String>) : DialogWrapper(project) {
    private val args = LinkedHashMap(initial)
    private val modules = MavenArgs.modules()
    private val filter = JBTextField().apply { emptyText.text = "Filtrar modulos" }
    private val keys = ArrayList<String>()
    private val model = object : DefaultTableModel(arrayOf("Modulo", "Pasta", "Parametros extra"), 0) {
        override fun isCellEditable(row: Int, column: Int) = column == 2
    }
    private val table = JBTable(model)

    init {
        title = "Parametros Maven por modulo"
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(190)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(300)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(420)
        model.addTableModelListener { e ->
            if (e.type == javax.swing.event.TableModelEvent.UPDATE && e.column == 2) {
                val r = e.firstRow
                if (r in keys.indices) args[keys[r]] = model.getValueAt(r, 2) as? String ?: ""
            }
        }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refill()
            override fun removeUpdate(e: DocumentEvent) = refill()
            override fun changedUpdate(e: DocumentEvent) = refill()
        })
        init()
        refill()
    }

    /** Parametros a guardar (so os preenchidos). */
    fun result(): Map<String, String> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return args.mapValues { MavenArgs.clean(it.value) }.filterValues { it.isNotEmpty() }
    }

    private fun refill() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val q = filter.text.trim().lowercase()
        keys.clear()
        model.rowCount = 0
        keys += MavenArgs.ALL
        model.addRow(arrayOf("Todos os modulos", "", args[MavenArgs.ALL] ?: ""))
        for (m in modules) {
            if (q.isNotEmpty() && !m.name.lowercase().contains(q)) continue
            keys += m.name
            model.addRow(arrayOf(m.name, (if (m.kind == Kind.BACKEND) "backend · " else "frontend · ") + m.dir, args[m.name] ?: ""))
        }
    }

    private fun addToAll(a: String) {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val cur = (args[MavenArgs.ALL] ?: "").trim()
        if (a !in cur.split(Regex("\\s+"))) args[MavenArgs.ALL] = (if (cur.isEmpty()) "" else "$cur ") + a
        refill()
    }

    override fun createCenterPanel(): JComponent {
        val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JBLabel("Sugestoes (para «Todos»):"))
            for (a in listOf("-Dmaven.gitcommitid.skip=true", "-o", "-U")) add(JButton("+ $a").apply { addActionListener { addToAll(a) } })
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel("<html>O que escreveres e acrescentado ao fim do <code>mvn ...</code> do build desse modulo. A linha <b>Todos os modulos</b> vale para qualquer build; o campo do modulo junta-se a ela.<br>" +
                "Ex.: <code>-Dmaven.gitcommitid.skip=true</code> ignora o git-commit-id-plugin so no build local.</html>"), BorderLayout.NORTH)
            add(filter, BorderLayout.CENTER)
            add(chips, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(980), JBUI.scale(560))
        }
    }
}
