package orastudio

data class CatalogObject(val name: String, val type: ObjectType)
data class CatalogColumn(val name: String, val type: String)

/** Fonte de nomes para o autocompletar. Os metodos devolvem null enquanto os dados ainda estao a carregar. */
interface Catalog {
    fun schemas(): List<String>?
    fun objects(owner: String): List<CatalogObject>?
    fun columns(owner: String, name: String): List<CatalogColumn>?
    fun members(owner: String, pkg: String): List<String>?
}

enum class ItemKind { COLUMN, OBJECT, SCHEMA, MEMBER, KEYWORD }

data class Item(val text: String, val kind: ItemKind, val type: ObjectType? = null, val detail: String = "", val right: String = "")

class CompletionResult(val prefixStart: Int, val prefix: String, val items: List<Item>, val loading: Boolean)

data class TableRef(val owner: String?, val name: String)
