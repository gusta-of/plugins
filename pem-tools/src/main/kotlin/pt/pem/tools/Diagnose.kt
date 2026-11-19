package pt.pem.tools

/** Falha conhecida de build, reconhecida pelo texto que o Maven escreveu. [hash] e o objeto git que nao foi encontrado. */
data class Diagnosis(val hash: String?, val summary: String)

object BuildDiagnosis {
    const val GIT_COMMIT_ID_SKIP = "-Dmaven.gitcommitid.skip=true"
    private val MISSING = Regex("Missing (?:tree|commit|blob|object)\\s+([0-9a-f]{7,40})", RegexOption.IGNORE_CASE)

    /**
     * git-commit-id-plugin (2.2.6) com um JGit antigo nao le os packs `loose-*.pack` que o `git maintenance` cria
     * (multi-pack-index): falha com «Missing tree <hash>» mesmo com o objeto presente no repositorio.
     */
    fun diagnose(output: String): Diagnosis? {
        if (!output.contains("git-commit-id", ignoreCase = true)) return null
        val m = MISSING.find(output) ?: return null
        val h = m.groupValues[1].lowercase()
        return Diagnosis(h, "O git-commit-id-plugin nao consegue ler o repositorio git (Missing tree ${h.take(10)}...). " +
            "Costuma acontecer com packs «loose-*» criados pelo git maintenance; o objeto existe, e o JGit antigo do plugin que nao o le.")
    }

    /** Junta [flag] aos parametros de «todos os modulos» (sem repetir). */
    fun withGlobalArg(args: Map<String, String>, flag: String): Map<String, String> {
        val cur = (args[MavenArgs.ALL] ?: "").trim()
        if (flag in cur.split(Regex("\\s+"))) return args
        return args + (MavenArgs.ALL to (if (cur.isEmpty()) "" else "$cur ") + flag)
    }
}
