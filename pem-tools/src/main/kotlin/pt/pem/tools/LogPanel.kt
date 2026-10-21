package pt.pem.tools

import com.intellij.icons.AllIcons
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Consola de logs em Swing puro (nao depende do editor do IntelliJ, por isso continua a mostrar o
 * output quando o tool window e escondido ou a aba e desacoplada), com campo de pesquisa.
 */
class LogPanel : JPanel(BorderLayout()) {

    private val pane = object : JTextPane() {
        override fun getScrollableTracksViewportWidth() = false
    }.apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
    }

    private val normal = attr(null)
    private val error = attr(JBColor(Color(0xC62828), Color(0xFF6B68)))
    private val system = attr(JBColor(Color(0x1565C0), Color(0x6CB6FF)))

    private val pending = ConcurrentLinkedQueue<Pair<String, SimpleAttributeSet>>()

    private val search = JBTextField().apply { emptyText.text = "Pesquisar nos logs" }
    private val countLabel = JBLabel(" ")
    private val follow = JBCheckBox("Seguir", true)

    private val matchPainter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(0xFFEB3B), Color(0x7A6A00)))
    private val currentPainter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(0xFF9800), Color(0xB35C00)))
    private var matches = listOf<Int>()
    private var current = -1

    private val flushTimer = Timer(100) { flush() }

    init {
        val bar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 0)
            add(search, BorderLayout.CENTER)
            add(JPanel().apply {
                add(iconButton(AllIcons.Actions.PreviousOccurence, "Anterior (Shift+Enter)") { step(-1) })
                add(iconButton(AllIcons.Actions.NextOccurence, "Seguinte (Enter)") { step(1) })
                add(countLabel)
                add(follow)
                add(iconButton(AllIcons.Actions.GC, "Limpar consola") { clear() })
            }, BorderLayout.EAST)
        }
        add(bar, BorderLayout.NORTH)
        add(JBScrollPane(pane), BorderLayout.CENTER)

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = runSearch(true)
            override fun removeUpdate(e: DocumentEvent) = runSearch(true)
            override fun changedUpdate(e: DocumentEvent) = runSearch(true)
        })
        search.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) step(if (e.isShiftDown) -1 else 1)
            }
        })
        flushTimer.start()
    }

    private fun attr(color: Color?) = SimpleAttributeSet().also { if (color != null) StyleConstants.setForeground(it, color) }

    /** Seguro a partir de qualquer thread. */
    fun print(text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) {
        val a = when (type) {
            ConsoleViewContentType.ERROR_OUTPUT -> error
            ConsoleViewContentType.SYSTEM_OUTPUT -> system
            else -> normal
        }
        pending.add(ANSI.replace(text, "") to a)
    }

    fun attachToProcess(handler: ProcessHandler) {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                print(event.text, if (outputType === ProcessOutputTypes.STDERR) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
            }
        })
    }

    fun clear() {
        pending.clear()
        pane.text = ""
        runSearch(false)
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val doc = pane.styledDocument
        var item = pending.poll()
        while (item != null) {
            doc.insertString(doc.length, item.first, item.second)
            item = pending.poll()
        }
        if (doc.length > MAX_CHARS) doc.remove(0, doc.length - KEEP_CHARS)
        if (search.text.isNotEmpty()) runSearch(false)
        if (follow.isSelected) pane.caretPosition = doc.length
    }

    private fun runSearch(jump: Boolean) {
        val hl = pane.highlighter
        hl.removeAllHighlights()
        val q = search.text.lowercase()
        if (q.isEmpty()) {
            matches = emptyList(); current = -1; countLabel.text = " "
            return
        }
        val doc = pane.styledDocument
        val text = doc.getText(0, doc.length).lowercase()
        val found = ArrayList<Int>()
        var i = text.indexOf(q)
        while (i >= 0 && found.size < MAX_MATCHES) {
            found += i
            hl.addHighlight(i, i + q.length, matchPainter)
            i = text.indexOf(q, i + q.length)
        }
        matches = found
        current = if (found.isEmpty()) -1 else if (jump || current !in found.indices) found.size - 1 else current
        countLabel.text = if (found.isEmpty()) "0" else "${current + 1}/${found.size}"
        if (jump && current >= 0) revealCurrent()
    }

    private fun step(dir: Int) {
        if (matches.isEmpty()) return
        follow.isSelected = false
        current = (current + dir + matches.size) % matches.size
        runSearch(false)
        revealCurrent()
    }

    private fun revealCurrent() {
        val q = search.text.length
        val start = matches[current]
        pane.highlighter.addHighlight(start, start + q, currentPainter)
        countLabel.text = "${current + 1}/${matches.size}"
        pane.modelToView2D(start)?.let { pane.scrollRectToVisible(it.bounds) }
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[;\\d]*[A-Za-z]")
        private const val MAX_CHARS = 4_000_000
        private const val KEEP_CHARS = 3_000_000
        private const val MAX_MATCHES = 5000
    }
}
