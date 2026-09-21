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

/** A category the app settled on, and whether it worked it out or merely guessed. */
data class CategoryChoice(val categoryId: Long, val inferred: Boolean)

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
 *
 * Matched as whole words, longest first - see [Categorizer.seedCategoryFor]. Order in
 * this list carries no meaning, so a rule can be added anywhere it reads well.
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
    // Both of these contain a keyword belonging to another category ("UBER",
    // "JIO"). They are here because longest-match-wins, so the specific name
    // beats the generic word it happens to start with.
    "UBER EATS" to "Food",
    "JIOMART" to "Food",
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
    "EB" to "Bills",
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

    /**
     * Each seed keyword as a whole-word matcher, longest first.
     *
     * Both halves of this are load-bearing, and the substring version had neither.
     * Whole words, because `contains` filed a gola stall and a Sholapur mess as
     * Transportation - "OLA" sits inside both - and JioMart as a phone bill. Longest
     * first, because "UBER EATS" contains "UBER" and a first-match scan would book
     * dinner as a taxi.
     *
     * Precompiled: this runs on every unrecognised payee, and 32 regexes rebuilt per
     * message is work done for nothing.
     */
    private val SEED_MATCHERS: List<Triple<Regex, String, Int>> =
        SEED_KEYWORD_RULES
            .map { (keyword, category) ->
                Triple(
                    Regex("""(?<![A-Z0-9])${Regex.escape(keyword)}(?![A-Z0-9])"""),
                    category,
                    keyword.length,
                )
            }
            .sortedByDescending { (_, _, length) -> length }

    /**
     * Which of the three tiers wins, given what each of them found.
     *
     * Pure, and separate from the lookups, because this ordering is the whole safety
     * argument for adding a model to a ledger: the model is last, so it can only ever
     * answer where the app would otherwise have given up, and no transaction that is
     * categorised correctly today can be categorised differently tomorrow. That is a
     * claim worth being able to test without a database.
     *
     * [prediction] is consulted only when it cleared its own gate. An unconfident
     * prediction is still useful for ordering a picker; it is not an answer.
     */
    fun decide(
        learnedRuleCategoryId: Long?,
        seedCategoryId: Long?,
        prediction: Prediction?,
    ): CategoryChoice? {
        // Your own past decision. Always wins, and is never marked as a guess.
        learnedRuleCategoryId?.let { return CategoryChoice(it, inferred = false) }
        // Deterministic and auditable: the same payee gives the same answer forever.
        seedCategoryId?.let { return CategoryChoice(it, inferred = false) }
        // Resemblance. Marked, reversible, and only when it is sure.
        if (prediction != null && prediction.confident) {
            return CategoryChoice(prediction.categoryId, inferred = true)
        }
        return null
    }

    /** Seeded keyword fallback, used only when no learned rule exists for this payee. */
    fun seedCategoryFor(payee: String?): String? {
        val key = payeeKey(payee) ?: return null
        return SEED_MATCHERS.firstOrNull { (pattern, _, _) -> pattern.containsMatchIn(key) }?.second
    }
}
