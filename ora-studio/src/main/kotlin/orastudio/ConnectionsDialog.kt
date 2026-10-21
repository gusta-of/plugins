package orastudio

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal fun doc(onChange: () -> Unit) = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = onChange()
    override fun removeUpdate(e: DocumentEvent) = onChange()
    override fun changedUpdate(e: DocumentEvent) = onChange()
}

/** "Data Sources": lista de ligacoes a esquerda e formulario a direita (Name, Driver, Host, Port, SID, User, Password, URL). */
class ConnectionsDialog(owner: Window?, private val initial: ConnectionConfig?) : JDialog(owner, "Ligações (Data Sources)", ModalityType.APPLICATION_MODAL) {

    /** Copias de trabalho (so gravadas em OK/Apply). */
    private val work = ConfigStore.connections.map { it.copy() }.toMutableList()
    private val originalIds = ConfigStore.connections.map { it.id }.toSet()
    private val listModel = DefaultListModel<ConnectionConfig>()
    private val list = JList(listModel)
    private var current: ConnectionConfig? = null
    private var loading = false
    var changed = false
        private set
    val removedIds = mutableSetOf<String>()
    val touchedIds = mutableSetOf<String>()

    private val name = JTextField()
    private val driver = JComboBox<DriverConfig>()
    private val type = JComboBox(ConnType.entries.toTypedArray())
    private val host = JTextField()
    private val port = JTextField(6)
    private val database = JTextField()
    private val dbLabel = JLabel("SID:")
    private val user = JTextField()
    private val password = JPasswordField()
    private val savePwd = JComboBox(arrayOf("Para sempre", "Nunca (perguntar)"))
    private val url = JTextField()
    private val autoCommit = JCheckBox("Auto-commit nas consolas")
    private val rowLimit = JSpinner(SpinnerNumberModel(500, 10, 100000, 100))
    private val timeout = JSpinner(SpinnerNumberModel(15, 1, 300, 1))
    private val initSql = JTextArea(4, 40)
    private val testResult = JLabel(" ")
    private var urlEdited = false
    private var passwordTouched = false
    private val form = JTabbedPane()
    private val right = JPanel(BorderLayout())

    var applied = false
        private set

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        buildUi()
        reloadDrivers()
        work.forEach { listModel.addElement(it) }
        val sel = initial?.let { i -> work.firstOrNull { it.id == i.id } } ?: work.firstOrNull()
        if (sel != null) list.setSelectedValue(sel, true) else showEmpty()
        setSize(px(900), px(640))
        setLocationRelativeTo(owner)
    }

    private fun showEmpty() {
        current = null
        right.isVisible = false
    }

    private fun buildUi() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>, v: Any?, i: Int, s: Boolean, f: Boolean): Component {
                val c = super.getListCellRendererComponent(l, (v as ConnectionConfig).name.ifBlank { "(sem nome)" }, i, s, f) as JLabel
                c.icon = Icons.connectionOff
                return c
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }

        val listButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(smallButton("+", "Nova ligação") { addNew() })
            add(smallButton("−", "Remover") { removeSelected() })
            add(smallButton("Duplicar", "Duplicar") { duplicateSelected() })
        }
        val left = JPanel(BorderLayout()).apply {
            add(listButtons, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(px(230), px(100))
        }

        driver.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>, v: Any?, i: Int, s: Boolean, f: Boolean): Component =
                super.getListCellRendererComponent(l, (v as? DriverConfig)?.let { d -> d.name + (d.jars.firstOrNull()?.let { j -> "  (${java.io.File(j).name})" } ?: "  (sem JAR!)") }, i, s, f)
        }
        val general = FormPanel()
        general.add("Nome:", name)
        general.add("Driver:", driver, extra = smallButton("Drivers…", "Gerir drivers (JARs ojdbc)") { editDrivers() })
        general.add("Tipo de ligação:", type, fill = false)
        general.add("Host:", host, extra = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(JLabel("Porta:")); add(port) })
        general.add(dbLabel, database)
        general.add("Autenticação:", JLabel("Utilizador e palavra-passe"))
        general.add("Utilizador:", user)
        general.add("Palavra-passe:", password, extra = savePwd)
        general.add("URL:", url)
        general.addWide(JLabel("O URL sobrepõe-se aos campos acima.").apply { foreground = Color.GRAY })
        general.addWide(JPanel(), weighty = 1.0)

        val options = FormPanel()
        options.add("", autoCommit, fill = false)
        options.add("Linhas por página:", rowLimit, fill = false)
        options.add("Timeout de ligação (s):", timeout, fill = false)
        options.addWide(JLabel("SQL a executar ao ligar (separado por ;):"))
        options.addWide(JScrollPane(initSql), weighty = 1.0)
        form.addTab("Geral", general)
        form.addTab("Opções", options)

        val test = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(smallButton("Testar ligação") { test() })
            add(testResult)
        }
        right.add(form, BorderLayout.CENTER)
        right.add(test, BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { dividerLocation = px(230) }
        val ok = JButton("OK").apply { addActionListener { applyChanges(); dispose() } }
        val cancel = JButton("Cancelar").apply { addActionListener { dispose() } }
        val applyBtn = JButton("Aplicar").apply { addActionListener { applyChanges() } }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply { add(ok); add(cancel); add(applyBtn) }
        contentPane.add(split, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        rootPane.defaultButton = ok

        // rotulo dinamico: SID / Service name
        type.addActionListener { updateDbLabel(); if (!loading) { syncUrl(); markEdited() } }
        val sync = doc { if (!loading) { syncUrl(); markEdited() } }
        host.document.addDocumentListener(sync); port.document.addDocumentListener(sync); database.document.addDocumentListener(sync)
        val mark = doc { if (!loading) markEdited() }
        name.document.addDocumentListener(doc { if (!loading) { current?.name = name.text; list.repaint(); markEdited() } })
        user.document.addDocumentListener(mark)
        password.document.addDocumentListener(doc { if (!loading) { passwordTouched = true; markEdited() } })
        url.document.addDocumentListener(doc { if (!loading) { urlEdited = url.text != generatedFromFields(); markEdited() } })
        driver.addActionListener { if (!loading) markEdited() }
        savePwd.addActionListener { if (!loading) markEdited() }
        autoCommit.addActionListener { if (!loading) markEdited() }
    }

    private fun updateDbLabel() {
        dbLabel.text = if (type.selectedItem == ConnType.SERVICE_NAME) "Service name:" else if (type.selectedItem == ConnType.SID) "SID:" else "Base de dados:"
        database.isEnabled = type.selectedItem != ConnType.URL
        host.isEnabled = type.selectedItem != ConnType.URL
        port.isEnabled = type.selectedItem != ConnType.URL
    }

    private fun markEdited() {
        changed = true
        current?.let { touchedIds += it.id }
    }

    private fun reloadDrivers() {
        loading = true
        driver.removeAllItems()
        ConfigStore.drivers.forEach { driver.addItem(it) }
        loading = false
    }

    private fun editDrivers() {
        DriversDialog(this).isVisible = true
        reloadDrivers()
        current?.let { c -> ConfigStore.drivers.firstOrNull { it.id == c.driverId }?.let { driver.selectedItem = it } }
    }

    private fun generatedFromFields(): String {
        val tmp = ConnectionConfig(type = (type.selectedItem as ConnType).name, host = host.text.trim(), port = port.text.trim().toIntOrNull() ?: 1521, database = database.text.trim())
        return tmp.generatedUrl()
    }

    private fun syncUrl() {
        if (urlEdited && type.selectedItem != ConnType.URL) return
        if (type.selectedItem == ConnType.URL) return
        loading = true
        url.text = generatedFromFields()
        loading = false
    }

    // ---- selecao ----

    private fun select(c: ConnectionConfig?) {
        saveCurrent()
        current = c
        if (c == null) { showEmpty(); return }
        right.isVisible = true
        loading = true
        name.text = c.name
        driver.selectedItem = ConfigStore.driver(c.driverId)
        type.selectedItem = c.connType
        host.text = c.host
        port.text = c.port.toString()
        database.text = c.database
        user.text = c.user
        password.text = ""
        passwordTouched = false
        password.putClientProperty("JTextField.placeholderText", if (c.passwordEnc != null) "<guardada>" else "")
        savePwd.selectedIndex = if (c.savePassword) 0 else 1
        urlEdited = !c.url.isNullOrBlank() && c.url != c.generatedUrl()
        url.text = c.effectiveUrl()
        autoCommit.isSelected = c.autoCommit
        rowLimit.value = c.rowLimit
        timeout.value = c.connectTimeoutSec
        initSql.text = c.initSql.orEmpty()
        testResult.text = " "
        updateDbLabel()
        loading = false
    }

    private fun readInto(c: ConnectionConfig) {
        c.name = name.text.trim()
        (driver.selectedItem as? DriverConfig)?.let { c.driverId = it.id }
        c.type = (type.selectedItem as ConnType).name
        c.host = host.text.trim()
        c.port = port.text.trim().toIntOrNull() ?: 1521
        c.database = database.text.trim()
        c.user = user.text.trim()
        c.savePassword = savePwd.selectedIndex == 0
        val gen = c.copy(url = null).generatedUrl()
        val typed = url.text.trim()
        c.url = if (c.connType == ConnType.URL) typed else if (typed.isNotEmpty() && typed != gen) typed else null
        c.autoCommit = autoCommit.isSelected
        c.rowLimit = (rowLimit.value as Number).toInt()
        c.connectTimeoutSec = (timeout.value as Number).toInt()
        c.initSql = initSql.text.trim().ifEmpty { null }
        if (passwordTouched) {
            val p = String(password.password)
            c.setPassword(p.ifEmpty { null })
            if (!c.savePassword) c.passwordEnc = null
        }
        if (!c.savePassword) c.passwordEnc = null
    }

    private fun saveCurrent() {
        current?.let { readInto(it) }
    }

    // ---- accoes ----

    private fun addNew() {
        saveCurrent()
        val c = ConnectionConfig(name = "Nova ligação", host = "localhost", database = "XE", user = "")
        work += c
        listModel.addElement(c)
        list.setSelectedValue(c, true)
        markEdited()
        name.requestFocusInWindow(); name.selectAll()
    }

    private fun removeSelected() {
        val c = list.selectedValue ?: return
        if (JOptionPane.showConfirmDialog(this, "Remover a ligação \"${c.name}\"?", "Remover", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        current = null
        work.remove(c)
        listModel.removeElement(c)
        removedIds += c.id
        changed = true
        if (listModel.size() > 0) list.selectedIndex = 0 else showEmpty()
    }

    private fun duplicateSelected() {
        saveCurrent()
        val c = list.selectedValue ?: return
        val copy = c.copyAs(c.name + " (cópia)")
        work += copy
        listModel.addElement(copy)
        list.setSelectedValue(copy, true)
        markEdited()
    }

    private fun test() {
        val c = current ?: return
        val probe = c.copy()
        readInto(probe)
        val pwd = if (passwordTouched) String(password.password) else c.password() ?: ""
        testResult.foreground = Color.GRAY
        testResult.text = "A testar…"
        Bg.run({
            val conn = Drivers.connect(probe, pwd)
            try {
                val md = conn.metaData
                "OK — ${md.databaseProductVersion.lineSequence().first()}  (driver ${md.driverVersion})"
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
        }, { e ->
            testResult.foreground = Color(0xEF5350)
            testResult.text = "<html>${e.shortMessage().lineSequence().first().replace("<", "&lt;")}</html>"
        }) { msg ->
            testResult.foreground = Color(0x4CAF50)
            testResult.text = msg
        }
    }

    private fun applyChanges() {
        saveCurrent()
        for (c in work) {
            if (c.name.isBlank()) c.name = c.effectiveUrl()
            c.normalized()
        }
        val list = ConfigStore.connections
        list.clear()
        list.addAll(work.map { it.copy() })
        ConfigStore.saveConnections()
        changed = true
        // marca como tocadas as que mudaram (para o chamador desligar as sessoes antigas)
        touchedIds += work.map { it.id }.filter { it in originalIds }
        applied = true
    }
}
