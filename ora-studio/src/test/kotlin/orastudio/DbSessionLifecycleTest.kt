package orastudio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ExecutionException

class DbSessionLifecycleTest {
    private fun fakeConn(): Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
        when (m.returnType) { Boolean::class.javaPrimitiveType -> false; else -> null }
    } as Connection

    @Test fun `fechar duas vezes nao falha e pedidos depois de fechar dao erro claro (nao RejectedExecutionException)`() {
        val s = DbSession(fakeConn(), "teste")
        assertEquals(7, s.sync { 7 })
        s.close()
        s.close() // 2.o fecho: antes lancava RejectedExecutionException
        assertTrue(s.closed)
        val f = s.submit { 1 }
        val e = runCatching { f.get() }.exceptionOrNull()
        assertTrue(e is ExecutionException && e.cause is IllegalStateException && e.cause!!.message!!.contains("fechada"), "erro: $e")
    }
}
