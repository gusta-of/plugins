package orastudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {

    @Test fun `urls geradas`() {
        val sid = ConnectionConfig(host = "10.0.0.1", port = 1521, database = "PEMQA")
        assertEquals("jdbc:oracle:thin:@10.0.0.1:1521:PEMQA", sid.effectiveUrl())
        val svc = ConnectionConfig(type = ConnType.SERVICE_NAME.name, host = "h", port = 1522, database = "svc.example")
        assertEquals("jdbc:oracle:thin:@//h:1522/svc.example", svc.effectiveUrl())
        val custom = ConnectionConfig(host = "h", database = "x", url = "jdbc:oracle:thin:@(DESCRIPTION=...)")
        assertEquals("jdbc:oracle:thin:@(DESCRIPTION=...)", custom.effectiveUrl())
    }

    @Test fun `password cifrada ida e volta`() {
        val c = ConnectionConfig()
        c.setPassword("s3cr3t-ç!")
        assertNotEquals("s3cr3t-ç!", c.passwordEnc)
        assertEquals("s3cr3t-ç!", c.password())
        c.setPassword(null)
        assertNull(c.password())
    }

    @Test fun `copyAs gera novo id`() {
        val a = ConnectionConfig(name = "A")
        val b = a.copyAs("B")
        assertNotEquals(a.id, b.id)
        assertEquals("B", b.name)
    }
}

class ConfigPersistenceTest {
    private fun file(n: String) = AppPaths.file(n)

    @Test fun `ligacoes sobrevivem a recarregar, e cada gravacao deixa copia da versao anterior`() {
        ConfigStore.load(force = true)
        ConfigStore.connections.clear()
        ConfigStore.connections.add(ConnectionConfig(name = "A", host = "h1", database = "X", user = "u").also { it.setPassword("p1") })
        ConfigStore.saveConnections()
        ConfigStore.connections.add(ConnectionConfig(name = "B", host = "h2", database = "Y", user = "v"))
        ConfigStore.saveConnections()
        assertTrue(Backups.all("connections.json").isNotEmpty(), "a segunda gravacao guarda a primeira versao")

        ConfigStore.load(force = true)
        assertEquals(listOf("A", "B"), ConfigStore.connections.map { it.name })
        assertEquals("p1", ConfigStore.connections[0].password())
    }

    @Test fun `ficheiro corrompido recupera da copia de seguranca e guarda o estragado`() {
        ConfigStore.load(force = true)
        ConfigStore.connections.clear()
        ConfigStore.connections.add(ConnectionConfig(name = "PEM-DEV", host = "h", database = "X", user = "u"))
        ConfigStore.saveConnections()
        ConfigStore.connections.add(ConnectionConfig(name = "PEM-QUA", host = "q", database = "Y", user = "u"))
        ConfigStore.saveConnections()
        file("connections.json").writeText("{ isto nao e json [[[")
        ConfigStore.load(force = true)
        assertTrue(ConfigStore.connections.isNotEmpty(), "recuperou de uma copia")
        assertTrue(ConfigStore.connections.any { it.name == "PEM-DEV" })
        assertTrue(AppPaths.dir.resolve("backups").toFile().listFiles { f -> f.name.contains(".corrupt.") }!!.isNotEmpty(), "guardou o ficheiro estragado")
    }

    @Test fun `ficheiro apagado e recuperado da copia`() {
        ConfigStore.load(force = true)
        ConfigStore.connections.clear()
        ConfigStore.connections.add(ConnectionConfig(name = "X1", host = "h", database = "X", user = "u"))
        ConfigStore.saveConnections()
        ConfigStore.connections.add(ConnectionConfig(name = "X2", host = "h", database = "X", user = "u"))
        ConfigStore.saveConnections()
        file("connections.json").delete()
        ConfigStore.load(force = true)
        assertTrue(ConfigStore.connections.any { it.name == "X1" })
    }

    @Test fun `chave apagada e reposta da copia (as palavras-passe continuam legiveis)`() {
        val c = ConnectionConfig().also { it.setPassword("segredo") } // cria a chave e a sua copia
        assertTrue(Backups.latest("secret.key") != null)
        file("secret.key").delete()
        assertEquals("segredo", c.password())
    }
}
