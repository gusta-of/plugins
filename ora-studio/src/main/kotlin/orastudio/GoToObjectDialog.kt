package orastudio

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Window
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.Timer

/** Ir para objecto (Ctrl+N): pesquisa por nome nos objectos visiveis para a ligacao. */
class GoToObjectDialog(owner: Window?, private val handle: ConnectionHandle, private val open: (ObjectRef) -> Unit) : JDialog(owner, "Ir para objecto — ${handle.name}", ModalityType.APPLICATION_MODAL) {

    private val field = JTextField()
    private val model = DefaultListModel<SearchHit>()
    private val list = JList(model)
    private val status = JLabel(" ")
    private val timer = Timer(250) { search() }.apply { isRepeats = false }
    private var seq = 0

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>, v: Any?, i: Int, s: Boolean, f: Boolean): Component {
                val r = (v as SearchHit).ref
                val c = super.getListCellRendererComponent(l, "${r.name}    ${r.owner} — ${r.type.label}", i, s, f) as JLabel
                c.icon = Icons.of(r.type)
                return c
            }
        }
        field.document.addDocumentListener(doc { timer.restart() })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { if (list.selectedIndex < model.size() - 1) list.selectedIndex++; list.ensureIndexIsVisible(list.selectedIndex); e.consume() }
                    KeyEvent.VK_UP -> { if (list.selectedIndex > 0) list.selectedIndex--; list.ensureIndexIsVisible(list.selectedIndex); e.consume() }
                    KeyEvent.VK_ENTER -> { choose(); e.consume() }
                    KeyEvent.VK_ESCAPE -> dispose()
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) choose() }
        })
        contentPane.add(field, BorderLayout.NORTH)
        contentPane.add(JScrollPane(list), BorderLayout.CENTER)
        contentPane.add(status, BorderLayout.SOUTH)
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        setSize(px(640), px(460))
        setLocationRelativeTo(owner)
    }

    private fun search() {
        val q = field.text.trim()
        if (q.length < 2) { model.clear(); status.text = "Escreve pelo menos 2 caracteres"; return }
        val my = ++seq
        status.text = "A pesquisar…"
        Bg.run({ handle.metadata.search(q, handle.currentSchema) }, { if (my == seq) status.text = it.shortMessage().lineSequence().first() }) { hits ->
            if (my != seq) return@run
            model.clear(); hits.forEach { model.addElement(it) }
            if (model.size() > 0) list.selectedIndex = 0
            status.text = "${hits.size} resultado(s)"
        }
    }

    private fun choose() {
        val h = list.selectedValue ?: return
        dispose()
        open(h.ref)
    }
}

/** Pede a palavra-passe quando nao esta guardada. Devolve null se cancelado. */
fun askPassword(parent: Component?, handle: ConnectionHandle): String? {
    val pf = JPasswordField(24)
    val panel = JPanel(BorderLayout(6, 6)).apply {
        add(JLabel("<html>Palavra-passe de <b>${handle.config.user}</b> em <b>${handle.name}</b>:</html>"), BorderLayout.NORTH)
        add(pf, BorderLayout.CENTER)
    }
    val r = JOptionPane.showConfirmDialog(parent, panel, "Palavra-passe", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
    return if (r == JOptionPane.OK_OPTION) String(pf.password) else null
}
