package orastudio

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Barra "Buscar" para um editor: texto simples ou regex, maiusculas/minusculas, realce de todas as
 * ocorrencias, "i/N" e navegacao (Enter / Shift+Enter, botoes, F3). Pode ficar sempre visivel (DDL) ou
 * abrir com Ctrl+F (consola). Discreta: uma linha fina, alinhada a direita.
 */
class FindBar(private val area: RSyntaxTextArea, private val autoHide: Boolean) : JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)) {

    private val field = JTextField(11).apply { toolTipText = "Texto a procurar (Enter = seguinte, Shift+Enter = anterior)" }
    private val matchCase = JCheckBox("Aa").apply { toolTipText = "Distinguir maiúsculas de minúsculas" }
    private val regex = JCheckBox(".*").apply { toolTipText = "Expressão regular" }
    private val count = JLabel(" ")
    private var matches: List<IntRange> = emptyList()
    private var current = -1
    private val debounce = Timer(150) { refresh(jump = true) }.apply { isRepeats = false }
    private val rescan = Timer(300) { if (isVisible) refresh(jump = false) }.apply { isRepeats = false }

    /** Botao minusculo e sem moldura (o campo de busca nao deve gastar espaco). */
    private fun tiny(text: String, tip: String, action: () -> Unit) = javax.swing.JButton(text).apply {
        toolTipText = tip
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        margin = java.awt.Insets(0, 3, 0, 3)
        font = font.deriveFont(font.size2D - 2f)
        addActionListener { action() }
    }

    private fun small(c: javax.swing.AbstractButton) = c.apply {
        font = font.deriveFont(font.size2D - 2f)
        margin = java.awt.Insets(0, 0, 0, 0)
        isFocusable = false
    }

    init {
        border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        val dim = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        add(JLabel("⌕").apply { foreground = dim; toolTipText = "Buscar no texto (Ctrl+F)" })
        field.font = field.font.deriveFont(field.font.size2D - 1f)
        field.margin = java.awt.Insets(0, 3, 0, 3)
        add(field)
        add(tiny("▲", "Anterior (Shift+Enter / Shift+F3)") { step(-1) })
        add(tiny("▼", "Seguinte (Enter / F3)") { step(1) })
        add(small(matchCase))
        add(small(regex))
        count.font = count.font.deriveFont(count.font.size2D - 2f)
        count.foreground = dim
        add(count)
        if (autoHide) add(tiny("×", "Fechar (Esc)") { close() })

        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        matchCase.addActionListener { refresh(jump = true) }
        regex.addActionListener { refresh(jump = true) }
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { debounce.stop(); if (matches.isEmpty()) refresh(jump = true) else step(if (e.isShiftDown) -1 else 1) }
                    KeyEvent.VK_ESCAPE -> close()
                    KeyEvent.VK_F3 -> step(if (e.isShiftDown) -1 else 1)
                }
            }
        })
        // o texto do editor mudou (consola): refaz a contagem e o realce
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { if (isVisible && field.text.isNotEmpty()) rescan.restart() }
            override fun removeUpdate(e: DocumentEvent) { if (isVisible && field.text.isNotEmpty()) rescan.restart() }
            override fun changedUpdate(e: DocumentEvent) {}
        })
        area.markAllHighlightColor = if (UiEnv.isDark()) Color(0x6B5B00) else Color(0xFFE066)
    }

    /** Mostra a barra e poe o foco no campo (pre-preenchido com a selecao, se houver). */
    fun open() {
        isVisible = true
        area.selectedText?.takeIf { it.isNotBlank() && !it.contains('\n') }?.let { field.text = it }
        revalidate()
        SwingUtilities.invokeLater { field.requestFocusInWindow(); field.selectAll() }
        if (field.text.isNotEmpty()) refresh(jump = true)
    }

    private fun close() {
        clearMarks()
        if (autoHide) { isVisible = false; revalidate() }
        area.requestFocusInWindow()
    }

    private fun clearMarks() {
        try { area.clearMarkAllHighlights() } catch (_: Exception) {}
    }

    // ---- API (tambem usada nos testes) ----

    /** Procura [text]; devolve o numero de ocorrencias. */
    fun search(text: String, caseSensitive: Boolean = false, useRegex: Boolean = false): Int {
        field.text = text
        matchCase.isSelected = caseSensitive
        regex.isSelected = useRegex
        debounce.stop()
        refresh(jump = true)
        return matches.size
    }

    fun matchCount(): Int = matches.size
    fun currentIndex(): Int = current
    fun statusText(): String = count.text

    fun refresh(jump: Boolean) {
        val q = field.text
        clearMarks()
        if (q.isEmpty()) {
            matches = emptyList(); current = -1; count.text = " "; field.putClientProperty("JComponent.outline", null)
            return
        }
        val pattern = try {
            val flags = if (matchCase.isSelected) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (regex.isSelected) Pattern.compile(q, flags or Pattern.MULTILINE) else Pattern.compile(Pattern.quote(q), flags)
        } catch (e: PatternSyntaxException) {
            matches = emptyList(); current = -1; count.text = "regex inválida"; return
        }
        val m = pattern.matcher(area.text)
        val found = ArrayList<IntRange>()
        while (m.find() && found.size < MAX) {
            if (m.end() > m.start()) found += m.start() until m.end()
        }
        matches = found
        if (found.isEmpty()) {
            current = -1
            count.text = "0 resultados"
            field.putClientProperty("JComponent.outline", "error")
            return
        }
        field.putClientProperty("JComponent.outline", null)
        try {
            val mc = matchCase.isSelected
            val rx = regex.isSelected
            val ctx = SearchContext(q).apply { this.matchCase = mc; isRegularExpression = rx; markAll = true }
            SearchEngine.markAll(area, ctx)
        } catch (_: Exception) {
        }
        if (jump || current !in found.indices) {
            val from = if (jump) minOf(area.selectionStart, area.caretPosition) else area.caretPosition
            current = found.indexOfFirst { it.first >= from }.let { if (it < 0) 0 else it }
            select()
        } else {
            updateLabel()
        }
    }

    fun step(dir: Int) {
        if (matches.isEmpty()) { refresh(jump = true); return }
        current = (current + dir + matches.size) % matches.size
        select()
    }

    private fun select() {
        val r = matches[current]
        area.caretPosition = r.first
        area.moveCaretPosition(r.last + 1)
        try {
            area.modelToView2D(r.first)?.let { rect -> area.scrollRectToVisible(rect.bounds.apply { grow(0, 40) }) }
        } catch (_: Exception) {
        }
        updateLabel()
    }

    private fun updateLabel() {
        count.text = "${current + 1}/${matches.size}" + if (matches.size >= MAX) "+" else ""
    }

    companion object {
        private const val MAX = 20_000
    }
}
