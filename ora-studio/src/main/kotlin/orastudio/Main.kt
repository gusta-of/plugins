package orastudio

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.util.UIScale
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    ConfigStore.load()
    val light = (System.getenv("ORASTUDIO_THEME") ?: ConfigStore.prefs.theme).equals("light", ignoreCase = true)
    System.setProperty("flatlaf.menuBarEmbedded", "false")
    UIManager.put("Component.arc", 8)
    UIManager.put("Button.arc", 8)
    UIManager.put("TextComponent.arc", 6)
    UIManager.put("ScrollBar.thumbArc", 999)
    UIManager.put("ScrollBar.width", 12)
    UIManager.put("TabbedPane.showTabSeparators", true)
    if (light) FlatLightLaf.setup() else FlatDarkLaf.setup()
    UiEnv.isDark = { FlatLaf.isLafDark() }
    UiEnv.scale = { UIScale.scale(it) }
    SwingUtilities.invokeLater { MainFrame().isVisible = true }
}
