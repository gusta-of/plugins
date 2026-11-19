package orastudio

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Mensagem + pilha de excepcao, para mostrar e copiar. */
fun fullErrorText(t: Throwable): String = (t.message?.takeIf { it.isNotBlank() } ?: t.toString()) + "\n\n" + t.stackTraceToString()

/** Caixa com o erro completo (texto selecionavel) e botao «Copiar tudo». */
object ErrorDialog {
    fun show(parent: Component?, title: String, text: String) {
        val area = JTextArea(text).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, px(12)); caretPosition = 0 }
        val owner = if (parent == null) null else SwingUtilities.getWindowAncestor(parent)
        val dlg = JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        val copy = JButton("Copiar tudo").apply { addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
        val close = JButton("Fechar").apply { addActionListener { dlg.dispose() } }
        dlg.contentPane.add(JScrollPane(area), BorderLayout.CENTER)
        dlg.contentPane.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT)).apply { add(copy); add(close) }, BorderLayout.SOUTH)
        (dlg.contentPane as JPanel).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        dlg.setSize(px(820), px(480))
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }
}
