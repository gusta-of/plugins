package orastudio

import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Icones desenhados (letra sobre quadrado arredondado), independentes da resolucao. */
class LetterIcon(private val letter: String, private val color: Color, private val round: Boolean = false) : Icon {
    override fun getIconWidth() = px(16)
    override fun getIconHeight() = px(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = iconWidth
            val h = iconHeight
            g2.color = color
            if (round) g2.fillOval(x + 1, y + 1, w - 2, h - 2) else g2.fillRoundRect(x + 1, y + 1, w - 2, h - 2, px(5), px(5))
            g2.color = Color.WHITE
            g2.font = g2.font.deriveFont(Font.BOLD, px(10).toFloat())
            val fm = g2.fontMetrics
            g2.drawString(letter, x + (w - fm.stringWidth(letter)) / 2, y + (h - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

object Icons {
    private val byType = mapOf(
        ObjectType.TABLE to LetterIcon("T", Color(0x3D7EBF)),
        ObjectType.VIEW to LetterIcon("V", Color(0x4E9A53)),
        ObjectType.MATERIALIZED_VIEW to LetterIcon("M", Color(0x2A9D8F)),
        ObjectType.PROCEDURE to LetterIcon("P", Color(0x8E5BBF)),
        ObjectType.FUNCTION to LetterIcon("F", Color(0xD1822B)),
        ObjectType.PACKAGE to LetterIcon("K", Color(0xB84A5C)),
        ObjectType.TRIGGER to LetterIcon("Z", Color(0xC9A227)),
        ObjectType.TYPE to LetterIcon("Y", Color(0x6B7A8F)),
        ObjectType.SEQUENCE to LetterIcon("S", Color(0x7A8F3D)),
        ObjectType.SYNONYM to LetterIcon("→", Color(0x8A8A8A)),
        ObjectType.INDEX to LetterIcon("I", Color(0x5E7DA8)),
    )

    val connectionOff = LetterIcon("DB", Color(0x7A7A7A))
    val connectionOn = LetterIcon("DB", Color(0x3FA34D))
    val schema = LetterIcon("S", Color(0x5B6EAE), round = true)
    val schemaCurrent = LetterIcon("S", Color(0x3FA34D), round = true)
    val console = LetterIcon("›_", Color(0x5B6EAE))
    val folder = LetterIcon("▸", Color(0x888888), round = true)

    private val column = LetterIcon("c", Color(0x4F8FBF), round = true)
    private val member = LetterIcon("m", Color(0xD1822B), round = true)
    private val keyword = LetterIcon("k", Color(0x8A8A8A), round = true)

    fun of(type: ObjectType): Icon = byType.getValue(type)

    fun forItem(item: Item): Icon = when (item.kind) {
        ItemKind.OBJECT -> item.type?.let { of(it) } ?: keyword
        ItemKind.COLUMN -> column
        ItemKind.MEMBER -> member
        ItemKind.SCHEMA -> schema
        ItemKind.KEYWORD -> keyword
    }

    /** Cor de ligacao (hyperlink) adequada ao tema. */
    fun linkColor(): Color = if (UiEnv.isDark()) Color(0x589DF6) else Color(0x2470B3)
}
