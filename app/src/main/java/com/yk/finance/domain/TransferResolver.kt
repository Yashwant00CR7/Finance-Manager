package com.yk.finance.domain

import com.yk.finance.data.Txn
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParsedSms

/** How an incoming message relates to what we already hold. */
sealed interface Relation {
    /** Same transaction, already recorded. Drop the new copy. */
    data class Duplicate(val existing: Txn) : Relation

    /**
     * The other leg of a movement between two of your own accounts.
     * Both legs get linked and excluded from spending.
     */
    data class TransferLeg(val counterpart: Txn) : Relation

    object Independent : Relation
}

object TransferResolver {

    /** Banks can stamp each leg with slightly different times. */
    const val MATCH_WINDOW_DAYS = 3L
    val MATCH_WINDOW_MILLIS = MATCH_WINDOW_DAYS * 24 * 60 * 60 * 1000L

    /**
     * Verified against two real messages sharing reference 634455667788:
     *
     *   ICICI  Acct XX742 debited  Rs 3000.00  16-Sep-26  A K SHARMA
     *   Union  A/c *8317  Credited Rs 3000.00  16-09-2026  Mob Bk
     *
     * A naive "same reference means duplicate" rule would merge these and delete a
     * leg - the Union balance would never rise while the ICICI balance fell, and a
     * 3,000 transfer between your own accounts would book as spending.
     *
     * The discriminator is direction and account, not the reference alone:
     *   same account   + same direction     -> duplicate (bank alerted twice)
     *   other account  + opposite direction -> two legs of one transfer
     */
    fun classify(incoming: ParsedSms, accountId: Long, candidates: List<Txn>): Relation {
        for (existing in candidates) {
            if (existing.amountPaise != incoming.amountPaise) continue

            val sameAccount = existing.accountId == accountId
            val sameDirection = existing.direction == incoming.direction

            if (sameAccount && sameDirection) return Relation.Duplicate(existing)

            if (!sameAccount && !sameDirection) return Relation.TransferLeg(existing)
        }
        return Relation.Independent
    }

    /**
     * An ATM withdrawal is money changing pocket, not money spent. The bank debit is
     * paired with a credit into the Cash wallet, and neither counts as spending - your
     * actual cash expenses are the manual entries you log afterwards.
     *
     * Detected by channel keywords ("ATM", "WDL"), which are far more stable than any
     * bank's sentence layout - important here, because the ICICI ATM *format* is an
     * unverified guess while the keywords are not.
     */
    fun isCashWithdrawal(sms: ParsedSms): Boolean =
        sms.direction == Direction.DEBIT && sms.channel == com.yk.finance.parser.Channel.ATM
}
