package com.yk.finance.importer

/**
 * A known export format.
 *
 * A profile is data, never code: it corrects the heuristics for a file we have already
 * seen, and a file we have not seen still imports through [GENERIC]. That is the
 * difference between "supports My Money Pro" and "supports exports".
 */
data class ImportProfile(
    val name: String,
    /** Normalised header names that identify this format. Empty matches nothing. */
    val signature: Set<String>,
    /** Source category name (normalised) to the name this app should use. */
    val categoryAliases: Map<String, String> = emptyMap(),
    /** Category cells that mean "no category", usually on transfer rows. */
    val categoryPlaceholders: Set<String> = emptySet(),
    val columnOverrides: Map<String, Canonical> = emptyMap(),
) {
    fun matches(headers: List<String>): Boolean {
        if (signature.isEmpty()) return false
        val normalised = headers.map(ColumnMap::normalise).toSet()
        return signature.all { it in normalised }
    }

    fun category(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val key = trimmed.lowercase().trim()
        if (key in categoryPlaceholders) return null
        // Strip a leading sort hack - ".Clothing" sorts to the top of an alphabetical
        // list, which is a property of the old app's UI and not part of the name.
        val cleaned = trimmed.trimStart('.', '*', '#').trim()
        return categoryAliases[ColumnMap.normalise(trimmed)] ?: cleaned.ifEmpty { null }
    }
}

object ImportProfiles {

    /**
     * My Money Pro. Quirks encoded rather than coded around: a trailing space after
     * every closing quote, a header of `"NOTES" `, `(-)/(+)/(*)` type prefixes,
     * `A->B` transfer routes in the account column, and `  -  ` as the category on a
     * transfer.
     */
    val MY_MONEY_PRO = ImportProfile(
        name = "My Money Pro",
        signature = setOf("time", "type", "amount", "category", "account", "notes"),
        categoryAliases = mapOf(
            "clothing" to "Clothing",
            // Fruit and eggs were always filed under Food there; this app has no
            // Groceries category for exactly that reason.
            "groceries" to "Food",
        ),
        categoryPlaceholders = setOf("-", "--", "---", "n/a", "none"),
    )

    val GENERIC = ImportProfile(
        name = "Generic",
        signature = emptySet(),
        categoryPlaceholders = setOf("-", "--", "n/a", "none", "uncategorized", "uncategorised"),
    )

    private val KNOWN = listOf(MY_MONEY_PRO)

    fun forHeaders(headers: List<String>): ImportProfile =
        KNOWN.firstOrNull { it.matches(headers) } ?: GENERIC
}
