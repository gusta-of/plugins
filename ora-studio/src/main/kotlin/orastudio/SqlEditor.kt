package orastudio

import org.fife.ui.rsyntaxtextarea.LinkGenerator
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.InputEvent
import javax.swing.JLabel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/**
 * Editor SQL com realce de sintaxe. Mantendo Ctrl premido, os identificadores que correspondem a um
 * objecto da BD (tabela, view, MV, trigger, procedure, ...) ficam azuis e sublinhados; Ctrl+clique abre-o.
 */
class SqlEditor(
    private val handle: ConnectionHandle,
    private val owners: () -> List<String>,
    private val navigate: (ObjectRef) -> Unit,
    editable: Boolean = true,
) {
    val area = RSyntaxTextArea(20, 80)
    val scroll = RTextScrollPane(area)

    /** Barra de busca: sempre visivel nos DDLs/codigo (so leitura); na consola abre com Ctrl+F. */
    val findBar = FindBar(area, autoHide = editable)

    /** Editor + barra de busca. */
    val component: javax.swing.JPanel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        add(findBar, java.awt.BorderLayout.NORTH)
        add(scroll, java.awt.BorderLayout.CENTER)
    }

    var completion: CompletionPopup? = null
        private set

    private val prefetchTimer = Timer(450) { prefetch() }.apply { isRepeats = false }

    init {
        area.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_SQL
        area.isEditable = editable
        area.tabSize = 2
        area.isCodeFoldingEnabled = false
        area.setAntiAliasingEnabled(true)
        area.isBracketMatchingEnabled = true
        area.paintTabLines = false
        area.setHyperlinksEnabled(true)
        area.setLinkScanningMask(InputEvent.CTRL_DOWN_MASK)
        area.hyperlinkForeground = Icons.linkColor()
        applyTheme(area)
        scroll.setLineNumbersEnabled(true)
        scroll.gutter.lineNumberFont = area.font.deriveFont(px(11).toFloat())
        scroll.isFoldIndicatorEnabled = false
        scroll.border = null
        area.linkGenerator = Generator()
        if (editable) completion = CompletionPopup(area, CompletionEngine(handle.catalog, owners), handle.catalog)
        findBar.isVisible = !editable
        val ctrl = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun bind(ks: javax.swing.KeyStroke, name: String, action: () -> Unit) {
            area.inputMap.put(ks, name)
            area.actionMap.put(name, object : javax.swing.AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = action() })
        }
        bind(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, ctrl), "orastudio-find") { findBar.open() }
        bind(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F3, 0), "orastudio-find-next") { findBar.step(1) }
        bind(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F3, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "orastudio-find-prev") { findBar.step(-1) }
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) {}
            private fun changed() = prefetchTimer.restart()
        })
    }

    var text: String
        get() = area.text
        set(v) {
            area.text = v
            area.caretPosition = 0
            area.discardAllEdits()
            prefetch()
        }

    /** Resolve em segundo plano os identificadores do texto (para o Ctrl+rato ser instantaneo). */
    fun prefetch() {
        if (!handle.connected) return
        handle.resolver.prefetch(area.text, owners())
    }

    private inner class Generator : LinkGenerator {
        override fun isLinkAtOffset(textArea: RSyntaxTextArea, offs: Int): LinkGeneratorResult? {
            val token = tokenAt(textArea, offs) ?: return null
            val line = textArea.getLineOfOffset(offs)
            val lineStart = textArea.getLineStartOffset(line)
            val lineText = textArea.document.getText(lineStart, textArea.getLineEndOffset(line) - lineStart).trimEnd('\n', '\r')
            val ident = SqlTokens.identifierAt(lineText, offs - lineStart) ?: return null
            if (token.offset > offs || offs > token.endOffset) return null
            val refs = handle.resolver.cached(owners(), ident.qualifier, ident.name) ?: return null
            if (refs.isEmpty()) return null
            return object : LinkGeneratorResult {
                override fun execute(): HyperlinkEvent? {
                    SwingUtilities.invokeLater { open(refs) }
                    return null
                }

                override fun getSourceOffset() = lineStart + ident.start
            }
        }
    }

    private fun tokenAt(ta: RSyntaxTextArea, offs: Int): Token? {
        val t = ta.modelToToken(offs) ?: return null
        return if (t.type in linkable) t else null
    }

    private val linkable = setOf(TokenTypes.IDENTIFIER, TokenTypes.FUNCTION, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.DATA_TYPE, TokenTypes.VARIABLE)

    /** Abre o objecto; com varios candidatos (ex.: MV e a sua tabela gemea) mostra "Escolher declaração". */
    fun open(refs: List<ObjectRef>) {
        if (refs.size == 1) { navigate(refs[0]); return }
        if (!area.isShowing) { navigate(refs[0]); return }
        val menu = JPopupMenu()
        menu.add(JLabel("  Escolher declaração  ").apply { font = font.deriveFont(Font.BOLD) })
        menu.addSeparator()
        refs.forEach { r ->
            menu.add(javax.swing.JMenuItem("${r.name}   (${r.owner}) — ${r.type.label}", Icons.of(r.type)).apply { addActionListener { navigate(r) } })
        }
        val p = try {
            val m = MouseInfo.getPointerInfo().location
            SwingUtilities.convertPointFromScreen(m, area)
            m
        } catch (_: Exception) { Point(10, 10) }
        menu.show(area, p.x, p.y + 4)
    }

    companion object {
        private val monoFamily: String by lazy {
            val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            listOf("JetBrains Mono", "Fira Code", "DejaVu Sans Mono", "Consolas", "Menlo").firstOrNull { it in names } ?: Font.MONOSPACED
        }

        fun applyTheme(area: RSyntaxTextArea) {
            val res = if (UiEnv.isDark()) "dark.xml" else "idea.xml"
            try {
                val theme = Theme.load(RSyntaxTextArea::class.java.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/$res"))
                theme.apply(area)
                // setFont converte tambem os estilos do realce de sintaxe para o novo tamanho (o baseFont do tema nao chega)
                area.font = Font(monoFamily, Font.PLAIN, px(13))
            } catch (_: Exception) {
                area.font = Font(monoFamily, Font.PLAIN, px(13))
            }
            area.hyperlinkForeground = Icons.linkColor()
        }
    }
}
