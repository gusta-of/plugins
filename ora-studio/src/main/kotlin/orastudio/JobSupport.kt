package orastudio

/** Filtro de jobs pelo nome: texto = contem (sem maiusculas); `*` = curinga; `/expressao/` = regex. Casa com `OWNER.NOME`. */
object JobFilter {
    fun matcher(text: String): (JobInfo) -> Boolean {
        val t = text.trim()
        if (t.isEmpty()) return { true }
        val re: Regex = try {
            when {
                t.length >= 2 && t.startsWith("/") && t.endsWith("/") -> Regex(t.substring(1, t.length - 1), RegexOption.IGNORE_CASE)
                t.contains('*') -> Regex(t.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
                else -> Regex(Regex.escape(t), RegexOption.IGNORE_CASE)
            }
        } catch (_: Exception) {
            Regex(Regex.escape(t), RegexOption.IGNORE_CASE) // regex invalida: trata como texto
        }
        return { j -> re.containsMatchIn("${j.owner}.${j.name}") }
    }
}

/** Texto do documento/painel de um job: agenda, estado, historico e o codigo que executa. */
object JobText {
    fun build(j: JobInfo, runs: List<JobRun>): String {
        val l = ArrayList<String>()
        l += "-- JOB ${j.owner}.${j.name}   [${if (j.kind == "scheduler") "DBMS_SCHEDULER" else "DBMS_JOB"}]"
        l += "-- Estado: ${j.state}   Ativo: ${if (j.enabled) "sim" else "nao"}" + if (j.failures > 0) "   Falhas: ${j.failures}" else ""
        l += "-- Agenda: ${j.schedule.ifBlank { "(sem)" }}"
        l += "-- Proxima execucao: ${j.nextRun.ifBlank { "—" }}"
        l += "-- Ultima execucao:  ${j.lastRun.ifBlank { "—" }}" + if (j.lastDuration.isNotBlank()) "  (duracao ${j.lastDuration})" else ""
        if (j.jobType.isNotBlank()) l += "-- Tipo: ${j.jobType}"
        if (j.comments.isNotBlank()) l += "-- Comentario: ${j.comments}"
        if (j.kind == "scheduler") {
            l += "--"
            l += "-- Historico (ultimas ${runs.size} execucoes):"
            if (runs.isEmpty()) l += "--   (nenhuma execucao registada)"
            for (r in runs) {
                l += "--   ${r.startedAt}  ${r.status.padEnd(10)} ${r.duration}" + if (r.errorCode != 0) "  ORA-${r.errorCode.toString().padStart(5, '0')}" else ""
                if (r.info.isNotBlank() && r.status != "SUCCEEDED") r.info.lineSequence().take(4).forEach { l += "--       $it" }
            }
        }
        l += "--"
        l += "-- Codigo executado:"
        l += ""
        val a = j.action.trim()
        l += if (a.endsWith(";") || a.endsWith("/")) a else "$a;"
        return l.joinToString("\n") + "\n"
    }
}
