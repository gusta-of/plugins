package pt.pem.tools

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PemToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val settings = PemSettings.getInstance().state

        val taskConsole = TaskRunner.newConsole()
        val wlConsole = TaskRunner.newConsole()
        val runner = TaskRunner(project, taskConsole)
        val wlRunner = TaskRunner(project, wlConsole)
        val ops = Operations(runner, wlRunner)

        // ---- campos ----
        val workspaceField = pathField(project, settings.workspacePath, "Pasta do workspace (ex.: PEM_Aplicacao)") { settings.workspacePath = it }
        val domainField = pathField(project, settings.domainPath, "Pasta do dominio WebLogic (ex.: .../domains/PEM)") { settings.domainPath = it }
        val profileBox = ComboBox(Modules.mavenProfiles.toTypedArray()).apply {
            isEditable = true
            selectedItem = settings.mavenProfile
            addActionListener { settings.mavenProfile = (selectedItem as? String).orEmpty().trim() }
        }

        val threadsBox = ComboBox(Modules.threadOptions.toTypedArray()).apply {
            isEditable = false
            toolTipText = "Threads do Build Frontend rapido (mvn -T)"
            selectedItem = Modules.sanitizeThreads(settings.threads).takeIf { it in Modules.threadOptions } ?: "1C"
            addActionListener { settings.threads = Modules.sanitizeThreads((selectedItem as? String).orEmpty()) }
        }
        fun threads() = Modules.sanitizeThreads((threadsBox.selectedItem as? String).orEmpty())

        val skipTests = JBCheckBox("Pular testes", settings.skipTests).apply {
            toolTipText = "Acrescenta -Dmaven.test.skip=true aos builds"
            addActionListener { settings.skipTests = isSelected }
        }
        ops.skipTests = { skipTests.isSelected }

        val localMock = JBCheckBox("localMock", settings.localMock).apply {
            toolTipText = "Adiciona -PlocalMock ao build do modulo pem-common-domain (e so a ele)"
            addActionListener { settings.localMock = isSelected }
        }
        ops.localMock = { localMock.isSelected }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Workspace:"), workspaceField)
            .addLabeledComponent(JBLabel("AdminServer:"), domainField)
            .addLabeledComponent(JBLabel("Perfil Maven:"), JPanel(BorderLayout()).apply { add(profileBox, BorderLayout.WEST) })
            .addComponent(JPanel(WrapLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(skipTests)
                add(localMock)
                add(JBLabel("Threads (-T):"))
                add(threadsBox)
            })
            .panel

        fun paths(): Paths? {
            val wsText = workspaceField.text.trim()
            val domText = domainField.text.trim()
            val ws = runCatching { Path.of(wsText) }.getOrDefault(Path.of(""))
            val dom = runCatching { Path.of(domText) }.getOrDefault(Path.of(""))
            val problem = when {
                wsText.isEmpty() -> "Indica o caminho do workspace"
                domText.isEmpty() -> "Indica o caminho do AdminServer (dominio WebLogic)"
                !Files.isDirectory(ws) -> "Workspace invalido: $ws"
                !Files.isDirectory(dom) -> "Dominio WebLogic invalido: $dom"
                else -> null
            }
            if (problem != null) {
                notify(project, problem, NotificationType.ERROR)
                return null
            }
            return Paths(ws, dom)
        }

        fun profile() = (profileBox.selectedItem as? String).orEmpty().trim()

        fun button(text: String, tip: String, action: () -> Unit) =
            JButton(text).apply { toolTipText = tip; addActionListener { action() } }

        fun withPaths(block: (Paths) -> Unit) = { paths()?.let(block); Unit }

        val tabs = JBTabbedPane().apply {
            addTab("Tarefas", taskConsole)
            addTab("WebLogic", wlConsole)
        }

        fun selectTab(title: String) {
            val i = tabs.indexOfTab(title)
            if (i >= 0) tabs.selectedIndex = i
        }

        // ---- botao "travado" (vermelho) enquanto o processo que ele iniciou corre ----
        fun lock(b: JButton) {
            b.putClientProperty("locked", true)
            b.putClientProperty("JButton.backgroundColor", JBColor(Color(0xC62828), Color(0xB71C1C)))
            b.foreground = Color.WHITE
            b.repaint()
        }
        fun unlock(b: JButton) {
            b.putClientProperty("locked", null)
            b.putClientProperty("JButton.backgroundColor", null)
            b.foreground = null
            b.repaint()
        }
        fun JButton.isLocked() = getClientProperty("locked") == true

        val active = mutableMapOf<TaskRunner, JButton>()
        for (r in listOf(runner, wlRunner)) {
            r.onFinished = { SwingUtilities.invokeLater { active.remove(r)?.let(::unlock) } }
        }

        /** Botao de tarefa: abre a consola certa, e fica vermelho/bloqueado enquanto a tarefa corre. */
        fun tb(icon: Icon, tip: String, text: String = "", wl: Boolean = false, action: () -> Unit): JButton {
            val b = iconButton(icon, tip, text) {}
            val r = if (wl) wlRunner else runner
            b.addActionListener {
                if (b.isLocked()) return@addActionListener
                val before = r.busy
                action()
                if (!before && r.busy) {
                    active[r] = b
                    lock(b)
                }
                if (!before) selectTab(if (wl) "WebLogic" else "Tarefas")
            }
            return b
        }

        // ---- botoes compactos (icone + tooltip) ----
        val toolbar = JPanel(WrapLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(group("WebLogic") {
                val startBtn = iconButton(AllIcons.Actions.Execute, "WebLogic: Start (startWebLogic)") {}
            startBtn.addActionListener {
                if (startBtn.isLocked()) return@addActionListener
                withPaths { ops.startWebLogic(it) }()
                selectTab("WebLogic")
                if (ops.weblogicRunning) lock(startBtn)
            }
            ops.onWebLogicExit = { SwingUtilities.invokeLater { unlock(startBtn) } }
            add(startBtn)
                add(tb(AllIcons.Actions.Suspend, "WebLogic: Stop - gracioso e depois forca a paragem", wl = true, action = withPaths { ops.stopWebLogic(it) }))
                add(tb(AllIcons.Actions.Cancel, "WebLogic: Kill - termina logo todos os processos do dominio (sem stop gracioso)", wl = true, action = withPaths { ops.killWebLogic(it) }))
            })
            add(group("Limpeza") {
                add(tb(AllIcons.Actions.GC, "Limpar cache/classpath (clear_classpath.sh): cache WL, targets, ~/.m2 pem. O servidor tem de estar PARADO", action = withPaths { p ->
                val ok = Messages.showYesNoDialog(project,
                    "O AdminServer (e managed servers) estao PARADOS?\nVai apagar cache do dominio, todas as pastas target/ e ~/.m2/.../modules/pem (exceto fonts).",
                    "Limpar cache/classpath", Messages.getWarningIcon())
                if (ok == Messages.YES) ops.clearClasspath(p)
            }))
                add(tb(AllIcons.Actions.DeleteTag, "Limpar pastas target/ (clean-targets.sh)", action = withPaths { ops.cleanTargets(it) }))
                add(tb(AllIcons.Debugger.KillProcess, "Matar processos java dos modulos (kill-module-jars.sh)") { ops.killModuleJars() })
            })
            add(group("Build") {
                add(tb(AllIcons.Actions.Compile, "Build Backend (build-backend.sh)", "BE", action = withPaths { ops.buildBackend(it, profile()) }))
                add(tb(AllIcons.Actions.Compile, "Build Frontend (mvn clean install em spms-plugins)", "FE", action = withPaths { ops.buildFrontend(it, profile(), fast = false) }))
                add(tb(AllIcons.Actions.Compile, "Build Frontend rapido (usa o campo Threads -T; padrao 1C)", "FE ⚡", action = withPaths { ops.buildFrontend(it, profile(), fast = true, threads = threads()) }))
                add(tb(AllIcons.Nodes.Module, "Build de modulos isolados (varios; backend e/ou frontend)...", action = withPaths { p ->
                val d = ModulePickerDialog(project, "Build isolado", "Build", Modules.buildEntries())
                if (d.showAndGet()) ops.buildModules(p, d.selected(), profile())
            }))
            })
            add(group("Deploy") {
                add(tb(AllIcons.Actions.Upload, "Deploy Backend (varios modulos)...", "BE", action = withPaths { p ->
                val d = DeployDialog(project, "Deploy Backend", Modules.backendDeploy)
                if (d.showAndGet()) ops.deployBackend(p, d.selected())
            }))
                add(tb(AllIcons.Actions.Upload, "Deploy Frontend (varios modulos)...", "FE", action = withPaths { p ->
                val d = DeployDialog(project, "Deploy Frontend", Modules.frontendDeploy.keys.toList())
                if (d.showAndGet()) ops.deployFrontend(p, d.selected())
            }))
                add(tb(AllIcons.Nodes.Module, "Deploy de modulos isolados (varios; backend e/ou frontend)...", action = withPaths { p ->
                val d = ModulePickerDialog(project, "Deploy isolado", "Deploy", Modules.deployEntries())
                if (d.showAndGet()) ops.deployModules(p, d.selected())
            }))
            })
            add(group("Tarefa") {
                add(iconButton(AllIcons.Actions.Suspend, "Cancelar a tarefa em curso") { runner.cancel() })
            })
        }
        toolbar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (toolbar.width != lastWidth) {
                    lastWidth = toolbar.width
                    toolbar.revalidate()
                }
            }
        })
        val buttons = toolbar


        // ---- progresso (percentagem do build/deploy em curso) ----
        val progress = JProgressBar(0, 100).apply { isVisible = false }
        val progressText = JBLabel(" ").apply { isVisible = false }
        // Depois de terminar, a barra fica 5s no ecra e esconde-se; reaparece na proxima tarefa.
        val hideTimer = javax.swing.Timer(5000) {
            progress.isVisible = false
            progressText.isVisible = false
        }.apply { isRepeats = false }
        val progressListener: (Int, Int, String) -> Unit = { done, total, label ->
            SwingUtilities.invokeLater {
                hideTimer.stop()
                progress.isVisible = true
                progressText.isVisible = true
                if (label.isEmpty()) hideTimer.restart()
                when {
                    total == 0 -> { progress.isIndeterminate = true; progressText.text = label }
                    label.isEmpty() -> {
                        progress.isIndeterminate = false
                        progress.value = done * 100 / total
                        progressText.text = "${progress.value}%  ($done/$total) concluido"
                    }
                    else -> {
                        progress.isIndeterminate = false
                        progress.value = done * 100 / total
                        progressText.text = "${progress.value}%  ($done/$total)  $label"
                    }
                }
            }
        }

        runner.onProgress = progressListener
        wlRunner.onProgress = progressListener
        val detachButton = iconButton(AllIcons.Actions.MoveToWindow, "Desacoplar a aba selecionada numa janela solta") { detach(tabs) }
        val bottom = JPanel(BorderLayout()).apply {
            add(progress, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(progressText, BorderLayout.CENTER)
                add(detachButton, BorderLayout.EAST)
            }, BorderLayout.CENTER)
        }
        val consoles = JPanel(BorderLayout()).apply {
            add(bottom, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(form, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
        }
        val root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(consoles, BorderLayout.CENTER)
        }

        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
    }

    /** Move a aba selecionada para uma janela solta; ao fechar a janela, a aba volta ao sitio. */
    private fun detach(tabs: JBTabbedPane) {
        val idx = tabs.selectedIndex
        if (idx < 0) return
        val title = tabs.getTitleAt(idx)
        val comp = tabs.getComponentAt(idx)
        tabs.removeTabAt(idx)
        val frame = JFrame("PEM WL Tools - $title").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            contentPane.add(comp, BorderLayout.CENTER)
            preferredSize = Dimension(1000, 650)
            pack()
            setLocationRelativeTo(null)
        }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                frame.contentPane.remove(comp)
                val at = idx.coerceAtMost(tabs.tabCount)
                tabs.insertTab(title, null, comp, null, at)
                tabs.selectedIndex = at
            }
        })
        frame.isVisible = true
    }

    /** Grupo de botoes precedido por um unico rotulo de categoria (quebra de linha como um bloco). */
    private fun group(title: String, fill: JPanel.() -> Unit) = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(2), 0).apply { alignOnBaseline = true }).apply {
        add(JBLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        })
        fill()
    }

    private fun pathField(project: Project, initial: String, hint: String, onChange: (String) -> Unit) = TextFieldWithBrowseButton().apply {
        text = initial
        (textField as? com.intellij.ui.components.JBTextField)?.emptyText?.text = hint
        toolTipText = hint
        addActionListener {
            // API estavel em todas as versoes (addBrowseFolderListener mudou de assinatura entre 241 e 252)
            val start = text.trim().takeIf { it.isNotEmpty() }?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, start)?.let { text = it.path }
        }
        textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChange(text.trim())
            override fun removeUpdate(e: DocumentEvent) = onChange(text.trim())
            override fun changedUpdate(e: DocumentEvent) = onChange(text.trim())
        })
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("PEM WL Tools")
            .createNotification(msg, type).notify(project)
    }
}
