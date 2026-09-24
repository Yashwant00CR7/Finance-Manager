package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.GateMode
import com.yk.finance.data.PendingReview
import com.yk.finance.data.SenderEntry
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.SenderIdentity
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
    /**
     * Null means no guessing at all, which is what every test that does not care about
     * it gets. The model is the last tier in [resolveCategory] and never the first.
     */
    private val model: CategoryModel? = null,
) {

    /**
     * The gate, and then the parser.
     *
     * Everything up to the decision is bookkeeping; [SenderGate] makes the decision
     * itself and has no database in it, which is what lets the ACTIVE branch - the one
     * that destroys messages - be tested exhaustively.
     */
    suspend fun ingest(sender: String, body: String, receivedAt: Long): IngestOutcome {
        val observation = SenderGate.observe(sender, body)

        // Being *listed* and being *looked up* are different permissions, and
        // conflating them silently breaks the escape hatch. A bare mobile number is
        // never added to the seen list - that list must not become a record of
        // everyone who texts you - but if you deliberately enrolled one, the gate is a
        // plain lookup and has to find it.
        val entry = when {
            observation.header.isEmpty() -> null
            observation.listable -> noteSeen(observation.header, receivedAt, observation.transactional)
            else -> dao.senderByHeader(observation.header)
        }

        return when (val decision = SenderGate.decide(observation, body, entry, gateMode())) {
            is SenderGate.Decision.Drop -> IngestOutcome.Ignored(decision.reason)
            is SenderGate.Decision.Review -> {
                dao.insertReview(
                    PendingReview(
                        sender = sender,
                        rawMessage = body,
                        receivedAt = receivedAt,
                        reason = decision.reason,
                    ),
                )
                IngestOutcome.Queued
            }
            is SenderGate.Decision.Parse -> booked(sender, decision.identity, body, receivedAt)
        }
    }

    /**
     * Records that a header sent something, and how it looked.
     *
     * Updates first and inserts only when nothing was updated, so a sender already in
     * the table is never overwritten - in particular its state and its bank binding
     * survive every message it sends.
     */
    private suspend fun noteSeen(header: String, at: Long, transactional: Boolean): SenderEntry? {
        val bump = if (transactional) 1 else 0
        if (dao.touchSender(header, at, bump) == 0) {
            dao.insertSenderIfAbsent(
                SenderEntry(
                    header = header,
                    firstSeenAt = at,
                    lastSeenAt = at,
                    messageCount = 1,
                    transactionalCount = bump,
                ),
            )
        }
        return dao.senderByHeader(header)
    }

    /**
     * Missing row means observe, which is the direction that loses nothing. A gate that
     * failed closed on a database it could not read would go silently deaf.
     */
    private suspend fun gateMode(): GateMode = dao.gateState()?.mode ?: GateMode.OBSERVE

    private suspend fun booked(
        sender: String,
        identity: SenderIdentity,
        body: String,
        receivedAt: Long,
    ): IngestOutcome =
        when (val result = parser.parse(sender, body, receivedAt, identity)) {
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
            is ParseResult.Parsed -> record(result.sms, receivedAt, identity.header)
        }

    private suspend fun record(sms: ParsedSms, receivedAt: Long, header: String): IngestOutcome {
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
                    insertTxn(sms, account, categoryId = null, countsAsSpending = false, groupId = groupId, source = TxnSource.TRANSFER_LEG, header = header)
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
            insertTxn(sms, account, categoryId = null, countsAsSpending = false, groupId = groupId, source = TxnSource.TRANSFER_LEG, header = header)
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
                    sender = header,
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
        val choice = resolveCategory(sms, account.id)
        val txnId = insertTxn(
            sms,
            account,
            categoryId = choice?.categoryId,
            countsAsSpending = countsAsSpending,
            groupId = null,
            source = TxnSource.SMS,
            header = header,
            categoryWasInferred = choice?.inferred == true,
        )
        applyBalance(account, sms)
        maybeRollCycle(sms, receivedAt)
        return IngestOutcome.Recorded(
            txnId = txnId,
            accountId = account.id,
            needsCategory = countsAsSpending && choice == null,
        )
    }

    private suspend fun insertTxn(
        sms: ParsedSms,
        account: Account,
        categoryId: Long?,
        countsAsSpending: Boolean,
        groupId: String?,
        source: TxnSource,
        header: String,
        categoryWasInferred: Boolean = false,
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
                sender = header,
                categoryWasInferred = categoryWasInferred,
            ),
        )
    }

    /**
     * Guess, or return null so the caller can ask.
     *
     * Three tiers, weakest last. A learned rule is your own past decision and always
     * wins. A seed keyword is deterministic and auditable, so it wins next. The model
     * only ever fires where the app would otherwise have given up, which is what makes
     * turning it on incapable of changing any outcome that works today.
     *
     * Note that the payee is no longer a precondition for reaching the bottom of this
     * function. It used to be - Union sends none, so a quarter of spending returned at
     * the first line and every Union debit settled into the ask queue. The model is
     * the first tier with anything to say about a row that has no text in it.
     */
    private suspend fun resolveCategory(sms: ParsedSms, accountId: Long): CategoryChoice? {
        if (sms.direction != Direction.DEBIT) return null

        val rule = Categorizer.payeeKey(sms.payee)?.let { dao.ruleFor(it)?.categoryId }
        val categories = dao.allCategories()
        val seed = Categorizer.seedCategoryFor(sms.payee)
            ?.let { name -> categories.firstOrNull { it.name == name }?.id }

        // Income categories are excluded rather than merely unlikely. The model is
        // trained on credits too, because salary landing on the first of the month
        // teaches the context features something true - but "Salary" must never be
        // offered as an answer for money going out.
        val prediction = model?.let {
            val allowed = categories.filterNot { c -> c.isIncome }.map { c -> c.id }.toSet()
            it.predict(Features.of(sms, accountId), allowed)
        }

        return Categorizer.decide(rule, seed, prediction)
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
