package pt.pem.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.AbstractCellEditor
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/** Editor das regras de realce: uma linha por regra (expressao, cores, linha toda / so o trecho, bloco). */
class HighlightDialog(project: Project?) : DialogWrapper(project, true) {

    private val rules = HighlightStore.rules.map { it.copy() }.toMutableList()

    private val model = object : AbstractTableModel() {
        private val cols = listOf("Ativa", "Nome", "Expressao", "Modo", "Aa", "Linha toda", "Fim do bloco", "Fundo", "Texto", "Negrito")
        override fun getRowCount() = rules.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = when (c) { 0, 4, 5, 9 -> java.lang.Boolean::class.java; else -> String::class.java }
        override fun isCellEditable(r: Int, c: Int) = true
        override fun getValueAt(r: Int, c: Int): Any = rules[r].let {
            when (c) { 0 -> it.enabled; 1 -> it.name; 2 -> it.pattern; 3 -> MODES.firstOrNull { m -> m.first == it.effectiveMode() }?.second ?: "Contem"; 4 -> it.caseSensitive; 5 -> it.wholeLine
                6 -> it.blockEnd; 7 -> it.background; 8 -> it.foreground; else -> it.bold }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            val it = rules[r]
            when (c) {
                0 -> it.enabled = v as Boolean; 1 -> it.name = v as String; 2 -> it.pattern = v as String; 3 -> { it.mode = MODES.first { m -> m.second == v }.first; it.regex = it.mode == "regex" }
                4 -> it.caseSensitive = v as Boolean; 5 -> it.wholeLine = v as Boolean; 6 -> it.blockEnd = v as String
                7 -> it.background = v as String; 8 -> it.foreground = v as String; 9 -> it.bold = v as Boolean
            }
            fireTableCellUpdated(r, c)
        }
    }

    private val table = JBTable(model)

    private companion object {
        val MODES = listOf("contains" to "Contem", "starts" to "Comeca com", "ends" to "Termina com", "equals" to "Igual a", "regex" to "Regex")
    }

    init {
        title = "Realce de cores nos logs"
        table.setShowGrid(true)
        table.rowHeight = JBUI.scale(24)
        table.columnModel.getColumn(3).cellEditor = javax.swing.DefaultCellEditor(javax.swing.JComboBox(MODES.map { it.second }.toTypedArray()))
        val colorCol = ColorCell()
        for (c in listOf(7, 8)) {
            table.columnModel.getColumn(c).cellRenderer = ColorSwatchRenderer()
            table.columnModel.getColumn(c).cellEditor = colorCol
        }
        table.columnModel.getColumn(2).cellRenderer = PatternRenderer { r -> rules.getOrNull(r) }
        listOf(0 to 50, 1 to 150, 2 to 260, 3 to 90, 4 to 40, 5 to 80, 6 to 110, 7 to 60, 8 to 60, 9 to 60).forEach { (i, w) ->
            table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w)
        }
        init()
    }

    /** Celula de cor: quadrado com a cor; clique abre o seletor. */
    private inner class ColorCell : AbstractCellEditor(), TableCellEditor {
        private var current = ""
        private val swatch = ColorSwatch()

        override fun getTableCellEditorComponent(t: JTable?, v: Any?, sel: Boolean, r: Int, c: Int): Component {
            current = v as? String ?: ""
            // abre o seletor depois de o editor estar montado
            javax.swing.SwingUtilities.invokeLater {
                val chosen = JColorChooser.showDialog(t, "Escolher cor", parseHex(current) ?: Color.WHITE)
                if (chosen != null) current = String.format("#%02X%02X%02X", chosen.red, chosen.green, chosen.blue)
                stopCellEditing()
            }
            swatch.color = parseHex(current)
            return swatch
        }

        override fun getCellEditorValue(): Any = current
    }

    override fun createCenterPanel(): JComponent {
        val add = JButton("Adicionar").apply { addActionListener {
            table.cellEditor?.stopCellEditing()
            rules += HighlightRule(name = "Nova regra", pattern = "", mode = "contains", regex = false, background = "#7A5C00", foreground = "#FFFFFF")
            this@HighlightDialog.model.fireTableRowsInserted(rules.size - 1, rules.size - 1)
            table.setRowSelectionInterval(rules.size - 1, rules.size - 1)
        } }
        val remove = JButton("Remover").apply { addActionListener {
            table.cellEditor?.stopCellEditing()
            val r = table.selectedRow
            if (r >= 0) { rules.removeAt(r); this@HighlightDialog.model.fireTableDataChanged() }
        } }
        fun move(d: Int) {
            table.cellEditor?.stopCellEditing()
            val r = table.selectedRow
            val to = r + d
            if (r < 0 || to !in rules.indices) return
            rules.add(to, rules.removeAt(r)); this@HighlightDialog.model.fireTableDataChanged(); table.setRowSelectionInterval(to, to)
        }
        val up = JButton("Subir").apply { addActionListener { move(-1) } }
        val down = JButton("Descer").apply { addActionListener { move(1) } }
        val reset = JButton("Repor predefinicoes").apply { addActionListener {
            val ok = javax.swing.JOptionPane.showConfirmDialog(table,
                "Repor as predefinicoes?\n\nTodas as regras desta lista serao substituidas pelas de origem (as suas regras, como as de SOAP e JSON,\ndeixam de estar na lista). So fica definitivo ao carregar em OK.",
                "Repor predefinicoes", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE)
            if (ok != javax.swing.JOptionPane.YES_OPTION) return@addActionListener
            table.cellEditor?.stopCellEditing()
            rules.clear(); rules += HighlightDefaults.rules(); this@HighlightDialog.model.fireTableDataChanged()
        } }
        val help = JLabel("<html>Modo: Contem / Comeca com / Termina com / Igual a (em texto, * e curinga) ou Regex. A 1.ª regra que casar com a linha ganha (por ordem). «Fim do bloco»: pinta da linha que casa até à que casa com essa 2.ª expressão. " +
            "Sem «Linha toda» só o trecho que casa é pintado. Cores: duplo clique na célula. Só afeta texto novo.</html>")
        val sizeLabel = JLabel("${HighlightStore.fontSize} pt")
        val slider = javax.swing.JSlider(HighlightStore.MIN_FONT, HighlightStore.MAX_FONT, HighlightStore.fontSize).apply {
            majorTickSpacing = 4; minorTickSpacing = 1; paintTicks = true
            toolTipText = "Tamanho da fonte do terminal (aplica-se logo e fica guardado)"
            addChangeListener { sizeLabel.text = "$value pt"; HighlightStore.setFontSize(value) }
        }
        val fontRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("Tamanho da fonte do terminal:")); add(slider); add(sizeLabel) }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(450))
            add(JPanel(BorderLayout()).apply { add(fontRow, BorderLayout.NORTH); add(help, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(add); add(remove); add(up); add(down); add(reset) }, BorderLayout.SOUTH)
        }
    }

    override fun doOKAction() {
        table.cellEditor?.stopCellEditing()
        HighlightStore.save(rules.filter { it.pattern.isNotEmpty() || it.name.isNotEmpty() })
        super.doOKAction()
    }
}

fun parseHex(h: String): Color? = try { if (h.isBlank()) null else Color.decode(h) } catch (_: Exception) { null }

/** Quadrado de cor desenhado a mao (nao depende do fundo/hover/selecao da tabela). */
class ColorSwatch : JComponent() {
    var color: Color? = null
    var rowBackground: Color = Color.WHITE
    var tip: String? = null

    override fun paint(g: java.awt.Graphics) {
        val c = color
        g.color = c ?: rowBackground
        g.fillRect(0, 0, width, height)
        // o codigo da cor fica sempre escrito (legivel sobre a cor), mesmo que a tabela repinte a celula
        val label = if (c == null) "—" else String.format("#%02X%02X%02X", c.red, c.green, c.blue)
        g.color = if (c == null) Color.GRAY else if ((c.red * 299 + c.green * 587 + c.blue * 114) / 1000 > 140) Color.BLACK else Color.WHITE
        g.font = font ?: g.font
        val fm = g.fontMetrics
        g.drawString(label, (width - fm.stringWidth(label)) / 2, (height + fm.ascent - fm.descent) / 2)
    }
}

class ColorSwatchRenderer : TableCellRenderer {
    private val swatch = ColorSwatch()
    override fun getTableCellRendererComponent(t: JTable?, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        val hex = v as? String ?: ""
        swatch.color = parseHex(hex)
        swatch.rowBackground = t?.background ?: Color.WHITE
        swatch.toolTipText = hex.ifEmpty { "sem cor (usa a da consola)" }
        return swatch
    }
}

/** Texto da expressao com os curingas que o modo implica: Contem = *x*, Comeca com = x*, Termina com = *x, Igual a = x. */
fun decoratePattern(rule: HighlightRule): String {
    val p = rule.pattern
    return when (rule.effectiveMode()) {
        "contains" -> "*$p*"
        "starts" -> "$p*"
        "ends" -> "*$p"
        else -> p
    }
}

class PatternRenderer(private val ruleAt: (Int) -> HighlightRule?) : TableCellRenderer {
    private val label = JLabel()
    override fun getTableCellRendererComponent(t: JTable?, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        val rule = ruleAt(r)
        label.text = if (rule != null) decoratePattern(rule) else (v as? String).orEmpty()
        label.toolTipText = if (rule != null && rule.effectiveMode() != "regex") "modo ${rule.effectiveMode()}: * = qualquer texto" else null
        label.isOpaque = true
        label.background = if (sel) t?.selectionBackground else t?.background
        label.foreground = if (sel) t?.selectionForeground else t?.foreground
        label.font = t?.font
        label.border = javax.swing.BorderFactory.createEmptyBorder(0, 3, 0, 3)
        return label
    }
}
