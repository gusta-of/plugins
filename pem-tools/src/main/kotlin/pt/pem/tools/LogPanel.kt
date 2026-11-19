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

    @Volatile private var wrapLines = true

    private val pane = object : JTextPane() {
        /** true -> as linhas quebram na largura da janela; false -> barra de scroll horizontal */
        override fun getScrollableTracksViewportWidth() = wrapLines
    }.apply {
        editorKit = WrapStyledKit()
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
    }

    private val normal = attr(null)
    private val error = attr(JBColor(Color(0xC62828), Color(0xFF6B68)))
    private val system = attr(JBColor(Color(0x1565C0), Color(0x6CB6FF)))

    /** Item da fila de escrita: um troco de texto (o ultimo de uma linha traz [lineId]) ou a marca «botao de payload». */
    private class Item(val text: String, val attr: SimpleAttributeSet, val lineId: Long = -1, val startsLine: Boolean = false,
                       val markLine: Long = -1, val kind: String = "", val payload: String = "")

    private val pending = ConcurrentLinkedQueue<Item>()

    /** Projeto (para abrir o JSON/SOAP formatado num editor com cores); sem ele abre numa caixa. */
    @Volatile var project: com.intellij.openapi.project.Project? = null
    private val detector = PayloadDetector()
    private var nextLine = 0L
    /** Onde fica o fim de cada linha recente (Position acompanha as edicoes do documento). */
    private val linePos = object : LinkedHashMap<Long, javax.swing.text.Position>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, javax.swing.text.Position>) = size > 1500
    }
    private var payloadCount = 0

    private val engine = HighlightEngine(emptyList())
    private val partial = StringBuilder()
    private var partialAttr: SimpleAttributeSet = normal
    private var appliedFont = -1
    private val rulesListener: () -> Unit = {
        synchronized(engine) { engine.setRules(HighlightStore.rules) }
        javax.swing.SwingUtilities.invokeLater { applyFont() }
    }

    private fun fontAttrs(size: Int) = SimpleAttributeSet().also {
        StyleConstants.setFontFamily(it, Font.MONOSPACED)
        StyleConstants.setFontSize(it, JBUI.scaleFontSize(size.toFloat()))
    }

    /** O JTextPane ignora pane.font para texto com atributos: aplica o tamanho a tudo o que ja la esta e ao novo. */
    private fun applyFont() {
        val size = HighlightStore.fontSize
        if (size == appliedFont) return
        appliedFont = size
        pane.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(size.toFloat()))
        val doc = pane.styledDocument
        doc.setCharacterAttributes(0, doc.length, fontAttrs(size), false)
        if (search.text.isNotEmpty()) runSearch(false)
    }

    private val search = JBTextField().apply { emptyText.text = "Pesquisar nos logs" }
    private val countLabel = JBLabel(" ")
    private val follow = JBCheckBox("Seguir", true)
    private val wrap = JBCheckBox("Quebrar linhas", PemSettings.getInstance().state.logWrap).apply {
        toolTipText = "Quebra as linhas compridas na largura da janela (desligado: scroll horizontal)"
    }

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
                add(wrap)
                add(iconButton(AllIcons.General.GearPlain, "Realce de cores: configurar regras (expressao -> cor, blocos)") { HighlightDialog(null).show() })
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
        wrapLines = wrap.isSelected
        wrap.addActionListener {
            wrapLines = wrap.isSelected
            PemSettings.getInstance().state.logWrap = wrapLines
            pane.revalidate(); pane.repaint()
            if (follow.isSelected) pane.caretPosition = pane.styledDocument.length
        }
        HighlightStore.load()
        applyFont()
        engine.setRules(HighlightStore.rules)
        HighlightStore.onChange(rulesListener)
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
        synchronized(engine) {
            // a consola trabalha por linhas: guarda o resto sem '\n' ate chegar o fim da linha
            partial.append(ANSI.replace(text, ""))
            partialAttr = a
            var i = partial.indexOf("\n")
            while (i >= 0) {
                emitLine(partial.substring(0, i), a, "\n")
                partial.delete(0, i + 1)
                i = partial.indexOf("\n")
            }
            if (partial.isNotEmpty()) lastPartial = System.currentTimeMillis()
        }
    }

    @Volatile private var lastPartial = 0L

    private fun emitLine(line: String, base: SimpleAttributeSet, eol: String) {
        val clean = line.trimEnd('\r')
        val id = nextLine++
        val segs = engine.segments(clean)
        for ((idx, s) in segs.withIndex()) {
            val last = idx == segs.size - 1
            pending.add(Item(if (last) s.text + eol else s.text, styled(base, s.style), lineId = id, startsLine = idx == 0))
        }
        if (segs.isEmpty()) pending.add(Item(eol, base, lineId = id, startsLine = true))
        // JSON / SOAP completos: o botao vai para a linha onde o payload comecou
        for (ev in detector.push(clean, id)) pending.add(Item("", base, markLine = ev.ref, kind = ev.kind, payload = ev.text))
    }

    /** Clique no botao: pre-visualizacao numa caixa por cima desta janela; dai pode abrir no editor. */
    private fun previewPayload(owner: java.awt.Component, kind: String, text: String) {
        val formatted = PayloadFormat.format(kind, text)
        PayloadDialog(owner, project, kind, formatted) { openPayload(kind, text) }.show()
    }

    private fun openPayload(kind: String, text: String) {
        val formatted = PayloadFormat.format(kind, text)
        val proj = project
        if (proj == null) { TextBoxDialog(null, if (kind == "json") "JSON" else "SOAP / XML", formatted).show(); return }
        val ext = if (kind == "json") "json" else "xml"
        val type = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension(ext)
        val file = com.intellij.testFramework.LightVirtualFile("payload-${++payloadCount}.$ext", type, formatted)
        file.isWritable = false // so leitura: nao e um ficheiro do projeto nem pede para guardar
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(proj).openFile(file, true)
    }

    private fun insertButton(doc: javax.swing.text.StyledDocument, at: Int, kind: String, text: String) {
        val btn = javax.swing.JButton(if (kind == "json") "{ } JSON" else "</> SOAP/XML").apply {
            toolTipText = "Abrir formatado num editor"
            isFocusable = false
            margin = JBUI.insets(0, 4)
            font = font.deriveFont(font.size2D - 2f)
            addActionListener { previewPayload(this, kind, text) }
        }
        val a = SimpleAttributeSet()
        val holder = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, JBUI.scale(8))
            add(btn)
            alignmentY = 0.8f
        }
        StyleConstants.setComponent(a, holder)
        doc.insertString(at.coerceIn(0, doc.length), " ", a)
    }

    private fun styled(base: SimpleAttributeSet, st: RuleStyle?): SimpleAttributeSet {
        if (st == null) return base
        val a = SimpleAttributeSet(base)
        parseColor(st.foreground)?.let { StyleConstants.setForeground(a, it) }
        parseColor(st.background)?.let { StyleConstants.setBackground(a, it) }
        if (st.bold) StyleConstants.setBold(a, true)
        return a
    }

    private fun parseColor(hex: String): Color? = try { if (hex.isBlank()) null else Color.decode(hex) } catch (_: Exception) { null }

    fun attachToProcess(handler: ProcessHandler) {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                print(event.text, if (outputType === ProcessOutputTypes.STDERR) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
            }
        })
    }

    fun clear() {
        pending.clear()
        synchronized(engine) { partial.setLength(0); detector.reset(); linePos.clear() }
        pane.text = ""
        runSearch(false)
    }

    private fun flush() {
        // resto de linha sem '\n' parado ha mais de 300 ms: mostra-o ja (p.ex. um prompt)
        synchronized(engine) {
            if (partial.isNotEmpty() && System.currentTimeMillis() - lastPartial > 300) {
                emitLine(partial.toString(), partialAttr, "")
                partial.setLength(0)
            }
        }
        if (pending.isEmpty()) return
        val doc = pane.styledDocument
        var item = pending.poll()
        while (item != null) {
            if (item.markLine >= 0) {
                linePos.remove(item.markLine)?.let { p -> insertButton(doc, p.offset, item.kind, item.payload) }
            } else {
                val before = doc.length
                doc.insertString(before, item.text, SimpleAttributeSet(item.attr).also { it.addAttributes(fontAttrs(HighlightStore.fontSize)) })
                // inicio da linha: e aqui que fica o botao (sempre visivel, mesmo com linhas enormes), se aparecer um payload
                if (item.lineId >= 0 && item.startsLine) linePos[item.lineId] = doc.createPosition(before)
            }
            item = pending.poll()
        }
        if (doc.length > MAX_CHARS) {
            doc.remove(0, doc.length - KEEP_CHARS)
            linePos.entries.removeAll { it.value.offset <= 0 } // linhas apagadas do topo (colapsam para o offset 0)
        }
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
