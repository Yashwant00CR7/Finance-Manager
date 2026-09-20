package com.yk.finance.domain

import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction

/**
 * One line of a split bill, as the sheet holds it before anything is written.
 *
 * [owedBy] is only meaningful when the category is [Sharing.LENT]; it is dropped
 * otherwise rather than carried invisibly, so a name can never linger on a row that
 * nobody owes.
 */
data class SplitPart(
    val categoryId: Long?,
    val amountPaise: Long,
    val owedBy: String? = null,
)

/**
 * Turns one bank debit into the several things it actually was.
 *
 * A single message - "Rs 320.00 debited ... KA 05 JUICE BAR" - can be two facts at
 * once: 200 you ate, and 120 you fronted for a friend who will pay it back. Before
 * this, the app forced one category on the whole 320, so either Food was overstated by
 * 120 every time or it was understated by 200. Hand-compensating for that month after
 * month is what made the same shop flip between categories, which the payee rule then
 * chased.
 *
 * Parts are ordinary transactions sharing a [Txn.splitGroupId] - the pattern
 * [Txn.transferGroupId] already established - so Records, the donut, budgets, search
 * and the CSV export needed no changes at all to understand them. A part in a LENT
 * category is written with `countsAsSpending = false`, which is the flag
 * [Ledger.isExpense] already reads, so money you are owed leaves your expense figure
 * and your budgets without either having to learn a new concept.
 *
 * Pure, and separate from the writing, for the same reason the importer's Committer is:
 * the arithmetic that must never be wrong is the part worth testing, and it can be
 * tested without a database in the way.
 */
object Splitter {

    /** What to write. Empty on every count means the split was already as asked. */
    data class Plan(
        val updates: List<Txn>,
        val inserts: List<Txn>,
        val deleteIds: List<Long>,
    ) {
        val isEmpty: Boolean get() = updates.isEmpty() && inserts.isEmpty() && deleteIds.isEmpty()
    }

    /**
     * What is left to assign, in paise.
     *
     * Zero is the only state the sheet may save in. A split that does not sum to what
     * the bank took is a ledger that disagrees with the account it describes, and the
     * disagreement would be invisible - every part looks like a perfectly ordinary
     * transaction on its own.
     */
    fun remainder(totalPaise: Long, parts: List<SplitPart>): Long =
        totalPaise - parts.sumOf { it.amountPaise }

    fun isBalanced(totalPaise: Long, parts: List<SplitPart>): Boolean =
        remainder(totalPaise, parts) == 0L && parts.isNotEmpty() &&
            parts.all { it.amountPaise > 0 }

    /**
     * The rows a split group is made of, oldest first, ignoring any repayment.
     *
     * A settlement shares the group id but is not a part of the bill - it is the money
     * coming back - so it must never be counted when the parts are rebuilt or summed.
     */
    fun partsOf(group: List<Txn>): List<Txn> =
        group.filter { it.source != TxnSource.SETTLEMENT }.sortedBy { it.id }

    fun settlementsOf(group: List<Txn>): List<Txn> =
        group.filter { it.source == TxnSource.SETTLEMENT }

    /** What the bank actually took for this bill. */
    fun groupTotal(group: List<Txn>): Long = partsOf(group).sumOf { it.amountPaise }

    /**
     * Whether a row is spending at all, before sharing is considered.
     *
     * Read off the row rather than carried, so re-splitting an already-split bill
     * cannot drift: a part that was LENT last time must not teach the next plan that
     * the whole bill was never spending.
     */
    private fun baseCounts(anchor: Txn): Boolean =
        anchor.direction == Direction.DEBIT &&
            anchor.source != TxnSource.TRANSFER_LEG &&
            anchor.source != TxnSource.ADJUSTMENT

    /**
     * Rewrites a transaction, or an existing split group, into [parts].
     *
     * The first part reuses the original row, so its reference, raw message and
     * fingerprint survive - the bank's own text still says "Rs 320.00" even though the
     * row now reads 200, which is what keeps the import able to recognise the bill it
     * came from. Further parts are new rows carrying no reference and no fingerprint,
     * because they are the app's bookkeeping and have no counterpart in anyone's export.
     *
     * Note what is *not* here: nothing touches an account balance. The bank moved 320
     * once; dividing it up afterwards moves nothing, and an adjustBalance call in this
     * path would invent money on every split.
     */
    fun plan(
        group: List<Txn>,
        parts: List<SplitPart>,
        groupId: String,
        sharingOf: (Long?) -> Sharing,
    ): Plan {
        val reusable = partsOf(group)
        require(reusable.isNotEmpty()) { "a split needs a row to split" }
        val anchor = reusable.first()
        val counts = baseCounts(anchor)

        // One part in an ordinary category is not a split at all - it is the whole bill
        // in one place - so it keeps no group. Left with one, the row would be locked
        // out of ordinary amount editing forever to protect an invariant with nothing
        // to protect. A single LENT part does keep its group: a repayment has to have
        // something to attach itself to.
        val single = parts.size == 1 && sharingOf(parts.first().categoryId) != Sharing.LENT
        val assigned = if (single) null else groupId

        val updates = mutableListOf<Txn>()
        val inserts = mutableListOf<Txn>()

        parts.forEachIndexed { index, part ->
            val lent = sharingOf(part.categoryId) == Sharing.LENT
            val owedBy = part.owedBy?.trim()?.takeIf { lent && it.isNotBlank() }
            if (index < reusable.size) {
                val row = reusable[index]
                val next = row.copy(
                    amountPaise = part.amountPaise,
                    categoryId = part.categoryId,
                    countsAsSpending = counts && !lent,
                    splitGroupId = assigned,
                    owedBy = owedBy,
                )
                if (next != row) updates.add(next)
            } else {
                inserts.add(
                    anchor.copy(
                        id = 0,
                        amountPaise = part.amountPaise,
                        categoryId = part.categoryId,
                        countsAsSpending = counts && !lent,
                        splitGroupId = assigned,
                        owedBy = owedBy,
                        // The bank messaged once, about one row. A second part is the
                        // app's own record, so it claims neither the reference nor the
                        // fingerprint - both are how a row is recognised as already
                        // imported, and two rows answering to one identity is how an
                        // import quietly drops or duplicates a transaction.
                        source = TxnSource.MANUAL,
                        reference = null,
                        fingerprint = null,
                        rawMessage = null,
                        importBatchId = null,
                        // Counted per month, this column is a direct measure of what
                        // SMS capture missed. Copying it onto a row the app invented
                        // would inflate that measure by one every time a bill is split.
                        noSmsCounterpart = false,
                    ),
                )
            }
        }

        return Plan(updates, inserts, reusable.drop(parts.size).map { it.id })
    }

    /**
     * Merges a group back into the single row it came from.
     *
     * The oldest row survives and takes the whole amount back, because it is the one
     * holding the bank's reference and raw message. Refused while a repayment is
     * attached: that credit is real money that arrived, and silently unhooking it would
     * either lose it or turn it back into income you never earned.
     */
    fun unsplit(group: List<Txn>): Plan? {
        if (settlementsOf(group).isNotEmpty()) return null
        val parts = partsOf(group)
        if (parts.size < 2) return null
        val anchor = parts.first()
        return Plan(
            updates = listOf(
                anchor.copy(
                    amountPaise = parts.sumOf { it.amountPaise },
                    countsAsSpending = baseCounts(anchor),
                    splitGroupId = null,
                    owedBy = null,
                ),
            ),
            inserts = emptyList(),
            deleteIds = parts.drop(1).map { it.id },
        )
    }

    /**
     * The category a split should teach the payee, if any.
     *
     * The largest part that is not money someone else owes you. Who you happened to eat
     * with is not a fact about the shop, so a LENT part teaches nothing - otherwise one
     * split lunch would leave the app filing every future visit there as a debt to
     * nobody.
     */
    fun learnTarget(parts: List<SplitPart>, sharingOf: (Long?) -> Sharing): Long? =
        parts
            .filter { it.categoryId != null && sharingOf(it.categoryId) != Sharing.LENT }
            .maxByOrNull { it.amountPaise }
            ?.categoryId

    /**
     * Folds each split group back into one row of the bill's full value.
     *
     * The importer matches a file row against the ledger by amount within a date
     * window. Once 320 is stored as 200 + 120 there is no 320 to find, so the bank's
     * own export would report the bill as missing from the app and offer to import it
     * again - a duplicate created by the act of splitting. Collapsing first means the
     * importer sees the bill the way the bank does.
     *
     * Settlements pass through untouched: a repayment is a real credit the bank really
     * did message about, so it has to stay individually matchable.
     */
    fun collapseGroups(ledger: List<Txn>): List<Txn> {
        if (ledger.none { it.splitGroupId != null }) return ledger
        val out = mutableListOf<Txn>()
        val byGroup = linkedMapOf<String, MutableList<Txn>>()
        ledger.forEach { txn ->
            val group = txn.splitGroupId
            when {
                group == null || txn.source == TxnSource.SETTLEMENT -> out.add(txn)
                else -> byGroup.getOrPut(group) { mutableListOf() }.add(txn)
            }
        }
        byGroup.values.forEach { rows ->
            val parts = rows.sortedBy { it.id }
            val anchor = parts.first()
            out.add(anchor.copy(amountPaise = parts.sumOf { it.amountPaise }))
        }
        return out
    }

    /** One person's outstanding balance, and what it is made of. */
    data class Owed(
        val person: String,
        val lentPaise: Long,
        val repaidPaise: Long,
        val rows: List<Txn>,
    ) {
        val outstandingPaise: Long get() = lentPaise - repaidPaise
        val isSettled: Boolean get() = outstandingPaise <= 0L
    }

    /**
     * Who owes you what, largest first.
     *
     * Only rows the split sheet wrote are counted - a LENT category with
     * `countsAsSpending = false`. Transactions you filed under "For Friend Return Later"
     * before this existed stay exactly as they are, counted as the spending they were
     * always reported as. Retro-converting them would move totals in months you have
     * already read and closed, to assert a debt the app was never actually tracking.
     */
    fun owed(ledger: List<Txn>, sharingOf: (Long?) -> Sharing): List<Owed> {
        val lent = ledger.filter {
            it.splitGroupId != null &&
                !it.countsAsSpending &&
                it.source != TxnSource.SETTLEMENT &&
                sharingOf(it.categoryId) == Sharing.LENT
        }
        if (lent.isEmpty()) return emptyList()

        val repaidByGroup = ledger
            .filter { it.source == TxnSource.SETTLEMENT && it.splitGroupId != null }
            .groupBy { it.splitGroupId!! }
            .mapValues { (_, rows) -> rows.sumOf { it.amountPaise } }

        return lent
            .groupBy { it.owedBy?.trim().orEmpty().ifBlank { "Someone" } }
            .map { (person, rows) ->
                // A repayment settles the group it is attached to, and a group holds at
                // most one person's share, so crediting it per group cannot leak
                // someone else's money into this person's balance.
                val repaid = rows.map { it.splitGroupId!! }.distinct()
                    .sumOf { repaidByGroup[it] ?: 0L }
                Owed(
                    person = person,
                    lentPaise = rows.sumOf { it.amountPaise },
                    repaidPaise = repaid,
                    rows = rows.sortedByDescending { it.occurredAt },
                )
            }
            .filterNot { it.isSettled }
            .sortedByDescending { it.outstandingPaise }
    }
}
