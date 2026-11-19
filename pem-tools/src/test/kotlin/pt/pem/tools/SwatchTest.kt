package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JTable

class SwatchTest {
    @Test fun `o quadrado de cor pinta a cor mesmo com a linha selecionada`() {
        val table = JTable(1, 1)
        val r = ColorSwatchRenderer()
        for (selected in listOf(false, true)) {
            val comp = r.getTableCellRendererComponent(table, "#B71C1C", selected, selected, 0, 0)
            comp.setSize(60, 24)
            val img = BufferedImage(60, 24, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            comp.paint(g)
            g.dispose()
            assertEquals(Color(0xB71C1C).rgb, img.getRGB(30, 12))
        }
    }

    @Test fun `curingas mostrados conforme o modo`() {
        fun d(mode: String) = decoratePattern(HighlightRule(pattern = "x", mode = mode))
        assertEquals("*x*", d("contains"))
        assertEquals("x*", d("starts"))
        assertEquals("*x", d("ends"))
        assertEquals("x", d("equals"))
        assertEquals("x", d("regex"))
    }
}
