package com.yk.finance.domain

import com.yk.finance.data.Txn

/**
 * The model's opinion about one transaction.
 *
 * [confident] is the only part the ingestor acts on. Everything else is for ordering a
 * picker, where being wrong costs a scroll rather than a wrong total.
 */
data class Prediction(
    val categoryId: Long,
    /**
     * How far ahead of the runner-up, in nats.
     *
     * Deliberately not normalised by evidence count, and that is what makes the gate
     * behave correctly across banks: a payee-rich ICICI row brings twenty-odd features
     * and can open a wide margin, while a payee-less Union row brings six and mostly
     * cannot. The threshold does not need to know which bank it is looking at - a row
     * with little evidence fails it on its own.
     */
    val margin: Double,
    /** Passed every gate, so the app may file it - marked as a guess. */
    val confident: Boolean,
)

/**
 * Categorisation by resemblance, for the payees no rule can predict.
 *
 * Sits last in SmsIngestor.resolveCategory: a learned rule is your own past decision
 * and a seed keyword is deterministic, so both win. This only ever fires where the app
 * would otherwise have given up, which is why turning it on cannot make any existing
 * behaviour worse.
 *
 * A projection over the ledger rather than a stored thing, the same way
 * QuickAddSuggestion is. Nothing to migrate, nothing to keep in sync, nothing that can
 * disagree with the transactions it was built from - and a Snapshot restore is correct
 * for free, because the model is rebuilt from whichever database is now there.
 */
class CategoryModel {

    private val bayes = NaiveBayes()

    /**
     * Whether the model may file a category, as opposed to merely ranking one.
     *
     * Gates [Prediction.confident] rather than [predict] itself, which is the whole
     * distinction the Settings toggle is about: turning it off should stop the app
     * writing to your ledger, not stop it putting the likely category at the top of a
     * picker. Ordering a list wrong costs a scroll.
     */
    @Volatile var autoFileEnabled: Boolean = true

    val totalExamples: Int get() = synchronized(bayes) { bayes.totalExamples }

    /**
     * Rebuild from the categorised ledger.
     *
     * Rows the model filed itself are excluded by the caller's query, and that is the
     * whole defence against the feedback loop: a model that reads its own guesses back
     * as training data reinforces its own mistakes until they cannot be shifted. The
     * filter is checked again here because it is too important to leave to one SQL
     * WHERE clause.
     */
    fun rebuildFrom(rows: List<Txn>) = synchronized(bayes) {
        bayes.clear()
        rows.forEach { txn ->
            val categoryId = txn.categoryId ?: return@forEach
            if (txn.categoryWasInferred) return@forEach
            bayes.learn(categoryId, Features.of(txn))
        }
    }

    fun learn(txn: Txn, categoryId: Long) = synchronized(bayes) {
        bayes.learn(categoryId, Features.of(txn))
    }

    fun unlearn(txn: Txn, categoryId: Long) = synchronized(bayes) {
        bayes.unlearn(categoryId, Features.of(txn))
    }

    /**
     * Every candidate category, best first.
     *
     * [allowed] restricts the answer to categories that could be right for this row -
     * expense categories for a debit. Credits are still trained on, because a salary
     * landing on the first of the month teaches the context features something true;
     * they simply must not be offered as an answer for money going out.
     */
    fun rank(features: List<String>, allowed: Set<Long>? = null): List<Scored> =
        synchronized(bayes) { bayes.score(features) }
            .let { scored -> if (allowed == null) scored else scored.filter { it.categoryId in allowed } }

    fun rank(txn: Txn, allowed: Set<Long>? = null): List<Scored> = rank(Features.of(txn), allowed)

    /**
     * The gate. All three conditions, or the app asks.
     *
     * The constants are a deliberate guess, set conservatively, and they are the part
     * of this feature that most needs real numbers rather than judgement. Every guess
     * is recorded as one, so the app can report how often it was right - and that is
     * what these should be tuned against, once there is enough of it to mean anything.
     */
    fun predict(features: List<String>, allowed: Set<Long>? = null): Prediction? {
        val scored = rank(features, allowed)
        val top = scored.firstOrNull() ?: return null
        // With one candidate there is no runner-up to be ahead of, and no way to tell
        // a real winner from the only option. Rank it, never file it.
        val margin = if (scored.size < 2) 0.0 else top.logProbability - scored[1].logProbability
        val confident = autoFileEnabled &&
            totalExamples >= MIN_TOTAL_EXAMPLES &&
            top.examples >= MIN_CATEGORY_EXAMPLES &&
            margin >= MIN_LOG_MARGIN
        return Prediction(top.categoryId, margin, confident)
    }

    fun predict(txn: Txn, allowed: Set<Long>? = null): Prediction? = predict(Features.of(txn), allowed)

    companion object {
        /**
         * How far ahead of the runner-up before the app will act. Naive Bayes reports
         * badly calibrated probabilities - it multiplies independence assumptions that
         * do not hold, and will happily say 0.99 about very little - so the gate reads
         * the gap between the top two rather than the number on the top one.
         */
        const val MIN_LOG_MARGIN = 2.5

        /** Below this the model has seen a category, not learned it. */
        const val MIN_CATEGORY_EXAMPLES = 5

        /** A fresh install guesses nothing. It has nothing to guess from. */
        const val MIN_TOTAL_EXAMPLES = 50
    }
}
