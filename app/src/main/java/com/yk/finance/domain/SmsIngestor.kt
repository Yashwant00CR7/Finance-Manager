package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.PendingReview
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.TransactionParser
import java.util.UUID

/** What ingestion did, so the caller can decide whether to notify. */
sealed interface IngestOutcome {
    /**
     * [needsCategory] is true when no learned rule and no seeded keyword matched, so
     * the app declined to guess. The caller asks rather than letting it settle into
     * Uncategorised unnoticed.
     */
    data class Recorded(
        val txnId: Long,
        val accountId: Long,
        val needsCategory: Boolean = false,
    ) : IngestOutcome
    data class Transfer(val groupId: String) : IngestOutcome
    data class DroppedDuplicate(val existingId: Long) : IngestOutcome
    data object Queued : IngestOutcome
    data class Ignored(val reason: String) : IngestOutcome
}

/**
 * Turns one incoming SMS into ledger state. Single entry point, so the BroadcastReceiver
 * stays trivial and all of this is testable without Android.
 */
class SmsIngestor(
    private val dao: FinanceDao,
    private val parser: TransactionParser,
) {

    suspend fun ingest(sender: String, body: String, receivedAt: Long): IngestOutcome =
        when (val result = parser.parse(sender, body, receivedAt)) {
            is ParseResult.Ignored -> IngestOutcome.Ignored(result.reason)
            is ParseResult.NeedsReview -> {
                dao.insertReview(
                    PendingReview(
                        sender = result.sender,
                        rawMessage = result.raw,
                        receivedAt = receivedAt,
                        reason = result.reason,
                    ),
                )
                IngestOutcome.Queued
            }
            is ParseResult.Parsed -> record(result.sms, receivedAt)
        }

    private suspend fun record(sms: ParsedSms, receivedAt: Long): IngestOutcome {
        val account = resolveAccount(sms)

        // --- duplicate vs self-transfer -------------------------------------
        val reference = sms.reference
        if (reference != null) {
            val candidates = dao.findByReference(
                reference,
                sms.occurredAt - TransferResolver.MATCH_WINDOW_MILLIS,
                sms.occurredAt + TransferResolver.MATCH_WINDOW_MILLIS,
            )
            when (val relation = TransferResolver.classify(sms, account.id, candidates)) {
                is Relation.Duplicate -> return IngestOutcome.DroppedDuplicate(relation.existing.id)

                is Relation.TransferLeg -> {
                    val groupId = relation.counterpart.transferGroupId ?: UUID.randomUUID().toString()

                    // The already-recorded leg was booked as ordinary spending before
                    // its counterpart arrived. Retract that now.
                    dao.updateTxn(
                        relation.counterpart.copy(
                            countsAsSpending = false,
                            transferGroupId = groupId,
                            source = TxnSource.TRANSFER_LEG,
                        ),
                    )
                    insertTxn(sms, account, categoryId = null, countsAsSpending = false, groupId = groupId, source = TxnSource.TRANSFER_LEG)
                    applyBalance(account, sms)
                    maybeRollCycle(sms, receivedAt)
                    return IngestOutcome.Transfer(groupId)
                }

                Relation.Independent -> Unit
            }
        }

        // --- ATM withdrawal: bank -> Cash wallet, never spending --------------
        if (TransferResolver.isCashWithdrawal(sms)) {
            val cash = ensureCashAccount()
            val groupId = UUID.randomUUID().toString()
            insertTxn(sms, account, categoryId = null, countsAsSpending = false, groupId = groupId, source = TxnSource.TRANSFER_LEG)
            dao.insertTxn(
                Txn(
                    accountId = cash.id,
                    direction = Direction.CREDIT,
                    amountPaise = sms.amountPaise,
                    occurredAt = sms.occurredAt,
                    payee = "Cash withdrawal",
                    reference = sms.reference,
                    channel = sms.channel,
                    countsAsSpending = false,
                    transferGroupId = groupId,
                    source = TxnSource.TRANSFER_LEG,
                    note = "Moved to Cash wallet - log what you spend it on",
                ),
            )
            dao.adjustBalance(cash.id, sms.amountPaise)
            applyBalance(account, sms)
            maybeRollCycle(sms, receivedAt)
            return IngestOutcome.Transfer(groupId)
        }

        // --- ordinary transaction -------------------------------------------
        val countsAsSpending = sms.direction == Direction.DEBIT
        val categoryId = resolveCategory(sms)
        val txnId = insertTxn(
            sms,
            account,
            categoryId = categoryId,
            countsAsSpending = countsAsSpending,
            groupId = null,
            source = TxnSource.SMS,
        )
        applyBalance(account, sms)
        maybeRollCycle(sms, receivedAt)
        return IngestOutcome.Recorded(
            txnId = txnId,
            accountId = account.id,
            needsCategory = countsAsSpending && categoryId == null,
        )
    }

    private suspend fun insertTxn(
        sms: ParsedSms,
        account: Account,
        categoryId: Long?,
        countsAsSpending: Boolean,
        groupId: String?,
        source: TxnSource,
    ): Long {
        return dao.insertTxn(
            Txn(
                accountId = account.id,
                direction = sms.direction,
                amountPaise = sms.amountPaise,
                occurredAt = sms.occurredAt,
                payee = sms.payee,
                reference = sms.reference,
                channel = sms.channel,
                categoryId = categoryId,
                countsAsSpending = countsAsSpending,
                transferGroupId = groupId,
                source = source,
                rawMessage = sms.raw,
            ),
        )
    }

    /**
     * Guess, or return null so the caller can ask.
     *
     * A learned rule is the user's own past decision and always wins. The seeded
     * keyword list is the only guessing this does, and it only fires on a payee - so
     * Union, which sends none, always falls through to the ask.
     */
    private suspend fun resolveCategory(sms: ParsedSms): Long? {
        if (sms.direction != Direction.DEBIT) return null
        val key = Categorizer.payeeKey(sms.payee) ?: return null
        dao.ruleFor(key)?.let { return it.categoryId }
        val seeded = Categorizer.seedCategoryFor(sms.payee) ?: return null
        return dao.allCategories().firstOrNull { it.name == seeded }?.id
    }

    /**
     * Balance handling differs per bank by necessity.
     *
     * Union supplies "Avl Bal", which is the bank's own truth and always wins - this
     * is what makes that account self-healing after a missed message.
     *
     * ICICI supplies nothing, so its balance can only be computed by arithmetic and
     * will drift with every message the phone never received. That is why ICICI is
     * prompted for reconciliation once per cycle.
     */
    private suspend fun applyBalance(account: Account, sms: ParsedSms) {
        val authoritative = sms.availableBalancePaise
        if (authoritative != null) {
            dao.setBalance(account.id, authoritative)
            if (!account.providesBalance) {
                dao.updateAccount(account.copy(providesBalance = true))
            }
            return
        }
        val delta = if (sms.direction == Direction.DEBIT) -sms.amountPaise else sms.amountPaise
        dao.adjustBalance(account.id, delta)
    }

    /**
     * Auto-create on first sight. The transaction is recorded immediately and the
     * account is flagged for confirmation - money is never held hostage to a prompt,
     * and nothing is lost if you ignore the prompt forever.
     */
    private suspend fun resolveAccount(sms: ParsedSms): Account {
        dao.findAccount(sms.bank, sms.accountToken)?.let { return it }
        val created = Account(
            displayName = "${sms.bank} ..${sms.accountToken}",
            bank = sms.bank,
            accountToken = sms.accountToken,
            kind = AccountKind.BANK,
            providesBalance = sms.availableBalancePaise != null,
            needsConfirmation = true,
        )
        val id = dao.insertAccount(created)
        return created.copy(id = id)
    }

    private suspend fun ensureCashAccount(): Account {
        dao.cashAccount()?.let { return it }
        val cash = Account(
            displayName = "Cash",
            bank = "CASH",
            accountToken = "CASH",
            kind = AccountKind.CASH,
            providesBalance = false,
        )
        val id = dao.insertAccount(cash)
        return cash.copy(id = id)
    }

    /**
     * Cycle rolls on the real salary credit, with the computed last-working-day as a
     * backstop so the app never stalls in a month where salary is missed or untagged.
     */
    private suspend fun maybeRollCycle(sms: ParsedSms, now: Long) {
        val state: CycleState = dao.cycleState() ?: CycleCalculator.seed(now).also { dao.upsertCycle(it) }
        // Each roll also writes its boundary: CycleState forgets the cycle it just
        // left, and cycle_history is the only thing that remembers it happened.
        when {
            CycleCalculator.isSalaryCredit(sms, state, now) -> {
                dao.upsertCycle(CycleCalculator.rolled(state, sms.occurredAt))
                CycleBackfill.record(dao, sms.occurredAt, fromSalary = true)
            }
            CycleCalculator.shouldRollByDate(state, now) -> {
                dao.upsertCycle(CycleCalculator.rolled(state, now))
                CycleBackfill.record(dao, now, fromSalary = false)
            }
        }
    }
}
