package orastudio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Catalogo ligado a uma ligacao Oracle. Carrega em background (numa sessao propria) e guarda em cache. */
class ServerCatalog(private val handle: ConnectionHandle) : Catalog {
    private val cache = ConcurrentHashMap<String, Any>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "catalog").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Chamado (fora da EDT) sempre que chegam dados novos. */
    fun onChange(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun clear() { cache.clear() }
    fun invalidateObjects() { cache.keys.filter { it.startsWith("o:") || it.startsWith("c:") || it.startsWith("m:") }.forEach { cache.remove(it) } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, load: (java.sql.Connection) -> T): T? {
        (cache[key] as? T)?.let { return it }
        if (handle.needsPassword()) return null
        if (inflight.add(key)) {
            pool.execute {
                try {
                    val s = handle.completionSession()
                    val v = s.sync { load(s.conn) }
                    cache[key] = v as Any
                    listeners.forEach { l -> try { l() } catch (_: Exception) {} }
                } catch (t: Throwable) {
                    AppLog.error("Autocompletar: falha a carregar $key", t)
                    cache[key] = (emptyList<Any>() as Any)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    override fun schemas(): List<String>? = cached("s") { c ->
        c.prepareStatement("SELECT username FROM all_users ORDER BY username").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    override fun objects(owner: String): List<CatalogObject>? = cached("o:$owner") { c ->
        c.prepareStatement(
            "SELECT object_name, object_type FROM all_objects WHERE owner = ? AND object_type IN (${MetadataService.TYPES_IN}) AND object_name NOT LIKE 'BIN\$%'",
        ).use { ps ->
            ps.fetchSize = 500
            ps.setString(1, owner)
            ps.executeQuery().use { rs ->
                val byName = LinkedHashMap<String, CatalogObject>()
                while (rs.next()) {
                    val t = ObjectType.of(rs.getString(2)) ?: continue
                    val name = rs.getString(1)
                    val prev = byName[name]
                    // uma MV tem uma tabela com o mesmo nome: fica so a MV
                    if (prev == null || (t == ObjectType.MATERIALIZED_VIEW)) byName[name] = CatalogObject(name, t)
                }
                byName.values.sortedBy { it.name }
            }
        }
    }

    override fun columns(owner: String, name: String): List<CatalogColumn>? = cached("c:$owner.$name") { c ->
        fun cols(o: String, n: String): List<CatalogColumn> =
            c.prepareStatement("SELECT column_name, data_type FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id").use { ps ->
                ps.setString(1, o); ps.setString(2, n)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(CatalogColumn(rs.getString(1), rs.getString(2))) } }
            }
        var r = cols(owner, name)
        if (r.isEmpty()) {
            // synonym -> tabela destino
            c.prepareStatement("SELECT table_owner, table_name FROM all_synonyms WHERE owner IN (?, 'PUBLIC') AND synonym_name = ? ORDER BY DECODE(owner, 'PUBLIC', 1, 0)").use { ps ->
                ps.setString(1, owner); ps.setString(2, name)
                ps.executeQuery().use { rs -> if (rs.next() && rs.getString(1) != null) r = cols(rs.getString(1), rs.getString(2)) }
            }
        }
        r
    }

    override fun members(owner: String, pkg: String): List<String>? = cached("m:$owner.$pkg") { c ->
        c.prepareStatement("SELECT DISTINCT procedure_name FROM all_procedures WHERE owner = ? AND object_name = ? AND procedure_name IS NOT NULL ORDER BY procedure_name").use { ps ->
            ps.setString(1, owner); ps.setString(2, pkg)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}
