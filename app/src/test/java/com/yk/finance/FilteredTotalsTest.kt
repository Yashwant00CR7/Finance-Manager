package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Ledger
import com.yk.finance.parser.Direction
import com.yk.finance.ui.RecordFilter
import com.yk.finance.ui.TypeFilter
import com.yk.finance.ui.apply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header's figures and the list beneath them, agreeing.
 *
 * They used not to. Narrowing to Food left the whole month's expense sitting above
 * twelve Food rows, which reads as their sum - the one way a filter can lie about
 * money. Both now come from the same narrowing, and these are the tests that keep
 * them coming from the same narrowing.
 */
class FilteredTotalsTest {

    private val salaryAccount = 1L
    private val cardAccount = 2L
    private val food = 10L
    private val transport = 11L

    private fun spend(
        amount: Long,
        accountId: Long = salaryAccount,
        categoryId: Long? = food,
    ) = Txn(
        accountId = accountId,
        direction = Direction.DEBIT,
        amountPaise = amount,
        occurredAt = 0,
        payee = "SHOP",
        reference = null,
        categoryId = categoryId,
        countsAsSpending = true,
        source = TxnSource.SMS,
    )

    private fun income(amount: Long, accountId: Long = salaryAccount) = Txn(
        accountId = accountId,
        direction = Direction.CREDIT,
        amountPaise = amount,
        occurredAt = 0,
        payee = "PAYROLL",
        reference = null,
        countsAsSpending = false,
        source = TxnSource.SMS,
    )

    private val ledger = listOf(
        spend(100_000, categoryId = food),
        spend(50_000, categoryId = food, accountId = cardAccount),
        spend(30_000, categoryId = transport),
        spend(20_000, categoryId = null),
        income(500_000),
    )

    @Test
    fun `no filter leaves every row and the whole month's figures`() {
        val shown = RecordFilter().apply(ledger)
        val totals = Ledger.totals(shown)

        assertEquals(ledger.size, shown.size)
        assertEquals(200_000L, totals.expensePaise)
        assertEquals(500_000L, totals.incomePaise)
    }

    @Test
    fun `filtering to a category totals only that category`() {
        val filter = RecordFilter(categoryId = food)
        val totals = Ledger.totals(filter.apply(ledger))

        assertEquals(150_000L, totals.expensePaise)
        assertEquals(0L, totals.incomePaise)
    }

    /** The defect, stated directly: the narrowed figure must not equal the month's. */
    @Test
    fun `a filtered expense figure differs from the unfiltered one`() {
        val filter = RecordFilter(categoryId = transport)

        assertNotEquals(
            Ledger.totals(ledger).expensePaise,
            Ledger.totals(filter.apply(ledger)).expensePaise,
        )
        assertEquals(30_000L, Ledger.totals(filter.apply(ledger)).expensePaise)
    }

    @Test
    fun `filtering to an account totals only that account`() {
        val filter = RecordFilter(accountId = cardAccount)
        val totals = Ledger.totals(filter.apply(ledger))

        assertEquals(50_000L, totals.expensePaise)
    }

    @Test
    fun `two filters narrow together`() {
        val filter = RecordFilter(categoryId = food, accountId = salaryAccount)
        val totals = Ledger.totals(filter.apply(ledger))

        assertEquals(100_000L, totals.expensePaise)
    }

    @Test
    fun `the income type filter hides every expense`() {
        val filter = RecordFilter(type = TypeFilter.INCOME)
        val totals = Ledger.totals(filter.apply(ledger))

        assertEquals(0L, totals.expensePaise)
        assertEquals(500_000L, totals.incomePaise)
    }

    @Test
    fun `uncategorised only keeps the unlabelled spending`() {
        val filter = RecordFilter(uncategorisedOnly = true)
        val shown = filter.apply(ledger)

        assertEquals(1, shown.size)
        assertEquals(20_000L, Ledger.totals(shown).expensePaise)
    }

    /**
     * The header only claims to be filtered when something is filtered. isActive is
     * what decides whether the figures narrow at all, so an empty filter reporting
     * itself active would narrow nothing while announcing that it had.
     */
    @Test
    fun `an empty filter is not active and every populated one is`() {
        assertTrue(!RecordFilter().isActive)

        listOf(
            RecordFilter(accountId = cardAccount),
            RecordFilter(categoryId = food),
            RecordFilter(type = TypeFilter.EXPENSE),
            RecordFilter(uncategorisedOnly = true),
        ).forEach { assertTrue(it.isActive) }
    }

    @Test
    fun `a filter matching nothing totals zero rather than the month`() {
        val filter = RecordFilter(categoryId = 999L)
        val totals = Ledger.totals(filter.apply(ledger))

        assertTrue(filter.apply(ledger).isEmpty())
        assertEquals(0L, totals.expensePaise)
        assertEquals(0L, totals.incomePaise)
    }
}
