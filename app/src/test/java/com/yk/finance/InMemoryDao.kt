package com.yk.finance

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.yk.finance.data.Account
import com.yk.finance.data.AccountAlias
import com.yk.finance.data.AppDatabase
import com.yk.finance.data.Budget
import com.yk.finance.data.BudgetAlert
import com.yk.finance.data.Category
import com.yk.finance.data.CategoryRule
import com.yk.finance.data.ConfirmedPattern
import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.IgnoredAccount
import com.yk.finance.data.ImportBatch
import com.yk.finance.data.ImportedRow
import com.yk.finance.data.LearnedPattern
import com.yk.finance.data.PendingReview
import com.yk.finance.data.QuickAddSuggestion
import com.yk.finance.data.Txn
import com.yk.finance.domain.ImportService
import com.yk.finance.domain.Repository
import com.yk.finance.parser.Direction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A working FinanceDao over plain collections, for tests that need the ingestor or the
 * repository to actually write and read back.
 *
 * Faithful where the tests depend on it - including the UNIQUE (bank, accountToken) index
 * on accounts, which is what makes the order of a merge matter - and refuses loudly where
 * nothing under test should be calling. Flows are empty: the repository builds them at
 * construction and tests read state through the suspend queries instead.
 */
class InMemoryDao : FinanceDao {

    val accounts = linkedMapOf<Long, Account>()
    val txns = linkedMapOf<Long, Txn>()
    val reviews = linkedMapOf<Long, PendingReview>()
    val learned = linkedMapOf<String, LearnedPattern>()
    val aliases = linkedMapOf<Pair<String, String>, AccountAlias>()
    val ignored = linkedMapOf<Pair<String, String>, IgnoredAccount>()
    val rules = linkedMapOf<String, CategoryRule>()
    val categories = linkedMapOf<Long, Category>()
    val confirmed = linkedMapOf<String, ConfirmedPattern>()
    val boundaries = mutableListOf<CycleBoundary>()
    var cycle: CycleState? = null

    private var nextId = 1L
    private fun id() = nextId++

    private fun checkUnique(account: Account) {
        val clash = accounts.values.any {
            it.id != account.id && it.bank == account.bank && it.accountToken == account.accountToken
        }
        check(!clash) { "UNIQUE constraint failed: accounts.bank, accounts.accountToken" }
    }

    // ----- accounts -----
    override fun observeAccounts(): Flow<List<Account>> = emptyFlow()
    override suspend fun findAccount(bank: String, token: String) =
        accounts.values.firstOrNull { it.bank == bank && it.accountToken == token }
    override suspend fun accountById(id: Long) = accounts[id]
    override suspend fun cashAccount() = accounts.values.firstOrNull { it.kind.name == "CASH" }
    override suspend fun insertAccount(account: Account): Long {
        val row = account.copy(id = if (account.id == 0L) id() else account.id)
        checkUnique(row)
        accounts[row.id] = row
        return row.id
    }
    override suspend fun updateAccount(account: Account) {
        checkUnique(account)
        if (account.id in accounts) accounts[account.id] = account
    }
    override suspend fun adjustBalance(id: Long, deltaPaise: Long) {
        accounts[id]?.let { accounts[id] = it.copy(currentBalancePaise = it.currentBalancePaise + deltaPaise) }
    }
    override suspend fun setBalance(id: Long, balancePaise: Long) {
        accounts[id]?.let { accounts[id] = it.copy(currentBalancePaise = balancePaise) }
    }
    override suspend fun deleteAccount(id: Long) { accounts.remove(id) }
    override suspend fun reassignTransactions(fromAccountId: Long, toAccountId: Long) {
        txns.values.filter { it.accountId == fromAccountId }.forEach { txns[it.id] = it.copy(accountId = toAccountId) }
    }
    override suspend fun txnsForAccount(accountId: Long) = txns.values.filter { it.accountId == accountId }

    // ----- aliases and ignored accounts -----
    override suspend fun aliasFor(bank: String, token: String) = aliases[bank to token]
    override suspend fun aliasesOf(accountId: Long) = aliases.values.filter { it.accountId == accountId }
    override fun observeAliases(): Flow<List<AccountAlias>> = emptyFlow()
    override suspend fun upsertAlias(alias: AccountAlias) { aliases[alias.bank to alias.accountToken] = alias }
    override suspend fun repointAliases(fromAccountId: Long, toAccountId: Long) {
        aliases.replaceAll { _, a -> if (a.accountId == fromAccountId) a.copy(accountId = toAccountId) else a }
    }
    override suspend fun deleteAliasesOf(accountId: Long) { aliases.values.removeIf { it.accountId == accountId } }
    override suspend fun deleteAlias(bank: String, token: String) { aliases.remove(bank to token) }
    override suspend fun ignoreAccount(account: IgnoredAccount) { ignored[account.bank to account.accountToken] = account }
    override suspend fun isAccountIgnored(bank: String, token: String) = (bank to token) in ignored
    override fun observeIgnoredAccounts(): Flow<List<IgnoredAccount>> = emptyFlow()
    override suspend fun unignoreAccount(bank: String, token: String) { ignored.remove(bank to token) }

    // ----- transactions -----
    override suspend fun insertTxn(txn: Txn): Long {
        val row = txn.copy(id = if (txn.id == 0L) id() else txn.id)
        txns[row.id] = row
        return row.id
    }
    override suspend fun updateTxn(txn: Txn) { if (txn.id in txns) txns[txn.id] = txn }
    override fun observeRecent(limit: Int): Flow<List<Txn>> = emptyFlow()
    override fun observeForAccount(accountId: Long): Flow<List<Txn>> = emptyFlow()
    override suspend fun txnById(id: Long) = txns[id]
    override suspend fun deleteTxn(id: Long) { txns.remove(id) }
    override suspend fun findByReference(reference: String, from: Long, to: Long) =
        txns.values.filter { it.reference == reference && it.occurredAt in from..to }
    override suspend fun spendingSince(cycleStart: Long) =
        txns.values.filter { it.countsAsSpending && it.direction == Direction.DEBIT && it.occurredAt >= cycleStart }
    override fun observeSpendingSince(cycleStart: Long): Flow<List<Txn>> = emptyFlow()
    override suspend fun transferLegs(groupId: String) = txns.values.filter { it.transferGroupId == groupId }
    override fun observeUnaccountedCash(cashAccountId: Long): Flow<Long> = emptyFlow()
    override fun observeQuickAdd(limit: Int): Flow<List<QuickAddSuggestion>> = emptyFlow()
    override fun observeNeedsCategory(limit: Int): Flow<List<Txn>> = emptyFlow()
    override suspend fun needsCategoryCount() =
        txns.values.count { it.categoryId == null && it.countsAsSpending && it.direction == Direction.DEBIT }
    override suspend fun allTransactions() = txns.values.sortedBy { it.occurredAt }
    override suspend fun allAccounts() = accounts.values.toList()

    // ----- imports: nothing under test imports -----
    override suspend fun insertImportBatch(batch: ImportBatch) = unused()
    override suspend fun importBatches(): List<ImportBatch> = unused()
    override suspend fun insertImportedRow(row: ImportedRow): Long = unused()
    override suspend fun importedRows(batchId: String): List<ImportedRow> = unused()
    override fun observeImportBatches(): Flow<List<ImportBatch>> = emptyFlow()
    override suspend fun latestImportBatch(): ImportBatch? = unused()
    override suspend fun txnByFingerprint(fingerprint: String): Txn? = unused()
    override suspend fun allFingerprints(): List<String> = unused()
    override suspend fun txnsFromImport(batchId: String): List<Txn> = unused()
    override suspend fun deleteTxnsFromImport(batchId: String) = unused()
    override suspend fun deleteImportedRows(batchId: String) = unused()
    override suspend fun deleteImportBatch(batchId: String) = unused()

    // ----- categories and rules -----
    override fun observeCategories(): Flow<List<Category>> = emptyFlow()
    override suspend fun allCategories() = categories.values.toList()
    override suspend fun insertCategory(category: Category): Long = insertCategoryReturningId(category)
    override suspend fun ruleFor(key: String) = rules[key]
    override suspend fun allRules() = rules.values.toList()
    override suspend fun categoryByName(name: String) = categories.values.firstOrNull { it.name == name }
    override suspend fun updateCategory(category: Category) { categories[category.id] = category }
    override suspend fun deleteCategory(id: Long) { categories.remove(id) }
    override suspend fun moveTransactionsToCategory(fromId: Long, toId: Long) = unused()
    override suspend fun moveRulesToCategory(fromId: Long, toId: Long) = unused()
    override suspend fun moveBudgetsToCategory(fromId: Long, toId: Long) = unused()
    override suspend fun deleteBudgetsForCategory(id: Long) = unused()
    override suspend fun upsertRule(rule: CategoryRule) { rules[rule.payeeKey] = rule }
    override suspend fun uncategorisedWithPayee() = txns.values.filter { it.categoryId == null && it.payee != null }
    override suspend fun setCategoryForIds(ids: List<Long>, categoryId: Long) {
        ids.forEach { id -> txns[id]?.let { txns[id] = it.copy(categoryId = categoryId) } }
    }
    override suspend fun clearCategoryForIds(ids: List<Long>) {
        ids.forEach { id -> txns[id]?.let { txns[id] = it.copy(categoryId = null) } }
    }
    override suspend fun deleteRuleByKey(key: String) { rules.remove(key) }

    // ----- budgets -----
    override fun observeBudgets(): Flow<List<Budget>> = emptyFlow()
    override suspend fun allBudgets(): List<Budget> = emptyList()
    override suspend fun upsertBudget(budget: Budget) = unused()
    override suspend fun deleteBudget(id: Long) = unused()
    override suspend fun recordAlert(alert: BudgetAlert) = unused()
    override suspend fun alertAlreadySent(budgetId: Long, cycleStart: Long, threshold: Int): Int = unused()

    // ----- review tray -----
    override suspend fun insertReview(review: PendingReview) {
        val row = review.copy(id = if (review.id == 0L) id() else review.id)
        reviews[row.id] = row
    }
    override fun observeReviews(): Flow<List<PendingReview>> = emptyFlow()
    override suspend fun allReviews() = reviews.values.sortedBy { it.receivedAt }
    override fun observeReviewCount(): Flow<Int> = emptyFlow()
    override suspend fun reviewCount() = reviews.size
    override suspend fun dismissReview(id: Long) { reviews.remove(id) }
    override suspend fun reviewById(id: Long) = reviews[id]

    // ----- pattern trust and learned templates -----
    override suspend fun confirmPattern(pattern: ConfirmedPattern) { confirmed[pattern.patternId] = pattern }
    override suspend fun isPatternConfirmed(patternId: String) = patternId in confirmed
    override fun observeConfirmedPatterns(): Flow<List<ConfirmedPattern>> = emptyFlow()
    override suspend fun revokePattern(patternId: String) { confirmed.remove(patternId) }
    override suspend fun insertLearnedPattern(pattern: LearnedPattern) { learned[pattern.id] = pattern }
    override suspend fun allLearnedPatterns() = learned.values.sortedByDescending { it.createdAt }
    override fun observeLearnedPatterns(): Flow<List<LearnedPattern>> = emptyFlow()
    override suspend fun deleteLearnedPattern(id: String) { learned.remove(id) }

    // ----- cycle -----
    override suspend fun cycleState() = cycle
    override fun observeCycleState(): Flow<CycleState?> = emptyFlow()
    override suspend fun upsertCycle(state: CycleState) { cycle = state }
    override fun observeAllTransactions(): Flow<List<Txn>> = emptyFlow()
    override suspend fun allCredits() = txns.values.filter { it.direction == Direction.CREDIT }.sortedBy { it.occurredAt }
    override suspend fun spendingBetween(from: Long, to: Long) =
        txns.values.filter { it.countsAsSpending && it.direction == Direction.DEBIT && it.occurredAt >= from && it.occurredAt < to }
    override suspend fun incomeBetween(from: Long, to: Long): List<Txn> = unused()
    override suspend fun allBoundaries() = boundaries.sortedBy { it.startMillis }
    override fun observeBoundaries(): Flow<List<CycleBoundary>> = emptyFlow()
    override suspend fun boundaryCount() = boundaries.size
    override suspend fun insertBoundary(boundary: CycleBoundary): Long {
        if (boundaries.any { it.startMillis == boundary.startMillis }) return -1
        val row = boundary.copy(id = id())
        boundaries += row
        return row.id
    }
    override suspend fun deleteBoundary(startMillis: Long) { boundaries.removeIf { it.startMillis == startMillis } }
    override fun observeRules(): Flow<List<CategoryRule>> = emptyFlow()
    override suspend fun deleteRule(id: Long) { rules.values.removeIf { it.id == id } }
    override suspend fun categoryById(id: Long) = categories[id]
    override suspend fun insertCategoryReturningId(category: Category): Long {
        val row = category.copy(id = if (category.id == 0L) id() else category.id)
        categories[row.id] = row
        return row.id
    }
    override suspend fun splitGroup(groupId: String) = txns.values.filter { it.splitGroupId == groupId }.sortedBy { it.id }
    override suspend fun deleteTxns(ids: List<Long>) { ids.forEach { txns.remove(it) } }
    override suspend fun categorisedNotInferred() = txns.values.filter { it.categoryId != null && !it.categoryWasInferred }
    override fun observeGuessCount(): Flow<Int> = emptyFlow()
    override suspend fun confirmGuess(id: Long) {
        txns[id]?.let { txns[id] = it.copy(categoryWasInferred = false) }
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by these tests")

    /** A repository over this dao. Its ImportService is never called, so its database is a shell. */
    fun repository(): Repository = Repository(this, ImportService(ShellDatabase(this), this))

    private class ShellDatabase(private val dao: FinanceDao) : AppDatabase() {
        override fun dao() = dao
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException()
        override fun createInvalidationTracker() = InvalidationTracker(this)
        override fun clearAllTables() = Unit
    }
}
