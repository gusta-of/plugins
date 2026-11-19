package orastudio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JobSupportTest {
    private fun job(owner: String, name: String, kind: String = "scheduler") =
        JobInfo(kind, owner, name, true, "SCHEDULED", "FREQ=DAILY;BYHOUR=3", "2026-11-06 03:00:00", "2026-11-05 03:00:00", "00:00:02", "PLSQL_BLOCK", "BEGIN pem.limpa; END;", 1, "limpeza")

    @Test fun `filtro de jobs por texto curinga regex e regex invalida`() {
        val a = job("PEM", "LIMPA_LOGS"); val b = job("PEM", "ENVIA_EMAIL"); val c = job("APP", "LIMPA_CACHE")
        fun names(f: String) = listOf(a, b, c).filter(JobFilter.matcher(f)).map { it.name }
        assertEquals(listOf("LIMPA_LOGS", "ENVIA_EMAIL", "LIMPA_CACHE"), names(""))
        assertEquals(listOf("LIMPA_LOGS", "LIMPA_CACHE"), names("limpa"))               // sem maiusculas
        assertEquals(listOf("LIMPA_LOGS"), names("limpa*logs"))                         // curinga
        assertEquals(listOf("LIMPA_CACHE"), names("app."))                              // tambem casa com o owner
        assertEquals(listOf("ENVIA_EMAIL", "LIMPA_CACHE"), names("/_(email|cache)$/"))  // regex
        assertEquals(emptyList<String>(), names("/([/"))                                // regex invalida = texto literal, sem rebentar
    }

    @Test fun `texto do job com historico e codigo`() {
        val t = JobText.build(job("PEM", "LIMPA_LOGS"), listOf(JobRun("2026-11-05 03:00:00", "FAILED", "00:00:02", 942, "ORA-00942: nao existe\nmais")))
        assertTrue(t.startsWith("-- JOB PEM.LIMPA_LOGS   [DBMS_SCHEDULER]"))
        assertTrue(t.contains("FAILED     00:00:02  ORA-00942"))
        assertTrue(t.contains("--       ORA-00942: nao existe"))
        assertTrue(t.trimEnd().endsWith("BEGIN pem.limpa; END;"))
        assertFalse(JobText.build(job("PEM", "JOB 1", "dbms_job"), emptyList()).contains("Historico"))
    }
}
