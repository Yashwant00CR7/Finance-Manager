package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction

/**
 * What deleting an account does to everything else.
 *
 * An account is not only its own rows. A transfer has a leg on another account, a split
 * bill can be settled by a credit that landed somewhere else, and both are tied together
 * by a group id that would otherwise point at rows that no longer exist. Worked out here,
 * as a plan of rows to delete and rows to rewrite, so that the rule can be read and tested
 * without a database - the same arrangement as [Splitter].
 *
 * Two ways to go, and the choice is the person's:
 *
 *  - **Delete with its history.** Its rows go, and its balance leaves the total. Rows on
 *    your other accounts stay exactly where they are and keep moving their balance by
 *    what they always did: a 3,000 transfer out of Union happened to Union whatever became
 *    of the account it went to.
 *
 *  - **Move its history to another account.** For two entries that were really one
 *    account, or an old account folded into the one that replaced it. Rows move across and
 *    the destination's balance moves by what they add up to. A transfer *between* the two
 *    would become money moving from an account to itself, so the pair is removed instead.
 */
object AccountRemoval {

    /** Banks whose identity is a placeholder rather than anything a message carries. */
    private val PLACEHOLDER_BANKS = setOf("UNBOUND", "CASH")

    data class Plan(
        val deleteIds: List<Long>,
        val updates: List<Txn>,
        /** How far the destination's balance moves. Always 0 when deleting outright. */
        val balanceDeltaPaise: Long,
    )

    /** Whether bank messages arrive for this account, so they could be switched off. */
    fun isMessageLinked(account: Account): Boolean = account.bank !in PLACEHOLDER_BANKS

    /** An account added by hand that no message has been attached to yet. */
    fun isUnbound(account: Account): Boolean = account.bank == "UNBOUND"

    /**
     * Whether moving [from] into [to] should hand [to] the bank's identity as well.
     *
     * Only into an account that has none. That is the case the inbox's "This is Savings"
     * exists for: Savings was added by hand at setup, the bank's messages created a second
     * entry beside it, and from now on those messages have to reach Savings.
     */
    fun adoptsIdentity(from: Account, to: Account): Boolean =
        isMessageLinked(from) && isUnbound(to)

    /**
     * Cards and deposit accounts do not mix. A card's balance is deliberately never
     * computed, so card spending moved into a savings account would draw down a balance
     * the bank never touched, and savings moved into a card would vanish from every total.
     *
     * Nor does a bank account go into the Cash wallet: its messages would follow it there,
     * and the bank's printed balance would start overwriting the cash you hold.
     */
    fun canMoveInto(from: Account, to: Account): Boolean =
        from.id != to.id &&
            (from.kind == AccountKind.CREDIT_CARD) == (to.kind == AccountKind.CREDIT_CARD) &&
            !(isMessageLinked(from) && to.kind == AccountKind.CASH)

    /**
     * The names an import may know the merged account by: the destination's own, plus
     * whatever the old account was called and known as. Without this, an export that named
     * the old account bound to nothing the day after the merge. An auto-created account's
     * "UNION ..2209" is not a name anything else uses, so it is not carried.
     */
    fun mergedImportNames(from: Account, to: Account, namesOf: (Account) -> List<String>): String? {
        val carried = namesOf(from) + listOfNotNull(from.displayName.takeIf { !from.needsConfirmation })
        return (namesOf(to) + carried)
            .filterNot { it.equals(to.displayName, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    /**
     * [own] is every row on the account; [related] is every row on another account that
     * shares a transfer or split group with one of them.
     */
    fun deleteWithHistory(accountId: Long, own: List<Txn>, related: List<Txn>): Plan {
        val transferGroups = own.mapNotNullTo(mutableSetOf()) { it.transferGroupId }
        val splitGroups = own.mapNotNullTo(mutableSetOf()) { it.splitGroupId }
        val updates = related
            .filter { it.accountId != accountId }
            .mapNotNull { row ->
                var next = row
                // The other leg stays a transfer - its money did move between two of
                // your accounts, so it is neither income nor spending - but it is no
                // longer linked to a row that is gone.
                if (row.transferGroupId in transferGroups) next = next.copy(transferGroupId = null)
                // A repayment of a debt that no longer exists goes back to being an
                // ordinary credit, exactly as Repository.unsettle leaves one.
                if (row.splitGroupId in splitGroups) {
                    next = next.copy(
                        splitGroupId = null,
                        source = if (row.source == TxnSource.SETTLEMENT) restoredSource(row) else row.source,
                    )
                }
                next.takeIf { it != row }
            }
        return Plan(deleteIds = own.map { it.id }, updates = updates, balanceDeltaPaise = 0)
    }

    fun moveTo(accountId: Long, targetId: Long, own: List<Txn>, related: List<Txn>): Plan {
        // Transfers between the two accounts. Both legs go, and the destination's own
        // leg is reversed out of its balance, because the money never left the account
        // it is now all in.
        val internalGroups = related
            .filter { it.accountId == targetId && it.transferGroupId != null }
            .mapNotNullTo(mutableSetOf()) { it.transferGroupId }
            .intersect(own.mapNotNullTo(mutableSetOf()) { it.transferGroupId })

        val ownInternal = own.filter { it.transferGroupId in internalGroups }
        val targetInternal = related.filter { it.accountId == targetId && it.transferGroupId in internalGroups }
        val moved = own.filter { it.transferGroupId !in internalGroups }

        return Plan(
            deleteIds = (ownInternal + targetInternal).map { it.id },
            updates = moved.map { it.copy(accountId = targetId) },
            balanceDeltaPaise = moved.sumOf(::effect) - targetInternal.sumOf(::effect),
        )
    }

    /** What a row did to its account's balance. */
    private fun effect(txn: Txn): Long =
        if (txn.direction == Direction.CREDIT) txn.amountPaise else -txn.amountPaise

    private fun restoredSource(txn: Txn): TxnSource =
        if (txn.rawMessage != null) TxnSource.SMS else TxnSource.MANUAL
}
