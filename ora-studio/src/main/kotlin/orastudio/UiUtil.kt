package orastudio

import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.util.concurrent.Executors
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Trabalho em background com retorno na EDT. */
object Bg {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bg").apply { isDaemon = true } }

    fun <T> run(work: () -> T, onError: (Throwable) -> Unit = {}, onOk: (T) -> Unit = {}) {
        pool.execute {
            try {
                val r = work()
                SwingUtilities.invokeLater {
                    try {
                        onOk(r)
                    } catch (t: Throwable) {
                        AppLog.error("Erro ao mostrar resultado em background", t)
                        onError(t)
                    }
                }
            } catch (t: Throwable) {
                AppLog.error("Erro em tarefa de background", t)
                SwingUtilities.invokeLater { onError(t) }
            }
        }
    }
}

/** Pixeis independentes da escala do ecra (HiDPI). */
fun px(n: Int): Int = UiEnv.scale(n)

fun edt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}

fun Component.parentWindow(): Window? = SwingUtilities.getWindowAncestor(this)

/** Mensagem curta (primeira linha) de uma excepcao, sem o prefixo da classe. */
fun Throwable.shortMessage(): String {
    var t: Throwable = this
    while (t.cause != null && t.message.isNullOrBlank()) t = t.cause!!
    return (t.message ?: t.toString()).trim()
}

fun showError(parent: Component?, title: String, t: Throwable) {
    val msg = t.shortMessage()
    val area = JTextArea(msg).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        columns = 60
        rows = minOf(12, msg.lines().sumOf { 1 + it.length / 60 }.coerceAtLeast(2))
    }
    JOptionPane.showMessageDialog(parent, JScrollPane(area).apply { border = null; preferredSize = Dimension(560, area.preferredSize.height.coerceAtMost(260) + 8) }, title, JOptionPane.ERROR_MESSAGE)
}

/** Linha de formulario em GridBagLayout: rotulo + componente(s). */
class FormPanel : JPanel(GridBagLayout()) {
    private var row = 0

    fun add(label: String, comp: JComponent, fill: Boolean = true, extra: JComponent? = null): FormPanel =
        add(JLabel(label), comp, fill, extra)

    fun add(label: JLabel, comp: JComponent, fill: Boolean = true, extra: JComponent? = null): FormPanel {
        add(label, GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 4, 4, 10)
        })
        add(comp, GridBagConstraints().apply {
            gridx = 1; gridy = row; weightx = 1.0; anchor = GridBagConstraints.WEST
            this.fill = if (fill) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
            insets = Insets(4, 0, 4, 4)
            if (extra != null) gridwidth = 1
        })
        if (extra != null) add(extra, GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4, 0, 4, 4) })
        row++
        return this
    }

    fun addWide(comp: JComponent, weighty: Double = 0.0): FormPanel {
        add(comp, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 3; weightx = 1.0; this.weighty = weighty
            fill = if (weighty > 0) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        })
        row++
        return this
    }
}

fun smallButton(text: String, tip: String? = null, action: () -> Unit) = JButton(text).apply {
    toolTipText = tip
    isFocusable = false
    addActionListener { action() }
}

/** FlowLayout que quebra linha quando falta largura (a altura preferida acompanha a largura actual). */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : java.awt.FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: java.awt.Container) = layoutSize(target, true)

    override fun minimumLayoutSize(target: java.awt.Container) = layoutSize(target, false).apply { width -= hgap + 1 }

    private fun layoutSize(target: java.awt.Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            if (targetWidth == 0) targetWidth = Int.MAX_VALUE
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m: Component = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight
            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}

/** Painel com WrapLayout que recalcula a altura quando a largura muda. */
class WrapPanel(hgap: Int = 5, vgap: Int = 3) : JPanel(WrapLayout(java.awt.FlowLayout.LEFT, hgap, vgap)) {
    private var lastWidth = -1

    init {
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (width != lastWidth) { lastWidth = width; revalidate() }
            }
        })
    }
}
