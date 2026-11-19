package pt.pem.tools

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.regex.Pattern

/**
 * Regra de realce dos logs (versao simplificada do «Highlighting» do Grep Console).
 * [blockEnd] preenchido -> a regra pinta um BLOCO: da linha que casa com [pattern] ate a linha que casa com [blockEnd] (inclusive).
 */
data class HighlightRule(
    var enabled: Boolean = true,
    var name: String = "",
    var pattern: String = "",
    /** legado (antes de existir [mode]): true = regex, false = texto */
    var regex: Boolean = true,
    /** contains | starts | ends | equals | regex  (vazio = deduz de [regex]); fora de regex, '*' e um curinga */
    var mode: String = "",
    var caseSensitive: Boolean = false,
    var wholeLine: Boolean = true,
    var blockEnd: String = "",
    var background: String = "",
    var foreground: String = "",
    var bold: Boolean = false,
)

fun HighlightRule.effectiveMode(): String = mode.ifBlank { if (regex) "regex" else "contains" }

/** Expressao Java equivalente ao texto da regra, conforme o modo (prefixo, sufixo, contem, igual, regex). */
fun HighlightRule.toRegex(): String {
    val m = effectiveMode()
    if (m == "regex") return pattern
    val body = pattern.split('*').joinToString(".*") { Pattern.quote(it) }
    return when (m) { "starts" -> "^$body"; "ends" -> "$body$"; "equals" -> "^$body$"; else -> body }
}

data class RuleStyle(val foreground: String, val background: String, val bold: Boolean)

/** Um troco de texto com o estilo a aplicar (null = o estilo normal da consola). */
data class Seg(val text: String, val style: RuleStyle?)

object HighlightDefaults {
    fun rules(): List<HighlightRule> = listOf(
        HighlightRule(name = "WebLogic <Error> (bloco)", pattern = "<Error>", mode = "contains", blockEnd = ">\\s*$", background = "#B71C1C", foreground = "#FFFFFF"),
        HighlightRule(name = "Erro", mode = "regex", pattern = "\\b(FATAL|SEVERE|ERROR)\\b|BUILD FAILURE|Caused by:", background = "#7F1D1D", foreground = "#FFFFFF"),
        HighlightRule(name = "Aviso", mode = "regex", pattern = "<Warning>|\\bWARN(ING)?\\b", background = "#7A5C00", foreground = "#FFF3C4"),
        HighlightRule(name = "Sucesso", mode = "regex", pattern = "BUILD SUCCESS|Server state changed to RUNNING|BEA-149074", background = "#1B5E20", foreground = "#FFFFFF"),
        HighlightRule(name = "Estado do servidor", mode = "regex", pattern = "Server state changed to \\w+", wholeLine = false, background = "#0D47A1", foreground = "#FFFFFF", bold = true),
    )
}

/**
 * Aplica as regras linha a linha (com estado, por causa dos blocos). Nao e thread-safe: use um por consola
 * e sincronize. Regras com regex invalida sao ignoradas.
 */
class HighlightEngine(rules: List<HighlightRule>) {
    private class Compiled(val rule: HighlightRule, val p: Pattern, val end: Pattern?) {
        val style = RuleStyle(rule.foreground, rule.background, rule.bold)
    }

    private var compiled: List<Compiled> = compile(rules)
    private var block: Compiled? = null

    fun setRules(rules: List<HighlightRule>) {
        compiled = compile(rules)
        block = null
    }

    private fun compile(rules: List<HighlightRule>): List<Compiled> = rules.mapNotNull { r ->
        if (!r.enabled || r.pattern.isEmpty()) return@mapNotNull null
        try {
            val flags = if (r.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            val p = Pattern.compile(r.toRegex(), flags)
            val end = r.blockEnd.takeIf { it.isNotEmpty() }?.let { Pattern.compile(it, flags) }
            Compiled(r, p, end)
        } catch (_: Exception) { null }
    }

    /** [line] sem o terminador de linha. */
    fun segments(line: String): List<Seg> {
        val open = block
        if (open != null) {
            if (open.end!!.matcher(line).find()) block = null
            return listOf(Seg(line, open.style))
        }
        for (c in compiled) {
            if (!c.p.matcher(line).find()) continue
            if (c.end != null) {
                if (!c.end.matcher(line).find()) block = c
                return listOf(Seg(line, c.style))
            }
            if (c.rule.wholeLine) return listOf(Seg(line, c.style))
        }
        // so os trechos que casam (sem sobreposicao; a primeira regra tem prioridade)
        val taken = ArrayList<Triple<Int, Int, RuleStyle>>()
        for (c in compiled) {
            if (c.end != null || c.rule.wholeLine) continue
            val m = c.p.matcher(line)
            while (m.find()) {
                if (m.end() == m.start()) continue
                if (taken.none { m.start() < it.second && m.end() > it.first }) taken += Triple(m.start(), m.end(), c.style)
            }
        }
        if (taken.isEmpty()) return listOf(Seg(line, null))
        taken.sortBy { it.first }
        val out = ArrayList<Seg>()
        var pos = 0
        for ((s, e, st) in taken) {
            if (s > pos) out += Seg(line.substring(pos, s), null)
            out += Seg(line.substring(s, e), st)
            pos = e
        }
        if (pos < line.length) out += Seg(line.substring(pos), null)
        return out
    }
}

/** Regras partilhadas por todas as consolas (guardadas nas definicoes do plugin). */
object HighlightStore {
    private val gson = Gson()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile var rules: List<HighlightRule> = emptyList()
        private set

    fun parse(json: String): List<HighlightRule>? = try {
        gson.fromJson<List<HighlightRule>>(json, object : TypeToken<List<HighlightRule>>() {}.type)
    } catch (_: Exception) { null }

    const val MIN_FONT = 8
    const val MAX_FONT = 28
    @Volatile var fontSize = 12
        private set

    /** Muda (e guarda) o tamanho da fonte das consolas. */
    fun setFontSize(size: Int) {
        fontSize = size.coerceIn(MIN_FONT, MAX_FONT)
        PemSettings.getInstance().state.logFontSize = fontSize
        listeners.forEach { it() }
    }

    fun load() {
        fontSize = PemSettings.getInstance().state.logFontSize.coerceIn(MIN_FONT, MAX_FONT)
        val json = PemSettings.getInstance().state.highlightRules
        rules = (if (json.isBlank()) null else parse(json)) ?: HighlightDefaults.rules()
    }

    fun save(newRules: List<HighlightRule>) {
        rules = newRules.map { it.copy() }
        PemSettings.getInstance().state.highlightRules = gson.toJson(rules)
        listeners.forEach { it() }
    }

    fun onChange(l: () -> Unit) { listeners += l }
    fun offChange(l: () -> Unit) { listeners -= l }
}
