package com.yk.finance

import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Periods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Calendar months, which is now what every figure in the app is counted over.
 *
 * The bug these exist to prevent is the one that shipped: with no salary boundary
 * recorded, the old period list collapsed to a single bucket spanning all of time, so
 * a header reading "September, 2026" was totalling July, August and September together
 * while both arrows sat dead. A month here has exact edges and there is always more
 * than one of them as soon as the ledger spans more than one.
 */
class PeriodsMonthTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        CycleCalculator.startOfDayMillis(LocalDate.of(year, month, day))

    private fun noon(year: Int, month: Int, day: Int): Long =
        millis(year, month, day) + 12 * 3600_000L

    @Test
    fun `an empty ledger offers the current month and nothing else`() {
        val months = Periods.months(null, null, noon(2026, 9, 20))

        assertEquals(1, months.size)
        assertEquals("September, 2026", months.single().label)
        assertTrue(months.single().isOpen)
    }

    @Test
    fun `a ledger spanning three months offers three stops`() {
        val months = Periods.months(noon(2026, 7, 15), noon(2026, 9, 18), noon(2026, 9, 20))

        assertEquals(
            listOf("July, 2026", "August, 2026", "September, 2026"),
            months.map { it.label },
        )
    }

    @Test
    fun `only the month we are in is open`() {
        val months = Periods.months(noon(2026, 7, 15), noon(2026, 9, 18), noon(2026, 9, 20))

        assertEquals(listOf(false, false, true), months.map { it.isOpen })
    }

    @Test
    fun `no month is ever inferred`() {
        val months = Periods.months(noon(2026, 7, 15), noon(2026, 9, 18), noon(2026, 9, 20))

        assertTrue(months.none { it.inferred })
    }

    /**
     * The catch-all is gone. The old earliest period ran from Long.MIN_VALUE, which is
     * what let one bucket swallow every imported row; a month owns its own days only.
     */
    @Test
    fun `a month contains its own days and no others`() {
        val august = Periods.months(noon(2026, 7, 15), noon(2026, 9, 18), noon(2026, 9, 20))
            .single { it.label == "August, 2026" }

        assertTrue(august.contains(millis(2026, 8, 1)))
        assertTrue(august.contains(millis(2026, 8, 31) + 23 * 3600_000L))
        assertFalse(august.contains(millis(2026, 8, 1) - 1))
        assertFalse(august.contains(millis(2026, 9, 1)))
    }

    @Test
    fun `every row in the ledger lands in exactly one month`() {
        val rows = listOf(
            noon(2026, 7, 1), noon(2026, 7, 31), noon(2026, 8, 15),
            noon(2026, 9, 1), noon(2026, 9, 20),
        )
        val months = Periods.months(rows.min(), rows.max(), noon(2026, 9, 20))

        rows.forEach { at ->
            assertEquals(1, months.count { it.contains(at) })
        }
    }

    /**
     * A future-dated entry has to be reachable. Without this it would count towards an
     * account balance while sitting in a month the arrows cannot get to, which is the
     * one way a transaction can be present and invisible at the same time.
     */
    @Test
    fun `the range stretches forward to cover a future-dated row`() {
        val months = Periods.months(noon(2026, 9, 1), noon(2026, 11, 4), noon(2026, 9, 20))

        assertEquals(
            listOf("September, 2026", "October, 2026", "November, 2026"),
            months.map { it.label },
        )
        assertEquals("September, 2026", months.single { it.isOpen }.label)
    }

    @Test
    fun `a ledger that starts after today still offers this month`() {
        val months = Periods.months(noon(2026, 12, 1), noon(2026, 12, 1), noon(2026, 9, 20))

        assertEquals("September, 2026", months.first().label)
        assertEquals("December, 2026", months.last().label)
    }

    @Test
    fun `a year boundary is crossed cleanly`() {
        val months = Periods.months(noon(2026, 11, 20), noon(2027, 1, 5), noon(2027, 1, 5))

        assertEquals(
            listOf("November, 2026", "December, 2026", "January, 2027"),
            months.map { it.label },
        )
    }

    @Test
    fun `monthStartOf snaps to the first of the month and stays there`() {
        val mid = Periods.monthStartOf(noon(2026, 9, 18))

        assertEquals(millis(2026, 9, 1), mid)
        assertEquals(mid, Periods.monthStartOf(mid))
    }

    /**
     * The mapping the budget migration leans on. A cycle is labelled by start + 7 days,
     * so these are the months those limits were set *on screen*, whatever date the
     * boundary carried.
     */
    @Test
    fun `a cycle start maps to the month it was labelled with`() {
        assertEquals(millis(2026, 9, 1), Periods.labelledMonthStart(millis(2026, 8, 31)))
        assertEquals(millis(2026, 10, 1), Periods.labelledMonthStart(millis(2026, 9, 30)))
        assertEquals(millis(2026, 10, 1), Periods.labelledMonthStart(millis(2026, 10, 2)))
        assertEquals(millis(2026, 9, 1), Periods.labelledMonthStart(millis(2026, 9, 5)))
    }

    @Test
    fun `labelledMonthStart agrees with the label the header showed`() {
        listOf(millis(2026, 8, 31), millis(2026, 9, 30), millis(2026, 10, 2)).forEach { start ->
            val mapped = Periods.labelledMonthStart(start)
            assertEquals(Periods.labelFor(start), Periods.labelFor(mapped))
        }
    }

    /** Applied twice it must be a no-op, which is what lets the migration run unguarded. */
    @Test
    fun `labelledMonthStart is idempotent`() {
        listOf(millis(2026, 8, 31), millis(2026, 2, 27), millis(2026, 12, 29)).forEach { start ->
            val once = Periods.labelledMonthStart(start)
            assertEquals(once, Periods.labelledMonthStart(once))
        }
    }
}
