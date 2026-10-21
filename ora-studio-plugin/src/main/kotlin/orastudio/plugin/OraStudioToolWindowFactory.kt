package orastudio.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import orastudio.ConfigStore
import orastudio.MainPanel
import orastudio.UiEnv

/** Janela "Ora Studio": o mesmo painel do programa independente, partilhando as suas ligacoes (~/.config/orastudio). */
class OraStudioToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        UiEnv.isDark = { !JBColor.isBright() }
        UiEnv.scale = { JBUI.scale(it) }
        ConfigStore.load()

        val panel = MainPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, Disposable { panel.dispose() })
        toolWindow.contentManager.addContent(content)
    }
}
