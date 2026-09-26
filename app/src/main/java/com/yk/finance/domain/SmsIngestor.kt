package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.ConfirmedPattern
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.PendingReview
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.ParsedSms
import com.yk.finance.parser.Tier
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

    /**
     * Understood, but by a pattern this device has not yet agreed with.
     *
     * The figures are in the tray with the message they came from, waiting for one tap. Not
     * [Queued], which means nothing could read the message at all - the difference is what
     * the tray shows the person, and whether answering it teaches the app anything.
     */
    data class AwaitingConfirmation(val patternId: String) : IngestOutcome
    data class Ignored(val reason: String) : IngestOutcome
}

/**
 * Whether a parse may reach the ledger without being shown to anyone first.
 *
 * Extracted from the ingestor deliberately. It is the single most consequential rule in the
 * app - it decides what is allowed to write money down unattended - and inside a class that
 * needs a ninety-method DAO it would be effectively untestable. Here it is a pure function
 * with three cases and its own tests.
 *
 * [Tier.VERIFIED] books because those patterns were written against messages that arrived on
 * the author's own phone and are asserted against in ParserTest.
 *
 * [Tier.RESEARCHED] books only once this device has confirmed it. The sample behind such a
 * pattern was genuine, but it belonged to a stranger and may predate the bank's current
 * template, and the only person who can tell is the one holding the phone the message
 * arrived on.
 *
 * [Tier.GENERIC] never books, and passing `confirmed = true` does not change that. Those
 * shapes are written to match across banks, which is exactly why a phishing SMS fits them;
 * agreeing with one once says nothing about the next message it will match.
 */
internal fun mayBookItself(tier: Tier, confirmed: Boolean): Boolean = when (tier) {
    Tier.VERIFIED -> true
    Tier.RESEARCHED -> confirmed
    Tier.GENERIC -> false
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
            is ParseResult.Parsed -> recordIfTrusted(result.sms, sender, body, receivedAt)
        }

    /**
     * Decides whether a parse may book itself.
     *
     * [Tier.VERIFIED] patterns were written against messages that arrived on the author's own
     * handset and book immediately. Everything else was written from a sample found in a
     * public source - real, but somebody else's, and possibly from before the bank last
     * changed its template - so it books only once this device has agreed with it. The
     * agreement is per pattern, not per bank: a bank's UPI alert and its ATM alert are
     * different sentences with independent chances of being wrong.
     *
     * [Tier.GENERIC] never passes, however many times it is confirmed. Those shapes match
     * across banks by design and a phishing SMS fits them perfectly; they exist to put
     * something readable in the tray, not to be believed.
     */
    private suspend fun recordIfTrusted(
        sms: ParsedSms,
        sender: String,
        body: String,
        receivedAt: Long,
    ): IngestOutcome {
        val confirmed = sms.tier == Tier.RESEARCHED && dao.isPatternConfirmed(sms.patternId)
        if (mayBookItself(sms.tier, confirmed)) return record(sms, receivedAt)

        dao.insertReview(
            PendingReview(
                sender = sender,
                rawMessage = body,
                receivedAt = receivedAt,
                reason = "read by ${sms.bank} pattern ${sms.patternId} - please confirm",
                patternId = sms.patternId,
            ),
        )
        return IngestOutcome.AwaitingConfirmation(sms.patternId)
    }

    /**
     * Accepts a tray entry that a pattern had already read, and remembers the pattern.
     *
     * The message is deliberately re-parsed rather than replayed from stored fields. The app
     * may have been updated since it was queued, and what a person is agreeing to is what the
     * code produces *now* - which is also what the tray just showed them. Storing nine columns
     * of parse alongside the raw text would only create a second version that could drift
     * from it.
     *
     * Recording then goes through [record] exactly as a live message would, so a confirmed
     * transaction gets the same duplicate detection, transfer pairing, ATM handling and
     * categorisation as one that never needed asking about.
     */
    suspend fun confirmAndIngest(review: PendingReview): IngestOutcome {
        val parsed = (parser.parse(review.sender, review.rawMessage, review.receivedAt)
            as? ParseResult.Parsed)?.sms
            ?: return IngestOutcome.Ignored("no pattern reads this message any more")

        // A generic shape is never promoted to trusted, however often it is accepted: it
        // matches across banks by design, so agreeing with it once says nothing about the
        // next message it will match.
        if (parsed.tier == Tier.RESEARCHED) {
            dao.confirmPattern(
                ConfirmedPattern(
                    patternId = parsed.patternId,
                    bank = parsed.bank,
                    confirmedAt = System.currentTimeMillis(),
                ),
            )
        }

        val outcome = record(parsed, review.receivedAt)
        dao.dismissReview(review.id)
        return outcome
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
        val choice = resolveCategory(sms, account.id)
        val txnId = insertTxn(
            sms,
            account,
            categoryId = choice?.categoryId,
            countsAsSpending = countsAsSpending,
            groupId = null,
            source = TxnSource.SMS,
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
        // A credit card is a debt, and this app deliberately does not pretend to know how
        // large it is. The figure a card alert carries is the remaining limit, which is not
        // money you hold; and the arithmetic fallback below would be worse than nothing,
        // because it would count only the spending seen since the app was installed and
        // present that as the whole of what you owe. The spending is recorded, which is the
        // part that matters. The balance stays out of it.
        if (account.kind == AccountKind.CREDIT_CARD) return

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
            kind = if (sms.isCard) AccountKind.CREDIT_CARD else AccountKind.BANK,
            // A card's "Avl Lmt" is not a balance, so a card never provides one. See
            // applyBalance for why its balance is left alone entirely.
            providesBalance = !sms.isCard && sms.availableBalancePaise != null,
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
