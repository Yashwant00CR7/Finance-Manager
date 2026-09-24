package com.yk.finance.domain

import com.yk.finance.backup.CsvExport
import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Category
import com.yk.finance.data.CategoryRule
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.GateMode
import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.importer.AccountBinder
import com.yk.finance.importer.Committer
import com.yk.finance.importer.CsvImporter
import com.yk.finance.importer.DiffMatcher
import com.yk.finance.importer.DryRunResult
import com.yk.finance.importer.ImportOutcome
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction

/** Everything the UI can do to the ledger. */
class Repository(
    private val dao: FinanceDao,
    private val imports: ImportService,
    /** Null in tests that do not exercise learning. See [CategoryModel]. */
    private val model: CategoryModel? = null,
    private val outcomes: GuessOutcomes = GuessOutcomes.None,
) {

    val accounts = dao.observeAccounts()
    val recentTransactions = dao.observeRecent()
    val categories = dao.observeCategories()
    val reviews = dao.observeReviews()
    val reviewCount = dao.observeReviewCount()
    val senders = dao.observeSenders()
    val gateState = dao.observeGateState()
    /** Senders you have not ruled on that have sent something money-shaped. */
    val unenrolledTransactional = dao.observeUnenrolledTransactional()
    val budgets = dao.observeBudgets()
    val cycle = dao.observeCycleState()

    /** History-ranked chips for the quick-add sheet. */
    val quickAdd = dao.observeQuickAdd()

    /** How many rows are currently carrying a guess nobody has looked at. */
    val guessCount = dao.observeGuessCount()

    /** Spending the app declined to guess a category for. */
    val needsCategory = dao.observeNeedsCategory()

    // ----- export -----

    /**
     * The whole ledger as CSV. A readable report, not a backup: balances, budgets and
     * cycle state are not representable here. Snapshot is the thing that restores.
     */
    suspend fun ledgerCsv(): String = buildString {
        CsvExport.write(
            transactions = dao.allTransactions(),
            accounts = dao.allAccounts(),
            categories = dao.allCategories(),
            out = this,
        )
    }

    // ----- import -----

    /** Every import this ledger has ever taken, newest first. */
    val importBatches = dao.observeImportBatches()

    /**
     * Runs importer stages 1-6 against a file: reads it, compares the result with what
     * the app captured for itself, and works out exactly what a commit would do.
     *
     * Writes nothing. Stage 7 is the only code that writes, it lives in [ImportService],
     * and it is never reached from here - so this can be run against real exports as
     * often as you like. That is what makes the one-shot import rehearsable.
     *
     * The comparison window opens at the later of the cycle start and the first
     * transaction the app ever recorded. Measuring across days when the app was not
     * installed would report a capture failure that never happened.
     */
    suspend fun dryRun(
        fileName: String,
        text: String,
        cycleStart: Long,
        now: Long,
    ): DryRunResult {
        val plan = when (val outcome = CsvImporter.read(fileName, text)) {
            is ImportOutcome.Refused -> return DryRunResult.Refused(outcome.reason)
            is ImportOutcome.Ready -> outcome.plan
        }

        val accounts = dao.allAccounts()
        val ledger = dao.allTransactions()
        val ledgerStart = ledger.minOfOrNull { it.occurredAt } ?: now
        val windowStart = maxOf(cycleStart, ledgerStart)
        val bindingFor = { name: String -> AccountBinder.bindingFor(accounts, name) }

        val diff = DiffMatcher.diff(
            plan = plan,
            ledger = ledger,
            bindingFor = bindingFor,
            windowStart = windowStart,
            windowEnd = now,
        )

        val commit = Committer.plan(
            batchId = "import-" + now.toString(36) + "-" + kotlin.math.abs(fileName.hashCode()).toString(36),
            plan = plan,
            diff = diff,
            bindingFor = bindingFor,
            knownFingerprints = dao.allFingerprints().toSet(),
            knownCategories = dao.allCategories().map { it.name }.toSet(),
            canonicalCategory = CategorySync::canonical,
        )

        return DryRunResult.Ready(
            plan = plan,
            diff = diff,
            bindings = plan.accountNames.map { name ->
                DryRunResult.Binding(name, AccountBinder.accountFor(accounts, name))
            },
            commit = commit,
        )
    }

    /**
     * Import day. Writes the plan the rehearsal just showed, in one transaction.
     *
     * Takes the rehearsal's own [DryRunResult.Ready] rather than a file, so what is
     * written is exactly what was on screen when you agreed to it - there is no second
     * read of the file between the preview and the write.
     */
    suspend fun commitImport(ready: DryRunResult.Ready, now: Long): CommitResult =
        imports.commit(ready.commit, ready.diff, now)

    suspend fun undoImport(batchId: String): Boolean = imports.undo(batchId)

    suspend fun anchorBalance(batchId: String, accountId: Long, actualBalancePaise: Long, now: Long) =
        imports.anchorBalance(batchId, accountId, actualBalancePaise, now)

    suspend fun latestImport() = dao.latestImportBatch()

    /**
     * Teaches an account the name another app knows it by.
     *
     * The alias is removed from every other account first: a name that resolved to two
     * accounts would make the diff silently wrong rather than visibly incomplete.
     */
    suspend fun bindAlias(accountId: Long, alias: String) {
        val wanted = alias.trim()
        if (wanted.isEmpty()) return
        dao.allAccounts().forEach { account ->
            val existing = AccountBinder.aliasesOf(account)
            val keep = existing.filterNot { it.equals(wanted, ignoreCase = true) }
            val next = if (account.id == accountId) keep + wanted else keep
            if (next != existing) {
                dao.updateAccount(account.copy(aliases = next.joinToString("\n").ifEmpty { null }))
            }
        }
    }

    // ----- setup -----

    /** Setup wizard: plain-language accounts, no bank codes or digits required. */
    suspend fun createDeclaredAccount(name: String, openingBalancePaise: Long, isCash: Boolean) {
        dao.insertAccount(
            Account(
                displayName = name,
                bank = if (isCash) "CASH" else "UNBOUND",
                // Unbound accounts get a unique placeholder token; the real token is
                // attached when you bind the first SMS to this account.
                accountToken = if (isCash) "CASH" else "PENDING-${System.nanoTime()}",
                kind = if (isCash) AccountKind.CASH else AccountKind.BANK,
                openingBalancePaise = openingBalancePaise,
                currentBalancePaise = openingBalancePaise,
                providesBalance = false,
                needsConfirmation = false,
            ),
        )
    }

    /**
     * One-tap binding: the auto-created account seen in an SMS is folded into the
     * account you declared at setup. Its transactions and balance move across.
     */
    suspend fun bindAutoAccountTo(autoCreated: Account, declared: Account) {
        // Order is load-bearing. accounts has a UNIQUE index on (bank, accountToken),
        // so the auto-created row must be gone before the declared row can adopt its
        // identity - otherwise both rows hold the same pair and the update throws.
        dao.reassignTransactions(fromAccountId = autoCreated.id, toAccountId = declared.id)
        dao.deleteAccount(autoCreated.id)
        dao.updateAccount(
            declared.copy(
                bank = autoCreated.bank,
                accountToken = autoCreated.accountToken,
                // The declared opening balance already reflects real money; the auto
                // account's balance is only the delta accumulated since it appeared.
                currentBalancePaise = declared.currentBalancePaise + autoCreated.currentBalancePaise,
                providesBalance = autoCreated.providesBalance,
                needsConfirmation = false,
            ),
        )
    }

    suspend fun renameAccount(account: Account, name: String) {
        dao.updateAccount(account.copy(displayName = name, needsConfirmation = false))
    }

    // ----- manual cash -----

    /**
     * A hand-entered expense, on any account.
     *
     * Cash is the ordinary case - this, not the ATM withdrawal, is what counts as
     * spending, which is what stops cash being double-counted. Entering one against a
     * bank account is allowed but is the caller's judgement: the bank will usually SMS
     * the same payment, and nothing here can tell the two apart.
     */
    suspend fun addSpend(
        accountId: Long,
        amountPaise: Long,
        payee: String?,
        note: String?,
        categoryId: Long?,
        occurredAt: Long,
    ): Long {
        val id = dao.insertTxn(
            Txn(
                accountId = accountId,
                direction = Direction.DEBIT,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = payee,
                reference = null,
                channel = Channel.UNKNOWN,
                categoryId = categoryId,
                countsAsSpending = true,
                source = TxnSource.MANUAL,
                note = note,
            ),
        )
        dao.adjustBalance(accountId, -amountPaise)
        return id
    }

    /** Removes a transaction and gives its money back. Undo for a mis-tapped chip. */
    suspend fun undoSpend(txnId: Long) {
        val txn = dao.txnById(txnId) ?: return
        dao.adjustBalance(
            txn.accountId,
            if (txn.direction == Direction.DEBIT) txn.amountPaise else -txn.amountPaise,
        )
        dao.deleteTxn(txn.id)
    }

    /** The default account for hand entry: cash, created on demand. */
    suspend fun ensureCashAccount(): Account {
        dao.cashAccount()?.let { return it }
        val created = Account(
            displayName = "Cash",
            bank = "CASH",
            accountToken = "CASH",
            kind = AccountKind.CASH,
        )
        return created.copy(id = dao.insertAccount(created))
    }

    suspend fun addCashSpend(
        amountPaise: Long,
        payee: String?,
        categoryId: Long?,
        occurredAt: Long,
        note: String? = null,
    ) {
        addSpend(ensureCashAccount().id, amountPaise, payee, note, categoryId, occurredAt)
    }

    fun unaccountedCash(cashAccountId: Long) = dao.observeUnaccountedCash(cashAccountId)

    // ----- categorisation -----

    /**
     * What one correction taught, so the interface can say it out loud and take it back.
     *
     * Learning used to be silent and permanent: you nudged a category while fixing a
     * note and changed what the app would do with that payee forever, with nothing on
     * screen to say so.
     */
    data class Learned(
        val payeeKey: String,
        val categoryId: Long,
        /** Rows the rule filed on your behalf. Not the row you tapped - you meant that one. */
        val backfilledIds: List<Long>,
        /**
         * The rows the back-fill filed, held whole rather than as ids.
         *
         * Undo clears their categories, so their examples have to come out of the
         * model with them - and the features are computed from the row, which by undo
         * time has already been rewritten in the database.
         *
         * The row you tapped is deliberately absent. Undo takes back the rule, not
         * your decision, so that row keeps its category and therefore keeps its place
         * in the model.
         */
        val backfilledRows: List<Txn> = emptyList(),
    )

    /**
     * One correction teaches the payee, and files rows that were never filed.
     *
     * The back-fill is bounded to rows with no category at all. Unbounded, it reached
     * through the whole ledger by raw payee text and overwrote decisions you had made
     * by hand - filing a shop as For Others in August, then tapping Food there in
     * September, silently rewrote August to Food and moved a total you had already read.
     *
     * Matching is on [Categorizer.payeeKey], the same normalised key the rule is stored
     * under. The old SQL matched the raw string, so the rule and its own back-fill
     * disagreed about which rows belonged to a payee.
     */
    suspend fun categorise(txn: Txn, categoryId: Long): Learned? {
        // A guess someone has looked at is no longer a guess, whichever way they
        // decided - and it is the looking, not the changing, that makes the row safe
        // to train on.
        if (txn.categoryWasInferred) {
            if (txn.categoryId == categoryId) outcomes.confirmed() else outcomes.corrected()
        }
        val decided = txn.copy(categoryId = categoryId, categoryWasInferred = false)
        dao.updateTxn(decided)

        // The model has to end up believing exactly what the ledger says, so a change
        // of mind is a swap rather than an addition. A row that already carried a
        // category it did not guess is already an example, and learning the new one
        // without retiring the old would leave it asserting both.
        txn.categoryId?.takeIf { !txn.categoryWasInferred }?.let { model?.unlearn(txn, it) }

        // Taught regardless of whether a rule is possible. A Union row has no payee to
        // key a rule on, so correcting one used to teach nothing at all - and those are
        // the rows with the most to teach, because they are the ones the app cannot
        // otherwise guess.
        model?.learn(decided, categoryId)

        val key = Categorizer.payeeKey(txn.payee)
            ?: return Learned("", categoryId, emptyList())
        dao.upsertRule(CategoryRule(payeeKey = key, categoryId = categoryId, learned = true))

        val candidates = dao.uncategorisedWithPayee()
        val backfilled = Categorizer.backfillTargets(candidates, key, txn.id)
        if (backfilled.isEmpty()) return Learned(key, categoryId, backfilled)

        dao.setCategoryForIds(backfilled, categoryId)
        // Back-filled rows had no category, so they were not examples before and need
        // no retirement - only the new example each of them now is.
        val filled = candidates.filter { it.id in backfilled.toSet() }
        filled.forEach { model?.learn(it.copy(categoryId = categoryId), categoryId) }
        return Learned(key, categoryId, backfilled, backfilledRows = filled)
    }

    /**
     * Accepts a guess exactly as it stands.
     *
     * Not the same as calling [categorise] with the category already there: this is the
     * one-tap path through a list of guesses, and it deliberately writes no rule. The
     * model earned this row, so letting it also mint a permanent payee rule would turn
     * a glance into a commitment.
     */
    suspend fun confirmGuess(txn: Txn) {
        val categoryId = txn.categoryId ?: return
        if (!txn.categoryWasInferred) return
        dao.confirmGuess(txn.id)
        outcomes.confirmed()
        model?.learn(txn.copy(categoryWasInferred = false), categoryId)
    }

    /**
     * Takes back a rule and un-files exactly the rows it filed.
     *
     * The model follows the ledger here rather than the rule. Undo clears the
     * back-filled categories, so those examples go with them; it leaves the row you
     * tapped alone, so that example stays. Anything else and the model would start
     * disagreeing with the transactions it claims to be a projection of.
     */
    suspend fun undoLearned(learned: Learned) {
        learned.backfilledRows.forEach { model?.unlearn(it, learned.categoryId) }
        if (learned.payeeKey.isNotEmpty()) dao.deleteRuleByKey(learned.payeeKey)
        if (learned.backfilledIds.isNotEmpty()) dao.clearCategoryForIds(learned.backfilledIds)
    }

    // ----- v2.2: split bills -----

    /**
     * Writes a split, and returns the category it should teach the payee.
     *
     * Balance is deliberately untouched throughout. The bank took 320 once and said so
     * once; carving it into 200 and 120 afterwards moves no money, and an adjustBalance
     * anywhere in this path would invent some on every split.
     */
    suspend fun applySplit(anchor: Txn, parts: List<SplitPart>): Long? {
        val group = anchor.splitGroupId?.let { dao.splitGroup(it) } ?: listOf(anchor)
        val groupId = anchor.splitGroupId ?: java.util.UUID.randomUUID().toString()
        val sharing = sharingLookup()

        val plan = Splitter.plan(group, parts, groupId, sharing)
        plan.updates.forEach { dao.updateTxn(it) }
        plan.inserts.forEach { dao.insertTxn(it) }
        if (plan.deleteIds.isNotEmpty()) dao.deleteTxns(plan.deleteIds)

        return Splitter.learnTarget(parts, sharing)
    }

    /**
     * Records a new payment already divided.
     *
     * For cash, which sends no message and so can never be come back to: if the bill
     * cannot be split at the moment you key it in, it never will be. The full amount
     * is booked once - which is the single balance movement - and only then divided,
     * so the account moves by what you actually paid and by nothing else.
     */
    suspend fun addSplitSpend(
        accountId: Long,
        amountPaise: Long,
        payee: String?,
        note: String?,
        occurredAt: Long,
        parts: List<SplitPart>,
    ): Long? {
        val id = addSpend(accountId, amountPaise, payee, note, parts.firstOrNull()?.categoryId, occurredAt)
        val anchor = dao.txnById(id) ?: return null
        return applySplit(anchor, parts)
    }

    /** Teaches the payee from a split, if there was anything worth teaching. */
    suspend fun learnFromSplit(anchor: Txn, categoryId: Long): Learned? {
        val key = Categorizer.payeeKey(anchor.payee) ?: return null
        dao.upsertRule(CategoryRule(payeeKey = key, categoryId = categoryId, learned = true))
        return Learned(key, categoryId, emptyList())
    }

    /** Merges a split back into one row. Null when a repayment is still attached. */
    suspend fun unsplit(groupId: String): Boolean {
        val plan = Splitter.unsplit(dao.splitGroup(groupId)) ?: return false
        plan.updates.forEach { dao.updateTxn(it) }
        if (plan.deleteIds.isNotEmpty()) dao.deleteTxns(plan.deleteIds)
        return true
    }

    /**
     * Credits that could be somebody paying you back, newest first.
     *
     * Offered for you to pick from rather than matched automatically. A 120 refund from
     * a shop and a 120 repayment from Arun are the same number, and an app that guesses
     * between them is wrong in a way that is very hard to notice afterwards.
     */
    suspend fun settlementCandidates(limit: Int = 20): List<Txn> =
        dao.allCredits()
            .filter {
                it.splitGroupId == null &&
                    it.transferGroupId == null &&
                    it.source != TxnSource.TRANSFER_LEG &&
                    it.source != TxnSource.ADJUSTMENT
            }
            .sortedByDescending { it.occurredAt }
            .take(limit)

    /** Attaches an existing credit to the debt it clears. Balance already reflects it. */
    suspend fun settleWithCredit(groupId: String, creditId: Long) {
        val credit = dao.txnById(creditId) ?: return
        dao.updateTxn(credit.copy(splitGroupId = groupId, source = TxnSource.SETTLEMENT))
    }

    /** Cash handed back. Real money arriving, so this one does move the balance. */
    suspend fun settleWithCash(groupId: String, amountPaise: Long, occurredAt: Long, from: String?) {
        val cash = ensureCashAccount()
        dao.insertTxn(
            Txn(
                accountId = cash.id,
                direction = Direction.CREDIT,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = from,
                reference = null,
                countsAsSpending = false,
                source = TxnSource.SETTLEMENT,
                splitGroupId = groupId,
                note = "Repaid",
            ),
        )
        dao.adjustBalance(cash.id, amountPaise)
    }

    /**
     * Detaches a repayment from the debt it was clearing.
     *
     * The row goes back to being an ordinary credit rather than being deleted - it is
     * money that genuinely arrived in an account, and deleting it would take a real
     * balance backwards to undo a piece of bookkeeping.
     */
    suspend fun unsettle(txnId: Long) {
        val txn = dao.txnById(txnId) ?: return
        val restored = if (txn.rawMessage != null) TxnSource.SMS else TxnSource.MANUAL
        dao.updateTxn(txn.copy(splitGroupId = null, source = restored))
    }

    /**
     * Accepts that a debt is not coming back.
     *
     * Converted where it stands, on the day it happened, rather than booked as a fresh
     * expense today - the money left your account that afternoon and it was always that
     * month's spending. You only found out later. The month's total moves, which is why
     * the interface states the figure before doing it.
     */
    suspend fun writeOff(txnId: Long, givenCategoryId: Long) {
        val txn = dao.txnById(txnId) ?: return
        dao.updateTxn(
            txn.copy(
                categoryId = givenCategoryId,
                countsAsSpending = true,
                owedBy = null,
            ),
        )
    }

    private suspend fun sharingLookup(): (Long?) -> Sharing {
        val byId = dao.allCategories().associateBy { it.id }
        return { id -> id?.let { byId[it]?.sharing } ?: Sharing.NONE }
    }

    // ----- reconcile (ICICI and any bank that sends no Avl Bal) -----

    /**
     * Books the gap between computed and real balance as a visible ADJUSTMENT row
     * rather than quietly rewriting the number. The size of these adjustments is the
     * honest measure of how much the parser is missing.
     */
    suspend fun reconcile(account: Account, actualBalancePaise: Long, now: Long) {
        val delta = actualBalancePaise - account.currentBalancePaise
        if (delta != 0L) {
            dao.insertTxn(
                Txn(
                    accountId = account.id,
                    direction = if (delta < 0) Direction.DEBIT else Direction.CREDIT,
                    amountPaise = kotlin.math.abs(delta),
                    occurredAt = now,
                    payee = "Balance correction",
                    reference = null,
                    countsAsSpending = false,
                    source = TxnSource.ADJUSTMENT,
                    note = "Reconciled to actual balance",
                ),
            )
        }
        dao.setBalance(account.id, actualBalancePaise)
        dao.updateAccount(account.copy(lastReconciledAt = now, currentBalancePaise = actualBalancePaise))
    }

    /** True when a non-self-healing account has not been confirmed this cycle. */
    suspend fun needsReconcilePrompt(account: Account): Boolean {
        if (account.providesBalance || account.kind == AccountKind.CASH) return false
        val cycle = dao.cycleState() ?: return false
        return (account.lastReconciledAt ?: 0L) < cycle.cycleStartMillis
    }

    // ----- salary tagging -----

    /** Teaches the cycle anchor from a credit you identify as salary. */
    suspend fun markAsSalary(txn: Txn) {
        val account = dao.accountById(txn.accountId) ?: return
        val state = dao.cycleState() ?: CycleCalculator.seed(txn.occurredAt)
        // The boundary is written as well as the state: without it the period strip
        // would forget this cycle ever started the moment the next one rolls.
        CycleBackfill.record(dao, txn.occurredAt, fromSalary = true, txnId = txn.id)
        dao.upsertCycle(
            state.copy(
                salaryBank = account.bank,
                salaryPayee = txn.payee,
                salaryAmountPaise = txn.amountPaise,
                cycleStartMillis = CycleCalculator.startOfDayMillis(
                    CycleCalculator.toLocalDate(txn.occurredAt),
                ),
                lastRollMillis = txn.occurredAt,
            ),
        )
    }

    // ----- budgets & review tray -----

    /**
     * Writes a limit, either standing or pinned to one cycle.
     *
     * [thisCycleOnly] is the difference between "Food is 4,500 from now on" and "Food
     * is 5,000 in September because of the wedding" - the second must not silently
     * become the first, which is the whole reason Budget carries a periodStart.
     */
    suspend fun setBudget(
        categoryId: Long?,
        limitPaise: Long,
        periodStart: Long,
        thisCycleOnly: Boolean,
    ) {
        dao.upsertBudget(
            BudgetResolver.rowToWrite(
                budgets = dao.allBudgets(),
                categoryId = categoryId,
                limitPaise = limitPaise,
                periodStart = periodStart,
                thisCycleOnly = thisCycleOnly,
            ),
        )
    }

    suspend fun deleteBudget(id: Long) = dao.deleteBudget(id)

    suspend fun dismissReview(id: Long) = dao.dismissReview(id)

    /** Rescues a message the parser could not read, as a hand-entered transaction. */
    suspend fun recordFromReview(
        reviewId: Long,
        accountId: Long,
        amountPaise: Long,
        direction: Direction,
        payee: String?,
        categoryId: Long?,
        occurredAt: Long,
    ) {
        dao.insertTxn(
            Txn(
                accountId = accountId,
                direction = direction,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = payee,
                reference = null,
                categoryId = categoryId,
                countsAsSpending = direction == Direction.DEBIT,
                source = TxnSource.MANUAL,
            ),
        )
        dao.adjustBalance(accountId, if (direction == Direction.DEBIT) -amountPaise else amountPaise)
        dao.dismissReview(reviewId)
    }

    // ----- v2.0: editing what is already recorded -----

    /**
     * Rewrites a transaction and repairs both balances.
     *
     * The old effect is reversed before the new one is applied, and the account may
     * change, so this is two adjustments rather than a delta - moving a 500 expense
     * from Card to Cash has to give Card its money back and take it from Cash.
     *
     * SMS-sourced rows keep their rawMessage and reference whatever is edited: the
     * message the bank actually sent is evidence, and nothing on the edit screen is
     * allowed to erase it.
     */
    suspend fun updateTransaction(
        id: Long,
        accountId: Long,
        direction: Direction,
        amountPaise: Long,
        occurredAt: Long,
        payee: String?,
        note: String?,
        categoryId: Long?,
    ) {
        val existing = dao.txnById(id) ?: return
        // A split part's *amount* is the group's business, never one row's. Let this
        // through and the group stops summing to what the bank took - 200 + 150 against
        // a 320 debit - while every part still looks like a perfectly ordinary
        // transaction, so nothing on screen would ever show the disagreement. Narrowed
        // to the amount rather than the whole row, because fixing a note or a date on
        // one part breaks nothing.
        if (existing.splitGroupId != null && amountPaise != existing.amountPaise) return
        dao.adjustBalance(
            existing.accountId,
            if (existing.direction == Direction.DEBIT) existing.amountPaise else -existing.amountPaise,
        )
        val countsAsSpending = when {
            // A transfer leg or a correction stays what it is; editing its note must
            // not promote it into spending and double-count money that never moved.
            existing.source == TxnSource.TRANSFER_LEG -> false
            existing.source == TxnSource.ADJUSTMENT -> false
            else -> direction == Direction.DEBIT
        }
        dao.updateTxn(
            existing.copy(
                accountId = accountId,
                direction = direction,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = payee,
                note = note,
                categoryId = categoryId,
                countsAsSpending = countsAsSpending,
            ),
        )
        dao.adjustBalance(accountId, if (direction == Direction.DEBIT) -amountPaise else amountPaise)
    }

    /**
     * Teaches a payee from an edit, reporting it so the interface can offer Undo.
     *
     * Was fired inside updateTransaction with no return value and no notice, so changing
     * a category while fixing a note rewrote what the app would do with that payee
     * forever and said nothing.
     */
    suspend fun learnFromEdit(payee: String?, categoryId: Long): Learned? {
        val key = Categorizer.payeeKey(payee) ?: return null
        dao.upsertRule(CategoryRule(payeeKey = key, categoryId = categoryId, learned = true))
        return Learned(key, categoryId, emptyList())
    }

    /** Deletes a transaction and gives its money back. Both legs go if it is a transfer. */
    suspend fun deleteTransaction(id: Long) {
        val txn = dao.txnById(id) ?: return
        val group = txn.transferGroupId
        val rows = if (group != null) dao.transferLegs(group) else listOf(txn)
        rows.forEach { row ->
            dao.adjustBalance(
                row.accountId,
                if (row.direction == Direction.DEBIT) row.amountPaise else -row.amountPaise,
            )
            dao.deleteTxn(row.id)
        }
    }

    /** Hand-entered income. The mirror of addSpend, and never counted as spending. */
    suspend fun addIncome(
        accountId: Long,
        amountPaise: Long,
        payee: String?,
        note: String?,
        categoryId: Long?,
        occurredAt: Long,
    ): Long {
        val id = dao.insertTxn(
            Txn(
                accountId = accountId,
                direction = Direction.CREDIT,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = payee,
                reference = null,
                channel = Channel.UNKNOWN,
                categoryId = categoryId,
                countsAsSpending = false,
                source = TxnSource.MANUAL,
                note = note,
            ),
        )
        dao.adjustBalance(accountId, amountPaise)
        return id
    }

    /**
     * Money moved between your own accounts: two legs sharing a group id, neither
     * counted as spending. This is the same shape the SMS path produces for an ATM
     * withdrawal, so a transfer entered by hand and one detected from a message are
     * indistinguishable downstream - which is what stops either being read as an expense.
     */
    suspend fun addTransfer(
        fromAccountId: Long,
        toAccountId: Long,
        amountPaise: Long,
        note: String?,
        occurredAt: Long,
    ): Long {
        val groupId = "manual-" + occurredAt.toString(36) + "-" + System.nanoTime().toString(36)
        val out = dao.insertTxn(
            Txn(
                accountId = fromAccountId,
                direction = Direction.DEBIT,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = "Transfer",
                reference = null,
                countsAsSpending = false,
                transferGroupId = groupId,
                source = TxnSource.TRANSFER_LEG,
                note = note,
            ),
        )
        dao.insertTxn(
            Txn(
                accountId = toAccountId,
                direction = Direction.CREDIT,
                amountPaise = amountPaise,
                occurredAt = occurredAt,
                payee = "Transfer",
                reference = null,
                countsAsSpending = false,
                transferGroupId = groupId,
                source = TxnSource.TRANSFER_LEG,
                note = note,
            ),
        )
        dao.adjustBalance(fromAccountId, -amountPaise)
        dao.adjustBalance(toAccountId, amountPaise)
        return out
    }

    // ----- v2.0: categories, their looks, and the rules that feed them -----

    val rules = dao.observeRules()

    suspend fun createCategory(name: String, isIncome: Boolean): Long {
        val wanted = CategorySync.canonical(name.trim())
        if (wanted.isEmpty()) return -1
        dao.categoryByName(wanted)?.let { return it.id }
        return dao.insertCategoryReturningId(
            Category(
                name = wanted,
                isIncome = isIncome,
                iconKey = Looks.categoryIcon(wanted),
                colourHex = Looks.categoryColour(wanted),
            ),
        )
    }

    suspend fun updateCategory(category: Category, name: String, iconKey: String, colourHex: String) {
        val wanted = name.trim().ifEmpty { category.name }
        dao.updateCategory(category.copy(name = wanted, iconKey = iconKey, colourHex = colourHex))
    }

    /**
     * Deletes a category, moving everything that points at it somewhere else first.
     *
     * A destination is required rather than optional: transactions left holding a
     * deleted id read on screen as spending that lost its label for no reason, and the
     * learned rules would keep filing new spending into a category that is gone.
     */
    suspend fun deleteCategory(category: Category, moveToId: Long) {
        if (category.id == moveToId) return
        dao.moveTransactionsToCategory(category.id, moveToId)
        dao.moveRulesToCategory(category.id, moveToId)
        if (dao.allBudgets().any { it.categoryId == moveToId }) {
            dao.deleteBudgetsForCategory(category.id)
        } else {
            dao.moveBudgetsToCategory(category.id, moveToId)
        }
        dao.deleteCategory(category.id)
    }

    /** Forgets one learned payee mapping. The transactions it already filed stay put. */
    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    suspend fun updateAccountLook(account: Account, name: String, iconKey: String, colourHex: String) {
        dao.updateAccount(
            account.copy(
                displayName = name.trim().ifEmpty { account.displayName },
                iconKey = iconKey,
                colourHex = colourHex,
                needsConfirmation = false,
            ),
        )
    }

    // ----- v2.0: periods -----

    val boundaries = dao.observeBoundaries()

    val allTransactions = dao.observeAllTransactions()

    // ----- sender registry -----

    suspend fun enrollSender(header: String, bankKey: String?) =
        SenderEnrollment.enroll(dao, header, bankKey)

    suspend fun unenrollSender(header: String) = SenderEnrollment.unenroll(dao, header)

    suspend fun dismissSender(header: String) = SenderEnrollment.dismiss(dao, header)

    suspend fun setGateMode(mode: GateMode) = SenderEnrollment.setMode(dao, mode)

    /** How much a sender has actually produced, for an honest un-enrol confirmation. */
    suspend fun txnCountForSender(header: String): Int = dao.txnCountForSender(header)
}
