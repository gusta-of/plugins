package orastudio

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Lista de sugestoes "leve": um componente desenhado na camada de popups da propria janela (nao uma janela
 * do sistema). Assim fica sempre colada ao cursor e nao rouba o foco ao editor, mesmo no Wayland/IntelliJ,
 * onde janelas soltas sao posicionadas pelo compositor e fecham ao perder o foco.
 */
class CompletionPopup(
    private val area: RSyntaxTextArea,
    private val engine: CompletionEngine,
    private val catalog: ServerCatalog,
) {
    private val model = DefaultListModel<Item>()
    private val list = JList(model).apply {
        cellRenderer = ItemRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        isFocusable = false
        visibleRowCount = MAX_ROWS
    }
    private val scroll = JScrollPane(list).apply {
        isFocusable = false
        verticalScrollBar.isFocusable = false
        border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY)
    }
    private val timer = Timer(120) { refresh(manual = false) }.apply { isRepeats = false }
    private var prefixStart = 0
    private var waitingData = false
    private var suppressTyped = false

    val isVisible: Boolean get() = scroll.parent != null
    fun itemCount(): Int = model.size()
    fun items(): List<Item> = (0 until model.size()).map { model[it] }
    fun bounds() = scroll.bounds

    init {
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                // so sugere ao escrever uma letra/digito/_/. (nao ao colar texto nem ao carregar o ficheiro)
                val typed = if (e.length == 1) try { e.document.getText(e.offset, 1)[0] } catch (_: Exception) { null } else null
                if (typed != null && (SqlSplitter.isIdentPart(typed) || typed == '.')) timer.restart() else hide()
            }

            override fun removeUpdate(e: DocumentEvent) { if (isVisible) timer.restart() }
            override fun changedUpdate(e: DocumentEvent) {}
        })
        area.addCaretListener(object : CaretListener {
            override fun caretUpdate(e: CaretEvent) {
                if (isVisible && (e.dot < prefixStart || e.dot > prefixStart + 200)) hide()
            }
        })
        area.addFocusListener(object : FocusAdapter() { override fun focusLost(e: FocusEvent) = hide() })
        area.addMouseListener(object : MouseAdapter() { override fun mousePressed(e: MouseEvent) = hide() })
        area.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE && e.isControlDown) {
                    refresh(manual = true)
                    e.consume()
                    return
                }
                if (!isVisible) return
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { move(1); e.consume() }
                    KeyEvent.VK_UP -> { move(-1); e.consume() }
                    KeyEvent.VK_PAGE_DOWN -> { move(MAX_ROWS); e.consume() }
                    KeyEvent.VK_PAGE_UP -> { move(-MAX_ROWS); e.consume() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> { accept(); suppressTyped = true; e.consume() }
                    KeyEvent.VK_ESCAPE -> { hide(); e.consume() }
                }
            }

            override fun keyTyped(e: KeyEvent) {
                // o Enter/Tab que acabou de aceitar uma sugestao nao deve inserir "\n"/"\t"
                if (suppressTyped && (e.keyChar == '\n' || e.keyChar == '\t')) e.consume()
                suppressTyped = false
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = list.locationToIndex(e.point)
                if (i >= 0) { list.selectedIndex = i; accept(); area.requestFocusInWindow() }
            }
        })
        catalog.onChange {
            if (waitingData) {
                waitingData = false
                SwingUtilities.invokeLater { if (area.isFocusOwner || area.hasFocus()) refresh(manual = true) }
            }
        }
    }

    /** Calcula as sugestoes e mostra/actualiza a lista (usado pelo temporizador e pelos testes). */
    fun refresh(manual: Boolean) {
        val text = area.text
        val caret = area.caretPosition
        val r = engine.complete(text, caret)
        if (r.loading) waitingData = true
        val qualified = r.prefixStart > 0 && text[r.prefixStart - 1] == '.'
        val onlyItself = r.items.size == 1 && r.items[0].text.equals(r.prefix, ignoreCase = true)
        if (r.items.isEmpty() || onlyItself || (!manual && r.prefix.isEmpty() && !qualified)) { hide(); return }
        prefixStart = r.prefixStart
        model.clear()
        r.items.take(300).forEach { model.addElement(it) }
        list.selectedIndex = 0
        list.ensureIndexIsVisible(0)
        show()
    }

    private fun show() {
        val root = SwingUtilities.getRootPane(area) ?: return
        val lp = root.layeredPane
        val rect = try { area.modelToView2D(prefixStart) } catch (_: Exception) { null } ?: return
        val rows = minOf(model.size(), MAX_ROWS)
        val rowH = list.fixedCellHeight.takeIf { it > 0 } ?: list.getCellBounds(0, 0)?.height ?: px(22)
        val pref = list.preferredSize
        val w = pref.width.coerceIn(px(320), px(640)) + scroll.verticalScrollBar.preferredSize.width
        val h = rowH * rows + 4
        // posicao: por baixo da linha do cursor, alinhada ao inicio da palavra; por cima se nao couber
        val belowPt = SwingUtilities.convertPoint(area, rect.x.toInt(), (rect.y + rect.height).toInt() + 2, lp)
        val abovePt = SwingUtilities.convertPoint(area, rect.x.toInt(), rect.y.toInt() - h - 2, lp)
        val x = belowPt.x.coerceIn(0, maxOf(0, lp.width - w))
        var y = belowPt.y
        if (y + h > lp.height && abovePt.y >= 0) y = abovePt.y
        scroll.setBounds(x, y, w, h)
        if (scroll.parent !== lp) {
            scroll.parent?.remove(scroll)
            lp.add(scroll, JLayeredPane.POPUP_LAYER)
        }
        lp.repaint(scroll.bounds)
        scroll.revalidate()
    }

    fun hide() {
        timer.stop()
        val p = scroll.parent ?: return
        val b = scroll.bounds
        p.remove(scroll)
        (p as? javax.swing.JComponent)?.repaint(b) ?: p.repaint()
    }

    private fun move(delta: Int) {
        val n = model.size()
        if (n == 0) return
        val i = (list.selectedIndex + delta).coerceIn(0, n - 1)
        list.selectedIndex = i
        list.ensureIndexIsVisible(i)
    }

    /** Insere a sugestao seleccionada no lugar da palavra que se esta a escrever. */
    fun accept() {
        val item = list.selectedValue ?: return hide()
        val caret = area.caretPosition
        hide()
        try {
            area.document.remove(prefixStart, caret - prefixStart)
            area.document.insertString(prefixStart, item.text, null)
        } catch (_: Exception) {
        }
    }

    private class ItemRenderer : JPanel(BorderLayout(8, 0)), ListCellRenderer<Item> {
        private val left = JLabel()
        private val right = JLabel()

        init {
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(1, 4, 1, 6)
        }

        override fun getListCellRendererComponent(list: JList<out Item>, item: Item?, index: Int, selected: Boolean, focus: Boolean): Component {
            val fg = if (selected) list.selectionForeground else list.foreground
            background = if (selected) list.selectionBackground else list.background
            isOpaque = true
            val dim = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val hex = "%06x".format(dim.rgb and 0xFFFFFF)
            left.icon = item?.let { Icons.forItem(it) }
            left.text = if (item == null) "" else "<html>${esc(item.text)} <span style='color:#$hex'>${esc(item.detail)}</span></html>"
            left.foreground = fg
            left.font = list.font
            right.text = item?.right.orEmpty()
            right.foreground = if (selected) fg else dim
            right.font = list.font.deriveFont(Font.PLAIN, list.font.size2D - 1f)
            return this
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    }

    companion object {
        private const val MAX_ROWS = 10
    }
}
