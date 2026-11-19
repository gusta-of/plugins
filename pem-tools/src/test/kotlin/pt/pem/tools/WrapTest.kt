package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import javax.swing.JTextPane

class WrapTest {
    private fun pane(wrapKit: Boolean) = object : JTextPane() {
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        if (wrapKit) editorKit = WrapStyledKit()
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    @Test fun `uma linha enorme sem espacos quebra na largura da janela (e so com o kit novo)`() {
        System.setProperty("java.awt.headless", "true")
        val xml = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">" + "<ns0:Item>valor</ns0:Item>".repeat(60) + "</soapenv:Envelope>"
        fun height(p: JTextPane): Int {
            p.styledDocument.insertString(0, xml + "\n", null)
            p.setSize(400, 100)
            return p.preferredSize.height
        }
        val plain = pane(false)
        val wrapped = pane(true)
        val hPlain = height(plain)
        val hWrap = height(wrapped)
        val oneLine = wrapped.getFontMetrics(wrapped.font).height
        // com o kit novo ocupa varias linhas visuais; sem ele ficava numa so (cortada)
        assertTrue(hWrap > oneLine * 5, "quebrou em varias linhas: $hWrap vs linha $oneLine")
        assertTrue(hWrap > hPlain, "mais alto que o comportamento antigo ($hPlain)")
        // o texto continua igual
        assertEquals(xml + "\n", wrapped.styledDocument.getText(0, wrapped.styledDocument.length))
    }
}
