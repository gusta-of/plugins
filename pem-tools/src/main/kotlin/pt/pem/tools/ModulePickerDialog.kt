package pt.pem.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CheckBoxListListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

enum class Kind(val label: String) { BACKEND("Backend"), FRONTEND("Frontend") }

data class ModuleEntry(val name: String, val kind: Kind) {
    override fun toString() = "$name   [${kind.label}]"
}

/** Escolha de varios modulos (backend e/ou frontend), com filtro. A selecao mantem-se ao filtrar. */
class ModulePickerDialog(project: Project, title: String, okText: String, private val entries: List<ModuleEntry>) : DialogWrapper(project) {

    private val checked = linkedSetOf<ModuleEntry>()
    private var visible: List<ModuleEntry> = entries
    private val list = CheckBoxList<ModuleEntry>()
    private val filter = JBTextField().apply { emptyText.text = "Filtrar modulos" }
    private val counter = JBLabel(" ")

    init {
        this.title = title
        setOKButtonText(okText)
        list.setCheckBoxListListener(CheckBoxListListener { index, value ->
            val e = visible.getOrNull(index) ?: return@CheckBoxListListener
            if (value) checked += e else checked -= e
            updateCounter()
        })
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refill()
            override fun removeUpdate(e: DocumentEvent) = refill()
            override fun changedUpdate(e: DocumentEvent) = refill()
        })
        init()
        refill()
    }

    /** Modulos marcados, pela ordem original da lista. */
    fun selected(): List<ModuleEntry> = entries.filter { it in checked }

    private fun refill() {
        val q = filter.text.trim().lowercase()
        visible = entries.filter { q.isEmpty() || it.toString().lowercase().contains(q) }
        list.setItems(visible) { it.toString() }
        visible.forEach { list.setItemSelected(it, it in checked) }
        list.repaint()
        updateCounter()
    }

    private fun updateCounter() {
        counter.text = "${checked.size} selecionado(s)"
        isOKActionEnabled = checked.isNotEmpty()
    }

    private fun setVisible(value: Boolean) {
        visible.forEach { if (value) checked += it else checked -= it; list.setItemSelected(it, value) }
        list.repaint()
        updateCounter()
    }

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JButton("Todos").apply { addActionListener { setVisible(true) } })
            add(JButton("Nenhum").apply { addActionListener { setVisible(false) } })
            add(counter)
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(filter, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(400, 500)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = filter

    override fun doValidate() = if (checked.isEmpty()) ValidationInfo("Seleciona pelo menos um modulo") else null
}
