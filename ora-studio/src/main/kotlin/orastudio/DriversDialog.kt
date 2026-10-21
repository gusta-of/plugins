package orastudio

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.filechooser.FileNameExtensionFilter

/** Gestao de drivers JDBC: lista de JARs (ojdbc6, ojdbc8, ojdbc11, ...) por driver. */
class DriversDialog(parent: Component) : JDialog((parent as? java.awt.Window) ?: parent.parentWindow(), "Drivers JDBC", ModalityType.APPLICATION_MODAL) {

    private val drivers = ConfigStore.drivers.map { it.copy(jars = it.jars.toMutableList()) }.toMutableList()
    private val model = DefaultListModel<DriverConfig>()
    private val list = JList(model)
    private val name = JTextField()
    private val className = JTextField()
    private val jarModel = DefaultListModel<String>()
    private val jarList = JList(jarModel)
    private var current: DriverConfig? = null
    private var loading = false

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        drivers.forEach { model.addElement(it) }
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>, v: Any?, i: Int, s: Boolean, f: Boolean) =
                super.getListCellRendererComponent(l, (v as DriverConfig).name, i, s, f)
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }

        val form = FormPanel()
        form.add("Nome:", name)
        form.add("Classe:", className)
        form.addWide(JLabel("JARs do driver (ojdbc6, ojdbc8, ojdbc11, …):"))
        form.addWide(JScrollPane(jarList), weighty = 1.0)
        val jarButtons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(smallButton("Adicionar JAR…") { addJar() })
            add(smallButton("Remover") { removeJar() })
            add(smallButton("Detectar no ~/.m2", "Procura ojdbc*.jar em ~/.m2/repository/com/oracle") { detect() })
            add(smallButton("Descarregar ojdbc8", "Descarrega ${Drivers.DOWNLOAD_COORDS.substringAfterLast('/')} do Maven Central") { download() })
        }
        form.addWide(jarButtons)

        val listButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(smallButton("+") { add() })
            add(smallButton("−") { remove() })
        }
        val left = JPanel(BorderLayout()).apply {
            add(listButtons, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(px(200), px(100))
        }
        name.document.addDocumentListener(doc { if (!loading) { current?.name = name.text; list.repaint() } })
        className.document.addDocumentListener(doc { if (!loading) current?.className = className.text.trim() })

        val ok = JButton("OK").apply { addActionListener { save(); dispose() } }
        val cancel = JButton("Cancelar").apply { addActionListener { dispose() } }
        contentPane.add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, form).apply { dividerLocation = px(200) }, BorderLayout.CENTER)
        contentPane.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply { add(ok); add(cancel) }, BorderLayout.SOUTH)
        if (model.size() > 0) list.selectedIndex = 0
        setSize(px(780), px(480))
        setLocationRelativeTo(parent)
    }

    private fun select(d: DriverConfig?) {
        current = d
        loading = true
        name.text = d?.name.orEmpty()
        className.text = d?.className.orEmpty()
        jarModel.clear()
        d?.jars?.forEach { jarModel.addElement(it) }
        loading = false
    }

    private fun add() {
        val d = DriverConfig(name = "Novo driver")
        drivers += d; model.addElement(d); list.setSelectedValue(d, true)
    }

    private fun remove() {
        val d = list.selectedValue ?: return
        if (d.id == Drivers.DEFAULT_ID) { JOptionPane.showMessageDialog(this, "O driver Oracle por omissão não pode ser removido."); return }
        drivers.remove(d); model.removeElement(d)
        if (model.size() > 0) list.selectedIndex = 0
    }

    private fun addJar() {
        val d = current ?: return
        val fc = JFileChooser(File(System.getProperty("user.home"), ".m2/repository/com/oracle"))
        fc.fileFilter = FileNameExtensionFilter("Drivers JDBC (*.jar)", "jar")
        fc.isMultiSelectionEnabled = true
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        fc.selectedFiles.forEach { if (it.absolutePath !in d.jars) { d.jars += it.absolutePath; jarModel.addElement(it.absolutePath) } }
    }

    private fun removeJar() {
        val d = current ?: return
        val i = jarList.selectedIndex
        if (i < 0) return
        d.jars.removeAt(i); jarModel.remove(i)
    }

    private fun detect() {
        val d = current ?: return
        val found = Drivers.detectAllJars()
        if (found.isEmpty()) { JOptionPane.showMessageDialog(this, "Nenhum ojdbc*.jar encontrado em ~/.m2."); return }
        val pick = JOptionPane.showInputDialog(this, "Escolhe o JAR (um por driver):", "JARs encontrados", JOptionPane.QUESTION_MESSAGE, null,
            found.map { it.absolutePath }.toTypedArray(), found.first().absolutePath) as? String ?: return
        d.jars.clear(); d.jars.add(pick)
        jarModel.clear(); jarModel.addElement(pick)
    }

    private fun download() {
        val d = current ?: return
        if (JOptionPane.showConfirmDialog(this,
                "Descarregar ${Drivers.DOWNLOAD_COORDS.substringAfterLast('/')} (≈ 5 MB) do Maven Central\npara ${AppPaths.dir.resolve("drivers")}?",
                "Descarregar driver", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        Bg.run({ Drivers.downloadOjdbc8() }, { showError(this, "Download", it) }) { f ->
            d.jars.clear(); d.jars.add(f.absolutePath)
            jarModel.clear(); jarModel.addElement(f.absolutePath)
        }
    }

    private fun save() {
        val store = ConfigStore.drivers
        store.clear()
        store.addAll(drivers)
        ConfigStore.saveDrivers()
    }
}
