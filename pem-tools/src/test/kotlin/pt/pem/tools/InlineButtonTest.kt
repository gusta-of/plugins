package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** A tecnica do botao inline: Position no fim da linha + insercao posterior de um componente no documento. */
class InlineButtonTest {
    @Test fun `botao inserido no fim de uma linha antiga, mesmo depois de chegarem mais linhas e de apagar o topo`() {
        System.setProperty("java.awt.headless", "true")
        val pane = JTextPane()
        val doc = pane.styledDocument
        doc.insertString(0, "antiga 1\n", null)
        doc.insertString(doc.length, "payload: {\n", null)
        val pos = doc.createPosition(doc.length - 1) // antes do \n da 2.a linha
        doc.insertString(doc.length, "  \"a\": 1\n}\nultima\n", null)
        doc.remove(0, "antiga 1\n".length)             // o topo e' apagado: a Position acompanha
        val a = SimpleAttributeSet()
        StyleConstants.setComponent(a, JButton("{ } JSON"))
        doc.insertString(pos.offset, " ", a)
        assertEquals("payload: { \n  \"a\": 1\n}\nultima\n", doc.getText(0, doc.length))
        val el = doc.getCharacterElement("payload: {".length)
        assertNotNull(StyleConstants.getComponent(el.attributes))
    }

    @Test fun `botao no INICIO de uma linha antiga fica antes do texto`() {
        val pane = JTextPane()
        val doc = pane.styledDocument
        doc.insertString(0, "antiga\n", null)
        val before = doc.length
        doc.insertString(before, "<S:Envelope>...</S:Envelope>\n", null)
        val start = doc.createPosition(before)
        doc.insertString(doc.length, "mais uma\n", null)
        doc.remove(0, "antiga\n".length)
        val a = SimpleAttributeSet()
        StyleConstants.setComponent(a, JButton("x"))
        doc.insertString(start.offset, " ", a)
        assertEquals(" <S:Envelope>...</S:Envelope>\nmais uma\n", doc.getText(0, doc.length))
        assertNotNull(StyleConstants.getComponent(doc.getCharacterElement(0).attributes))
    }
}
