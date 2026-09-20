package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.importer.AccountBinding
import com.yk.finance.importer.ColumnMap
import com.yk.finance.importer.Dialect
import com.yk.finance.importer.DiffMatcher
import com.yk.finance.importer.ImportPlan
import com.yk.finance.importer.MatchWindow
import com.yk.finance.importer.RowKind
import com.yk.finance.importer.StagedTxn
import com.yk.finance.importer.ValueParsers
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private fun at(day: Int, hour: Int = 12, minute: Int = 0): Long =
    LocalDateTime.of(2026, 9, day, hour, minute).atZone(ValueParsers.IST).toInstant().toEpochMilli()

class MatchWindowTest {

    @Test
    fun `a one-off amount identifies itself, so it can match a week out`() {
        assertEquals(MatchWindow.Within(7 * DAY), DiffMatcher.windowFor(1))
    }

    @Test
    fun `an amount seen constantly must match the same day`() {
        // ₹50 appears twenty times and identifies nothing. Without this the matcher
        // pairs unrelated bus fares and reports capture it never achieved.
        assertEquals(MatchWindow.SameDay, DiffMatcher.windowFor(20))
        assertEquals(MatchWindow.SameDay, DiffMatcher.windowFor(5))
    }

    @Test
    fun `everything in between gets a day and a half`() {
        assertEquals(MatchWindow.Within(36 * HOUR), DiffMatcher.windowFor(2))
        assertEquals(MatchWindow.Within(36 * HOUR), DiffMatcher.windowFor(4))
    }
}

class DiffTest {

    private val bankId = 1L

    private fun staged(
        amountPaise: Long,
        occurredAt: Long,
        kind: RowKind = RowKind.EXPENSE,
        account: String = "Card",
        line: Int = 1,
    ) = StagedTxn(
        lineNumber = line, kind = kind, amountPaise = amountPaise, occurredAt = occurredAt,
        timeWasInferred = false, accountName = account, toAccountName = null,
        categoryName = null, note = null, reference = null, rawLine = "", rawJson = "{}",
    )

    private fun txn(
        id: Long,
        amountPaise: Long,
        occurredAt: Long,
        direction: Direction = Direction.DEBIT,
        accountId: Long = 1L,
        source: TxnSource = TxnSource.SMS,
        transferGroupId: String? = null,
    ) = Txn(
        id = id, accountId = accountId, direction = direction, amountPaise = amountPaise,
        occurredAt = occurredAt, payee = null, reference = null, countsAsSpending = true,
        source = source, transferGroupId = transferGroupId,
    )

    private fun plan(rows: List<StagedTxn>) = ImportPlan(
        fileName = "x.csv",
        profileName = "Test",
        dialect = Dialect(','),
        mapping = ColumnMap.map(listOf("TIME", "AMOUNT")),
        dataRows = rows.size,
        staged = rows,
        rejected = emptyList(),
    )

    private fun diff(
        rows: List<StagedTxn>,
        ledger: List<Txn>,
        binding: (String) -> AccountBinding = { name ->
            when (name) {
                "Card" -> AccountBinding.Bank(bankId)
                "Cash" -> AccountBinding.Cash(99L)
                else -> AccountBinding.Unbound
            }
        },
    ) = DiffMatcher.diff(plan(rows), ledger, binding, at(1), at(30))

    @Test
    fun `a payment both sides recorded is matched once`() {
        val report = diff(
            listOf(staged(42500, at(10, 11))),
            listOf(txn(1, 42500, at(10, 11, 2))),
        )
        assertEquals(1, report.matched.size)
        assertEquals(0, report.fileOnly.size)
        assertEquals(0, report.appOnly.size)
        assertEquals(100, report.capturePercent)
    }

    @Test
    fun `a payment the app never saw is reported as missed, with its money`() {
        val report = diff(listOf(staged(42500, at(10))), emptyList())
        assertEquals(1, report.fileOnly.size)
        assertEquals(42500L, report.missedPaise)
        assertEquals(0, report.capturePercent)
    }

    @Test
    fun `a payment only the app saw is reported too`() {
        val report = diff(emptyList(), listOf(txn(1, 42500, at(10))))
        assertEquals(1, report.appOnly.size)
        // Nothing to compare against, so capture is not a failure here.
        assertEquals(100, report.capturePercent)
    }

    @Test
    fun `direction has to agree - a refund is not the payment`() {
        val report = diff(
            listOf(staged(42500, at(10))),
            listOf(txn(1, 42500, at(10), direction = Direction.CREDIT)),
        )
        assertEquals(1, report.fileOnly.size)
        assertEquals(1, report.appOnly.size)
    }

    @Test
    fun `one ledger row cannot satisfy two file rows`() {
        // Amounts appearing twice get the 36h window, so both rows reach the same
        // candidate; only the first may consume it.
        val report = diff(
            listOf(staged(10000, at(10), line = 1), staged(10000, at(11), line = 2)),
            listOf(txn(1, 10000, at(10))),
        )
        assertEquals(1, report.matched.size)
        assertEquals(1, report.fileOnly.size)
    }

    @Test
    fun `two candidates are never merged on a guess`() {
        val report = diff(
            listOf(staged(10000, at(10, 12))),
            listOf(txn(1, 10000, at(10, 9)), txn(2, 10000, at(10, 18))),
        )
        assertEquals(1, report.ambiguous.size)
        assertEquals(0, report.matched.size)
        // Both candidates are reserved, so neither is also reported as app-only.
        assertEquals(0, report.appOnly.size)
    }

    @Test
    fun `a frequent amount days apart is not a match`() {
        val rows = (1..6).map { staged(5000, at(it * 2), line = it) }
        val report = diff(rows, listOf(txn(1, 5000, at(3))))
        // Same-day only: the ledger row on the 3rd matches none of the even days.
        assertEquals(0, report.matched.size)
        assertEquals(6, report.fileOnly.size)
        assertEquals(1, report.appOnly.size)
    }

    @Test
    fun `a rare amount entered days late still matches`() {
        val report = diff(
            listOf(staged(133357, at(16))),
            listOf(txn(1, 133357, at(12))),
        )
        assertEquals(1, report.matched.size)
    }

    @Test
    fun `cash is excluded rather than counted as missed`() {
        val report = diff(listOf(staged(5000, at(10), account = "Cash")), emptyList())
        assertEquals(1, report.cashRowsExcluded)
        assertEquals(0, report.fileOnly.size)
        assertTrue(report.trustworthy)
    }

    @Test
    fun `an unbound account name makes the report say it is not a measurement`() {
        val report = diff(listOf(staged(5000, at(10), account = "Wallet")), emptyList())
        assertEquals(1, report.unboundRows)
        assertEquals(listOf("Wallet"), report.unmappedAccounts)
        assertFalse(report.trustworthy)
    }

    @Test
    fun `transfers and corrections are not part of the capture question`() {
        val report = diff(
            listOf(staged(300000, at(10), kind = RowKind.TRANSFER)),
            listOf(
                txn(1, 300000, at(10), transferGroupId = "g1"),
                txn(2, 5000, at(10), source = TxnSource.ADJUSTMENT),
            ),
        )
        assertEquals(1, report.transfersExcluded)
        assertEquals(0, report.fileOnly.size)
        assertEquals(0, report.appOnly.size)
    }

    @Test
    fun `rows outside the window are set aside, not counted against capture`() {
        val report = DiffMatcher.diff(
            plan(listOf(staged(5000, at(2)))),
            emptyList(),
            { AccountBinding.Bank(bankId) },
            at(10),
            at(20),
        )
        assertEquals(1, report.outOfWindowRows)
        assertEquals(0, report.fileOnly.size)
    }

    @Test
    fun `ambiguity counts as captured, because the app did record something`() {
        val report = diff(
            listOf(staged(10000, at(10, 12)), staged(42500, at(11))),
            listOf(txn(1, 10000, at(10, 9)), txn(2, 10000, at(10, 18))),
        )
        assertEquals(1, report.ambiguous.size)
        assertEquals(1, report.fileOnly.size)
        assertEquals(50, report.capturePercent)
    }
}
