package orastudio

import java.awt.Color
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** Registo colorido de mensagens (resultado das instrucoes, erros, DBMS_OUTPUT). */
class ConsoleLog : JScrollPane() {
    enum class Kind { INFO, OK, ERROR, OUTPUT, WARN }

    private val pane = JTextPane().apply { isEditable = false }
    private val attrs = Kind.entries.associateWith { k ->
        SimpleAttributeSet().also {
            when (k) {
                Kind.OK -> StyleConstants.setForeground(it, Color(0x4CAF50))
                Kind.ERROR -> StyleConstants.setForeground(it, Color(0xEF5350))
                Kind.WARN -> StyleConstants.setForeground(it, Color(0xFFA726))
                Kind.OUTPUT -> StyleConstants.setForeground(it, Color(0x9E9E9E))
                Kind.INFO -> {}
            }
        }
    }

    init {
        pane.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, px(12))
        setViewportView(pane)
    }

    fun log(text: String, kind: Kind = Kind.INFO) {
        val doc = pane.styledDocument
        doc.insertString(doc.length, text + "\n", attrs.getValue(kind))
        pane.caretPosition = doc.length
    }

    fun clear() {
        pane.text = ""
    }

    fun text(): String = pane.text
}

interface AppContext {
    fun openObject(handle: ConnectionHandle, ref: ObjectRef)
    fun openConsole(handle: ConnectionHandle, text: String? = null)
    fun status(msg: String)
}
