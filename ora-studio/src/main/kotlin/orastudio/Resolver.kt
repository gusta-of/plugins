package orastudio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Resolve identificadores do texto para objectos da BD, com cache. A resolucao faz-se em lote e em
 * background ([prefetch]); [cached] devolve so o que ja esta resolvido (usado pelo hover Ctrl+rato).
 */
class ObjectResolver(private val handle: ConnectionHandle) {

    private val cache = ConcurrentHashMap<String, List<ObjectRef>>()
    @Volatile private var schemas: Set<String>? = null
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "resolver").apply { isDaemon = true } }

    fun clear() {
        cache.clear()
        schemas = null
    }

    private fun key(owners: List<String>, qualifier: String?, name: String) = owners.joinToString(",") + "|" + qualifier.orEmpty() + "|" + name

    /** null = ainda nao resolvido; lista vazia = nao e um objecto. */
    fun cached(owners: List<String>, qualifier: String?, name: String): List<ObjectRef>? = cache[key(owners, qualifier, name)]

    /** Resolve ja (bloqueia ate a BD responder); usado pelo "ir para a definicao" do VS Code. */
    fun resolveNow(owners: List<String>, qualifier: String?, name: String): List<ObjectRef> {
        cached(owners, qualifier, name)?.let { return it }
        resolveBatch(listOf(qualifier to name), owners)
        return cached(owners, qualifier, name) ?: emptyList()
    }

    fun prefetch(text: String, owners: List<String>, done: () -> Unit = {}) {
        val refs = SqlTokens.extractRefs(text).filter { cache[key(owners, it.first, it.second)] == null }
        if (refs.isEmpty()) return
        pool.execute {
            try {
                resolveBatch(refs, owners)
                done()
            } catch (_: Exception) {
                // sem ligacao / sem permissoes: simplesmente nao ha links
            }
        }
    }

    private fun schemaSet(): Set<String> = schemas ?: handle.metadata.listSchemas().toSet().also { schemas = it }

    private fun resolveBatch(refs: List<Pair<String?, String>>, owners: List<String>) {
        val schemaNames = schemaSet()
        val session = handle.metaSession()

        // 1) nomes sem qualificador: no(s) owner(s) por omissao, depois PUBLIC
        val unqualified = refs.filter { it.first == null }.map { it.second }.distinct()
        val searchOwners = (owners + "PUBLIC").distinct()
        val found = HashMap<String, MutableList<ObjectRef>>()
        for (chunk in unqualified.chunked(300)) {
            queryNames(session, chunk, searchOwners).forEach { found.getOrPut(it.name) { mutableListOf() }.add(it) }
        }
        for (name in unqualified) {
            val all = found[name].orEmpty()
            val best = searchOwners.firstNotNullOfOrNull { o -> all.filter { it.owner == o }.takeIf { it.isNotEmpty() } }.orEmpty()
            cache[key(owners, null, name)] = best.sortedBy { it.type.ordinal.let { o -> if (it.type == ObjectType.MATERIALIZED_VIEW) -1 else o } }
        }

        // 2) SCHEMA.OBJECTO
        val qualified = refs.filter { it.first != null }
        for ((q, name) in qualified) {
            val k = key(owners, q, name)
            if (cache[k] != null) continue
            if (q!! !in schemaNames) {
                cache[k] = emptyList()
                continue
            }
            val hits = session.sync { handle.metadata.findExactOn(session.conn, q, name) }
            cache[k] = hits.sortedBy { if (it.type == ObjectType.MATERIALIZED_VIEW) -1 else it.type.ordinal }
        }
    }

    private fun queryNames(session: DbSession, names: List<String>, owners: List<String>): List<ObjectRef> = session.sync {
        val inNames = names.joinToString(",") { "?" }
        val inOwners = owners.joinToString(",") { "?" }
        session.conn.prepareStatement(
            "SELECT owner, object_name, object_type FROM all_objects WHERE object_name IN ($inNames) AND owner IN ($inOwners) " +
                "AND object_type IN (${MetadataService.TYPES_IN})",
        ).use { ps ->
            var i = 1
            names.forEach { ps.setString(i++, it) }
            owners.forEach { ps.setString(i++, it) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<ObjectRef>()
                while (rs.next()) ObjectType.of(rs.getString(3))?.let { out += ObjectRef(rs.getString(1), rs.getString(2), it) }
                out
            }
        }
    }
}
