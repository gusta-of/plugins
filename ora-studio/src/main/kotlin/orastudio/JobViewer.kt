package orastudio

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

/**
 * Painel de um job agendado: cabecalho, historico das ultimas execucoes e o codigo que executa (so leitura, com
 * realce SQL). Mostra uma barra de progresso enquanto o historico e pedido a base de dados.
 */
class JobViewerPanel(private val app: AppContext, val handle: ConnectionHandle, private val job: JobInfo) : JPanel(BorderLayout()) {

    private val editor = SqlEditor(handle, { listOfNotNull(job.owner, handle.currentSchema).distinct() }, { app.openObject(handle, it) }, editable = false)
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private val loadingLabel = JLabel("A carregar o job ${job.owner}.${job.name}…", SwingConstants.CENTER)
    private val refresh = JButton("Atualizar")
    @Volatile private var seq = 0

    val text: String get() = editor.text
    @Volatile var loaded = false
        private set

    init {
        val loading = JPanel(BorderLayout(0, 8)).apply {
            add(loadingLabel, BorderLayout.CENTER)
            add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.SOUTH)
            border = javax.swing.BorderFactory.createEmptyBorder(px(40), px(80), px(40), px(80))
        }
        body.add(loading, "loading")
        body.add(editor.component, "text")
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("<html><b>${job.owner}.${job.name}</b> — ${if (job.kind == "scheduler") "DBMS_SCHEDULER" else "DBMS_JOB"}</html>"))
            add(refresh)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        refresh.addActionListener { load() }
        load()
    }

    fun load() {
        val my = ++seq
        loaded = false
        cards.show(body, "loading")
        refresh.isEnabled = false
        Bg.run({
            handle.metaSession()
            val runs = if (job.kind == "scheduler") handle.metadata.jobRuns(job.owner, job.name) else emptyList()
            JobText.build(job, runs)
        }, { err ->
            if (my != seq) return@run
            editor.text = "-- Nao foi possivel obter o job ${job.owner}.${job.name}:\n-- ${err.shortMessage().lineSequence().joinToString("\n-- ")}\n"
            loaded = true
            cards.show(body, "text"); refresh.isEnabled = true
        }) { text ->
            if (my != seq) return@run
            editor.text = text
            editor.area.caretPosition = 0
            loaded = true
            cards.show(body, "text"); refresh.isEnabled = true
        }
    }
}
