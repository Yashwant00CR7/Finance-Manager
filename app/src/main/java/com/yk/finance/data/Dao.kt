package com.yk.finance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {

    // ----- accounts -----
    @Query("SELECT * FROM accounts ORDER BY kind, displayName")
    fun observeAccounts(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE bank = :bank AND accountToken = :token LIMIT 1")
    suspend fun findAccount(bank: String, token: String): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun accountById(id: Long): Account?

    @Query("SELECT * FROM accounts WHERE kind = 'CASH' LIMIT 1")
    suspend fun cashAccount(): Account?

    @Insert
    suspend fun insertAccount(account: Account): Long

    @Update
    suspend fun updateAccount(account: Account)

    @Query("UPDATE accounts SET currentBalancePaise = currentBalancePaise + :deltaPaise WHERE id = :id")
    suspend fun adjustBalance(id: Long, deltaPaise: Long)

    @Query("UPDATE accounts SET currentBalancePaise = :balancePaise WHERE id = :id")
    suspend fun setBalance(id: Long, balancePaise: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    /** Moves an auto-created account's history onto the account you declared at setup. */
    @Query("UPDATE transactions SET accountId = :toAccountId WHERE accountId = :fromAccountId")
    suspend fun reassignTransactions(fromAccountId: Long, toAccountId: Long)

    // ----- transactions -----
    @Insert
    suspend fun insertTxn(txn: Txn): Long

    @Update
    suspend fun updateTxn(txn: Txn)

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY occurredAt DESC")
    fun observeForAccount(accountId: Long): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun txnById(id: Long): Txn?

    /** Undo for a mis-tapped quick-add chip. The balance is repaired by the caller. */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    /**
     * Candidates for dedup/transfer matching. Same reference within a few days -
     * banks occasionally stamp the same RRN at slightly different times on each leg.
     */
    @Query(
        "SELECT * FROM transactions WHERE reference = :reference " +
            "AND occurredAt BETWEEN :from AND :to",
    )
    suspend fun findByReference(reference: String, from: Long, to: Long): List<Txn>

    @Query(
        "SELECT * FROM transactions WHERE countsAsSpending = 1 " +
            "AND direction = 'DEBIT' AND occurredAt >= :cycleStart",
    )
    suspend fun spendingSince(cycleStart: Long): List<Txn>

    @Query(
        "SELECT * FROM transactions WHERE countsAsSpending = 1 " +
            "AND direction = 'DEBIT' AND occurredAt >= :cycleStart",
    )
    fun observeSpendingSince(cycleStart: Long): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE transferGroupId = :groupId")
    suspend fun transferLegs(groupId: String): List<Txn>

    /** Cash withdrawn into the wallet minus cash you have actually logged spending. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amountPaise ELSE -amountPaise END), 0) " +
            "FROM transactions WHERE accountId = :cashAccountId",
    )
    fun observeUnaccountedCash(cashAccountId: Long): Flow<Long>

    // ----- quick add -----

    /**
     * Chips for the quick-add sheet, ranked by how often you have entered that exact
     * thing. Restricted to hand-entered and imported rows on purpose: a chip for an
     * SMS-captured payment would invite entering it a second time by hand.
     */
    @Query(
        "SELECT COALESCE(NULLIF(TRIM(note), ''), payee) AS label, amountPaise AS amountPaise, " +
            "categoryId AS categoryId, accountId AS accountId, " +
            "COUNT(*) AS uses, MAX(occurredAt) AS lastUsedAt " +
            "FROM transactions " +
            "WHERE direction = 'DEBIT' AND countsAsSpending = 1 " +
            "AND source IN ('MANUAL', 'IMPORT') " +
            "AND COALESCE(NULLIF(TRIM(note), ''), payee) IS NOT NULL " +
            "GROUP BY label, amountPaise, categoryId, accountId " +
            "ORDER BY uses DESC, lastUsedAt DESC LIMIT :limit",
    )
    fun observeQuickAdd(limit: Int = 8): Flow<List<QuickAddSuggestion>>

    /** Spending the app could not label confidently. The "ask" half of guess-or-ask. */
    @Query(
        "SELECT * FROM transactions WHERE categoryId IS NULL AND countsAsSpending = 1 " +
            "AND direction = 'DEBIT' ORDER BY occurredAt DESC LIMIT :limit",
    )
    fun observeNeedsCategory(limit: Int = 25): Flow<List<Txn>>

    @Query(
        "SELECT COUNT(*) FROM transactions WHERE categoryId IS NULL " +
            "AND countsAsSpending = 1 AND direction = 'DEBIT'",
    )
    suspend fun needsCategoryCount(): Int

    // ----- whole-ledger reads, for CSV export -----
    @Query("SELECT * FROM transactions ORDER BY occurredAt")
    suspend fun allTransactions(): List<Txn>

    @Query("SELECT * FROM accounts ORDER BY kind, displayName")
    suspend fun allAccounts(): List<Account>

    // ----- import bookkeeping (schema landed early; used by the importer) -----
    @Insert
    suspend fun insertImportBatch(batch: ImportBatch)

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    suspend fun importBatches(): List<ImportBatch>

    @Insert
    suspend fun insertImportedRow(row: ImportedRow): Long

    @Query("SELECT * FROM imported_rows WHERE batchId = :batchId ORDER BY lineNumber")
    suspend fun importedRows(batchId: String): List<ImportedRow>

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    fun observeImportBatches(): Flow<List<ImportBatch>>

    @Query("SELECT * FROM import_batches WHERE committed = 1 ORDER BY importedAt DESC LIMIT 1")
    suspend fun latestImportBatch(): ImportBatch?

    @Query("SELECT * FROM transactions WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun txnByFingerprint(fingerprint: String): Txn?

    /**
     * Every fingerprint already in the ledger. Read once per import rather than queried
     * per row: a second export of an overlapping period is the expected case, not the
     * exception, so this check runs against all 226 rows at once.
     */
    @Query("SELECT fingerprint FROM transactions WHERE fingerprint IS NOT NULL")
    suspend fun allFingerprints(): List<String>

    /** Undo reads these before deleting, because reversing a balance needs the amounts. */
    @Query("SELECT * FROM transactions WHERE importBatchId = :batchId")
    suspend fun txnsFromImport(batchId: String): List<Txn>

    /** Undo: an import is reversible only if every row it wrote can be found again. */
    @Query("DELETE FROM transactions WHERE importBatchId = :batchId")
    suspend fun deleteTxnsFromImport(batchId: String)

    @Query("DELETE FROM imported_rows WHERE batchId = :batchId")
    suspend fun deleteImportedRows(batchId: String)

    @Query("DELETE FROM import_batches WHERE id = :batchId")
    suspend fun deleteImportBatch(batchId: String)

    // ----- categories -----
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories")
    suspend fun allCategories(): List<Category>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: Category): Long

    @Query("SELECT * FROM category_rules WHERE payeeKey = :key LIMIT 1")
    suspend fun ruleFor(key: String): CategoryRule?

    @Query("SELECT * FROM category_rules")
    suspend fun allRules(): List<CategoryRule>

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun categoryByName(name: String): Category?

    @Update
    suspend fun updateCategory(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    // Merging two categories has to move everything that points at the loser, or the
    // rows would be left holding an id that no longer resolves to a name.
    @Query("UPDATE transactions SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun moveTransactionsToCategory(fromId: Long, toId: Long)

    @Query("UPDATE category_rules SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun moveRulesToCategory(fromId: Long, toId: Long)

    @Query("UPDATE budgets SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun moveBudgetsToCategory(fromId: Long, toId: Long)

    @Query("DELETE FROM budgets WHERE categoryId = :id")
    suspend fun deleteBudgetsForCategory(id: Long)

    @Upsert
    suspend fun upsertRule(rule: CategoryRule)

    /**
     * Candidates for a newly learned rule to back-fill.
     *
     * Deliberately only the rows that were never filed. A rule used to be applied with
     * `UPDATE ... WHERE payee = ?` and no other condition, which reached back through
     * the whole ledger and overwrote decisions you had made by hand - file a shop as
     * For Others in August, tap Food there in September, and August silently became
     * Food too.
     *
     * The payee match is finished in Kotlin rather than here, because the rules are
     * keyed on Categorizer.payeeKey - trimmed, uppercased, runs of whitespace collapsed
     * - and SQLite cannot collapse whitespace runs. Matching raw text here while
     * matching a normalised key there is how the two disagreed about which rows
     * belonged to a payee.
     */
    @Query("SELECT * FROM transactions WHERE categoryId IS NULL AND payee IS NOT NULL")
    suspend fun uncategorisedWithPayee(): List<Txn>

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun setCategoryForIds(ids: List<Long>, categoryId: Long)

    /** Undo for a back-fill: puts rows back to having no category at all. */
    @Query("UPDATE transactions SET categoryId = NULL WHERE id IN (:ids)")
    suspend fun clearCategoryForIds(ids: List<Long>)

    @Query("DELETE FROM category_rules WHERE payeeKey = :key")
    suspend fun deleteRuleByKey(key: String)

    // ----- budgets -----
    @Query("SELECT * FROM budgets")
    fun observeBudgets(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets")
    suspend fun allBudgets(): List<Budget>

    @Upsert
    suspend fun upsertBudget(budget: Budget)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudget(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordAlert(alert: BudgetAlert)

    @Query(
        "SELECT COUNT(*) FROM budget_alerts WHERE budgetId = :budgetId " +
            "AND cycleStartMillis = :cycleStart AND threshold = :threshold",
    )
    suspend fun alertAlreadySent(budgetId: Long, cycleStart: Long, threshold: Int): Int

    // ----- review tray -----
    @Insert
    suspend fun insertReview(review: PendingReview)

    @Query("SELECT * FROM pending_reviews ORDER BY receivedAt DESC")
    fun observeReviews(): Flow<List<PendingReview>>

    @Query("SELECT COUNT(*) FROM pending_reviews")
    fun observeReviewCount(): Flow<Int>

    @Query("DELETE FROM pending_reviews WHERE id = :id")
    suspend fun dismissReview(id: Long)

    // ----- cycle -----
    @Query("SELECT * FROM cycle_state WHERE id = 1")
    suspend fun cycleState(): CycleState?

    @Query("SELECT * FROM cycle_state WHERE id = 1")
    fun observeCycleState(): Flow<CycleState?>

    @Upsert
    suspend fun upsertCycle(state: CycleState)

    // ----- v2.0: whole-ledger observation -----

    /**
     * Every transaction, newest first. The period strip can land on any cycle and the
     * search box spans all of them, so a 200-row window would silently truncate both.
     * A personal ledger is thousands of rows, not millions; filtering happens in memory.
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAllTransactions(): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE direction = 'CREDIT' ORDER BY occurredAt")
    suspend fun allCredits(): List<Txn>

    @Query(
        "SELECT * FROM transactions WHERE countsAsSpending = 1 AND direction = 'DEBIT' " +
            "AND occurredAt >= :from AND occurredAt < :to",
    )
    suspend fun spendingBetween(from: Long, to: Long): List<Txn>

    /**
     * Credits inside a window, for the widget's income ceiling.
     *
     * The exclusions mirror Ledger.isIncome exactly and for its reasons: a transfer leg
     * is your own money moving, a settlement is your own money returning, and an
     * adjustment is a correction rather than earnings. Counting any of them would raise
     * the ceiling the widget measures your spending against, which is the one direction
     * an error here is invisible in - the bar would simply look healthier than it is.
     */
    @Query(
        "SELECT * FROM transactions WHERE direction = 'CREDIT' " +
            "AND transferGroupId IS NULL " +
            "AND source NOT IN ('TRANSFER_LEG', 'ADJUSTMENT', 'SETTLEMENT') " +
            "AND occurredAt >= :from AND occurredAt < :to",
    )
    suspend fun incomeBetween(from: Long, to: Long): List<Txn>

    // ----- v2.0: cycle history -----

    @Query("SELECT * FROM cycle_history ORDER BY startMillis")
    suspend fun allBoundaries(): List<CycleBoundary>

    @Query("SELECT * FROM cycle_history ORDER BY startMillis")
    fun observeBoundaries(): Flow<List<CycleBoundary>>

    @Query("SELECT COUNT(*) FROM cycle_history")
    suspend fun boundaryCount(): Int

    /** IGNORE, not REPLACE: startMillis is unique, and a re-run must not renumber rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBoundary(boundary: CycleBoundary): Long

    @Query("DELETE FROM cycle_history WHERE startMillis = :startMillis")
    suspend fun deleteBoundary(startMillis: Long)

    // ----- v2.0: learned rules, visible at last -----

    @Query("SELECT * FROM category_rules ORDER BY payeeKey")
    fun observeRules(): Flow<List<CategoryRule>>

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    // ----- v2.0: category and account editing -----

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun categoryById(id: Long): Category?

    @Insert
    suspend fun insertCategoryReturningId(category: Category): Long

    // ----- v2.2: split bills -----

    /** Every part of one split bill, plus any settlement that has landed against it. */
    @Query("SELECT * FROM transactions WHERE splitGroupId = :groupId ORDER BY id")
    suspend fun splitGroup(groupId: String): List<Txn>

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTxns(ids: List<Long>)

    // ----- v2.3: the category model -----

    /**
     * The model's entire training set: every categorised row it did not file itself.
     *
     * The `categoryWasInferred = 0` half is the defence against the feedback loop, and
     * it is the reason this is a query rather than "all categorised rows". A model
     * trained on its own output drifts towards whatever it already believes.
     *
     * Rows filed by a seed keyword are deliberately included. A SWIGGY row is correct
     * by construction, and it still teaches the context features - 8pm, 200-499, UPI -
     * which are the only features a payee-less Union row has to offer.
     */
    @Query("SELECT * FROM transactions WHERE categoryId IS NOT NULL AND categoryWasInferred = 0")
    suspend fun categorisedNotInferred(): List<Txn>

    /** Drives the "guessed" filter and the count beside it. */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryWasInferred = 1")
    fun observeGuessCount(): Flow<Int>

    /**
     * Accepts a guess as it stands. The category does not move; what changes is that a
     * person has now looked at it, which is what makes the row safe to learn from.
     */
    @Query("UPDATE transactions SET categoryWasInferred = 0 WHERE id = :id")
    suspend fun confirmGuess(id: Long)
}
