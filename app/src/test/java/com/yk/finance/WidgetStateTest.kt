package com.yk.finance

import com.yk.finance.domain.CycleCalculator
import com.yk.finance.widget.WidgetState
import com.yk.finance.widget.WidgetState.Ceiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the home screen widget draws.
 *
 * A widget is read in passing and believed without being checked, so the branching
 * behind the figure is pinned here rather than by resizing a home screen and squinting.
 */
class WidgetStateTest {

    private val sept = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 8, 31))
    private val midSept = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 9, 20))

    private fun state(
        spent: Long,
        budget: Long? = null,
        income: Long = 0,
    ) = WidgetState.of(sept, midSept, spent, budget, income)

    // ---------- which ceiling wins ----------

    @Test
    fun `a budget you set beats income every time`() {
        // Intent outranks description: a cap is a statement about what you meant to
        // spend, income is only a record of what turned up.
        val s = state(spent = 1_824_000, budget = 2_500_000, income = 4_200_000)
        assertEquals(Ceiling.BUDGET, s.ceiling)
        assertEquals(2_500_000L, s.ceilingPaise)
        assertEquals(72, s.percent)
    }

    @Test
    fun `income becomes the ceiling when no budget is set`() {
        val s = state(spent = 1_824_000, budget = null, income = 4_200_000)
        assertEquals(Ceiling.INCOME, s.ceiling)
        assertEquals(4_200_000L, s.ceilingPaise)
        assertEquals(43, s.percent)
        assertEquals(2_376_000L, s.remainingPaise)
    }

    @Test
    fun `a zero or negative budget is not a ceiling`() {
        // A budget row of 0 means "not set up", not "you may spend nothing" - reading
        // it the other way would put every widget permanently at over-budget red.
        assertEquals(Ceiling.INCOME, state(1000, budget = 0, income = 50_000).ceiling)
        assertEquals(Ceiling.NONE, state(1000, budget = 0, income = 0).ceiling)
    }

    @Test
    fun `no budget and no income yet means no bar at all`() {
        // The cycle can roll on the date fallback before salary lands, and a fresh
        // install has neither. A bar at zero would read as "you have spent nothing".
        val s = state(spent = 124_000, budget = null, income = 0)
        assertEquals(Ceiling.NONE, s.ceiling)
        assertFalse(s.hasBar)
        assertEquals(0, s.percent)
    }

    // ---------- going over ----------

    @Test
    fun `over budget reports the real percentage but a full bar`() {
        val s = state(spent = 3_000_000, budget = 2_500_000)
        assertTrue(s.exceeded)
        assertEquals(120, s.percent)
        // A trough cannot be 120% full, and clipping the number instead of the bar
        // would hide the only thing worth knowing.
        assertEquals(100, s.barPercent)
        assertEquals(500_000L, s.overPaise)
        assertEquals(0L, s.remainingPaise)
    }

    @Test
    fun `remaining never goes negative`() {
        assertEquals(0L, state(spent = 9_000_000, budget = 2_500_000).remainingPaise)
    }

    @Test
    fun `nothing spent is not over budget`() {
        val s = state(spent = 0, budget = 2_500_000)
        assertFalse(s.exceeded)
        assertEquals(0, s.percent)
        assertEquals(2_500_000L, s.remainingPaise)
    }

    @Test
    fun `with no ceiling nothing is ever reported as exceeded`() {
        // Otherwise a first launch with one payment on it would open showing red.
        assertFalse(state(spent = 500_000, budget = null, income = 0).exceeded)
    }

    // ---------- the label ----------

    @Test
    fun `a cycle opening in late August is labelled September`() {
        // The +7 day rule: salary on 31 Aug opens September, and the widget has to
        // agree with how Settings already names that cycle.
        assertEquals("September", state(0).periodLabel)
    }

    @Test
    fun `the label carries no year, because the widget has no room for one`() {
        assertFalse(state(0).periodLabel.contains(","))
        assertFalse(state(0).periodLabel.contains("2026"))
    }

    // ---------- days to payday ----------

    @Test
    fun `days to payday counts to this month's last working day`() {
        // 30 Sep 2026 is a Wednesday, so it is the boundary and it is 10 days out.
        val on20th = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 9, 20))
        assertEquals(10, WidgetState.daysToPayday(on20th))
    }

    @Test
    fun `on payday itself it counts to the next one, never to zero forever`() {
        val onBoundary = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 9, 30))
        assertTrue(WidgetState.daysToPayday(onBoundary) > 0)
    }

    @Test
    fun `days to payday is never negative`() {
        (1..28).forEach { day ->
            val millis = CycleCalculator.startOfDayMillis(LocalDate.of(2026, 2, day))
            assertTrue("day $day", WidgetState.daysToPayday(millis) >= 0)
        }
    }

    // ---------- formatting ----------

    @Test
    fun `figures are whole rupees, grouped the Indian way`() {
        assertEquals("₹18,240", WidgetState.shortRupees(1_824_000))
        assertEquals("₹1,00,000", WidgetState.shortRupees(10_000_000))
        assertEquals("₹0", WidgetState.shortRupees(0))
    }

    @Test
    fun `paise are dropped rather than rounded up`() {
        // Rounding up would let a widget claim you spent more than the ledger says,
        // which is a small lie in the direction that erodes trust fastest.
        assertEquals("₹99", WidgetState.shortRupees(9_999))
    }
}
