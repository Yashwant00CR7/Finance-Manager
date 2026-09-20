package com.yk.finance.domain

import com.yk.finance.data.AccountKind

/**
 * What a category or account looks like: an icon key and a colour.
 *
 * Keys, not drawables - this is domain code, and the mapping from key to a Compose
 * ImageVector lives in the UI layer (ui/Look.kt). Keeping it a string means the choice
 * survives in the database, in a CSV export, and in a backup taken on another build
 * where the icon set has grown.
 *
 * The seeded pairs deliberately match the app they were copied from, so a ledger that
 * moved across still reads at a glance: Food is red cutlery, Transportation a blue bus,
 * Bills a near-black receipt.
 */
object Looks {

    /** Every colour the pickers offer. Chosen to stay legible on both themes. */
    val PALETTE = listOf(
        "#E53935", // red
        "#D81B60", // pink
        "#8E24AA", // purple
        "#5E35B1", // deep purple
        "#3949AB", // indigo
        "#1E88E5", // blue
        "#00ACC1", // cyan
        "#00897B", // teal
        "#43A047", // green
        "#2E7D32", // dark green
        "#F9A825", // amber
        "#FB8C00", // orange
        "#6D4C41", // brown
        "#546E7A", // blue grey
        "#212121", // near black
        "#78909C", // grey
    )

    /** Every icon the pickers offer, by key. Rendered by ui/Look.kt. */
    val ICONS = listOf(
        "restaurant", "coffee", "cart", "bus", "car", "fuel", "flight", "train",
        "receipt", "bolt", "water", "phone", "wifi", "home", "chair", "build",
        "movie", "music", "sports", "gym", "game", "book", "school", "work",
        "health", "pill", "pets", "child", "gift", "people", "person", "handshake",
        "savings", "card", "cash", "wallet", "bank", "atm", "trending", "tag",
    )

    private val CATEGORY_LOOKS: Map<String, Pair<String, String>> = mapOf(
        "Food" to ("restaurant" to "#E53935"),
        "Transportation" to ("bus" to "#3949AB"),
        "Shopping" to ("cart" to "#00ACC1"),
        "Bills" to ("receipt" to "#212121"),
        "Entertainment" to ("movie" to "#5E35B1"),
        "Education" to ("school" to "#1E88E5"),
        "Health and Fitness" to ("health" to "#D81B60"),
        "Home" to ("home" to "#00897B"),
        "Sport" to ("sports" to "#43A047"),
        "Clothing" to ("tag" to "#FB8C00"),
        "For Others" to ("people" to "#2E7D32"),
        "For Friend Return Later" to ("handshake" to "#6D4C41"),
        UNCATEGORISED to ("tag" to "#78909C"),
        "Salary" to ("work" to "#2E7D32"),
        "From Parents" to ("gift" to "#00897B"),
        "Awards" to ("trending" to "#F9A825"),
        "Refunds" to ("savings" to "#43A047"),
    )

    private val ACCOUNT_LOOKS: Map<String, Pair<String, String>> = mapOf(
        "card" to ("card" to "#E53935"),
        "cash" to ("cash" to "#43A047"),
        "salary" to ("bank" to "#FB8C00"),
        "savings" to ("savings" to "#D81B60"),
        "wallet" to ("wallet" to "#5E35B1"),
    )

    fun categoryIcon(name: String): String = CATEGORY_LOOKS[name]?.first ?: "tag"

    fun categoryColour(name: String): String =
        CATEGORY_LOOKS[name]?.second ?: colourFromName(name)

    fun accountIcon(displayName: String, kind: AccountKind): String {
        ACCOUNT_LOOKS[displayName.trim().lowercase()]?.let { return it.first }
        return if (kind == AccountKind.CASH) "cash" else "card"
    }

    fun accountColour(displayName: String, kind: AccountKind): String {
        ACCOUNT_LOOKS[displayName.trim().lowercase()]?.let { return it.second }
        return if (kind == AccountKind.CASH) "#43A047" else colourFromName(displayName)
    }

    /**
     * A stable colour for a name nothing was seeded for.
     *
     * Deterministic on purpose: a category you invent today must look the same
     * tomorrow, on a restored backup, and on a reinstall. Uses a fixed hash rather than
     * String.hashCode() so it cannot drift with the platform.
     */
    fun colourFromName(name: String): String {
        var hash = 7
        name.trim().lowercase().forEach { c -> hash = hash * 31 + c.code }
        val index = ((hash % PALETTE.size) + PALETTE.size) % PALETTE.size
        return PALETTE[index]
    }
}
