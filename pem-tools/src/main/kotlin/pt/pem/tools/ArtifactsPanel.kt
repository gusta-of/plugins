package pt.pem.tools

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/** Caixa com um texto longo (erro/saida) copiavel. */
class TextBoxDialog(project: Project?, private val heading: String, private val text: String) : DialogWrapper(project, true) {
    init { title = heading; init() }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(text).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, font.size); caretPosition = 0 }
        return JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(900), JBUI.scale(520)) }
    }

    override fun createActions(): Array<Action> {
        val copy = object : AbstractAction("Copiar tudo") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
        return arrayOf(copy, okAction)
    }
}

/**
 * Separador «Artefactos»: resultado por modulo do ultimo build/deploy (OK/falha + «Ver erro») e, ao subir o WebLogic,
 * o estado de cada artefacto (em espera / a carregar / pronto / falhou).
 */
class ArtifactsPanel(private val project: Project, private val runner: TaskRunner, private val wl: WlWatcher) : JPanel(BorderLayout()) {

    private val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(4, 8) }
    private val debounce = Timer(150) { rebuild() }.apply { isRepeats = false }

    init {
        add(JBScrollPane(content), BorderLayout.CENTER)
        runner.onResults = { schedule() }
        wl.onChange = { schedule() }
        rebuild()
    }

    private fun schedule() = javax.swing.SwingUtilities.invokeLater { if (!debounce.isRunning) debounce.start() }

    private fun glyph(text: String, color: Color?) = JBLabel(text).apply { if (color != null) foreground = color; preferredSize = Dimension(JBUI.scale(16), preferredSize.height) }

    private fun row(icon: JComponent, name: String, tip: String, status: String?, errorAction: (() -> Unit)?) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        add(icon, BorderLayout.WEST)
        add(JBLabel(name).apply { toolTipText = tip }, BorderLayout.CENTER)
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            if (status != null) add(JBLabel(status).apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() })
            if (errorAction != null) add(JButton("Ver erro").apply { margin = JBUI.insets(0, 6); isFocusable = false; addActionListener { errorAction() } })
        }, BorderLayout.EAST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun heading(text: String, sub: String) = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel(text).apply { font = font.deriveFont(Font.BOLD) })
        add(JBLabel(sub).apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() })
    }

    private fun empty(text: String) = JBLabel(text).apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT }

    private fun rebuild() {
        content.removeAll()
        val ok = JBColor(Color(0x2E7D32), Color(0x89D185))
        val bad = JBColor(Color(0xC62828), Color(0xF48771))
        val warn = JBColor(Color(0xB26A00), Color(0xCCA700))

        // ---- build ----
        val items = runner.results.toList()
        content.add(heading("Build", runner.resultsTitle + if (runner.busy) " — a correr" else ""))
        if (items.isEmpty()) content.add(empty("Sem builds ainda."))
        for (r in items) {
            val icon = when (r.status) {
                StepResult.Status.OK -> glyph("✔", ok)
                StepResult.Status.FAIL -> glyph("✘", bad)
                StepResult.Status.WARN -> glyph("⚠", warn)
                StepResult.Status.RUNNING -> glyph("⟳", null)
            }
            val showErr = r.status == StepResult.Status.FAIL || r.status == StepResult.Status.WARN
            content.add(row(icon, r.label, r.label, null, if (showErr) ({ TextBoxDialog(project, "Erro: ${r.label}", r.output.ifBlank { "(sem saida)" }).show() }) else null))
        }

        // ---- weblogic ----
        val c = wl.counts()
        val head = if (wl.serverState.isNotEmpty()) wl.serverState + (if (c.total > 0) " — ${c.ready}/${c.total} prontos" + (if (c.failed > 0) ", ${c.failed} com erro" else "") else "") else ""
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        content.add(heading("WebLogic", head))
        val apps = wl.snapshot()
        if (apps.isEmpty()) content.add(empty(if (wl.active) "A aguardar os deployments…" else "Inicie o WebLogic para ver os artefactos."))
        for (a in apps) {
            val icon = when (a.state) {
                AppState.READY -> glyph("✔", ok)
                AppState.FAILED -> glyph("✘", bad)
                AppState.LOADING -> glyph("⟳", null)
                AppState.PENDING -> glyph("○", com.intellij.util.ui.UIUtil.getContextHelpForeground())
            }
            val label = when (a.state) { AppState.READY -> "pronto"; AppState.FAILED -> "falhou"; AppState.LOADING -> "a carregar"; AppState.PENDING -> "em espera" }
            val err: (() -> Unit)? = if (a.state == AppState.FAILED) ({
                TextBoxDialog(project, "Falha ao carregar: ${a.name}", wl.errorOf(a.name) ?: "(o WebLogic nao deu mais detalhes — veja o separador WebLogic)").show()
            }) else null
            content.add(row(icon, a.name, a.detail, label, err))
        }
        content.add(javax.swing.Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }
}
