package pt.pem.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Equivalente ao zenity --checklist dos scripts de deploy. */
class DeployDialog(project: Project, title: String, private val modules: List<String>) : DialogWrapper(project) {

    private val list = CheckBoxList<String>().apply { setItems(modules) { it } }

    init {
        this.title = title
        setOKButtonText("Deploy")
        init()
    }

    fun selected(): List<String> = modules.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent {
        val all = JButton("Todos").apply { addActionListener { modules.forEach { list.setItemSelected(it, true) }; list.repaint() } }
        val none = JButton("Nenhum").apply { addActionListener { modules.forEach { list.setItemSelected(it, false) }; list.repaint() } }
        val top = JPanel().apply { add(all); add(none) }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(360, 440)
        }
    }

    override fun doValidate() = if (selected().isEmpty()) com.intellij.openapi.ui.ValidationInfo("Seleciona pelo menos um modulo") else null
}
