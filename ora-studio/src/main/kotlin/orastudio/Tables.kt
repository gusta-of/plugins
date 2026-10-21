package orastudio

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private fun displayText(s: String): String = s.replace("\r\n", " ↵ ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

/** Grelha de resultados de uma consulta, com "carregar mais" sobre o cursor ainda aberto. */
class ResultsPanel(
    private val session: DbSession,
    private val cursor: ResultCursor,
    initial: List<Array<String?>>,
    private val elapsedMs: Long,
    private val pageSize: Int,
) : JPanel(BorderLayout()) {

    private val columns = cursor.columns
    private val rows = ArrayList<Array<String?>>(initial)
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size + 1
        override fun getColumnName(c: Int) = if (c == 0) "#" else columns[c - 1].name
        override fun getValueAt(r: Int, c: Int): Any? = if (c == 0) r + 1 else rows[r][c - 1]
    }
    private val table = JTable(model)
    private val info = JLabel(" ")
    private val moreBtn = JButton("Carregar mais")
    private val allBtn = JButton("Carregar tudo")

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setCellSelectionEnabled(true)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.columnModel.columnSelectionAllowed = true
        table.tableHeader.reorderingAllowed = false
        table.setShowGrid(true)
        table.gridColor = UIManager.getColor("Table.gridColor") ?: Color.GRAY
        table.setDefaultRenderer(Any::class.java, CellRenderer())
        table.tableHeader.defaultRenderer = HeaderRenderer(table.tableHeader.defaultRenderer, columns)
        installCopy()
        installPopup()
        sizeColumns()

        moreBtn.addActionListener { loadMore(pageSize) }
        allBtn.addActionListener { loadMore(Int.MAX_VALUE / 2) }
        val bar = WrapPanel(6, 2).apply {
            add(info)
            add(moreBtn)
            add(allBtn)
            add(JButton("Exportar CSV…").apply { addActionListener { exportCsv() } })
        }
        add(JScrollPane(table), BorderLayout.CENTER)
        add(bar, BorderLayout.SOUTH)
        refreshInfo()
    }

    private fun refreshInfo() {
        val more = if (cursor.exhausted) "" else " (há mais)"
        info.text = "${rows.size} linha(s)$more  •  ${columns.size} coluna(s)  •  $elapsedMs ms"
        moreBtn.isEnabled = !cursor.exhausted
        allBtn.isEnabled = !cursor.exhausted
    }

    private fun loadMore(n: Int) {
        moreBtn.isEnabled = false
        allBtn.isEnabled = false
        info.text = "A carregar…"
        session.submit {
            // em blocos, para a UI ir vendo as linhas a chegar
            var left = n
            while (left > 0 && !cursor.exhausted) {
                val chunk = cursor.fetch(minOf(left, 1000))
                left -= chunk.size
                edt {
                    val first = rows.size
                    rows.addAll(chunk)
                    if (chunk.isNotEmpty()) model.fireTableRowsInserted(first, rows.size - 1)
                    info.text = "A carregar… ${rows.size}"
                }
                if (session.cancelRequested) break
            }
        }.whenComplete { _, err ->
            edt {
                refreshInfo()
                if (err != null) info.text = "Erro: ${err.shortMessage()}"
            }
        }
    }

    fun dispose() {
        session.submit { cursor.close() }
    }

    private fun sizeColumns() {
        val fm = table.getFontMetrics(table.font)
        val hfm = table.tableHeader.getFontMetrics(table.tableHeader.font)
        for (c in 0..columns.size) {
            val col = table.columnModel.getColumn(c)
            if (c == 0) { col.preferredWidth = fm.stringWidth(rows.size.toString().padEnd(5, '0')) + 20; continue }
            var w = hfm.stringWidth(columns[c - 1].name) + 28
            for (r in 0 until minOf(rows.size, 80)) {
                val v = rows[r][c - 1] ?: "[NULL]"
                w = maxOf(w, fm.stringWidth(displayText(v.take(200))) + 16)
            }
            col.preferredWidth = w.coerceIn(60, 420)
        }
    }

    // ---- copiar / exportar ----

    private fun selectionText(withHeaders: Boolean): String {
        val rs = table.selectedRows
        val cs = table.selectedColumns.filter { it > 0 }
        if (rs.isEmpty() || cs.isEmpty()) return ""
        val sb = StringBuilder()
        if (withHeaders) sb.append(cs.joinToString("\t") { columns[it - 1].name }).append('\n')
        for (r in rs) sb.append(cs.joinToString("\t") { rows[r][it - 1] ?: "" }).append('\n')
        return sb.toString().trimEnd('\n')
    }

    private fun copy(withHeaders: Boolean) {
        val t = selectionText(withHeaders)
        if (t.isNotEmpty()) Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(t), null)
    }

    private fun installCopy() {
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "orastudio-copy")
        table.actionMap.put("orastudio-copy", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = copy(false)
        })
    }

    private fun installPopup() {
        val menu = JPopupMenu().apply {
            add(JMenuItem("Copiar").apply { addActionListener { copy(false) } })
            add(JMenuItem("Copiar com cabeçalhos").apply { addActionListener { copy(true) } })
            addSeparator()
            add(JMenuItem("Exportar CSV…").apply { addActionListener { exportCsv() } })
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybe(e)
            override fun mouseReleased(e: MouseEvent) = maybe(e)
            private fun maybe(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val r = table.rowAtPoint(e.point)
                val c = table.columnAtPoint(e.point)
                if (r >= 0 && c >= 0 && !table.isCellSelected(r, c)) table.changeSelection(r, c, false, false)
                menu.show(table, e.x, e.y)
            }
        })
    }

    private fun exportCsv() {
        val fc = JFileChooser(ConfigStore.prefs.lastDir?.let { File(it) })
        fc.selectedFile = File("resultado.csv")
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val f = fc.selectedFile
        ConfigStore.prefs.lastDir = f.parent
        try {
            f.bufferedWriter(Charsets.UTF_8).use { w ->
                fun q(s: String?): String = if (s == null) "" else if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
                w.write("﻿")
                w.write(columns.joinToString(";") { q(it.name) } + "\r\n")
                rows.forEach { r -> w.write(r.joinToString(";") { q(it) } + "\r\n") }
            }
            JOptionPane.showMessageDialog(this, "${rows.size} linhas exportadas para\n${f.absolutePath}\n(apenas as linhas já carregadas)")
        } catch (e: Exception) {
            showError(this, "Exportar CSV", e)
        }
    }

    private inner class CellRenderer : DefaultTableCellRenderer() {
        private val nullFont: Font by lazy { table.font.deriveFont(Font.ITALIC) }

        override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, column: Int): Component {
            val c = super.getTableCellRendererComponent(t, value, sel, focus, row, column) as DefaultTableCellRenderer
            val normalFg = if (sel) t.selectionForeground else t.foreground
            c.foreground = normalFg
            c.border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
            when {
                column == 0 -> {
                    c.text = value.toString()
                    c.horizontalAlignment = SwingConstants.RIGHT
                    c.foreground = if (sel) normalFg else Color.GRAY
                    c.font = t.font
                    c.toolTipText = null
                }
                value == null -> {
                    c.text = "[NULL]"
                    c.font = nullFont
                    c.foreground = if (sel) normalFg else Color.GRAY
                    c.horizontalAlignment = SwingConstants.LEFT
                    c.toolTipText = null
                }
                else -> {
                    val s = value.toString()
                    c.text = displayText(s.take(2000))
                    c.font = t.font
                    c.horizontalAlignment = if (columns[column - 1].numeric) SwingConstants.RIGHT else SwingConstants.LEFT
                    c.toolTipText = if (s.length > 40 || s.contains('\n')) "<html><pre>" + s.take(1500).replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>" else null
                }
            }
            return c
        }
    }

    private class HeaderRenderer(private val base: TableCellRenderer, private val cols: List<ColumnMeta>) : TableCellRenderer {
        override fun getTableCellRendererComponent(t: JTable, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int): Component {
            val comp = base.getTableCellRendererComponent(t, v, s, f, r, c)
            (comp as? JLabel)?.toolTipText = if (c > 0) cols[c - 1].name + " — " + cols[c - 1].typeName else null
            return comp
        }
    }
}

/** Valor de celula que representa um objecto navegavel. */
data class LinkCell(val ref: ObjectRef, val text: String = ref.qualified)

/** Tabela simples e so de leitura; celulas [LinkCell] sao links azuis sublinhados (um clique abre). */
class SimpleTable(headers: List<String>, rows: List<List<Any?>>, private val onOpen: (ObjectRef) -> Unit) : JScrollPane() {
    val table: JTable

    init {
        val model = object : AbstractTableModel() {
            override fun getRowCount() = rows.size
            override fun getColumnCount() = headers.size
            override fun getColumnName(c: Int) = headers[c]
            override fun getValueAt(r: Int, c: Int): Any? = rows[r][c]
        }
        table = JTable(model)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setCellSelectionEnabled(true)
        table.tableHeader.reorderingAllowed = false
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, column: Int): Component {
                val c = super.getTableCellRendererComponent(t, if (value is LinkCell) value.text else value?.toString().orEmpty().replace('\n', ' '), sel, focus, row, column)
                c.foreground = if (sel) t.selectionForeground else t.foreground
                if (value is LinkCell) {
                    if (!sel) c.foreground = Icons.linkColor()
                    c.font = t.font.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
                } else {
                    c.font = t.font
                }
                (c as JLabel).toolTipText = (value?.toString() ?: "").takeIf { it.length > 40 }
                return c
            }
        })
        val fm = table.getFontMetrics(table.font)
        for (c in headers.indices) {
            var w = fm.stringWidth(headers[c]) + 30
            for (r in 0 until minOf(rows.size, 60)) {
                val v = rows[r][c]
                w = maxOf(w, fm.stringWidth(if (v is LinkCell) v.text else v?.toString().orEmpty().take(120)) + 20)
            }
            table.columnModel.getColumn(c).preferredWidth = w.coerceIn(70, 520)
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val r = table.rowAtPoint(e.point); val c = table.columnAtPoint(e.point)
                if (r < 0 || c < 0) return
                (table.getValueAt(r, c) as? LinkCell)?.let { onOpen(it.ref) }
            }
        })
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val r = table.rowAtPoint(e.point); val c = table.columnAtPoint(e.point)
                table.cursor = if (r >= 0 && c >= 0 && table.getValueAt(r, c) is LinkCell) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
        setViewportView(table)
    }
}
