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
