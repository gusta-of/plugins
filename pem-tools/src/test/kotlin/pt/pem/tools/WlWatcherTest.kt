package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WlWatcherTest {
    private fun domain(): Path {
        val d = Files.createTempDirectory("wl")
        Files.createDirectories(d.resolve("config"))
        Files.writeString(d.resolve("config/config.xml"),
            "<domain><app-deployment><name>liferay</name></app-deployment><app-deployment><name>pem-crd-portlet</name></app-deployment><app-deployment><name>integration-portlet</name></app-deployment></domain>")
        return d
    }

    @Test fun `le os artefactos esperados do config xml`() {
        assertEquals(listOf("liferay", "pem-crd-portlet", "integration-portlet"), expectedApps(domain()))
    }

    @Test fun `espera, a carregar, pronto e falha com detalhe`() {
        val w = WlWatcher()
        w.start(domain())
        assertEquals(3, w.counts().loading)
        w.feed("<21/out/2026 16H11m UTC> <Notice> <WebLogicServer> <BEA-000365> <Server state changed to STANDBY.>")
        assertEquals("STANDBY", w.serverState)
        w.feed("<BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_NEW to STATE_PREPARED on server AdminServer.>", "file")
        assertEquals(AppState.LOADING, w.snapshot().first { it.name == "pem-crd-portlet" }.state)
        w.feed("<BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>", "file")
        assertEquals(AppState.READY, w.snapshot().first { it.name == "pem-crd-portlet" }.state)

        w.feed("<21/out/2026 16H11m UTC> <Error> <Deployer> <BEA-149205> <Failed to initialize the application \"integration-portlet\" due to error weblogic.application.ModuleException: Error: Unresolved Webapp Library references")
        w.feed("weblogic.application.ModuleException: Error: Unresolved Webapp Library")
        w.feed("\tat weblogic.servlet.internal.WebAppModule.init(WebAppModule.java:284)")
        w.feed("<21/out/2026 16H11m UTC> <Notice> <WebLogicServer> <BEA-000365> <Server state changed to RUNNING.>")
        val err = w.errorOf("integration-portlet")!!
        assertTrue(err.contains("Unresolved Webapp Library") && err.contains("WebAppModule.init"))
        assertEquals("RUNNING", w.serverState)
        // um artefacto que falhou nao volta a "pronto" por uma transicao posterior
        w.feed("<BEA-149060> <Module integration-portlet of application integration-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>")
        assertEquals(AppState.FAILED, w.snapshot().first { it.name == "integration-portlet" }.state)
        assertEquals(WlCounts(1, 1, 1, 3), w.counts())
        w.stop()
    }

    @Test fun `segue o ficheiro de log novo e a rotacao`() {
        val d = domain()
        val logs = d.resolve("servers/AdminServer/logs")
        Files.createDirectories(logs)
        val f = logs.resolve("AdminServer.log")
        Files.writeString(f, "<BEA-149060> <Module liferay of application liferay successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n")
        val w = WlWatcher()
        w.start(d)
        Files.writeString(f, Files.readString(f) + "<BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n")
        Thread.sleep(2300)
        assertEquals(AppState.PENDING, w.snapshot().first { it.name == "liferay" }.state)
        assertEquals(AppState.READY, w.snapshot().first { it.name == "pem-crd-portlet" }.state)
        Files.writeString(f, "<BEA-149060> <Module liferay of application liferay successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n")
        Thread.sleep(2300)
        assertEquals(AppState.READY, w.snapshot().first { it.name == "liferay" }.state)
        w.stop()
    }
}
