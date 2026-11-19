package pt.pem.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "PemToolsSettings", storages = [Storage("pem-tools.xml")])
class PemSettings : PersistentStateComponent<PemSettings.Data> {

    class Data {
        var workspacePath: String = ""
        var domainPath: String = ""
        var mavenProfile: String = ""
        var skipTests: Boolean = false
        var localMock: Boolean = false
        var threads: String = "1C"
        /** regras de realce dos logs (JSON); vazio = predefinicoes */
        var highlightRules: String = ""
        /** parametros extra do mvn: `*` = todos os modulos, ou o nome do modulo */
        var mavenArgs: MutableMap<String, String> = HashMap()
        /** tamanho da fonte das consolas de log (pt) */
        var logFontSize: Int = 12
        /** quebra de linha nas consolas de log */
        var logWrap: Boolean = true
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
    }

    companion object {
        fun getInstance(): PemSettings = ApplicationManager.getApplication().getService(PemSettings::class.java)
    }
}
