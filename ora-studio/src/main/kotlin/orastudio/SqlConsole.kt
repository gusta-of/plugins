package orastudio

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.DefaultHighlighter

/** Consola SQL ligada a uma sessao propria (transaccao propria), com editor, resultados e registo. */
class SqlConsolePanel(private val app: AppContext, val handle: ConnectionHandle, initialText: String?) : JPanel(BorderLayout()) {

    private var session: DbSession? = null
    private var running = false
    val busy: Boolean get() = running
    fun resultTabCount() = results.tabCount
    fun showResults() { bottom.selectedIndex = 0 }
    fun logText(): String = log.text()

    private val editor = SqlEditor(handle, { listOfNotNull(activeSchema ?: handle.currentSchema) }, { app.openObject(handle, it) })
    private val results = JTabbedPane()
    private val log = ConsoleLog()
    private val bottom = JTabbedPane()

    private val runBtn = JButton("▶ Executar")
    private val scriptBtn = JButton("▶▶ Script")
    private val stopBtn = JButton("■ Parar")
    private val commitBtn = JButton("Commit")
    private val rollbackBtn = JButton("Rollback")
    private val autoCommit = JCheckBox("Auto-commit", handle.config.autoCommit)
    private val stopOnError = JCheckBox("Parar no erro", true)
    private val limit = JComboBox(arrayOf(100, 500, 1000, 5000, 20000)).apply { selectedItem = handle.config.rowLimit.takeIf { it in 100..20000 } ?: 500 }
    /** Schema da sessão desta consola (ALTER SESSION SET CURRENT_SCHEMA); null = o do próprio utilizador. */
    @Volatile private var activeSchema: String? = null
    private var updatingSchemas = false
    private val schemaBox = JComboBox<String>().apply {
        prototypeDisplayValue = "XXXXXXXXXXXXXXXXXXXX"
        toolTipText = "Schema da consola: nomes sem prefixo (tabelas, views…) são resolvidos neste schema"
    }
    private val state = JLabel("Sem sessão")
    private val clock = JLabel(" ")
    private var started = 0L
    private val tick = Timer(250) { if (running) clock.text = "%.1f s".format((System.currentTimeMillis() - started) / 1000.0) }

    private val errorPainter = DefaultHighlighter.DefaultHighlightPainter(Color(0x66, 0x22, 0x22))
    private var errorTag: Any? = null

    private val persistFile get() = AppPaths.file("console-${handle.config.id}.sql")

    init {
        val toolbar = WrapPanel(6, 3).apply {
            add(runBtn); add(scriptBtn); add(stopBtn)
            add(JLabel("|")); add(autoCommit); add(commitBtn); add(rollbackBtn)
            add(JLabel("|")); add(JLabel("Schema:")); add(schemaBox)
            add(JLabel("|")); add(stopOnError); add(JLabel("Linhas:")); add(limit)
            add(JLabel("|")); add(state); add(clock)
        }
        runBtn.toolTipText = "Executa a instrução sob o cursor (ou a seleção) — Ctrl+Enter"
        scriptBtn.toolTipText = "Executa todo o script (ou a seleção) — Ctrl+Shift+Enter / F5"
        stopBtn.toolTipText = "Cancela a execução em curso"
        bottom.addTab("Resultados", results)
        bottom.addTab("Registo", log)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, editor.component, bottom).apply { resizeWeight = 0.5; isOneTouchExpandable = true }
        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        runBtn.addActionListener { runCurrent() }
        scriptBtn.addActionListener { runScript() }
        stopBtn.addActionListener { session?.cancelCurrent() }
        commitBtn.addActionListener { tx("COMMIT") }
        rollbackBtn.addActionListener { tx("ROLLBACK") }
        autoCommit.addActionListener { setAutoCommit(autoCommit.isSelected) }
        fillSchemas()
        handle.catalog.onChange { edt { fillSchemas() } }
        schemaBox.addActionListener { if (!updatingSchemas) changeSchema(schemaBox.selectedItem as? String) }

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "run-current") { runCurrent() }
        val ctrlShift = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx or KeyEvent.SHIFT_DOWN_MASK
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ctrlShift), "run-script") { runScript() }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "run-script-f5") { runScript() }

        // as consolas não ficam guardadas: apaga o ficheiro de versões antigas
        try { persistFile.delete() } catch (_: Exception) {}
        val saved = initialText
        editor.text = saved ?: "-- Ctrl+Enter executa a instrução sob o cursor; Ctrl+Shift+Enter executa o script todo\n"
        updateButtons()
        // ao abrir: resolver links do texto assim que houver sessao de metadados
        if (handle.connected) editor.prefetch()
    }

    private fun bindKey(ks: KeyStroke, name: String, action: () -> Unit) {
        editor.area.inputMap.put(ks, name)
        editor.area.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
        })
    }

    private fun updateButtons() {
        runBtn.isEnabled = !running
        scriptBtn.isEnabled = !running
        stopBtn.isEnabled = running
        val manual = !autoCommit.isSelected
        commitBtn.isEnabled = manual && !running
        rollbackBtn.isEnabled = manual && !running
    }

    // ---- schema ----

    private fun fillSchemas() {
        val own = handle.config.user.uppercase()
        val all = handle.catalog.schemas().orEmpty()
        val names = (listOfNotNull(handle.currentSchema ?: own) + all).distinct()
        updatingSchemas = true
        try {
            schemaBox.removeAllItems()
            names.forEach { schemaBox.addItem(it) }
            schemaBox.selectedItem = activeSchema ?: handle.currentSchema ?: own
        } finally { updatingSchemas = false }
    }

    private fun changeSchema(schema: String?) {
        if (schema == null) return
        activeSchema = if (schema == handle.currentSchema) null else schema
        editor.prefetch()
        val s = session ?: return
        s.submit { applySchema(s, schema) }.whenComplete { _, e ->
            edt {
                if (e == null) log.log("Schema da sessão: $schema", ConsoleLog.Kind.OK)
                else { log.log("Mudar de schema falhou: ${e.shortMessage()}", ConsoleLog.Kind.ERROR); bottom.selectedIndex = 1 }
            }
        }
    }

    private fun applySchema(s: DbSession, schema: String) {
        s.conn.createStatement().use { it.execute("ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdent(schema)) }
    }

    private fun quoteIdent(id: String) = if (Regex("^[A-Z][A-Z0-9_\\$#]*$").matches(id)) id else "\"" + id.replace("\"", "\"\"") + "\""

    // ---- sessao ----

    private fun withSession(then: (DbSession) -> Unit) {
        val cur = session
        if (cur != null && cur.isValid()) { then(cur); return }
        cur?.close()
        session = null
        state.text = "A ligar…"
        Bg.run({
            handle.openSession("console").also { s ->
                s.sync {
                    s.conn.autoCommit = autoCommit.isSelected
                    activeSchema?.let { applySchema(s, it) }
                }
            }
        }, { err ->
            state.text = "Sem sessão"
            log.log("Falha a ligar: ${err.shortMessage()}", ConsoleLog.Kind.ERROR)
            bottom.selectedIndex = 1
            running = false
            updateButtons()
        }) { s ->
            session = s
            state.text = "Ligado: ${handle.config.user}@${handle.name}"
            then(s)
        }
    }

    private fun setAutoCommit(on: Boolean) {
        updateButtons()
        val s = session ?: return
        s.submit { s.conn.autoCommit = on }.whenComplete { _, e ->
            if (e != null) edt { log.log("Auto-commit: ${e.shortMessage()}", ConsoleLog.Kind.ERROR) }
        }
    }

    private fun tx(cmd: String) {
        val s = session ?: return
        s.submit { if (cmd == "COMMIT") s.conn.commit() else s.conn.rollback() }.whenComplete { _, e ->
            edt {
                if (e == null) log.log("$cmd concluído", ConsoleLog.Kind.OK) else log.log("$cmd falhou: ${e.shortMessage()}", ConsoleLog.Kind.ERROR)
            }
        }
    }

    // ---- execucao ----

    fun runCurrent() {
        val area = editor.area
        val sel = area.selectedText
        val stmts = if (!sel.isNullOrBlank()) {
            SqlSplitter.split(sel).map { it.shifted(area.selectionStart) }
        } else {
            listOfNotNull(SqlSplitter.statementAt(area.text, area.caretPosition))
        }
        if (stmts.isEmpty()) { log.log("Nada para executar.", ConsoleLog.Kind.WARN); return }
        run(stmts.take(if (sel.isNullOrBlank()) 1 else stmts.size))
    }

    fun runScript() {
        val area = editor.area
        val sel = area.selectedText
        val stmts = if (!sel.isNullOrBlank()) SqlSplitter.split(sel).map { it.shifted(area.selectionStart) } else SqlSplitter.split(area.text)
        if (stmts.isEmpty()) { log.log("Nada para executar.", ConsoleLog.Kind.WARN); return }
        run(stmts)
    }

    private fun Stmt.shifted(by: Int) = Stmt(start + by, end + by, text, exec, plsql)

    private fun run(stmts: List<Stmt>) {
        if (running) return
        running = true
        updateButtons()
        clearErrorMark()
        closeResults()
        log.clear()
        started = System.currentTimeMillis()
        tick.start()
        val rowLimit = limit.selectedItem as Int
        log.log("▶ ${stmts.size} instrução(ões)…")
        var ok = 0
        var failed = 0
        var resultCount = 0
        withSession { s ->
            val listener = object : RunListener {
                override fun onStart(index: Int, total: Int, stmt: Stmt) = edt {
                    state.text = "A executar ${index + 1}/$total"
                }

                override fun onOutcome(outcome: Outcome) = edt {
                    val head = outcome.stmt.text.lineSequence().first { it.isNotBlank() }.take(90)
                    when (outcome) {
                        is QueryOutcome -> {
                            ok++
                            resultCount++
                            log.log("✔ $head  → ${outcome.rows.size}${if (outcome.cursor.exhausted) "" else "+"} linha(s) em ${outcome.elapsedMs} ms", ConsoleLog.Kind.OK)
                            val panel = ResultsPanel(s, outcome.cursor, outcome.rows, outcome.elapsedMs, rowLimit)
                            results.addTab("#$resultCount  ${head.take(24)}", panel)
                            results.selectedComponent = panel
                            bottom.selectedIndex = 0
                        }
                        is UpdateOutcome -> {
                            ok++
                            if (Regex("^(create|alter|drop|truncate|rename|grant|revoke|comment)\\b", RegexOption.IGNORE_CASE).containsMatchIn(outcome.stmt.exec.trimStart())) handle.catalog.invalidateObjects()
                            log.log("✔ $head  → ${outcome.message} (${outcome.elapsedMs} ms)", ConsoleLog.Kind.OK)
                            outcome.notes.forEach { log.log("   $it", ConsoleLog.Kind.WARN) }
                            if (outcome.notes.isNotEmpty()) bottom.selectedIndex = 1
                        }
                        is ErrorOutcome -> {
                            failed++
                            log.log("✘ $head", ConsoleLog.Kind.ERROR)
                            log.log("   ${outcome.error.message}", ConsoleLog.Kind.ERROR)
                            bottom.selectedIndex = 1
                            markError(outcome.stmt, outcome.error)
                        }
                    }
                }

                override fun onOutput(lines: List<String>) = edt {
                    log.log("— DBMS_OUTPUT —", ConsoleLog.Kind.OUTPUT)
                    lines.forEach { log.log(it, ConsoleLog.Kind.OUTPUT) }
                }

                override fun onDone(cancelled: Boolean) = edt {
                    running = false
                    tick.stop()
                    val total = (System.currentTimeMillis() - started) / 1000.0
                    clock.text = "%.1f s".format(total)
                    state.text = "Ligado: ${handle.config.user}@${handle.name}"
                    log.log(
                        (if (cancelled) "■ Cancelado. " else "") + "$ok com sucesso, $failed com erro — %.2f s".format(total),
                        if (failed > 0 || cancelled) ConsoleLog.Kind.WARN else ConsoleLog.Kind.OK,
                    )
                    updateButtons()
                    editor.prefetch()
                    app.status("Concluído: $ok ok, $failed erro(s)")
                }
            }
            s.submit { SqlRunner.run(s, stmts, rowLimit, stopOnError.isSelected, listener) }.whenComplete { _, err ->
                if (err != null) edt {
                    running = false
                    tick.stop()
                    log.log("Erro inesperado: ${err.shortMessage()}", ConsoleLog.Kind.ERROR)
                    bottom.selectedIndex = 1
                    updateButtons()
                }
            }
        }
    }

    private fun closeResults() {
        for (i in 0 until results.tabCount) (results.getComponentAt(i) as? ResultsPanel)?.dispose()
        results.removeAll()
    }

    // ---- marcar o erro no editor ----

    private fun markError(stmt: Stmt, err: DbError) {
        clearErrorMark()
        val area = editor.area
        val len = area.document.length
        val from = if (err.position >= 0) stmt.scriptOffset(err.position) else stmt.start
        val start = from.coerceIn(0, len)
        val end = if (err.position >= 0) {
            var e = start
            while (e < len && !area.text[e].isWhitespace() && area.text[e] !in ",;()") e++
            maxOf(e, minOf(start + 1, len))
        } else minOf(stmt.end, len)
        try {
            errorTag = area.highlighter.addHighlight(start, end, errorPainter)
            area.caretPosition = start
        } catch (_: Exception) {}
    }

    private fun clearErrorMark() {
        errorTag?.let { editor.area.highlighter.removeHighlight(it) }
        errorTag = null
    }

    fun focusEditor() {
        SwingUtilities.invokeLater { editor.area.requestFocusInWindow() }
    }

    /** Fecha a sessao (o texto da consola nao e guardado). */
    fun dispose() {
        tick.stop()
        closeResults()
        session?.close()
        session = null
    }
}
