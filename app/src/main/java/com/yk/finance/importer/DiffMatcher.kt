package com.yk.finance.importer

import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Splitter
import com.yk.finance.parser.Direction
import java.time.Instant
import java.time.LocalDate

/**
 * What one account name in the file resolves to.
 *
 * Three outcomes, not two: an unbound name and the cash wallet both fail to produce a
 * bank id, but they mean opposite things. Cash is expected to be absent from the app
 * and proves nothing; an unbound name means the report is incomplete and must say so.
 */
sealed interface AccountBinding {
    data class Bank(val id: Long) : AccountBinding

    /**
     * Carries its id for the same reason [Bank] does. Resolving cash to "whichever
     * account happens to be the first CASH row" made a match you had explicitly picked
     * unpickable: with two cash accounts the rows landed in the other one while the
     * screen reported success.
     */
    data class Cash(val id: Long) : AccountBinding

    data object Unbound : AccountBinding
}

/** How far apart two records may be and still be the same payment. */
sealed interface MatchWindow {
    /** For amounts that repeat constantly. Two ₹50 fares a week apart are not one fare. */
    data object SameDay : MatchWindow
    data class Within(val millis: Long) : MatchWindow
}

data class MatchedPair(val staged: StagedTxn, val txn: Txn) {
    val driftMillis: Long get() = txn.occurredAt - staged.occurredAt
}

/**
 * What the two ledgers disagree about.
 *
 * This is the measurement the migration is gated on: [fileOnly] is what SMS capture
 * missed, [appOnly] is what the old app missed, and until the first number is small
 * the import should not happen.
 */
data class DiffReport(
    val windowStart: Long,
    val windowEnd: Long,
    val matched: List<MatchedPair>,
    val ambiguous: List<StagedTxn>,
    val fileOnly: List<StagedTxn>,
    val appOnly: List<Txn>,
    val cashRowsExcluded: Int,
    val transfersExcluded: Int,
    val outOfWindowRows: Int,
    val unboundRows: Int,
    val unmappedAccounts: List<String>,
) {
    val comparable: Int get() = matched.size + ambiguous.size + fileOnly.size

    /**
     * The headline number: of the bank transactions the old app recorded in this
     * window, how many did the phone see for itself.
     *
     * Ambiguous counts as seen - the app did record a payment of that amount that day,
     * it just cannot prove which one. Counting it as missed would overstate the damage.
     */
    val capturePercent: Int
        get() = if (comparable == 0) 100 else ((matched.size + ambiguous.size) * 100) / comparable

    val missedPaise: Long get() = fileOnly.sumOf { it.amountPaise }

    /**
     * A capture figure computed while some rows could not be placed at all is not a
     * measurement, and presenting it as one is how a migration gets approved on a
     * number that was never true.
     */
    val trustworthy: Boolean get() = unboundRows == 0
}

object DiffMatcher {

    private const val HOUR = 3_600_000L
    private const val DEFAULT_WINDOW = 36 * HOUR
    private const val RARE_WINDOW = 7 * 24 * HOUR

    /**
     * Decision 13's adaptive window, keyed on how often that exact amount appears.
     *
     * A unique amount identifies itself, so it can be matched a week out - which is
     * what catches a payment entered days after it happened. ₹50 appears twenty times
     * and identifies nothing, so it has to be same-day or the matcher starts pairing
     * unrelated bus fares and reporting perfect capture.
     */
    fun windowFor(occurrences: Int): MatchWindow = when {
        occurrences <= 1 -> MatchWindow.Within(RARE_WINDOW)
        occurrences >= 5 -> MatchWindow.SameDay
        else -> MatchWindow.Within(DEFAULT_WINDOW)
    }

    private fun sameDay(a: Long, b: Long): Boolean =
        istDate(a) == istDate(b)

    private fun istDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ValueParsers.IST).toLocalDate()

    private fun within(window: MatchWindow, a: Long, b: Long): Boolean = when (window) {
        is MatchWindow.SameDay -> sameDay(a, b)
        is MatchWindow.Within -> kotlin.math.abs(a - b) <= window.millis
    }

    private fun directionOf(kind: RowKind): Direction? = when (kind) {
        RowKind.EXPENSE -> Direction.DEBIT
        RowKind.INCOME -> Direction.CREDIT
        RowKind.TRANSFER -> null
    }

    /**
     * Compares an import plan against the ledger the app captured for itself.
     *
     * Cash is excluded by construction, not by a rule: the app can never see cash, so
     * a cash row missing from it means nothing. Transfers are excluded too - both legs
     * are internal and matching them measures the transfer resolver rather than SMS
     * capture, which is a different question.
     */
    fun diff(
        plan: ImportPlan,
        ledger: List<Txn>,
        bindingFor: (String) -> AccountBinding,
        windowStart: Long,
        windowEnd: Long,
    ): DiffReport {
        var cashExcluded = 0
        var transfersExcluded = 0
        var outOfWindow = 0
        var unbound = 0
        val unmapped = linkedSetOf<String>()

        val eligible = mutableListOf<Pair<StagedTxn, Long>>()
        plan.staged.forEach { row ->
            when {
                row.kind == RowKind.TRANSFER -> transfersExcluded++
                row.occurredAt !in windowStart..windowEnd -> outOfWindow++
                else -> when (val binding = bindingFor(row.accountName)) {
                    is AccountBinding.Bank -> eligible.add(row to binding.id)
                    is AccountBinding.Cash -> cashExcluded++
                    AccountBinding.Unbound -> {
                        unbound++
                        unmapped.add(row.accountName)
                    }
                }
            }
        }

        val occurrences = eligible.groupingBy { it.first.amountPaise }.eachCount()

        // Collapsed first: a bill split into 200 + 120 has no 320 row for the bank's own
        // export to match against, so without this the file would report the bill as
        // missing from the app and offer to import a duplicate of it.
        val available = Splitter.collapseGroups(ledger)
            .filter { it.isComparable(windowStart, windowEnd) }
        val consumed = mutableSetOf<Long>()
        val reserved = mutableSetOf<Long>()

        val matched = mutableListOf<MatchedPair>()
        val ambiguous = mutableListOf<StagedTxn>()
        val fileOnly = mutableListOf<StagedTxn>()

        eligible.sortedBy { it.first.occurredAt }.forEach { (row, accountId) ->
            val window = windowFor(occurrences[row.amountPaise] ?: 1)
            val direction = directionOf(row.kind)
            val candidates = available.filter { candidate ->
                candidate.id !in consumed &&
                    candidate.accountId == accountId &&
                    candidate.direction == direction &&
                    candidate.amountPaise == row.amountPaise &&
                    within(window, row.occurredAt, candidate.occurredAt)
            }
            when (candidates.size) {
                0 -> fileOnly.add(row)
                1 -> {
                    matched.add(MatchedPair(row, candidates.first()))
                    consumed.add(candidates.first().id)
                }
                // Never merge on a guess. Two candidates means the answer is a person's.
                else -> {
                    ambiguous.add(row)
                    candidates.forEach { reserved.add(it.id) }
                }
            }
        }

        val appOnly = available.filter { it.id !in consumed && it.id !in reserved }

        return DiffReport(
            windowStart = windowStart,
            windowEnd = windowEnd,
            matched = matched,
            ambiguous = ambiguous,
            fileOnly = fileOnly,
            appOnly = appOnly,
            cashRowsExcluded = cashExcluded,
            transfersExcluded = transfersExcluded,
            outOfWindowRows = outOfWindow,
            unboundRows = unbound,
            unmappedAccounts = unmapped.toList(),
        )
    }

    /**
     * Rows that represent a real inflow or outflow the bank would have messaged about.
     * Transfer legs and balance corrections are the app's own bookkeeping and have no
     * counterpart in anyone else's export.
     */
    private fun Txn.isComparable(from: Long, to: Long): Boolean =
        occurredAt in from..to &&
            source != TxnSource.ADJUSTMENT &&
            transferGroupId == null
}
