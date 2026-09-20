package com.yk.finance

import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.domain.CycleBackfill
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.IST
import com.yk.finance.domain.Periods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The period strip's foundations: what a cycle is called, and where past cycles began.
 *
 * Both are reconstructions - the label is a month name over a salary cycle, and every
 * boundary before v3 had to be recovered from the ledger. These tests are what stop
 * either quietly drifting from what actually happened.
 */
class CycleHistoryTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        CycleCalculator.startOfDayMillis(LocalDate.of(year, month, day))

    private fun salary(
        id: Long,
        year: Int,
        month: Int,
        day: Int,
        amountPaise: Long = 5_000_000,
        payee: String? = "ACME PAYROLL",
        bank: String? = "UNION",
    ) = CycleBackfill.SalaryCandidate(
        txnId = id,
        occurredAt = millis(year, month, day) + 10 * 3600_000L,
        amountPaise = amountPaise,
        payee = payee,
        bank = bank,
    )

    private fun learnedState(cycleStart: Long) = CycleState(
        cycleStartMillis = cycleStart,
        salaryBank = "UNION",
        salaryPayee = "ACME PAYROLL",
        salaryAmountPaise = 5_000_000,
        lastRollMillis = cycleStart,
    )

    // ----- the label rule -----

    @Test
    fun `a cycle opened at a month end is labelled by the month it covers`() {
        assertEquals("September, 2026", Periods.labelFor(millis(2026, 8, 28)))
        assertEquals("October, 2026", Periods.labelFor(millis(2026, 9, 30)))
    }

    @Test
    fun `a late salary does not change the label`() {
        // Payroll slipping to the 2nd must not relabel October's cycle as November's.
        assertEquals("October, 2026", Periods.labelFor(millis(2026, 10, 2)))
    }

    @Test
    fun `an early salary does not change the label either`() {
        // Paid on the 25th for a holiday: still the cycle that covers October.
        assertEquals("October, 2026", Periods.labelFor(millis(2026, 9, 25)))
    }

    @Test
    fun `the label rolls the year over correctly`() {
        assertEquals("January, 2027", Periods.labelFor(millis(2026, 12, 29)))
    }

    // ----- building periods from boundaries -----

    private fun boundary(start: Long, inferred: Boolean = false) =
        CycleBoundary(startMillis = start, source = if (inferred) "FALLBACK" else "SALARY", inferred = inferred)

    @Test
    fun `periods chain end to end with the newest left open`() {
        val periods = Periods.from(
            listOf(boundary(millis(2026, 7, 31)), boundary(millis(2026, 8, 28)), boundary(millis(2026, 9, 29))),
            now = millis(2026, 10, 5),
        )

        assertEquals(3, periods.size)
        assertEquals(millis(2026, 8, 28), periods[0].toExclusive)
        assertEquals(millis(2026, 9, 29), periods[1].toExclusive)
        assertTrue(periods.last().isOpen)
        assertEquals(Long.MAX_VALUE, periods.last().toExclusive)
    }

    @Test
    fun `anything older than the first boundary still belongs to a period`() {
        val periods = Periods.from(listOf(boundary(millis(2026, 7, 31))), now = millis(2026, 8, 10))
        // An imported row from before the app existed must not fall through every
        // period and become invisible.
        assertTrue(periods.first().contains(millis(2025, 1, 1)))
    }

    @Test
    fun `a future boundary does not open a period you can be looking at`() {
        val periods = Periods.from(
            listOf(boundary(millis(2026, 9, 29)), boundary(millis(2026, 10, 30))),
            now = millis(2026, 10, 5),
        )
        assertEquals(1, periods.size)
        assertEquals(millis(2026, 9, 29), periods.single().startMillis)
    }

    @Test
    fun `with no boundaries at all there is still one period to show`() {
        val periods = Periods.from(emptyList(), now = millis(2026, 9, 20))
        assertEquals(1, periods.size)
        assertTrue(periods.single().inferred)
        assertTrue(periods.single().contains(millis(2026, 9, 20)))
    }

    // ----- the backfill -----

    @Test
    fun `boundaries are read from real salary credits where they exist`() {
        val credits = listOf(
            salary(1, 2026, 6, 26),
            salary(2, 2026, 7, 31),
            salary(3, 2026, 9, 29),
        )
        val derived = CycleBackfill.derive(
            credits = credits,
            state = learnedState(millis(2026, 9, 29)),
            earliestTxnMillis = millis(2026, 6, 20),
            now = millis(2026, 10, 3),
        )

        val salaries = derived.filter { it.source == "SALARY" }.map { it.startMillis }
        assertTrue(salaries.contains(millis(2026, 6, 26)))
        assertTrue(salaries.contains(millis(2026, 7, 31)))
        assertTrue(salaries.contains(millis(2026, 9, 29)))
        assertTrue(derived.none { it.inferred && it.startMillis == millis(2026, 7, 31) })
    }

    @Test
    fun `a month with no salary credit falls back and is flagged inferred`() {
        // August has no credit: that boundary is arithmetic, and says so.
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 7, 31), salary(2, 2026, 9, 29)),
            state = learnedState(millis(2026, 9, 29)),
            earliestTxnMillis = millis(2026, 7, 1),
            now = millis(2026, 10, 3),
        )

        val august = derived.firstOrNull {
            CycleCalculator.toLocalDate(it.startMillis).month == java.time.Month.AUGUST
        }
        assertTrue("August should have a boundary", august != null)
        assertTrue("August had no salary credit, so it must be inferred", august!!.inferred)
        assertEquals("FALLBACK", august.source)
    }

    @Test
    fun `boundaries come back sorted and unique`() {
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 7, 31), salary(2, 2026, 8, 28), salary(3, 2026, 9, 29)),
            state = learnedState(millis(2026, 9, 29)),
            earliestTxnMillis = millis(2026, 7, 1),
            now = millis(2026, 10, 3),
        )
        assertEquals(derived.map { it.startMillis }.sorted(), derived.map { it.startMillis })
        assertEquals(derived.map { it.startMillis }.distinct().size, derived.size)
    }

    @Test
    fun `the live cycle start always wins over the arithmetic near it`() {
        // Salary landed 29 Sep; the last-working-day rule says 30 Sep. Only one
        // boundary may survive, and it has to be the one the app observed.
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 9, 29)),
            state = learnedState(millis(2026, 9, 29)),
            earliestTxnMillis = millis(2026, 9, 1),
            now = millis(2026, 10, 3),
        )
        val september = derived.filter {
            CycleCalculator.toLocalDate(it.startMillis).month == java.time.Month.SEPTEMBER
        }
        assertEquals(1, september.size)
        assertEquals(millis(2026, 9, 29), september.single().startMillis)
    }

    @Test
    fun `an ordinary credit is not mistaken for salary`() {
        // 3,000 from family, in a month where salary is 50,000: nowhere near tolerance.
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 8, 28, amountPaise = 300_000, payee = "MOTHER")),
            state = learnedState(millis(2026, 8, 28)),
            earliestTxnMillis = millis(2026, 8, 1),
            now = millis(2026, 9, 10),
        )
        assertTrue(derived.none { it.source == "SALARY" && it.txnId == 1L })
    }

    @Test
    fun `two credits inside the same cycle roll it only once`() {
        // Salary plus an arrears payment a week later must not open two cycles.
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 8, 28), salary(2, 2026, 9, 4)),
            state = learnedState(millis(2026, 8, 28)),
            earliestTxnMillis = millis(2026, 8, 1),
            now = millis(2026, 9, 20),
        )
        assertEquals(1, derived.count { it.source == "SALARY" })
    }

    @Test
    fun `with nothing learned about salary every boundary is inferred`() {
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 8, 28)),
            state = CycleState(
                cycleStartMillis = millis(2026, 8, 28),
                lastRollMillis = millis(2026, 8, 28),
            ),
            earliestTxnMillis = millis(2026, 7, 1),
            now = millis(2026, 9, 20),
        )
        assertTrue(derived.all { it.inferred })
        assertFalse(derived.any { it.source == "SALARY" })
    }

    @Test
    fun `every transaction in the ledger lands inside some period`() {
        val derived = CycleBackfill.derive(
            credits = listOf(salary(1, 2026, 7, 31), salary(2, 2026, 8, 28)),
            state = learnedState(millis(2026, 8, 28)),
            earliestTxnMillis = millis(2026, 7, 5),
            now = millis(2026, 9, 15),
        )
        val periods = Periods.from(
            derived.map { CycleBoundary(startMillis = it.startMillis, source = it.source, inferred = it.inferred) },
            now = millis(2026, 9, 15),
        )

        listOf(
            millis(2026, 7, 5), millis(2026, 7, 31), millis(2026, 8, 15),
            millis(2026, 8, 28), millis(2026, 9, 14),
        ).forEach { moment ->
            assertEquals(
                "every moment must belong to exactly one period",
                1,
                periods.count { it.contains(moment) },
            )
        }
    }

    @Test
    fun `the timezone is IST, so a late-night record stays on its own day`() {
        assertEquals(IST.id, "Asia/Kolkata")
        val lateNight = millis(2026, 9, 19) + 23 * 3600_000L + 30 * 60_000L
        assertEquals(LocalDate.of(2026, 9, 19), CycleCalculator.toLocalDate(lateNight))
    }
}
