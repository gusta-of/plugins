package orastudio

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Icon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Aba desacoplada para uma janela independente (guarda de onde veio, para a poder voltar a acoplar). */
private class Detached(val frame: JFrame, val title: String, val icon: Icon?, val tip: String?, val index: Int)

/**
 * Toda a aplicacao num painel: navegador + abas (objectos e consolas). E usado pela janela do
 * programa independente ([MainFrame]) e pela janela lateral do plugin do IntelliJ.
 */
class MainPanel(closeInTitle: Boolean? = null) : JPanel(BorderLayout()), AppContext, NavActions {

    /**
     * No FlatLaf (programa independente) as abas tem o "x" nativo. Noutros temas (IntelliJ) o "x" nativo e a
     * componente personalizada nao existem/nao sao desenhados, por isso o "x" vai no proprio titulo.
     */
    private val textClose: Boolean = closeInTitle ?: !UIManager.getLookAndFeel().javaClass.name.contains("formdev")
    private val closeMark = "   ×"
    private fun shown(name: String) = if (textClose) name + closeMark else name
    private fun plain(title: String) = title.removeSuffix(closeMark)

    private val handles = LinkedHashMap<String, ConnectionHandle>()
    private val tabs = JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT)
    private val statusLabel = JLabel(" ")
    private val navigator = Navigator(this, this)
    private val tabKeys = HashMap<Component, String>()
    private val detached = LinkedHashMap<Component, Detached>()
    private var consoleCount = 0
    val split: JSplitPane

    /** Janela dona (para dialogos). */
    private fun owner(): Window? = SwingUtilities.getWindowAncestor(this)

    init {
        rebuildHandles()

        val navTools = WrapPanel(4, 4).apply {
            add(smallButton("+ Ligação", "Nova ligação (Data Source)") { manageConnections(null) })
            add(smallButton("› Consola", "Nova consola SQL na ligação selecionada") { newConsoleForSelection() })
            add(smallButton("⌕ Objecto", "Ir para objecto (Ctrl+N)") { goToObject() })
            add(smallButton("⇱ Desacoplar", "Abre a aba atual numa janela independente") { detachTab(tabs.selectedIndex) })
            add(smallButton("⋮", "Mais…") { menuButtonPopup(this) })
        }
        val left = JPanel(BorderLayout()).apply {
            add(navTools, BorderLayout.NORTH)
            add(JScrollPane(navigator), BorderLayout.CENTER)
            minimumSize = Dimension(px(180), px(100))
        }
        tabs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = tabs.indexAtLocation(e.x, e.y)
                if (i < 0) return
                if (e.button == MouseEvent.BUTTON1 && closeIfOnMark(i, e.x)) return
                if (e.button == MouseEvent.BUTTON2) closeTab(tabs.getComponentAt(i))
                else if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2 && tabs.getComponentAt(i) is SqlConsolePanel) renameTab(i)
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val i = tabs.indexAtLocation(e.x, e.y)
                if (i >= 0) tabMenu(i, e.x, e.y)
            }
        })
        if (textClose) tabs.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val i = tabs.indexAtLocation(e.x, e.y)
                tabs.cursor = if (i >= 0 && onCloseMark(i, e.x)) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) else java.awt.Cursor.getDefaultCursor()
            }
        })
        // botao "x" nas abas (FlatLaf, programa independente); no IntelliJ ficam o botao do meio, o menu e Ctrl+W
        tabs.putClientProperty("JTabbedPane.tabClosable", true)
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Fechar")
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", java.util.function.IntConsumer { i -> tabs.getComponentAt(i)?.let { closeTab(it) } })
        val welcome = JLabel(
            "<html><div style='text-align:center;color:gray'><h2>Ora Studio</h2>" +
                "Duplo clique num objecto abre a sua estrutura.<br>" +
                "Em qualquer código: <b>Ctrl+rato</b> sobre um nome (fica azul) e clique para navegar.<br><br>" +
                "<b>Clique direito num separador</b>: renomear / desacoplar / fechar &nbsp;•&nbsp; <b>Ctrl+Enter</b> executar &nbsp;•&nbsp; <b>Ctrl+Shift+Enter</b> executar script &nbsp;•&nbsp; botão <b>⌕ Objecto</b> ir para objecto</div></html>",
            SwingConstants.CENTER,
        )
        tabs.addTab(shown("Início"), welcome)
        split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs).apply {
            dividerLocation = ConfigStore.prefs.dividerLocation.takeIf { it > 0 } ?: px(300)
            isContinuousLayout = true
        }
        add(split, BorderLayout.CENTER)
        add(statusLabel.apply { border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8) }, BorderLayout.SOUTH)
        navigator.setConnections(handles.values.toList())
        SwingUtilities.invokeLater { checkDrivers() }
    }

    // ---- menu ----

    private data class MenuEntry(val text: String, val key: KeyStroke? = null, val action: () -> Unit)

    private fun entries(): Map<String, List<MenuEntry?>> {
        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        return linkedMapOf(
            "Ficheiro" to listOf(
                MenuEntry("Ligações (Data Sources)…") { manageConnections(navigator.selectedConn()?.handle?.config) },
                MenuEntry("Drivers JDBC…") { DriversDialog(this).isVisible = true },
            ),
            "Navegar" to listOf(
                MenuEntry("Ir para objecto…", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask)) { goToObject() },
                MenuEntry("Nova consola SQL", KeyStroke.getKeyStroke(KeyEvent.VK_L, mask or KeyEvent.SHIFT_DOWN_MASK)) { newConsoleForSelection() },
                MenuEntry("Desacoplar aba atual", KeyStroke.getKeyStroke(KeyEvent.VK_D, mask or KeyEvent.SHIFT_DOWN_MASK)) { detachTab(tabs.selectedIndex) },
                MenuEntry("Fechar aba", KeyStroke.getKeyStroke(KeyEvent.VK_W, mask)) { tabs.selectedComponent?.let { closeTab(it) } },
                MenuEntry("Aba seguinte", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, mask)) { if (tabs.tabCount > 0) tabs.selectedIndex = (tabs.selectedIndex + 1) % tabs.tabCount },
                MenuEntry("Aba anterior", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, mask)) { if (tabs.tabCount > 0) tabs.selectedIndex = (tabs.selectedIndex - 1 + tabs.tabCount) % tabs.tabCount },
            ),
            "Ajuda" to listOf(
                MenuEntry("Sobre") {
                    JOptionPane.showMessageDialog(this, "Ora Studio 1.0\nCliente Oracle com navegação por Ctrl+clique.\nConfiguração: ${AppPaths.dir}", "Sobre", JOptionPane.INFORMATION_MESSAGE)
                },
            ),
        )
    }

    /** Barra de menus (janela independente). [exit] acrescenta "Sair" ao menu Ficheiro. */
    fun buildMenuBar(exit: (() -> Unit)?): JMenuBar = JMenuBar().apply {
        for ((title, items) in entries()) {
            add(JMenu(title).apply {
                items.forEach { e -> if (e != null) add(JMenuItem(e.text).apply { accelerator = e.key; addActionListener { e.action() } }) }
                if (title == "Ficheiro" && exit != null) {
                    addSeparator()
                    add(JMenuItem("Sair").apply {
                        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
                        addActionListener { exit() }
                    })
                }
            })
        }
    }

    /** Mesmas accoes num menu "⋮" (usado no plugin, que nao tem barra de menus). */
    private fun menuButtonPopup(anchor: Component) {
        val menu = JPopupMenu()
        for ((_, items) in entries()) {
            items.forEach { e -> if (e != null) menu.add(JMenuItem(e.text).apply { addActionListener { e.action() } }) }
            menu.addSeparator()
        }
        menu.show(anchor, 0, anchor.height)
    }

    private fun checkDrivers() {
        val d = ConfigStore.drivers.firstOrNull { it.id == Drivers.DEFAULT_ID } ?: return
        if (d.jars.none { java.io.File(it).isFile }) {
            if (JOptionPane.showConfirmDialog(this, "O driver Oracle ainda não tem um JAR (ojdbc6 ou superior).\nConfigurar agora?", "Driver", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                DriversDialog(this).isVisible = true
            }
        }
    }

    // ---- ligacoes ----

    private fun rebuildHandles() {
        val keep = handles.toMap()
        handles.clear()
        for (c in ConfigStore.connections) handles[c.id] = keep[c.id] ?: ConnectionHandle(c)
        keep.filterKeys { it !in handles }.values.forEach { it.disconnect() }
    }

    private fun manageConnections(select: ConnectionConfig?) {
        val dlg = ConnectionsDialog(owner(), select)
        dlg.isVisible = true
        if (!dlg.applied) return
        // ligacoes alteradas: fecha as sessoes antigas (tabs e meta) e reconstroi
        val touched = dlg.touchedIds + dlg.removedIds
        touched.forEach { id -> handles[id]?.let { closeTabsOf(it); it.disconnect() } }
        touched.forEach { handles.remove(it) }
        rebuildHandles()
        navigator.setConnections(handles.values.toList())
    }

    override fun passwordFor(handle: ConnectionHandle): Boolean {
        if (!handle.needsPassword()) return true
        val p = askPassword(this, handle) ?: return false
        handle.password = p
        return true
    }

    override fun connect(node: ConnNode) { navigator.expandPath(javax.swing.tree.TreePath(node.path)) }

    override fun disconnect(node: ConnNode) {
        closeTabsOf(node.handle)
        node.handle.disconnect()
        navigator.collapseConn(node)
        status("Desligado de ${node.handle.name}")
    }

    override fun edit(node: ConnNode) = manageConnections(node.handle.config)

    override fun duplicate(node: ConnNode) {
        val c = node.handle.config
        ConfigStore.connections.add(c.copyAs(c.name + " (cópia)"))
        ConfigStore.saveConnections()
        rebuildHandles()
        navigator.setConnections(handles.values.toList())
    }

    override fun remove(node: ConnNode) {
        if (JOptionPane.showConfirmDialog(this, "Remover a ligação \"${node.handle.name}\"?", "Remover", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        closeTabsOf(node.handle)
        node.handle.disconnect()
        ConfigStore.connections.removeAll { it.id == node.handle.config.id }
        ConfigStore.saveConnections()
        rebuildHandles()
        navigator.setConnections(handles.values.toList())
    }

    fun handleOf(id: String): ConnectionHandle? = handles[id]

    fun tabCount(): Int = tabs.tabCount
    fun tabTitles(): List<String> = (0 until tabs.tabCount).map { plain(tabs.getTitleAt(it)) }
    fun tabTitlesRaw(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
    fun detachedCount(): Int = detached.size
    fun detachedTitles(): List<String> = detached.values.map { it.title }

    /** Simula um clique no "x" do titulo da aba [i] (testes). */
    fun clickCloseOf(i: Int) { val b = tabs.getBoundsAt(i); closeIfOnMark(i, b.x + b.width - px(6)) }

    // ---- AppContext ----

    override fun status(msg: String) { statusLabel.text = msg }

    override fun openObject(handle: ConnectionHandle, ref: ObjectRef) {
        val key = "obj:${handle.config.id}:${ref.owner}.${ref.name}:${ref.type}"
        existing(key)?.let { selectOrFront(it); return }
        val panel = ObjectViewerPanel(this, handle, ref)
        addTab(key, "${ref.name}", Icons.of(ref.type), "${ref.qualified}  [${ref.type.label}] — ${handle.name}", panel)
    }

    override fun openConsole(handle: ConnectionHandle, text: String?) {
        if (handle.needsPassword() && !passwordFor(handle)) return
        consoleCount++
        val n = tabKeys.values.count { it.startsWith("console:${handle.config.id}:") } + 1
        val panel = SqlConsolePanel(this, handle, text)
        addTab("console:${handle.config.id}:$consoleCount", "Console ${handle.name}" + if (n > 1) " ($n)" else "", Icons.console, handle.config.effectiveUrl(), panel)
        panel.focusEditor()
        // garante a sessao de metadados (links do Ctrl+rato) sem bloquear a UI
        if (!handle.connected) Bg.run({ handle.metaSession() }, { status("Falha a ligar: ${it.shortMessage().lineSequence().first()}") }) { navigator.repaint() }
    }

    private fun newConsoleForSelection() {
        val h = navigator.selectedConn()?.handle ?: handles.values.firstOrNull()
        if (h == null) { manageConnections(null); return }
        openConsole(h)
    }

    private fun goToObject() {
        val sel = tabs.selectedComponent
        val h = (sel as? SqlConsolePanel)?.handle ?: (sel as? ObjectViewerPanel)?.handle
            ?: navigator.selectedConn()?.handle ?: handles.values.firstOrNull { it.connected }
        if (h == null || !h.connected) {
            JOptionPane.showMessageDialog(this, "Liga-te primeiro a uma base de dados (duplo clique na ligação).")
            return
        }
        GoToObjectDialog(owner(), h) { openObject(h, it) }.isVisible = true
    }

    // ---- abas ----

    private fun existing(key: String): Component? = tabKeys.entries.firstOrNull { it.value == key }?.key?.takeIf { tabs.indexOfComponent(it) >= 0 || it in detached }

    /** Selecciona a aba; se estiver desacoplada, traz a sua janela para a frente. */
    private fun selectOrFront(c: Component) {
        val d = detached[c]
        if (d != null) { d.frame.toFront(); d.frame.requestFocus(); return }
        tabs.selectedComponent = c
    }

    private fun addTab(key: String, title: String, icon: Icon, tip: String, comp: Component) {
        tabKeys[comp] = key
        // titulo e icone nativos do JTabbedPane (um componente personalizado no cabecalho nao e desenhado pelo tema do IntelliJ)
        tabs.addTab(shown(title), icon, comp, tip)
        tabs.selectedIndex = tabs.indexOfComponent(comp)
    }

    private fun onCloseMark(i: Int, x: Int): Boolean {
        if (!textClose) return false
        val b = tabs.getBoundsAt(i) ?: return false
        return x >= b.x + b.width - px(26) && x <= b.x + b.width
    }

    private fun closeIfOnMark(i: Int, x: Int): Boolean {
        if (!onCloseMark(i, x)) return false
        tabs.getComponentAt(i)?.let { closeTab(it) }
        return true
    }

    private fun tabMenu(i: Int, x: Int, y: Int) {
        val comp = tabs.getComponentAt(i)
        val menu = JPopupMenu()
        if (comp is SqlConsolePanel) menu.add(JMenuItem("Renomear…").apply { addActionListener { renameTab(i) } })
        menu.add(JMenuItem("Desacoplar (janela independente)").apply { addActionListener { detachTab(i) } })
        menu.add(JMenuItem("Fechar").apply { addActionListener { closeTab(comp) } })
        menu.add(JMenuItem("Fechar as outras").apply {
            addActionListener { (0 until tabs.tabCount).map { tabs.getComponentAt(it) }.filter { it !== comp }.forEach { closeTab(it) } }
        })
        menu.add(JMenuItem("Fechar todas").apply { addActionListener { (0 until tabs.tabCount).map { tabs.getComponentAt(it) }.forEach { closeTab(it) } } })
        menu.show(tabs, x, y)
    }

    private fun renameTab(i: Int) {
        val name = JOptionPane.showInputDialog(this, "Nome da consola:", plain(tabs.getTitleAt(i))) ?: return
        if (name.isNotBlank()) tabs.setTitleAt(i, shown(name.trim()))
    }

    // ---- desacoplar / acoplar ----

    /**
     * Move a aba [i] para uma janela independente (como os terminais do PEM WL Tools). Fechar essa janela
     * volta a acoplar a aba no sitio onde estava. [show] so e false nos testes.
     */
    fun detachTab(i: Int, show: Boolean = true) {
        if (i < 0 || i >= tabs.tabCount) return
        val comp = tabs.getComponentAt(i)
        if (comp in detached) return
        val title = plain(tabs.getTitleAt(i))
        val icon = tabs.getIconAt(i)
        val tip = tabs.getToolTipTextAt(i)
        tabs.removeTabAt(i)
        val frame = JFrame("$title — Ora Studio").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            contentPane.add(comp, BorderLayout.CENTER)
            setSize(px(1000), px(650))
            setLocationRelativeTo(this@MainPanel)
        }
        val d = Detached(frame, title, icon, tip, i)
        detached[comp] = d
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                // fechar a janela volta a acoplar (a nao ser que a aba esteja a ser mesmo fechada)
                if (detached[comp] === d) redock(comp)
            }
        })
        if (show) frame.isVisible = true else frame.addNotify()
    }

    /** Volta a acoplar uma aba desacoplada. */
    fun redock(comp: Component) {
        val d = detached.remove(comp) ?: return
        d.frame.contentPane.remove(comp)
        if (d.frame.isDisplayable) d.frame.dispose()
        val at = d.index.coerceIn(0, tabs.tabCount)
        tabs.insertTab(shown(d.title), d.icon, comp, d.tip, at)
        tabs.selectedIndex = at
    }

    fun redockAll() {
        detached.keys.toList().forEach { redock(it) }
    }

    private fun closeTab(comp: Component) {
        detached.remove(comp)?.let { d ->
            d.frame.contentPane.remove(comp)
            d.frame.dispose()
        }
        when (comp) {
            is SqlConsolePanel -> comp.dispose()
            is ObjectViewerPanel -> comp.dispose()
        }
        tabKeys.remove(comp)
        if (tabs.indexOfComponent(comp) >= 0) tabs.remove(comp)
    }

    private fun closeTabsOf(handle: ConnectionHandle) {
        tabKeys.keys.toList().filter { (it as? SqlConsolePanel)?.handle === handle || (it as? ObjectViewerPanel)?.handle === handle }.forEach { closeTab(it) }
    }

    /** Guarda consolas, fecha sessoes e desliga as ligacoes. */
    fun dispose() {
        for (c in tabKeys.keys.toList()) closeTab(c)
        handles.values.forEach { it.disconnect() }
    }
}

/** Janela do programa independente. */
class MainFrame : JFrame("Ora Studio") {

    val panel = MainPanel()

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        jMenuBar = panel.buildMenuBar(exit = ::quit)
        contentPane.add(panel, BorderLayout.CENTER)
        val p = ConfigStore.prefs
        setSize(if (p.windowW > 0) p.windowW else px(1400), if (p.windowH > 0) p.windowH else px(850))
        if (p.windowX >= 0 && p.windowY >= 0) setLocation(p.windowX, p.windowY) else setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = quit()
        })
    }

    private fun quit() {
        panel.dispose()
        val p = ConfigStore.prefs
        p.windowX = x; p.windowY = y; p.windowW = width; p.windowH = height
        p.dividerLocation = panel.split.dividerLocation
        ConfigStore.savePrefs()
        dispose()
        System.exit(0)
    }
}
