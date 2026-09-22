package com.yk.finance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Budget
import com.yk.finance.data.Category
import com.yk.finance.data.CategoryRule
import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.data.ImportBatch
import com.yk.finance.data.PendingReview
import com.yk.finance.data.Prefs
import com.yk.finance.data.QuickAddSuggestion
import com.yk.finance.data.Sharing
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.BudgetEvaluator
import com.yk.finance.domain.BudgetProgress
import com.yk.finance.domain.BudgetResolver
import com.yk.finance.domain.CategoryModel
import com.yk.finance.domain.CommitResult
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.Looks
import com.yk.finance.domain.Period
import com.yk.finance.domain.Periods
import com.yk.finance.domain.Repository
import com.yk.finance.domain.SplitPart
import com.yk.finance.domain.Splitter
import com.yk.finance.domain.UNCATEGORISED
import com.yk.finance.importer.DryRunResult
import com.yk.finance.parser.Direction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which kinds of row the Records list is showing.
 *
 * [label] exists because the filter sheet used to render `option.name` - four shouting
 * enum constants that did not fit the width and broke mid-word into EXPENS/E and
 * TRANSF/ER. The display name is the enum's business, not each call site's.
 */
enum class TypeFilter(val label: String) {
    ALL("All"),
    INCOME("Income"),
    EXPENSE("Expense"),
    TRANSFER("Transfer"),
}

/**
 * The filter sheet's state.
 *
 * [isActive] drives the chip that stays on screen while anything is applied - a
 * filtered list whose header still reads the full period total would be read as the
 * real total, which is the one way a filter can lie.
 */
data class RecordFilter(
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val type: TypeFilter = TypeFilter.ALL,
    val uncategorisedOnly: Boolean = false,
    /** Rows the model filed and nobody has looked at yet. */
    val guessedOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = accountId != null || categoryId != null ||
            type != TypeFilter.ALL || uncategorisedOnly || guessedOnly
}

/**
 * The rows [filter] admits.
 *
 * Lifted out of the view model because the header's figures are now computed from the
 * same narrowing - the list and the totals above it have to agree about what is being
 * shown, and the cheapest way to guarantee that is for both to call this.
 */
internal fun RecordFilter.apply(rows: List<Txn>): List<Txn> = rows.filter { txn ->
    val accountOk = accountId == null || txn.accountId == accountId
    val categoryOk = categoryId == null || txn.categoryId == categoryId
    val typeOk = when (type) {
        TypeFilter.ALL -> true
        TypeFilter.EXPENSE -> Ledger.isExpense(txn)
        TypeFilter.INCOME -> Ledger.isIncome(txn)
        TypeFilter.TRANSFER -> txn.transferGroupId != null || txn.source == TxnSource.TRANSFER_LEG
    }
    val uncategorisedOk = !uncategorisedOnly ||
        (txn.categoryId == null && Ledger.isExpense(txn))
    val guessedOk = !guessedOnly || txn.categoryWasInferred
    accountOk && categoryOk && typeOk && uncategorisedOk && guessedOk
}

data class UiState(
    val accounts: List<Account> = emptyList(),
    val transactions: List<Txn> = emptyList(),
    val categories: List<Category> = emptyList(),
    val reviews: List<PendingReview> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val cycle: CycleState? = null,
    val boundaries: List<CycleBoundary> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
) {
    val bankAccounts get() = accounts.filter { it.kind == AccountKind.BANK }

    /**
     * Categories money can go *out* to. Income categories are excluded here rather
     * than filtered at each call site, so "Salary" can never be offered as somewhere
     * a payment went.
     */
    val expenseCategories get() = categories.filter { !it.isIncome }

    val incomeCategories get() = categories.filter { it.isIncome }
    val cashAccount get() = accounts.firstOrNull { it.kind == AccountKind.CASH }
    val netWorthPaise get() = accounts.sumOf { it.currentBalancePaise }

    /** Accounts auto-created from an unseen SMS, awaiting your one-tap confirmation. */
    val unconfirmed get() = accounts.filter { it.needsConfirmation }

    fun categoryName(id: Long?): String =
        id?.let { cid -> categories.firstOrNull { it.id == cid }?.name } ?: UNCATEGORISED

    fun categoryById(id: Long?): Category? =
        id?.let { cid -> categories.firstOrNull { it.id == cid } }

    fun accountById(id: Long?): Account? =
        id?.let { aid -> accounts.firstOrNull { it.id == aid } }

    fun iconKeyFor(categoryId: Long?): String =
        categoryById(categoryId)?.iconKey ?: Looks.categoryIcon(categoryName(categoryId))

    fun colourFor(categoryId: Long?): String =
        categoryById(categoryId)?.colourHex ?: Looks.categoryColour(categoryName(categoryId))

    /** How a category treats somebody else's money. NONE for anything unrecognised. */
    fun sharingOf(categoryId: Long?): Sharing = categoryById(categoryId)?.sharing ?: Sharing.NONE

    /**
     * Whether picking this category should open the split sheet.
     *
     * Keyed to the category rather than offered on every transaction: a Split entry
     * sitting at the top of the picker would be in the way of every ordinary
     * categorisation you ever make, for the sake of one you make twice a week. Choosing
     * "For Others" already says somebody else's money is involved, so that is the
     * moment to ask how much of it.
     */
    fun splitsOnPick(categoryId: Long?): Boolean = sharingOf(categoryId) != Sharing.NONE

    /** Where a written-off debt lands: the category meaning money simply given away. */
    val givenCategory get() = categories.firstOrNull { it.sharing == Sharing.GIVEN }

    /** Names you have already used, so the same person is spelled the same way twice. */
    val knownPeople: List<String> get() = transactions
        .mapNotNull { it.owedBy?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .sorted()
}

/** Guesses a person has looked at, split by whether the model had been right. */
data class GuessStats(val confirmed: Int, val corrected: Int) {
    val total get() = confirmed + corrected
    val hasEnoughToReport get() = total >= 5
}

class FinanceViewModel(
    private val repository: Repository,
    private val evaluator: BudgetEvaluator,
    private val model: CategoryModel,
    private val prefs: Prefs,
) : ViewModel() {

    /** Rows carrying a guess nobody has confirmed or corrected. */
    val guessCount: StateFlow<Int> = repository.guessCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _autoFile = MutableStateFlow(prefs.autoFileCategories)
    val autoFile: StateFlow<Boolean> = _autoFile

    fun setAutoFile(on: Boolean) {
        prefs.autoFileCategories = on
        model.autoFileEnabled = on
        _autoFile.value = on
    }

    private val _notifyOnRecord = MutableStateFlow(prefs.notifyOnRecord)
    val notifyOnRecord: StateFlow<Boolean> = _notifyOnRecord

    // No model or ledger state to keep in step - SmsReceiver reads the pref directly at
    // the moment it would post, so flipping this takes effect on the very next message.
    fun setNotifyOnRecord(on: Boolean) {
        prefs.notifyOnRecord = on
        _notifyOnRecord.value = on
    }

    private val _guessStats = MutableStateFlow(GuessStats(prefs.guessesConfirmed, prefs.guessesCorrected))
    val guessStats: StateFlow<GuessStats> = _guessStats

    private fun refreshGuessStats() {
        _guessStats.value = GuessStats(prefs.guessesConfirmed, prefs.guessesCorrected)
    }

    /**
     * The model's top few categories for a row, to float to the head of a picker.
     *
     * Capped deliberately. Reordering an entire list by a model this small would cost
     * more than it gives - the order of a category list is something you learn, and
     * shuffling all of it every time would make a category you know is there harder to
     * find than leaving it alone.
     */
    fun suggestedCategoryIds(txn: Txn?): List<Long> {
        val row = txn ?: return emptyList()
        val allowed = state.value.expenseCategories.map { it.id }.toSet()
        if (allowed.isEmpty()) return emptyList()
        return model.rank(row, allowed).take(SUGGESTIONS).map { it.categoryId }
    }

    /** Accepts a guess as it stands, without minting a payee rule. See Repository. */
    fun confirmGuess(txn: Txn) = viewModelScope.launch {
        repository.confirmGuess(txn)
        refreshGuessStats()
    }

    // combine() only has typed overloads up to five flows; a sixth would fall back to
    // the vararg form and erase every type to Any?. Nesting keeps this checked.
    private val core = combine(
        repository.accounts,
        repository.allTransactions,
        repository.categories,
        repository.reviews,
    ) { accounts, transactions, categories, reviews ->
        UiState(
            accounts = accounts,
            transactions = transactions,
            categories = categories,
            reviews = reviews,
        )
    }

    /** Carries the second four flows as one value, so nothing has to be cast back. */
    private data class LedgerExtras(
        val budgets: List<Budget>,
        val cycle: CycleState?,
        val boundaries: List<CycleBoundary>,
        val rules: List<CategoryRule>,
    )

    private val ledgerExtras = combine(
        repository.budgets,
        repository.cycle,
        repository.boundaries,
        repository.rules,
        ::LedgerExtras,
    )

    val state: StateFlow<UiState> = combine(core, ledgerExtras) { base, extras ->
        base.copy(
            budgets = extras.budgets,
            cycle = extras.cycle,
            boundaries = extras.boundaries,
            rules = extras.rules,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // ----- the period strip -----

    /**
     * Null means "the cycle I am in", which is where the app opens and where it
     * returns after a roll. Pinning an index instead would silently shift everything
     * by one the moment a new boundary was recorded.
     */
    private val selectedStart = MutableStateFlow<Long?>(null)

    /**
     * Calendar months, bounded by the ledger.
     *
     * Derived from the transactions rather than from [UiState.boundaries]: a period is
     * now a month, and a month exists because something happened in it. The old
     * boundary-driven list could collapse to a single period spanning all of time - one
     * bucket holding July through September while the header called it September - and
     * that is exactly the figure this replaces.
     */
    val periods: StateFlow<List<Period>> = state
        .map { s ->
            Periods.months(
                earliestMillis = s.transactions.minOfOrNull { it.occurredAt },
                latestMillis = s.transactions.maxOfOrNull { it.occurredAt },
                now = System.currentTimeMillis(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Falls back to the open month rather than the last one: a future-dated entry
    // extends the list past today, and opening the app onto a month that has not
    // happened yet would report an empty September as your September.
    val period: StateFlow<Period?> = combine(periods, selectedStart) { list, selected ->
        list.firstOrNull { it.startMillis == selected }
            ?: list.firstOrNull { it.isOpen }
            ?: list.lastOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The salary cycles the app has detected, for the Settings card only.
     *
     * Nothing else reads these any more. They are kept and still recorded because they
     * describe your payroll, which is a real thing worth knowing - but they no longer
     * decide what "September" means anywhere in the interface.
     */
    val salaryCycles: StateFlow<List<Period>> = state
        .map { Periods.from(it.boundaries, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun showPreviousPeriod() {
        val list = periods.value
        val index = list.indexOfFirst { it.startMillis == period.value?.startMillis }
        if (index > 0) selectedStart.value = list[index - 1].startMillis
    }

    fun showNextPeriod() {
        val list = periods.value
        val index = list.indexOfFirst { it.startMillis == period.value?.startMillis }
        if (index >= 0 && index < list.lastIndex) selectedStart.value = list[index + 1].startMillis
    }

    val hasPreviousPeriod: StateFlow<Boolean> = combine(periods, period) { list, current ->
        list.indexOfFirst { it.startMillis == current?.startMillis } > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasNextPeriod: StateFlow<Boolean> = combine(periods, period) { list, current ->
        val index = list.indexOfFirst { it.startMillis == current?.startMillis }
        index >= 0 && index < list.lastIndex
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ----- what the Records list shows -----

    private val _filter = MutableStateFlow(RecordFilter())
    val filter: StateFlow<RecordFilter> = _filter

    fun setFilter(filter: RecordFilter) { _filter.value = filter }
    fun clearFilter() { _filter.value = RecordFilter() }

    /** The selected cycle's rows, unfiltered. The header totals read from this. */
    val periodTransactions: StateFlow<List<Txn>> = combine(state, period) { s, p ->
        if (p == null) s.transactions else s.transactions.filter { p.contains(it.occurredAt) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the list actually renders: the period's rows with the filter applied. */
    val visibleTransactions: StateFlow<List<Txn>> =
        combine(periodTransactions, _filter) { rows, f -> f.apply(rows) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totals: StateFlow<Ledger.Totals> = periodTransactions
        .map { Ledger.totals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ledger.Totals(0, 0))

    /**
     * The totals for exactly the rows on screen.
     *
     * The header used to show [totals] whatever was filtered, so narrowing to Food left
     * the month's full expense sitting above twelve Food rows looking like their sum.
     * Records reads this instead; Analysis and Budgets keep reading [totals], because
     * the donut and the budget bars are not filtered and their header must not claim
     * to be.
     */
    val filteredTotals: StateFlow<Ledger.Totals> = visibleTransactions
        .map { Ledger.totals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ledger.Totals(0, 0))

    // ----- v2.2: money other people owe you -----

    /**
     * Across the whole ledger, not just the month on screen.
     *
     * A debt does not belong to the month it was incurred in - it is outstanding until
     * it is paid, and scoping this to the period strip would make what you are owed
     * disappear the moment you walked back to look at something else.
     */
    val owed: StateFlow<List<Splitter.Owed>> = state
        .map { s -> Splitter.owed(s.transactions) { id -> s.sharingOf(id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The rule just learned, if the interface still owes you the chance to undo it.
     *
     * Held here rather than passed to a screen, because learning happens on two paths -
     * the category picker and the edit screen - and both must be able to say what they
     * did in the same words.
     */
    private val _learned = MutableStateFlow<Repository.Learned?>(null)
    val learned: StateFlow<Repository.Learned?> = _learned

    fun dismissLearned() { _learned.value = null }

    fun undoLearned() = viewModelScope.launch {
        _learned.value?.let { repository.undoLearned(it) }
        _learned.value = null
        refreshDerived()
    }

    // ----- search, across every period -----

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(text: String) { _query.value = text }

    val searchResults: StateFlow<List<Txn>> = combine(state, _query) { s, q ->
        val needle = q.trim().lowercase()
        if (needle.length < 2) {
            emptyList()
        } else {
            s.transactions.filter { txn ->
                val category = s.categoryName(txn.categoryId).lowercase()
                val account = s.accountById(txn.accountId)?.displayName?.lowercase().orEmpty()
                val amount = (txn.amountPaise / 100).toString()
                txn.payee?.lowercase()?.contains(needle) == true ||
                    txn.note?.lowercase()?.contains(needle) == true ||
                    category.contains(needle) ||
                    account.contains(needle) ||
                    amount.contains(needle)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ----- analysis -----

    private val _showIncomeAnalysis = MutableStateFlow(false)
    val showIncomeAnalysis: StateFlow<Boolean> = _showIncomeAnalysis

    fun setAnalysisMode(income: Boolean) { _showIncomeAnalysis.value = income }

    val breakdown: StateFlow<List<Ledger.Slice>> =
        combine(periodTransactions, state, _showIncomeAnalysis) { rows, s, income ->
            Ledger.breakdown(rows, s.categories, income)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ----- budgets, resolved for the selected cycle -----

    val progress: StateFlow<List<BudgetProgress>> =
        combine(state, period, periodTransactions) { s, p, rows ->
            val start = p?.startMillis ?: return@combine emptyList()
            val spending = rows.filter { Ledger.isExpense(it) }
            BudgetResolver.effective(s.budgets, start).map { effective ->
                val budget = effective.budget
                val spent = if (budget.categoryId == null) {
                    spending.sumOf { it.amountPaise }
                } else {
                    spending.filter { it.categoryId == budget.categoryId }.sumOf { it.amountPaise }
                }
                BudgetProgress(
                    budgetId = budget.id,
                    categoryId = budget.categoryId,
                    categoryName = budget.categoryId?.let { s.categoryName(it) } ?: "Overall",
                    limitPaise = budget.limitPaise,
                    spentPaise = spent,
                    isOverride = effective.isOverride,
                )
            }.sortedByDescending { it.spentPaise }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uncategorisedSpend: StateFlow<Long> = periodTransactions
        .map { rows ->
            rows.filter { Ledger.isExpense(it) && it.categoryId == null }.sumOf { it.amountPaise }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val overall: StateFlow<Ledger.Overall> = state
        .map { Ledger.overall(it.transactions, it.accounts) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            Ledger.Overall(0, 0, 0, 0, 0),
        )

    // ----- the inbox: everything asking for your attention -----

    private val _reconcileNeeded = MutableStateFlow<List<Account>>(emptyList())
    val reconcileNeeded: StateFlow<List<Account>> = _reconcileNeeded

    val quickAdd: StateFlow<List<QuickAddSuggestion>> = repository.quickAdd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val needsCategory: StateFlow<List<Txn>> = repository.needsCategory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inboxCount: StateFlow<Int> = combine(
        state,
        needsCategory,
        _reconcileNeeded,
    ) { s, needs, reconcile ->
        s.reviews.size + s.unconfirmed.size + needs.size + reconcile.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _lastAdded = MutableStateFlow<Long?>(null)
    val lastAdded: StateFlow<Long?> = _lastAdded

    fun refreshDerived() = viewModelScope.launch {
        _reconcileNeeded.value = state.value.bankAccounts.filter { repository.needsReconcilePrompt(it) }
    }

    // ----- entry -----

    /**
     * Saves what the keypad screen holds.
     *
     * One entry point for all three kinds, because the differences are exactly the
     * ones that must not be decided twice: a transfer writes two legs and counts as
     * neither income nor spending, and income never touches a budget.
     */
    fun saveEntry(
        type: TypeFilter,
        accountId: Long,
        toAccountId: Long?,
        amountPaise: Long,
        categoryId: Long?,
        note: String?,
        occurredAt: Long,
    ) = viewModelScope.launch {
        _lastAdded.value = when (type) {
            TypeFilter.INCOME -> repository.addIncome(
                accountId = accountId,
                amountPaise = amountPaise,
                payee = note,
                note = note,
                categoryId = categoryId,
                occurredAt = occurredAt,
            )
            TypeFilter.TRANSFER -> {
                val destination = toAccountId ?: return@launch
                repository.addTransfer(
                    fromAccountId = accountId,
                    toAccountId = destination,
                    amountPaise = amountPaise,
                    note = note,
                    occurredAt = occurredAt,
                )
            }
            else -> repository.addSpend(
                accountId = accountId,
                amountPaise = amountPaise,
                payee = note,
                note = note,
                categoryId = categoryId,
                occurredAt = occurredAt,
            )
        }
        refreshDerived()
    }

    fun updateEntry(
        id: Long,
        accountId: Long,
        direction: Direction,
        amountPaise: Long,
        categoryId: Long?,
        note: String?,
        payee: String?,
        occurredAt: Long,
    ) = viewModelScope.launch {
        val before = state.value.transactions.firstOrNull { it.id == id }
        repository.updateTransaction(
            id = id,
            accountId = accountId,
            direction = direction,
            amountPaise = amountPaise,
            occurredAt = occurredAt,
            payee = payee,
            note = note,
            categoryId = categoryId,
        )
        // Teaching the payee from an edit is useful, but it used to happen inside the
        // repository with nothing said - you went in to fix a note, nudged the
        // category, and changed what the app does with that shop forever. Now it
        // surfaces, with the chance to take it back.
        if (categoryId != null && categoryId != before?.categoryId) {
            _learned.value = repository.learnFromEdit(payee, categoryId)
        }
        refreshDerived()
    }

    fun deleteTransaction(id: Long) = viewModelScope.launch {
        repository.deleteTransaction(id)
        refreshDerived()
    }

    /** One tap on a chip: the whole row, recorded, undoable from the snackbar. */
    fun quickAdd(suggestion: QuickAddSuggestion) = saveEntry(
        type = TypeFilter.EXPENSE,
        accountId = suggestion.accountId,
        toAccountId = null,
        amountPaise = suggestion.amountPaise,
        categoryId = suggestion.categoryId,
        note = suggestion.label,
        occurredAt = System.currentTimeMillis(),
    )

    fun undoLastAdd() = viewModelScope.launch {
        _lastAdded.value?.let { repository.deleteTransaction(it) }
        _lastAdded.value = null
        refreshDerived()
    }

    fun clearUndo() { _lastAdded.value = null }

    // ----- accounts -----

    fun addDeclaredAccount(name: String, openingPaise: Long, isCash: Boolean) = viewModelScope.launch {
        repository.createDeclaredAccount(name, openingPaise, isCash)
    }

    fun rename(account: Account, name: String) = viewModelScope.launch {
        repository.renameAccount(account, name)
    }

    fun updateAccountLook(account: Account, name: String, iconKey: String, colourHex: String) =
        viewModelScope.launch { repository.updateAccountLook(account, name, iconKey, colourHex) }

    fun bind(auto: Account, declared: Account) = viewModelScope.launch {
        repository.bindAutoAccountTo(auto, declared)
    }

    fun reconcile(account: Account, actualPaise: Long) = viewModelScope.launch {
        repository.reconcile(account, actualPaise, System.currentTimeMillis())
        refreshDerived()
    }

    // ----- categories and rules -----

    fun createCategory(name: String, isIncome: Boolean) = viewModelScope.launch {
        repository.createCategory(name, isIncome)
    }

    fun updateCategory(category: Category, name: String, iconKey: String, colourHex: String) =
        viewModelScope.launch { repository.updateCategory(category, name, iconKey, colourHex) }

    fun deleteCategory(category: Category, moveToId: Long) = viewModelScope.launch {
        repository.deleteCategory(category, moveToId)
        refreshDerived()
    }

    fun deleteRule(id: Long) = viewModelScope.launch { repository.deleteRule(id) }

    fun categorise(txn: Txn, categoryId: Long) = viewModelScope.launch {
        _learned.value = repository.categorise(txn, categoryId)
        refreshGuessStats()
        refreshDerived()
    }

    // ----- v2.2: splitting a bill -----

    /**
     * Writes the split, then teaches the payee from your own largest share.
     *
     * The teaching deliberately ignores any part somebody owes you: who you happened to
     * eat with is not a fact about the shop, and without this one split lunch would
     * leave every later visit there filed as a debt to nobody.
     */
    fun applySplit(anchor: Txn, parts: List<SplitPart>) = viewModelScope.launch {
        val learn = repository.applySplit(anchor, parts)
        _learned.value = learn?.let { repository.learnFromSplit(anchor, it) }
        refreshDerived()
    }

    /** A new payment recorded and divided in one go, for cash that has no message. */
    fun saveSplitEntry(
        accountId: Long,
        amountPaise: Long,
        payee: String?,
        note: String?,
        occurredAt: Long,
        parts: List<SplitPart>,
    ) = viewModelScope.launch {
        repository.addSplitSpend(accountId, amountPaise, payee, note, occurredAt, parts)
        refreshDerived()
    }

    /** False when a repayment is still attached, which has to be detached first. */
    suspend fun unsplit(groupId: String): Boolean =
        repository.unsplit(groupId).also { if (it) refreshDerived() }

    suspend fun settlementCandidates(): List<Txn> = repository.settlementCandidates()

    fun settleWithCredit(groupId: String, creditId: Long) = viewModelScope.launch {
        repository.settleWithCredit(groupId, creditId)
        refreshDerived()
    }

    fun settleWithCash(groupId: String, amountPaise: Long, from: String?) = viewModelScope.launch {
        repository.settleWithCash(groupId, amountPaise, System.currentTimeMillis(), from)
        refreshDerived()
    }

    fun unsettle(txnId: Long) = viewModelScope.launch {
        repository.unsettle(txnId)
        refreshDerived()
    }

    /** Accepts the money is not coming back. Moves the total of the month it happened in. */
    fun writeOff(txnId: Long) = viewModelScope.launch {
        val given = state.value.givenCategory?.id ?: return@launch
        repository.writeOff(txnId, given)
        refreshDerived()
    }

    fun markAsSalary(txn: Txn) = viewModelScope.launch { repository.markAsSalary(txn) }

    // ----- budgets -----

    fun setBudget(categoryId: Long?, limitPaise: Long, thisCycleOnly: Boolean) =
        viewModelScope.launch {
            val start = period.value?.startMillis ?: evaluator.currentPeriodStart()
            repository.setBudget(categoryId, limitPaise, start, thisCycleOnly)
            refreshDerived()
        }

    fun deleteBudget(id: Long) = viewModelScope.launch {
        repository.deleteBudget(id)
        refreshDerived()
    }

    // ----- review tray -----

    fun dismissReview(id: Long) = viewModelScope.launch { repository.dismissReview(id) }

    fun recordFromReview(
        reviewId: Long,
        accountId: Long,
        amountPaise: Long,
        direction: Direction,
        payee: String?,
        categoryId: Long?,
    ) = viewModelScope.launch {
        repository.recordFromReview(
            reviewId, accountId, amountPaise, direction, payee, categoryId,
            System.currentTimeMillis(),
        )
        refreshDerived()
    }

    /** Suspending so the caller can stream it straight into a file it already opened. */
    suspend fun ledgerCsv(): String = repository.ledgerCsv()

    // ----- import rehearsal -----

    private val _dryRun = MutableStateFlow<DryRunResult?>(null)
    val dryRun: StateFlow<DryRunResult?> = _dryRun

    private val _dryRunning = MutableStateFlow(false)
    val dryRunning: StateFlow<Boolean> = _dryRunning

    /** Kept so that correcting an account binding can re-run without re-picking the file. */
    private var lastFile: Pair<String, String>? = null

    fun runDryRun(fileName: String, text: String) = viewModelScope.launch {
        lastFile = fileName to text
        _dryRunning.value = true
        _dryRun.value = repository.dryRun(
            fileName = fileName,
            text = text,
            cycleStart = state.value.cycle?.cycleStartMillis ?: 0L,
            now = System.currentTimeMillis(),
        )
        _dryRunning.value = false
    }

    fun bindAlias(accountId: Long, alias: String) = viewModelScope.launch {
        repository.bindAlias(accountId, alias)
        // The diff was computed against the old binding, so it is now wrong. Re-run
        // rather than leave a stale number on screen looking authoritative.
        lastFile?.let { (name, text) -> runDryRun(name, text) }
    }

    fun clearDryRun() {
        lastFile = null
        _dryRun.value = null
        _commitResult.value = null
    }

    // ----- import day -----

    private val _commitResult = MutableStateFlow<CommitResult?>(null)
    val commitResult: StateFlow<CommitResult?> = _commitResult

    private val _committing = MutableStateFlow(false)
    val committing: StateFlow<Boolean> = _committing

    private val _lastBatch = MutableStateFlow<ImportBatch?>(null)
    val lastBatch: StateFlow<ImportBatch?> = _lastBatch

    init {
        viewModelScope.launch { _lastBatch.value = repository.latestImport() }
    }

    /**
     * Writes the import that is currently on screen.
     *
     * Takes the rehearsal result rather than the file, so nothing can change between
     * the preview you agreed to and the rows that land.
     */
    fun commitImport() = viewModelScope.launch {
        val ready = _dryRun.value as? DryRunResult.Ready ?: return@launch
        if (_committing.value) return@launch
        _committing.value = true
        val result = repository.commitImport(ready, System.currentTimeMillis())
        _commitResult.value = result
        if (result is CommitResult.Done) {
            _lastBatch.value = repository.latestImport()
            // The diff is now meaningless: every row it called missing has just been
            // written. Clearing it stops a stale figure sitting there looking current.
            _dryRun.value = null
            lastFile = null
        }
        _committing.value = false
        refreshDerived()
    }

    fun undoImport(batchId: String) = viewModelScope.launch {
        repository.undoImport(batchId)
        _commitResult.value = null
        _lastBatch.value = repository.latestImport()
        refreshDerived()
    }

    fun anchorBalance(batchId: String, accountId: Long, actualPaise: Long) = viewModelScope.launch {
        repository.anchorBalance(batchId, accountId, actualPaise, System.currentTimeMillis())
        refreshDerived()
    }

    fun dismissCommitResult() { _commitResult.value = null }

    class Factory(
        private val repository: Repository,
        private val evaluator: BudgetEvaluator,
        private val model: CategoryModel,
        private val prefs: Prefs,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FinanceViewModel(repository, evaluator, model, prefs) as T
    }

    private companion object {
        /** How many model suggestions may jump the queue in a picker. */
        const val SUGGESTIONS = 3
    }
}
