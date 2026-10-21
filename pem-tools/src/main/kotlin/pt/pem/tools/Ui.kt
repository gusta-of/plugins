package pt.pem.tools

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JSeparator
import javax.swing.SwingConstants

/** Botao compacto: icone (+ texto curto opcional); a descricao vai no tooltip. */
fun iconButton(icon: Icon, tip: String, text: String = "", action: () -> Unit) = JButton(text.ifEmpty { null }, icon).apply {
    toolTipText = tip
    isFocusable = false
    margin = JBUI.insets(1, 4)
    iconTextGap = JBUI.scale(2)
    addActionListener { action() }
}

fun toolbarSeparator() = JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(JBUI.scale(6), JBUI.scale(22)) }

/** FlowLayout que quebra linha quando falta largura (a altura preferida acompanha). */
class WrapLayout(align: Int = LEFT, hgap: Int = 3, vgap: Int = 3) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container) = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container) = layoutSize(target, false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
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
