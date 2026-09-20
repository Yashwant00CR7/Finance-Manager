package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Category
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Ledger
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as expense, what counts as income, and what counts as neither.
 *
 * Three screens read these figures - the Records header, the donut, and every budget -
 * so a disagreement here would show up as three different answers to the same question.
 */
class LedgerTotalsTest {

    private fun spend(amount: Long, categoryId: Long? = null, at: Long = 0) = Txn(
        accountId = 1,
        direction = Direction.DEBIT,
        amountPaise = amount,
        occurredAt = at,
        payee = "SHOP",
        reference = null,
        categoryId = categoryId,
        countsAsSpending = true,
        source = TxnSource.SMS,
    )

    private fun credit(
        amount: Long,
        source: TxnSource = TxnSource.SMS,
        group: String? = null,
    ) = Txn(
        accountId = 1,
        direction = Direction.CREDIT,
        amountPaise = amount,
        occurredAt = 0,
        payee = "PAYROLL",
        reference = null,
        countsAsSpending = false,
        transferGroupId = group,
        source = source,
    )

    private fun transferLeg(amount: Long, direction: Direction) = Txn(
        accountId = 1,
        direction = direction,
        amountPaise = amount,
        occurredAt = 0,
        payee = "Transfer",
        reference = null,
        countsAsSpending = false,
        transferGroupId = "group-1",
        source = TxnSource.TRANSFER_LEG,
    )

    private fun adjustment(amount: Long, direction: Direction) = Txn(
        accountId = 1,
        direction = direction,
        amountPaise = amount,
        occurredAt = 0,
        payee = "Balance correction",
        reference = null,
        countsAsSpending = false,
        source = TxnSource.ADJUSTMENT,
    )

    @Test
    fun `an ATM withdrawal is not spending`() {
        val rows = listOf(spend(32_000), transferLeg(300_000, Direction.DEBIT))
        assertEquals(32_000L, Ledger.totals(rows).expensePaise)
    }

    @Test
    fun `a balance correction is neither income nor expense`() {
        val rows = listOf(
            spend(10_000),
            adjustment(50_000, Direction.CREDIT),
            adjustment(20_000, Direction.DEBIT),
        )
        val totals = Ledger.totals(rows)
        assertEquals(10_000L, totals.expensePaise)
        assertEquals(0L, totals.incomePaise)
    }

    @Test
    fun `the credit leg of a transfer is not income`() {
        val rows = listOf(credit(500_000), transferLeg(300_000, Direction.CREDIT))
        assertEquals(500_000L, Ledger.totals(rows).incomePaise)
    }

    @Test
    fun `total is income minus expense`() {
        val rows = listOf(credit(2_593_700), spend(1_813_900))
        assertEquals(779_800L, Ledger.totals(rows).netPaise)
    }

    @Test
    fun `uncategorised spending gets its own slice rather than disappearing`() {
        val categories = listOf(Category(id = 1, name = "Food"))
        val rows = listOf(spend(60_000, categoryId = 1), spend(40_000, categoryId = null))

        val slices = Ledger.breakdown(rows, categories, income = false)
        assertEquals(2, slices.size)
        assertEquals(100_000L, slices.sumOf { it.amountPaise })
        assertTrue(slices.any { it.categoryId == null })
        assertEquals(100.0, slices.sumOf { it.percent }, 0.0001)
    }

    @Test
    fun `slices come back largest first`() {
        val categories = listOf(Category(id = 1, name = "Food"), Category(id = 2, name = "Bills"))
        val slices = Ledger.breakdown(
            listOf(spend(20_000, 1), spend(80_000, 2)),
            categories,
            income = false,
        )
        assertEquals("Bills", slices.first().name)
    }

    @Test
    fun `a period with no spending produces no slices rather than a divide by zero`() {
        assertTrue(Ledger.breakdown(emptyList(), emptyList(), income = false).isEmpty())
    }

    @Test
    fun `the overall card reports openings and corrections separately`() {
        val accounts = listOf(
            Account(
                id = 1,
                displayName = "Card",
                bank = "ICICI",
                accountToken = "123",
                kind = AccountKind.BANK,
                openingBalancePaise = 100_000,
                // 100,000 opened + 500,000 in - 50,000 out + 12,750 corrected.
                currentBalancePaise = 562_750,
            ),
        )
        val rows = listOf(spend(50_000), credit(500_000), adjustment(12_750, Direction.CREDIT))

        val overall = Ledger.overall(rows, accounts)
        assertEquals(50_000L, overall.expenseAllTimePaise)
        assertEquals(500_000L, overall.incomeAllTimePaise)
        assertEquals(562_750L, overall.totalBalancePaise)
        assertEquals(100_000L, overall.openingPaise)
        assertEquals(12_750L, overall.correctionsPaise)

        // The identity the screen is claiming: balance is explained by the four parts.
        assertEquals(
            overall.totalBalancePaise,
            overall.openingPaise + overall.incomeAllTimePaise -
                overall.expenseAllTimePaise + overall.correctionsPaise,
        )
    }

    @Test
    fun `a negative correction reduces the reconciling figure`() {
        val overall = Ledger.overall(listOf(adjustment(30_000, Direction.DEBIT)), emptyList())
        assertEquals(-30_000L, overall.correctionsPaise)
    }

    @Test
    fun `income and expense classification is mutually exclusive`() {
        val rows = listOf(
            spend(1), credit(1), transferLeg(1, Direction.DEBIT),
            transferLeg(1, Direction.CREDIT), adjustment(1, Direction.CREDIT),
        )
        rows.forEach { row ->
            assertFalse(
                "a row cannot be both income and expense",
                Ledger.isExpense(row) && Ledger.isIncome(row),
            )
        }
    }
}
