package com.yk.finance.domain

import androidx.room.withTransaction
import com.yk.finance.data.AppDatabase
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.ImportBatch
import com.yk.finance.data.ImportedRow
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.importer.CommitPlan
import com.yk.finance.importer.Committer
import com.yk.finance.importer.DiffReport
import com.yk.finance.importer.RowOutcome
import com.yk.finance.parser.Direction

/** What a commit did, or why it declined to do anything. */
sealed interface CommitResult {
    data class Done(
        val batchId: String,
        val rowsImported: Int,
        val inserted: Int,
        val merged: Int,
        val queued: Int,
        val skipped: Int,
        val rejected: Int,
        val txnsWritten: Int,
        val flaggedNoSms: Int,
    ) : CommitResult

    data class Refused(val reason: String) : CommitResult
}

/**
 * Stage 7 - the only code in the app that writes an import.
 *
 * Everything it writes happens inside one Room transaction. A half-written import is
 * the worst outcome available here: the ledger would be wrong in a way no report could
 * describe and no undo could unwind, because undo would not know where the import
 * stopped. All of it lands or none of it does.
 */
class ImportService(private val db: AppDatabase, private val dao: FinanceDao) {

    /**
     * Writes the plan.
     *
     * Refuses rather than guesses on both of its preconditions. Conservation failing
     * means the file was read wrongly and every count downstream is fiction; an
     * untrustworthy diff means some rows could not be placed at all, and importing
     * those would silently drop history while reporting success.
     */
    suspend fun commit(commit: CommitPlan, diff: DiffReport, now: Long): CommitResult =
        db.withTransaction {
            if (!commit.conserved) {
                return@withTransaction CommitResult.Refused(
                    "the rows do not add up (${commit.rowsImported} + ${commit.rowsQueued} + " +
                        "${commit.rowsRejected} is not ${commit.rowsTotal}). Nothing was written.",
                )
            }
            if (!diff.trustworthy) {
                return@withTransaction CommitResult.Refused(
                    "no account here matches these names yet: " +
                        diff.unmappedAccounts.joinToString(", ") +
                        ". Match them first - importing now would leave their rows out.",
                )
            }

            dao.insertImportBatch(
                ImportBatch(
                    id = commit.batchId,
                    fileName = commit.fileName,
                    importedAt = now,
                    profileName = commit.profileName,
                    rowsTotal = commit.rowsTotal,
                    rowsImported = commit.rowsImported,
                    rowsQueued = commit.rowsQueued,
                    rowsRejected = commit.rowsRejected,
                    reportJson = Committer.reportJson(commit, diff, now),
                    committed = true,
                ),
            )

            val categoryIds = mutableMapOf<String, Long>()
            val txnIdByLine = mutableMapOf<Int, Long>()
            commit.inserts.forEach { pending ->
                val id = dao.insertTxn(
                    Txn(
                        accountId = pending.accountId,
                        direction = pending.direction,
                        amountPaise = pending.amountPaise,
                        occurredAt = pending.occurredAt,
                        // The bank never told us a payee for these; the file's note is
                        // the note, not a payee. Writing it into payee would feed the
                        // learned-rule machinery strings no SMS will ever contain.
                        payee = null,
                        reference = pending.reference,
                        categoryId = categoryFor(categoryIds,pending.categoryName, pending.categoryIsIncome),
                        countsAsSpending = pending.countsAsSpending,
                        transferGroupId = pending.transferGroupId,
                        source = pending.source,
                        note = pending.note,
                        importBatchId = commit.batchId,
                        fingerprint = pending.fingerprint,
                        timeWasInferred = pending.timeWasInferred,
                        noSmsCounterpart = pending.noSmsCounterpart,
                    ),
                )
                dao.adjustBalance(
                    pending.accountId,
                    if (pending.direction == Direction.DEBIT) -pending.amountPaise else pending.amountPaise,
                )
                txnIdByLine.putIfAbsent(pending.lineNumber, id)
            }

            val mergeReasons = mutableMapOf<Int, String>()
            val mergedTxnIds = mutableMapOf<Int, Long>()
            commit.merges.forEach { merge ->
                val existing = dao.txnById(merge.txnId) ?: return@forEach
                val filled = mutableListOf<String>()
                var updated = existing

                if (existing.note.isNullOrBlank() && !merge.note.isNullOrBlank()) {
                    updated = updated.copy(note = merge.note)
                    filled += "note"
                }
                if (existing.categoryId == null) {
                    categoryFor(categoryIds,merge.categoryName, merge.categoryIsIncome)?.let {
                        updated = updated.copy(categoryId = it)
                        filled += "category"
                    }
                }

                // Deliberately NOT stamped with importBatchId. That column means "this
                // row was created by the import", and undo deletes every row carrying
                // it. Stamping a merge would make undo delete a transaction the phone
                // captured for itself - the one thing an undo must never do.
                if (filled.isNotEmpty()) dao.updateTxn(updated)
                mergedTxnIds[merge.lineNumber] = merge.txnId
                mergeReasons[merge.lineNumber] =
                    if (filled.isEmpty()) "already had everything the file adds"
                    else "filled: " + filled.joinToString(", ")
            }

            // Invariant I2 on disk: every row, understood or not, keeps its original
            // text and every column it arrived with - including the ones nothing read.
            commit.log.forEach { entry ->
                dao.insertImportedRow(
                    ImportedRow(
                        batchId = commit.batchId,
                        lineNumber = entry.lineNumber,
                        rawLine = entry.rawLine,
                        rawJson = entry.rawJson,
                        txnId = when (entry.outcome) {
                            RowOutcome.IMPORTED -> txnIdByLine[entry.lineNumber]
                            RowOutcome.MERGED -> mergedTxnIds[entry.lineNumber]
                            else -> null
                        },
                        outcome = entry.outcome,
                        reason = if (entry.outcome == RowOutcome.MERGED) {
                            mergeReasons[entry.lineNumber] ?: entry.reason
                        } else {
                            entry.reason
                        },
                    ),
                )
            }

            CommitResult.Done(
                batchId = commit.batchId,
                rowsImported = commit.rowsImported,
                inserted = commit.insertedRows,
                merged = commit.merges.size,
                queued = commit.queuedRows,
                skipped = commit.skippedRows,
                rejected = commit.rowsRejected,
                txnsWritten = commit.txnsWritten,
                flaggedNoSms = commit.flaggedNoSms,
            )
        }

    /**
     * Finds or creates the category a row names, caching within one commit.
     *
     * The cache matters: 226 rows across seventeen categories would otherwise be 226
     * lookups inside the transaction that is holding the database.
     */
    private suspend fun categoryFor(
        cache: MutableMap<String, Long>,
        name: String?,
        isIncome: Boolean,
    ): Long? {
        if (name.isNullOrBlank()) return null
        cache[name]?.let { return it }
        return CategorySync.resolve(dao, name, isIncome).id.also { cache[name] = it }
    }

    /**
     * Invariant I5, reversibility. Puts the ledger back where it was.
     *
     * One caveat stated plainly, because it cannot be engineered away without keeping a
     * second copy of every field: undoing a merge clears the note and category the
     * import filled in, whether or not you have since edited them. Merges only ever
     * fill blanks, so the pre-import state was blank - but an edit made after the
     * import and before the undo is lost with it.
     */
    suspend fun undo(batchId: String): Boolean = db.withTransaction {
        val batch = dao.importBatches().firstOrNull { it.id == batchId }
            ?: return@withTransaction false

        // Balances first: reversing them needs the amounts, and the rows are about to go.
        dao.txnsFromImport(batchId).forEach { txn ->
            dao.adjustBalance(
                txn.accountId,
                if (txn.direction == Direction.DEBIT) txn.amountPaise else -txn.amountPaise,
            )
        }
        dao.deleteTxnsFromImport(batchId)

        dao.importedRows(batchId)
            .filter { it.outcome == RowOutcome.MERGED }
            .forEach { row ->
                val reason = row.reason ?: return@forEach
                if (!reason.startsWith("filled:")) return@forEach
                val txn = row.txnId?.let { dao.txnById(it) } ?: return@forEach
                var restored = txn
                if (reason.contains("note")) restored = restored.copy(note = null)
                if (reason.contains("category")) restored = restored.copy(categoryId = null)
                if (restored != txn) dao.updateTxn(restored)
            }

        dao.deleteImportedRows(batchId)
        dao.deleteImportBatch(batchId)
        batch.committed
    }

    /**
     * Decision 14. Books the gap between the imported ledger and the real balance as a
     * visible row rather than quietly setting the number.
     *
     * The gap is expected and is not an error: the file covers a period, not all of
     * history, so the computed balance is the period's net movement applied to whatever
     * the account started at. Making it a transaction means the size of the assumption
     * is on screen instead of buried in a column.
     *
     * Carries the batch id so an undo of the import takes the anchor with it.
     */
    suspend fun anchorBalance(
        batchId: String,
        accountId: Long,
        actualBalancePaise: Long,
        now: Long,
    ): Boolean = db.withTransaction {
        val account = dao.accountById(accountId) ?: return@withTransaction false
        val delta = actualBalancePaise - account.currentBalancePaise
        if (delta != 0L) {
            dao.insertTxn(
                Txn(
                    accountId = accountId,
                    direction = if (delta < 0) Direction.DEBIT else Direction.CREDIT,
                    amountPaise = kotlin.math.abs(delta),
                    occurredAt = now,
                    payee = "Balance after import",
                    reference = null,
                    countsAsSpending = false,
                    source = TxnSource.ADJUSTMENT,
                    note = "The imported history did not reach the real balance. " +
                        "This row is the difference, kept visible rather than absorbed.",
                    importBatchId = batchId,
                ),
            )
        }
        dao.updateAccount(
            account.copy(currentBalancePaise = actualBalancePaise, lastReconciledAt = now),
        )
        true
    }
}
