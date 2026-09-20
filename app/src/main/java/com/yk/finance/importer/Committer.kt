package com.yk.finance.importer

import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction
import java.security.MessageDigest
import java.time.Instant

/**
 * What happened to one source row. Stored verbatim on `imported_rows.outcome`, so an
 * import can be explained line by line months later without the file.
 */
object RowOutcome {
    /** Written to the ledger as a new transaction. */
    const val IMPORTED = "IMPORTED"

    /** Folded into a transaction this app had already captured from SMS. */
    const val MERGED = "MERGED"

    /** Understood, deliberately not written, waiting on a decision only you can make. */
    const val QUEUED = "QUEUED"

    /** Understood, already present from an earlier import. Writing it would duplicate. */
    const val SKIPPED = "SKIPPED"

    /** Not understood. Never written, never dropped. */
    const val REJECTED = "REJECTED"
}

/**
 * A stable identity for a source row, so importing an overlapping export twice does not
 * duplicate its history.
 *
 * Deliberately built from the date rather than the timestamp: a second export of the
 * same period is the expected case here, and re-exports have been seen to shift times
 * by a minute while the day never moves.
 *
 * [occurrence] is what stops two genuinely identical rows - two ₹50 teas on the same
 * day with the same note - from collapsing into one. Within a file they are numbered,
 * so the second tea has its own fingerprint and a re-export reproduces both exactly.
 */
object Fingerprint {

    fun of(row: StagedTxn, occurrence: Int): String {
        val date = Instant.ofEpochMilli(row.occurredAt).atZone(ValueParsers.IST).toLocalDate()
        val material = listOf(
            date.toString(),
            row.kind.name,
            row.amountPaise.toString(),
            row.accountName.trim().lowercase(),
            row.toAccountName?.trim()?.lowercase().orEmpty(),
            row.note?.trim()?.lowercase().orEmpty(),
            row.categoryName?.trim()?.lowercase().orEmpty(),
            occurrence.toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    /** Numbers repeats within one file so identical rows stay distinguishable. */
    fun forFile(rows: List<StagedTxn>): Map<Int, String> {
        val seen = mutableMapOf<String, Int>()
        val out = LinkedHashMap<Int, String>()
        rows.forEach { row ->
            // The key must be the fingerprint material minus the occurrence, so ask
            // for occurrence 0 first and use that as the counting key.
            val base = of(row, 0)
            val n = seen.getOrDefault(base, 0)
            seen[base] = n + 1
            out[row.lineNumber] = if (n == 0) base else of(row, n)
        }
        return out
    }
}

/** A transaction the commit will write. Category is still a name; ids are the applier's job. */
data class PendingTxn(
    val lineNumber: Int,
    val accountId: Long,
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    val timeWasInferred: Boolean,
    val note: String?,
    val reference: String?,
    val categoryName: String?,
    val categoryIsIncome: Boolean,
    val countsAsSpending: Boolean,
    val source: TxnSource,
    val transferGroupId: String?,
    val noSmsCounterpart: Boolean,
    val fingerprint: String,
)

/**
 * Decision 2, note-merge. The app already has this payment from the bank's own SMS; the
 * file adds only the things the bank never said - what it was for, and which category.
 *
 * Merging only ever fills a field that is currently empty. An import is not allowed to
 * overwrite something you typed, and the file's version survives regardless in
 * `imported_rows.rawJson`, so nothing is lost by declining to overwrite.
 */
data class PendingMerge(
    val lineNumber: Int,
    val txnId: Long,
    val note: String?,
    val categoryName: String?,
    val categoryIsIncome: Boolean,
)

/**
 * Why a row is waiting on a person.
 *
 * Kept as a value rather than read back out of [WithheldRow.reason], because the import
 * button is refused on [NAME_UNMATCHED] and a precondition that depends on matching
 * prose breaks silently the first time the prose is reworded.
 *
 * Deliberately not persisted. `imported_rows` stores the sentence, which is what an
 * import has to be explainable by months later; the cause only has to survive as long
 * as the screen that is offering the decision.
 */
enum class QueueCause {
    /** The file names an account this app cannot match to one of its own. */
    NAME_UNMATCHED,

    /** A transfer whose two sides resolve to the same account. */
    SAME_ACCOUNT,

    /** More than one transaction here could be this row. */
    AMBIGUOUS,
}

/** Understood but not written, with the reason in plain language. */
data class WithheldRow(
    val lineNumber: Int,
    val outcome: String,
    val reason: String,
    /** Null for [RowOutcome.SKIPPED], which is not a decision anyone has to make. */
    val cause: QueueCause? = null,
)

/** A category the commit will have to create, named as this app names categories. */
data class CategorySeed(val name: String, val isIncome: Boolean)

/** One line of the permanent record. [RawRecord.rawJson] is invariant I2 on disk. */
data class RowLog(
    val lineNumber: Int,
    val rawLine: String,
    val rawJson: String,
    val outcome: String,
    val reason: String?,
)

/**
 * Everything the commit will do, worked out before anything is written.
 *
 * Inert on purpose - the same property that lets the dry run report on an [ImportPlan]
 * lets the UI show this in full before you agree to it. Nothing here touches a database.
 */
data class CommitPlan(
    val batchId: String,
    val fileName: String,
    val profileName: String,
    val rowsTotal: Int,
    val inserts: List<PendingTxn>,
    val merges: List<PendingMerge>,
    val withheld: List<WithheldRow>,
    val rejected: List<RejectedRow>,
    val newCategories: List<CategorySeed>,
    val log: List<RowLog>,
) {
    /** One row can become two transactions, so rows and writes are counted separately. */
    val rowsImported: Int get() = inserts.map { it.lineNumber }.distinct().size + merges.size
    val rowsQueued: Int get() = withheld.size
    val rowsRejected: Int get() = rejected.size
    val txnsWritten: Int get() = inserts.size

    val insertedRows: Int get() = inserts.map { it.lineNumber }.distinct().size
    val skippedRows: Int get() = withheld.count { it.outcome == RowOutcome.SKIPPED }
    val queuedRows: Int get() = withheld.count { it.outcome == RowOutcome.QUEUED }

    /**
     * Queued rows grouped by the sentence that explains them, biggest group first.
     *
     * Grouped on the sentence rather than on a name, because the sentence is what the
     * screen prints and what `imported_rows` keeps. Two rows held for the same reason
     * carry identical text, so the grouping is exact rather than a heuristic.
     */
    fun queuedByReason(): Map<String, Int> = withheld
        .filter { it.outcome == RowOutcome.QUEUED }
        .groupingBy { it.reason }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .associate { it.key to it.value }

    /**
     * How many distinct account names in the file nothing here matches.
     *
     * The import is refused while this is non-zero. Those rows would be left out of a
     * commit that reported success, which is the one failure an import must not have.
     */
    val unmatchedNameCount: Int
        get() = withheld
            .filter { it.cause == QueueCause.NAME_UNMATCHED }
            .map { it.reason }
            .distinct()
            .size

    /** Rows that will be flagged as bank activity SMS capture never produced. */
    val flaggedNoSms: Int get() = inserts.count { it.noSmsCounterpart }

    /** Invariant I1, carried through the commit. Every row is accounted for, once. */
    val conserved: Boolean get() = rowsImported + rowsQueued + rowsRejected == rowsTotal

    /** Net effect on each account's balance, for the preview. */
    fun balanceDeltas(): Map<Long, Long> = inserts.groupBy { it.accountId }.mapValues { (_, rows) ->
        rows.sumOf { if (it.direction == Direction.DEBIT) -it.amountPaise else it.amountPaise }
    }
}

/**
 * Stage 6 - deciding what an import will do. Pure: no Room, no Android, no clock beyond
 * the `now` handed in, which is what makes import day testable before it happens.
 *
 * The partition is total. Every staged row lands in exactly one of: merged onto an
 * existing transaction, written as new, withheld with a reason. Nothing falls through.
 */
object Committer {

    /** The one sentence an unmatched account name produces, in both places it can. */
    private fun unmatched(name: String?): String = "no account here matches \"$name\""

    fun plan(
        batchId: String,
        plan: ImportPlan,
        diff: DiffReport,
        bindingFor: (String) -> AccountBinding,
        knownFingerprints: Set<String> = emptySet(),
        knownCategories: Set<String> = emptySet(),
        canonicalCategory: (String) -> String = { it },
    ): CommitPlan {
        val fingerprints = Fingerprint.forFile(plan.staged)
        val matchedByLine = diff.matched.associateBy { it.staged.lineNumber }
        val ambiguousLines = diff.ambiguous.map { it.lineNumber }.toSet()
        val missedLines = diff.fileOnly.map { it.lineNumber }.toSet()

        val inserts = mutableListOf<PendingTxn>()
        val merges = mutableListOf<PendingMerge>()
        val withheld = mutableListOf<WithheldRow>()
        val log = mutableListOf<RowLog>()
        val wantedCategories = LinkedHashMap<String, Boolean>()

        fun withhold(row: StagedTxn, outcome: String, reason: String, cause: QueueCause? = null) {
            withheld.add(WithheldRow(row.lineNumber, outcome, reason, cause))
            log.add(RowLog(row.lineNumber, row.rawLine, row.rawJson, outcome, reason))
        }

        plan.staged.forEach { row ->
            val fingerprint = fingerprints.getValue(row.lineNumber)
            val category = row.categoryName?.let(canonicalCategory)?.takeIf { it.isNotBlank() }
            val isIncome = row.kind == RowKind.INCOME

            when {
                fingerprint in knownFingerprints ->
                    withhold(row, RowOutcome.SKIPPED, "already imported by an earlier run")

                // Two transactions here could be this row. Merging would be a guess and
                // inserting would double-count, so it waits for a person. See decision 13.
                row.lineNumber in ambiguousLines ->
                    withhold(
                        row,
                        RowOutcome.QUEUED,
                        "more than one transaction here could be this row",
                        QueueCause.AMBIGUOUS,
                    )

                matchedByLine.containsKey(row.lineNumber) -> {
                    val pair = matchedByLine.getValue(row.lineNumber)
                    merges.add(
                        PendingMerge(
                            lineNumber = row.lineNumber,
                            txnId = pair.txn.id,
                            note = row.note,
                            categoryName = category,
                            categoryIsIncome = isIncome,
                        ),
                    )
                    category?.let { wantedCategories.putIfAbsent(it, isIncome) }
                    log.add(
                        RowLog(
                            row.lineNumber, row.rawLine, row.rawJson, RowOutcome.MERGED,
                            "this app already had it from SMS",
                        ),
                    )
                }

                row.kind == RowKind.TRANSFER -> {
                    val fromId = accountIdFor(bindingFor(row.accountName))
                    val toId = row.toAccountName?.let { accountIdFor(bindingFor(it)) }
                    if (fromId == null) {
                        withhold(row, RowOutcome.QUEUED, unmatched(row.accountName), QueueCause.NAME_UNMATCHED)
                    } else if (toId == null) {
                        withhold(row, RowOutcome.QUEUED, unmatched(row.toAccountName), QueueCause.NAME_UNMATCHED)
                    } else if (fromId == toId) {
                        withhold(
                            row,
                            RowOutcome.QUEUED,
                            "both sides of this transfer are the same account",
                            QueueCause.SAME_ACCOUNT,
                        )
                    } else {
                        // Both legs, one group, neither counting as spending. A move
                        // between your own accounts is not money leaving.
                        val group = "import-$batchId-${row.lineNumber}"
                        inserts.add(leg(row, fromId, Direction.DEBIT, group, fingerprint))
                        inserts.add(leg(row, toId, Direction.CREDIT, group, fingerprint + "-in"))
                        log.add(
                            RowLog(
                                row.lineNumber, row.rawLine, row.rawJson, RowOutcome.IMPORTED,
                                "transfer, written as two legs",
                            ),
                        )
                    }
                }

                else -> {
                    val accountId = accountIdFor(bindingFor(row.accountName))
                    if (accountId == null) {
                        withhold(row, RowOutcome.QUEUED, unmatched(row.accountName), QueueCause.NAME_UNMATCHED)
                        return@forEach
                    }
                    category?.let { wantedCategories.putIfAbsent(it, isIncome) }
                    inserts.add(
                        PendingTxn(
                            lineNumber = row.lineNumber,
                            accountId = accountId,
                            direction = if (isIncome) Direction.CREDIT else Direction.DEBIT,
                            amountPaise = row.amountPaise,
                            occurredAt = row.occurredAt,
                            timeWasInferred = row.timeWasInferred,
                            note = row.note,
                            reference = row.reference,
                            categoryName = category,
                            categoryIsIncome = isIncome,
                            countsAsSpending = !isIncome,
                            source = TxnSource.IMPORT,
                            transferGroupId = null,
                            // Decision 3. Only rows inside the window where both were
                            // recording can carry this: the flag means "SMS should have
                            // caught this and did not", which is meaningless for a day
                            // before the app existed, and false by construction for cash,
                            // since no bank ever messages about a cash payment.
                            noSmsCounterpart = row.lineNumber in missedLines,
                            fingerprint = fingerprint,
                        ),
                    )
                    log.add(
                        RowLog(
                            row.lineNumber, row.rawLine, row.rawJson, RowOutcome.IMPORTED,
                            if (row.lineNumber in missedLines) "no SMS counterpart in this app" else null,
                        ),
                    )
                }
            }
        }

        plan.rejected.forEach { reject ->
            log.add(RowLog(reject.lineNumber, reject.rawLine, "{}", RowOutcome.REJECTED, reject.reason))
        }

        return CommitPlan(
            batchId = batchId,
            fileName = plan.fileName,
            profileName = plan.profileName,
            rowsTotal = plan.dataRows,
            inserts = inserts,
            merges = merges,
            withheld = withheld,
            rejected = plan.rejected,
            newCategories = wantedCategories
                .filterKeys { it !in knownCategories }
                .map { (name, income) -> CategorySeed(name, income) },
            log = log.sortedBy { it.lineNumber },
        )
    }

    private fun leg(
        row: StagedTxn,
        accountId: Long,
        direction: Direction,
        group: String,
        fingerprint: String,
    ) = PendingTxn(
        lineNumber = row.lineNumber,
        accountId = accountId,
        direction = direction,
        amountPaise = row.amountPaise,
        occurredAt = row.occurredAt,
        timeWasInferred = row.timeWasInferred,
        note = row.note,
        reference = row.reference,
        categoryName = null,
        categoryIsIncome = false,
        countsAsSpending = false,
        source = TxnSource.TRANSFER_LEG,
        transferGroupId = group,
        noSmsCounterpart = false,
        fingerprint = fingerprint,
    )

    /**
     * Cash is a real account here even though the diff excludes it. The exclusion is
     * about measurement - the app cannot *observe* cash - not about ownership. Cash
     * history is a large part of what the migration exists to rescue.
     *
     * The binding carries the id, so a name matched to a cash account resolves to the
     * account you picked. It used to resolve to whichever CASH row came first, which
     * made the choice unpickable the moment a second cash account existed.
     */
    private fun accountIdFor(binding: AccountBinding): Long? = when (binding) {
        is AccountBinding.Bank -> binding.id
        is AccountBinding.Cash -> binding.id
        AccountBinding.Unbound -> null
    }

    /**
     * The batch report, as JSON, for `import_batches.reportJson`.
     *
     * Written by hand for the same reason [RawRecord.rawJson] is: this build still has
     * no JSON dependency, and one is not worth adding to serialise a summary.
     */
    fun reportJson(commit: CommitPlan, diff: DiffReport, importedAt: Long): String {
        val fields = listOf(
            "fileName" to commit.fileName,
            "profile" to commit.profileName,
            "importedAt" to importedAt.toString(),
            "rowsTotal" to commit.rowsTotal.toString(),
            "inserted" to commit.insertedRows.toString(),
            "merged" to commit.merges.size.toString(),
            "queued" to commit.queuedRows.toString(),
            "skipped" to commit.skippedRows.toString(),
            "rejected" to commit.rowsRejected.toString(),
            "transactionsWritten" to commit.txnsWritten.toString(),
            "flaggedNoSmsCounterpart" to commit.flaggedNoSms.toString(),
            "conserved" to commit.conserved.toString(),
            "windowStart" to diff.windowStart.toString(),
            "windowEnd" to diff.windowEnd.toString(),
            "capturePercentAtImport" to diff.capturePercent.toString(),
            "capturePercentTrustworthy" to diff.trustworthy.toString(),
        )
        val withheldJson = commit.withheld.joinToString(",", "[", "]") {
            """{"line":${it.lineNumber},"outcome":"${it.outcome}","reason":"${escape(it.reason)}"}"""
        }
        val rejectedJson = commit.rejected.joinToString(",", "[", "]") {
            """{"line":${it.lineNumber},"reason":"${escape(it.reason)}"}"""
        }
        return fields.joinToString(",", "{", "") { (k, v) -> """"$k":"${escape(v)}"""" } +
            ""","withheld":$withheldJson,"rejected":$rejectedJson}"""
    }

    private fun escape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
