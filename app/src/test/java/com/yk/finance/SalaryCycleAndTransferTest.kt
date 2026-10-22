package com.yk.finance

import com.yk.finance.data.Budget
import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.Txn
import com.yk.finance.domain.BudgetEvaluator
import com.yk.finance.domain.BudgetResolver
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Period
import com.yk.finance.domain.Periods
import com.yk.finance.parser.Direction
import com.yk.finance.ui.FinanceViewModel
import com.yk.finance.ui.RecordFilter
import com.yk.finance.ui.TypeFilter
import com.yk.finance.ui.apply
import com.yk.finance.ui.monthSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SalaryCycleAndTransferTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        CycleCalculator.startOfDayMillis(LocalDate.of(year, month, day))

    @Test
    fun `transfer filter label is Self Transfer`() {
        assertEquals("Self Transfer", TypeFilter.TRANSFER.label)
    }

    @Test
    fun `monthSubtitle formats open salary cycle with start date and current date so far`() {
        val start = millis(2026, 9, 30) // Sep 30
        val now = millis(2026, 10, 5) + 12 * 3600_000L // Oct 5 noon

        val openPeriod = Period(
            startMillis = start,
            fromInclusive = start,
            toExclusive = Long.MAX_VALUE,
            label = Periods.labelFor(start),
            inferred = false,
            isOpen = true,
        )

        val subtitle = monthSubtitle(openPeriod, now)
        assertEquals("30 Sep - 5 Oct so far", subtitle)
    }

    @Test
    fun `monthSubtitle formats closed salary cycle with exact start and end dates`() {
        val start = millis(2026, 8, 28)
        val end = millis(2026, 9, 30)
        val now = millis(2026, 10, 5)

        val closedPeriod = Period(
            startMillis = start,
            fromInclusive = start,
            toExclusive = end,
            label = Periods.labelFor(start),
            inferred = false,
            isOpen = false,
        )

        val subtitle = monthSubtitle(closedPeriod, now)
        assertEquals("28 Aug - 29 Sep", subtitle)
    }

    @Test
    fun `BudgetResolver resolves override using either salary cycle start or labelled month start`() {
        val cycleStart = millis(2026, 8, 31) // cycle labelled September
        val labelledStart = Periods.labelledMonthStart(cycleStart) // 2026-09-01

        val standing = Budget(id = 1, categoryId = 10, limitPaise = 50_000_00, periodStart = null)
        val cycleOverride = Budget(id = 2, categoryId = 10, limitPaise = 60_000_00, periodStart = cycleStart)
        val monthOverride = Budget(id = 3, categoryId = 10, limitPaise = 70_000_00, periodStart = labelledStart)

        // When queried with cycleStart, cycle override wins
        val effective1 = BudgetResolver.effective(listOf(standing, cycleOverride), cycleStart)
        assertEquals(1, effective1.size)
        assertEquals(60_000_00L, effective1.single().budget.limitPaise)
        assertTrue(effective1.single().isOverride)

        // When queried with cycleStart and budget has labelledMonthStart, it matches too
        val effective2 = BudgetResolver.effective(listOf(standing, monthOverride), cycleStart)
        assertEquals(1, effective2.size)
        assertEquals(70_000_00L, effective2.single().budget.limitPaise)
        assertTrue(effective2.single().isOverride)
    }

    @Test
    fun `September period does not swallow transactions from months before it`() {
        val juneIncome = millis(2026, 6, 15)
        val julyIncome = millis(2026, 7, 15)
        val augIncome = millis(2026, 8, 15)
        val septIncome = millis(2026, 9, 15)

        val txns = listOf(
            Txn(id = 1, accountId = 1, amountPaise = 50_000_00, direction = Direction.CREDIT, occurredAt = juneIncome, payee = "ACME PAYROLL", reference = null, countsAsSpending = false, note = "June salary"),
            Txn(id = 2, accountId = 1, amountPaise = 50_000_00, direction = Direction.CREDIT, occurredAt = julyIncome, payee = "ACME PAYROLL", reference = null, countsAsSpending = false, note = "July salary"),
            Txn(id = 3, accountId = 1, amountPaise = 50_000_00, direction = Direction.CREDIT, occurredAt = augIncome, payee = "ACME PAYROLL", reference = null, countsAsSpending = false, note = "August salary"),
            Txn(id = 4, accountId = 1, amountPaise = 50_000_00, direction = Direction.CREDIT, occurredAt = septIncome, payee = "ACME PAYROLL", reference = null, countsAsSpending = false, note = "September salary"),
        )

        // Recorded boundaries in DB only have Aug 28 (opening September cycle) and Sep 30 (opening October)
        val recordedBoundaries = listOf(
            CycleBoundary(startMillis = millis(2026, 8, 28), source = "SALARY", inferred = false),
            CycleBoundary(startMillis = millis(2026, 9, 30), source = "SALARY", inferred = false),
        )

        val cycleState = CycleState(
            cycleStartMillis = millis(2026, 9, 30),
            salaryAmountPaise = 50_000_00,
            lastRollMillis = millis(2026, 9, 30),
        )

        val periods = FinanceViewModel.derivePeriods(
            transactions = txns,
            recordedBoundaries = recordedBoundaries,
            cycleState = cycleState,
            accounts = emptyList(),
            now = millis(2026, 10, 5),
        )

        val september = periods.single { it.label == "September, 2026" }

        assertTrue("September should contain September income", september.contains(septIncome))
        assertFalse("September must NOT contain June income", september.contains(juneIncome))
        assertFalse("September must NOT contain July income", september.contains(julyIncome))
        assertFalse("September must NOT contain mid-August income before boundary", september.contains(augIncome))

        val june = periods.firstOrNull { it.label == "June, 2026" }
        assertNotNull("June period must exist", june)
        assertTrue("June period must contain June income", june!!.contains(juneIncome))

        val july = periods.firstOrNull { it.label == "July, 2026" }
        assertNotNull("July period must exist", july)
        assertTrue("July period must contain July income", july!!.contains(julyIncome))

        val august = periods.firstOrNull { it.label == "August, 2026" }
        assertNotNull("August period must exist", august)
        assertTrue("August period must contain August income", august!!.contains(augIncome))
    }

    @Test
    fun `BudgetEvaluator currentPeriodStart returns active salary cycle start`() = kotlinx.coroutines.runBlocking {
        val cycleStart = millis(2026, 9, 30)
        val cycleState = CycleState(cycleStartMillis = cycleStart, lastRollMillis = cycleStart)
        val dao = java.lang.reflect.Proxy.newProxyInstance(
            FinanceDao::class.java.classLoader,
            arrayOf(FinanceDao::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("cycleState")) cycleState else null
        } as FinanceDao

        val evaluator = BudgetEvaluator(dao)
        assertEquals(cycleStart, evaluator.currentPeriodStart(millis(2026, 10, 5)))
    }

    @Test
    fun `transfer legs are matched by TRANSFER filter and excluded from expense and income`() {
        val debitLeg = Txn(
            id = 10,
            accountId = 1,
            direction = Direction.DEBIT,
            amountPaise = 5_000_00,
            occurredAt = millis(2026, 10, 1),
            payee = "Transfer",
            reference = null,
            countsAsSpending = false,
            transferGroupId = "transfer-123",
            source = com.yk.finance.data.TxnSource.TRANSFER_LEG,
        )
        val creditLeg = Txn(
            id = 11,
            accountId = 2,
            direction = Direction.CREDIT,
            amountPaise = 5_000_00,
            occurredAt = millis(2026, 10, 1),
            payee = "Transfer",
            reference = null,
            countsAsSpending = false,
            transferGroupId = "transfer-123",
            source = com.yk.finance.data.TxnSource.TRANSFER_LEG,
        )

        val txns = listOf(debitLeg, creditLeg)
        val filter = RecordFilter(type = TypeFilter.TRANSFER)
        val filtered = filter.apply(txns)
        assertEquals(2, filtered.size)

        // Transfer legs never count as expense or income
        val totals = com.yk.finance.domain.Ledger.totals(filtered)
        assertEquals(0L, totals.expensePaise)
        assertEquals(0L, totals.incomePaise)
    }

    @Test
    fun `monthSubtitle formats properly for future drift, today, and past dates`() {
        val start = millis(2026, 9, 30)
        val endExclusive = millis(2026, 10, 31)
        val openPeriod = Period(
            startMillis = start,
            fromInclusive = start,
            toExclusive = endExclusive,
            label = "October, 2026",
            inferred = false,
            isOpen = true,
        )

        // Clock drift / future start
        val beforeStart = millis(2026, 9, 25)
        assertEquals("from 30 Sep", monthSubtitle(openPeriod, beforeStart))

        // Same day
        val sameDay = millis(2026, 9, 30)
        assertEquals("today so far", monthSubtitle(openPeriod, sameDay))

        // Normal elapsed days
        val afterStart = millis(2026, 10, 5)
        assertEquals("30 Sep - 5 Oct so far", monthSubtitle(openPeriod, afterStart))

        // Closed period
        val closedPeriod = openPeriod.copy(isOpen = false)
        assertEquals("30 Sep - 30 Oct", monthSubtitle(closedPeriod, afterStart))
    }
}

