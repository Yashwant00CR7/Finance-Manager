package com.yk.finance.domain

import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn

/**
 * A category as the app seeds it.
 *
 * [isIncome] is not cosmetic: income categories are excluded from expense pickers,
 * quick-add chips and budget targets, so "Salary" can never be selected as somewhere
 * money went.
 *
 * [sharing] is the other behavioural one: picking such a category opens the split
 * sheet, because a payment made for somebody else is usually only partly for them.
 */
data class SeedCategory(
    val name: String,
    val isIncome: Boolean = false,
    val sharing: Sharing = Sharing.NONE,
)

/**
 * The category vocabulary, taken from My Money Pro verbatim.
 *
 * Adopted rather than invented so that the import maps one-to-one and so that years of
 * habit carry over - a category named something else is a category you hesitate over.
 * Groceries is deliberately absent: fruit and eggs were always filed under Food there,
 * and a category you never pick is worse than no category at all.
 */
val SEED_CATEGORIES = listOf(
    SeedCategory("Food"),
    SeedCategory("Transportation"),
    SeedCategory("Shopping"),
    SeedCategory("Bills"),
    SeedCategory("Entertainment"),
    SeedCategory("Education"),
    SeedCategory("Health and Fitness"),
    SeedCategory("Home"),
    SeedCategory("Sport"),
    SeedCategory("Clothing"),
    // The two that mean somebody else's money. GIVEN is ordinary spending that happened
    // to be for someone else and counts everywhere Food does; LENT is money you are
    // holding on someone's behalf, so it is carried as owed to you instead of as
    // expenditure. Both offer to split the bill, because you rarely pay only their share.
    SeedCategory("For Others", sharing = Sharing.GIVEN),
    SeedCategory("For Friend Return Later", sharing = Sharing.LENT),
    SeedCategory(UNCATEGORISED),
    SeedCategory("Salary", isIncome = true),
    SeedCategory("From Parents", isIncome = true),
    SeedCategory("Awards", isIncome = true),
    SeedCategory("Refunds", isIncome = true),
)

const val UNCATEGORISED = "Uncategorised"

/**
 * v1 category names and where they go now.
 *
 * Applied once at startup as a rename, so every transaction already filed keeps its
 * category id and nothing has to be recategorised by hand. Where the target name
 * already exists the two are merged instead.
 */
val CATEGORY_RENAMES: Map<String, String> = mapOf(
    "Food & Drink" to "Food",
    "Groceries" to "Food",
    "Transport" to "Transportation",
    "Bills & Utilities" to "Bills",
    "Health" to "Health and Fitness",
    "Rent" to "Home",
    "Transfers to people" to "For Others",
)

/**
 * Seeded merchant keywords. Deliberately small: your real payees are mostly UPI
 * strings that no generic list can predict ("paytmqr 1a2b3cd"), so the app is built
 * to learn from one correction rather than to ship a big dictionary.
 */
val SEED_KEYWORD_RULES: List<Pair<String, String>> = listOf(
    "SWIGGY" to "Food",
    "ZOMATO" to "Food",
    "JUICE" to "Food",
    "CAFE" to "Food",
    "HOTEL" to "Food",
    "RESTAURANT" to "Food",
    "CANTEEN" to "Food",
    "BAKERY" to "Food",
    "BIGBASKET" to "Food",
    "BLINKIT" to "Food",
    "ZEPTO" to "Food",
    "DMART" to "Food",
    "UBER" to "Transportation",
    "OLA" to "Transportation",
    "RAPIDO" to "Transportation",
    "IRCTC" to "Transportation",
    "PETROL" to "Transportation",
    "FUEL" to "Transportation",
    "AMAZON" to "Shopping",
    "FLIPKART" to "Shopping",
    "MYNTRA" to "Clothing",
    "AIRTEL" to "Bills",
    "JIO" to "Bills",
    "ELECTRICITY" to "Bills",
    "EB " to "Bills",
    "NETFLIX" to "Entertainment",
    "SPOTIFY" to "Entertainment",
    "BOOKMYSHOW" to "Entertainment",
    "PHARMACY" to "Health and Fitness",
    "APOLLO" to "Health and Fitness",
    "HOSPITAL" to "Health and Fitness",
    "MEDICAL" to "Health and Fitness",
)

object Categorizer {

    /**
     * The key a category rule is stored under. ICICI truncates payees at 15 characters
     * ("SRI LAKSHMI TRA", "KA 05 JUICE BAR") - but it truncates *consistently*, so the
     * truncated string is a stable key. Normalising case and whitespace is enough.
     */
    fun payeeKey(payee: String?): String? =
        payee?.trim()?.uppercase()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotBlank() }

    /**
     * Which already-recorded rows a newly learned rule may file, by id.
     *
     * Two bounds, and the app was missing both. Without the category bound, learning a
     * rule reached back through the whole ledger and overwrote decisions you had made
     * by hand: file a shop as For Others in August, tap Food there in September, and
     * August silently became Food - a total you had already read, moved by something
     * you did a month later. Without matching on the normalised [payeeKey], the rule
     * and its own back-fill disagreed about which rows even belonged to the payee, one
     * comparing raw bank text and the other a trimmed, uppercased key.
     *
     * The row you tapped is excluded because it is already filed and was never in
     * question - which is also what lets Undo put things back without arguing with you
     * about the one decision you definitely meant.
     */
    fun backfillTargets(candidates: List<Txn>, key: String, exceptId: Long): List<Long> =
        candidates
            .filter { it.id != exceptId && it.categoryId == null && payeeKey(it.payee) == key }
            .map { it.id }

    /** Seeded keyword fallback, used only when no learned rule exists for this payee. */
    fun seedCategoryFor(payee: String?): String? {
        val key = payeeKey(payee) ?: return null
        return SEED_KEYWORD_RULES.firstOrNull { (keyword, _) -> key.contains(keyword) }?.second
    }
}
