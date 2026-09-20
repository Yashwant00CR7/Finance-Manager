package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.importer.AccountBinding
import com.yk.finance.importer.ColumnMap
import com.yk.finance.importer.Committer
import com.yk.finance.importer.Dialect
import com.yk.finance.importer.DiffMatcher
import com.yk.finance.importer.DiffReport
import com.yk.finance.importer.Fingerprint
import com.yk.finance.importer.ImportPlan
import com.yk.finance.importer.QueueCause
import com.yk.finance.importer.RejectedRow
import com.yk.finance.importer.RowKind
import com.yk.finance.importer.RowOutcome
import com.yk.finance.importer.StagedTxn
import com.yk.finance.importer.ValueParsers
import com.yk.finance.parser.Direction
import com.yk.finance.ui.rupeesToPaise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private const val BANK = 1L
private const val CASH = 9L

private fun at(day: Int, hour: Int = 12, month: Int = 9): Long =
    LocalDateTime.of(2026, month, day, hour, 0).atZone(ValueParsers.IST).toInstant().toEpochMilli()

private fun staged(
    line: Int,
    amountPaise: Long,
    occurredAt: Long,
    kind: RowKind = RowKind.EXPENSE,
    account: String = "Card",
    toAccount: String? = null,
    category: String? = "Food",
    note: String? = "lunch",
) = StagedTxn(
    lineNumber = line, kind = kind, amountPaise = amountPaise, occurredAt = occurredAt,
    timeWasInferred = false, accountName = account, toAccountName = toAccount,
    categoryName = category, note = note, reference = null,
    rawLine = "raw$line", rawJson = """{"line":"$line"}""",
)

private fun txn(
    id: Long,
    amountPaise: Long,
    occurredAt: Long,
    direction: Direction = Direction.DEBIT,
    accountId: Long = BANK,
    note: String? = null,
    categoryId: Long? = null,
) = Txn(
    id = id, accountId = accountId, direction = direction, amountPaise = amountPaise,
    occurredAt = occurredAt, payee = "SOMEWHERE", reference = null, countsAsSpending = true,
    source = TxnSource.SMS, note = note, categoryId = categoryId,
)

private fun plan(rows: List<StagedTxn>, rejected: List<RejectedRow> = emptyList()) = ImportPlan(
    fileName = "export.csv",
    profileName = "My Money Pro",
    dialect = Dialect(','),
    mapping = ColumnMap.map(listOf("TIME", "AMOUNT")),
    dataRows = rows.size + rejected.size,
    staged = rows,
    rejected = rejected,
)

private val binding: (String) -> AccountBinding = { name ->
    when (name) {
        "Card", "Salary" -> AccountBinding.Bank(BANK)
        "Cash" -> AccountBinding.Cash(CASH)
        else -> AccountBinding.Unbound
    }
}

/** The window the app was recording in. Rows before it are history, not misses. */
private fun report(rows: List<StagedTxn>, ledger: List<Txn>): DiffReport =
    DiffMatcher.diff(plan(rows), ledger, binding, at(10), at(30))

private fun commit(
    rows: List<StagedTxn>,
    ledger: List<Txn> = emptyList(),
    rejected: List<RejectedRow> = emptyList(),
    knownFingerprints: Set<String> = emptySet(),
    knownCategories: Set<String> = emptySet(),
    canonical: (String) -> String = { it },
) = Committer.plan(
    batchId = "b1",
    plan = plan(rows, rejected),
    diff = report(rows, ledger),
    bindingFor = binding,
    knownFingerprints = knownFingerprints,
    knownCategories = knownCategories,
    canonicalCategory = canonical,
)

class CommitPartitionTest {

    @Test
    fun `a payment this app never saw is written, and marked as never seen`() {
        val result = commit(listOf(staged(1, 42500, at(15))))
        assertEquals(1, result.inserts.size)
        assertTrue(result.inserts.first().noSmsCounterpart)
        assertEquals(1, result.flaggedNoSms)
    }

    @Test
    fun `a payment both ledgers have is merged, never written twice`() {
        val result = commit(
            listOf(staged(1, 42500, at(15))),
            listOf(txn(100, 42500, at(15))),
        )
        assertEquals(0, result.inserts.size)
        assertEquals(1, result.merges.size)
        assertEquals(100L, result.merges.first().txnId)
        // Merging is the whole point of decision 2: the row exists, so importing it
        // again would double the month's spending.
        assertEquals(0, result.flaggedNoSms)
    }

    @Test
    fun `an ambiguous row is withheld rather than guessed at`() {
        val result = commit(
            listOf(staged(1, 10000, at(15))),
            listOf(txn(100, 10000, at(15, 9)), txn(101, 10000, at(15, 18))),
        )
        assertEquals(0, result.inserts.size)
        assertEquals(0, result.merges.size)
        assertEquals(1, result.queuedRows)
        assertEquals(RowOutcome.QUEUED, result.withheld.first().outcome)
    }

    @Test
    fun `history from before the app existed is written, and not blamed on the parser`() {
        // 20 August is outside the comparison window: the app was not installed, so
        // "no SMS counterpart" would be a statement about nothing.
        val result = commit(listOf(staged(1, 42500, at(20, month = 8))))
        assertEquals(1, result.inserts.size)
        assertFalse(result.inserts.first().noSmsCounterpart)
    }

    @Test
    fun `cash is imported even though the comparison ignores it`() {
        val result = commit(listOf(staged(1, 5000, at(15), account = "Cash")))
        assertEquals(1, result.inserts.size)
        assertEquals(CASH, result.inserts.first().accountId)
        // The app cannot observe cash, so flagging it as a capture failure would be a lie.
        assertFalse(result.inserts.first().noSmsCounterpart)
    }

    @Test
    fun `an unbound account withholds the row instead of dropping it`() {
        val result = commit(listOf(staged(1, 5000, at(15), account = "Wallet")))
        assertEquals(0, result.inserts.size)
        assertEquals(1, result.queuedRows)
        assertTrue(result.withheld.first().reason.contains("Wallet"))
    }

    @Test
    fun `every row is accounted for exactly once`() {
        val result = commit(
            rows = listOf(
                staged(1, 42500, at(15)),
                staged(2, 5000, at(16), account = "Cash"),
                staged(3, 700, at(17), account = "Wallet"),
                staged(4, 300000, at(18), kind = RowKind.TRANSFER, toAccount = "Cash"),
            ),
            rejected = listOf(RejectedRow(5, "junk", "no date on this row")),
        )
        assertTrue(result.conserved)
        assertEquals(5, result.rowsTotal)
        assertEquals(5, result.log.size)
        assertEquals(1, result.log.count { it.outcome == RowOutcome.REJECTED })
    }

    @Test
    fun `income is a credit and is not spending`() {
        val result = commit(
            listOf(staged(1, 5000000, at(15), kind = RowKind.INCOME, account = "Salary", category = "Salary")),
        )
        val row = result.inserts.single()
        assertEquals(Direction.CREDIT, row.direction)
        assertFalse(row.countsAsSpending)
        assertTrue(row.categoryIsIncome)
    }
}

class CommitTransferTest {

    @Test
    fun `a transfer becomes two legs that share a group and count as nothing`() {
        val result = commit(
            listOf(staged(1, 300000, at(18), kind = RowKind.TRANSFER, toAccount = "Cash", category = null)),
        )
        assertEquals(2, result.inserts.size)
        assertEquals(1, result.insertedRows)
        assertEquals(1, result.inserts.map { it.transferGroupId }.distinct().size)
        assertTrue(result.inserts.none { it.countsAsSpending })
        assertEquals(setOf(Direction.DEBIT, Direction.CREDIT), result.inserts.map { it.direction }.toSet())
        assertEquals(setOf(BANK, CASH), result.inserts.map { it.accountId }.toSet())
    }

    @Test
    fun `moving money between your own accounts does not change what you hold`() {
        val result = commit(
            listOf(staged(1, 300000, at(18), kind = RowKind.TRANSFER, toAccount = "Cash", category = null)),
        )
        assertEquals(0L, result.balanceDeltas().values.sum())
        assertEquals(-300000L, result.balanceDeltas()[BANK])
        assertEquals(300000L, result.balanceDeltas()[CASH])
    }

    @Test
    fun `a transfer with an unbound destination is withheld whole`() {
        val result = commit(
            listOf(staged(1, 300000, at(18), kind = RowKind.TRANSFER, toAccount = "Wallet", category = null)),
        )
        assertEquals(0, result.inserts.size)
        assertEquals(1, result.queuedRows)
    }

    @Test
    fun `a transfer to itself is withheld rather than written as two cancelling rows`() {
        val result = commit(
            listOf(staged(1, 300000, at(18), kind = RowKind.TRANSFER, account = "Card", toAccount = "Salary", category = null)),
        )
        assertEquals(0, result.inserts.size)
        assertEquals(1, result.queuedRows)
    }
}

class FingerprintTest {

    @Test
    fun `importing the same export twice writes nothing the second time`() {
        val rows = listOf(staged(1, 42500, at(15)), staged(2, 5000, at(16)))
        val first = commit(rows)
        val known = first.inserts.map { it.fingerprint }.toSet()

        val second = commit(rows, knownFingerprints = known)
        assertEquals(0, second.inserts.size)
        assertEquals(2, second.skippedRows)
        assertTrue(second.conserved)
    }

    @Test
    fun `two identical payments on one day are both kept`() {
        // Same amount, same day, same note. Collapsing these would silently delete a
        // real payment, which is the failure a naive fingerprint makes invisible.
        val rows = listOf(staged(1, 5000, at(15)), staged(2, 5000, at(15)))
        val prints = Fingerprint.forFile(rows)
        assertEquals(2, prints.values.distinct().size)
    }

    @Test
    fun `the same row always fingerprints the same way`() {
        val row = staged(1, 42500, at(15))
        assertEquals(Fingerprint.of(row, 0), Fingerprint.of(row, 0))
        // A different time on the same day is still the same payment: re-exports have
        // been seen to shift the minute while the day never moves.
        assertEquals(Fingerprint.of(row, 0), Fingerprint.of(staged(1, 42500, at(15, 18)), 0))
    }

    @Test
    fun `a different amount is a different row`() {
        assertTrue(Fingerprint.of(staged(1, 42500, at(15)), 0) != Fingerprint.of(staged(1, 42600, at(15)), 0))
    }
}

class CommitCategoryTest {

    @Test
    fun `a category this app renames is filed under the new name`() {
        val result = commit(
            listOf(staged(1, 5000, at(15), category = "Groceries")),
            canonical = { if (it == "Groceries") "Food" else it },
        )
        assertEquals("Food", result.inserts.single().categoryName)
        assertEquals(listOf("Food"), result.newCategories.map { it.name })
    }

    @Test
    fun `a category that already exists is not proposed as new`() {
        val result = commit(listOf(staged(1, 5000, at(15))), knownCategories = setOf("Food"))
        assertTrue(result.newCategories.isEmpty())
        assertEquals("Food", result.inserts.single().categoryName)
    }

    @Test
    fun `a transfer carries no category`() {
        val result = commit(
            listOf(staged(1, 300000, at(18), kind = RowKind.TRANSFER, toAccount = "Cash", category = null)),
        )
        assertTrue(result.inserts.all { it.categoryName == null })
        assertTrue(result.newCategories.isEmpty())
    }
}

class MergeShapeTest {

    @Test
    fun `a merge carries the file's note and category, for the applier to fill blanks with`() {
        val result = commit(
            listOf(staged(1, 42500, at(15), note = "dentist", category = "Health and Fitness")),
            listOf(txn(100, 42500, at(15))),
        )
        val merge = result.merges.single()
        assertEquals("dentist", merge.note)
        assertEquals("Health and Fitness", merge.categoryName)
    }

    @Test
    fun `a merged row is logged against the transaction it merged into`() {
        val result = commit(
            listOf(staged(1, 42500, at(15))),
            listOf(txn(100, 42500, at(15))),
        )
        val entry = result.log.single { it.outcome == RowOutcome.MERGED }
        assertEquals(1, entry.lineNumber)
        // I2: the original line survives whatever the importer decided about it.
        assertEquals("raw1", entry.rawLine)
    }
}

class ReportJsonTest {

    @Test
    fun `the batch report names the counts and the capture figure it was taken at`() {
        val rows = listOf(staged(1, 42500, at(15)))
        val json = Committer.reportJson(commit(rows), report(rows, emptyList()), at(20))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"rowsTotal\":\"1\""))
        assertTrue(json.contains("\"capturePercentAtImport\""))
        assertTrue(json.contains("\"conserved\":\"true\""))
    }

    @Test
    fun `a quote in a reason cannot break the report`() {
        val json = Committer.reportJson(
            commit(listOf(staged(1, 5000, at(15), account = "Wal\"let"))),
            report(listOf(staged(1, 5000, at(15), account = "Wal\"let")), emptyList()),
            at(20),
        )
        assertTrue(json.contains("\\\""))
    }
}

class RupeeEntryTest {

    @Test
    fun `a typed balance becomes paise without touching a double`() {
        assertEquals(1230L, rupeesToPaise("12.3"))
        assertEquals(1234L, rupeesToPaise("12.34"))
        assertEquals(1200L, rupeesToPaise("12"))
        assertEquals(50L, rupeesToPaise("0.50"))
    }

    @Test
    fun `nonsense is refused rather than rounded`() {
        assertNull(rupeesToPaise(""))
        assertNull(rupeesToPaise("12.345"))
        assertNull(rupeesToPaise("1.2.3"))
        assertNull(rupeesToPaise("abc"))
    }
}

/**
 * The queue has to explain itself.
 *
 * Every one of these exists because a real import of 226 rows reported "226 left for
 * you to decide" and nothing else, while the button beside it offered to import zero
 * rows. The count was true and useless; the reason was computed, stored, and never
 * shown.
 */
class QueueExplanationTest {

    private fun unmatched(rows: List<StagedTxn>) = Committer.plan(
        batchId = "b1",
        plan = plan(rows),
        diff = report(rows, emptyList()),
        bindingFor = { name ->
            when (name) {
                "Card" -> AccountBinding.Bank(BANK)
                "Cash" -> AccountBinding.Cash(CASH)
                else -> AccountBinding.Unbound
            }
        },
    )

    @Test
    fun `an unmatched name names itself rather than saying it is not bound`() {
        val result = unmatched(listOf(staged(1, 5000, at(15), account = "Salary")))
        assertEquals("""no account here matches "Salary"""", result.withheld.first().reason)
    }

    @Test
    fun `rows are grouped by reason, biggest group first`() {
        val rows = List(5) { staged(it + 1, 5000, at(15), account = "Salary") } +
            List(2) { staged(it + 6, 5000, at(15), account = "Wallet") }
        val grouped = unmatched(rows).queuedByReason()

        assertEquals(
            listOf("""no account here matches "Salary"""", """no account here matches "Wallet""""),
            grouped.keys.toList(),
        )
        assertEquals(listOf(5, 2), grouped.values.toList())
    }

    @Test
    fun `the unmatched name count is distinct names, not rows`() {
        val rows = List(5) { staged(it + 1, 5000, at(15), account = "Salary") } +
            List(2) { staged(it + 6, 5000, at(15), account = "Wallet") }
        val result = unmatched(rows)

        assertEquals(7, result.queuedRows)
        assertEquals(2, result.unmatchedNameCount)
    }

    @Test
    fun `a matched file has nothing to explain`() {
        val result = unmatched(listOf(staged(1, 5000, at(15), account = "Card")))
        assertEquals(0, result.unmatchedNameCount)
        assertTrue(result.queuedByReason().isEmpty())
    }

    @Test
    fun `each way of queueing a row carries its own cause`() {
        val rows = listOf(
            staged(1, 5000, at(15), account = "Salary"),
            staged(2, 5000, at(16), kind = RowKind.TRANSFER, account = "Card", toAccount = "Card"),
        )
        val causes = unmatched(rows).withheld.associate { it.lineNumber to it.cause }

        assertEquals(QueueCause.NAME_UNMATCHED, causes[1])
        assertEquals(QueueCause.SAME_ACCOUNT, causes[2])
    }

    /**
     * The cause is what the import button is refused on. Reading it back out of the
     * sentence would mean the refusal quietly stops working the next time the sentence
     * is reworded - which is exactly the change that produced this test.
     */
    @Test
    fun `an ambiguous row is queued without being blamed on a name`() {
        val rows = listOf(staged(1, 5000, at(15)))
        val ledger = listOf(txn(1, 5000, at(15)), txn(2, 5000, at(15, hour = 13)))
        val result = Committer.plan(
            batchId = "b1",
            plan = plan(rows),
            diff = report(rows, ledger),
            bindingFor = binding,
        )

        assertEquals(1, result.queuedRows)
        assertEquals(QueueCause.AMBIGUOUS, result.withheld.first().cause)
        assertEquals(0, result.unmatchedNameCount)
    }
}

/**
 * Cash resolves to the account that was matched, not to whichever cash account the
 * query returned first. With one cash account the two are the same and the bug is
 * invisible, which is why it survived until a second one was possible.
 */
class CashIdentityTest {

    @Test
    fun `a name matched to the second cash account lands in the second cash account`() {
        val firstCash = 7L
        val secondCash = 8L
        val rows = listOf(staged(1, 5000, at(15), account = "Wallet"))
        val result = Committer.plan(
            batchId = "b1",
            plan = plan(rows),
            diff = report(rows, emptyList()),
            bindingFor = { name ->
                when (name) {
                    // "Wallet" was matched to the second cash account. The first one
                    // exists and sorts ahead of it; it must not win.
                    "Wallet" -> AccountBinding.Cash(secondCash)
                    else -> AccountBinding.Unbound
                }
            },
        )

        assertEquals(1, result.inserts.size)
        assertEquals(secondCash, result.inserts.first().accountId)
        assertFalse(result.inserts.first().accountId == firstCash)
    }
}
