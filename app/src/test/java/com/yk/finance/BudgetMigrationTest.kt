package com.yk.finance

import com.yk.finance.data.Budget
import com.yk.finance.domain.BudgetMigration
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Periods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Re-pinning one-off budgets from salary cycles onto calendar months.
 *
 * The failure being prevented is silent: a limit pinned to a cycle starting 31 Aug
 * matches no month, so it stops applying and the category falls back to its standing
 * limit with nothing on screen to say so. You would only find out by wondering why a
 * budget you tightened never warned you.
 */
class BudgetMigrationTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        CycleCalculator.startOfDayMillis(LocalDate.of(year, month, day))

    private val food = 1L
    private val bills = 2L

    private fun pinned(id: Long, categoryId: Long?, at: Long, limit: Long = 450_000) =
        Budget(id = id, categoryId = categoryId, limitPaise = limit, periodStart = at)

    @Test
    fun `a cycle-pinned limit moves to the month it was labelled with`() {
        val plan = BudgetMigration.plan(listOf(pinned(1, food, millis(2026, 8, 31))))

        assertEquals(millis(2026, 9, 1), plan.upserts.single().periodStart)
        assertEquals(450_000L, plan.upserts.single().limitPaise)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `a standing limit is never touched`() {
        val standing = Budget(id = 1, categoryId = food, limitPaise = 450_000, periodStart = null)

        val plan = BudgetMigration.plan(listOf(standing))

        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `each category keeps its own limit`() {
        val plan = BudgetMigration.plan(
            listOf(
                pinned(1, food, millis(2026, 8, 31), limit = 450_000),
                pinned(2, bills, millis(2026, 8, 31), limit = 900_000),
            ),
        )

        assertEquals(2, plan.upserts.size)
        assertEquals(
            setOf(450_000L to food, 900_000L to bills),
            plan.upserts.map { it.limitPaise to it.categoryId }.toSet(),
        )
        assertTrue(plan.deleteIds.isEmpty())
    }

    /**
     * Two boundaries can carry one label - 28 Aug and 2 Sep are both "September". Left
     * alone that would leave one category holding two overrides for one month, and
     * BudgetResolver picks arbitrarily between them.
     */
    @Test
    fun `two cycles labelled the same month collapse to the newest limit`() {
        val plan = BudgetMigration.plan(
            listOf(
                pinned(1, food, millis(2026, 8, 28), limit = 450_000),
                pinned(2, food, millis(2026, 9, 2), limit = 600_000),
            ),
        )

        assertEquals(1, plan.upserts.size)
        assertEquals(600_000L, plan.upserts.single().limitPaise)
        assertEquals(millis(2026, 9, 1), plan.upserts.single().periodStart)
        assertEquals(listOf(1L), plan.deleteIds)
    }

    /** The loser goes even when it is the already-correct row, or a duplicate survives. */
    @Test
    fun `an already-migrated row loses to a newer one and is removed`() {
        val plan = BudgetMigration.plan(
            listOf(
                pinned(1, food, millis(2026, 9, 1), limit = 450_000),
                pinned(7, food, millis(2026, 8, 31), limit = 600_000),
            ),
        )

        assertEquals(600_000L, plan.upserts.single().limitPaise)
        assertEquals(listOf(1L), plan.deleteIds)
    }

    /** The whole reason it can run unguarded on every launch. */
    @Test
    fun `a second pass writes nothing`() {
        val first = BudgetMigration.plan(
            listOf(
                pinned(1, food, millis(2026, 8, 31)),
                pinned(2, bills, millis(2026, 9, 30)),
            ),
        )

        val second = BudgetMigration.plan(first.upserts)

        assertTrue(second.upserts.isEmpty())
        assertTrue(second.deleteIds.isEmpty())
    }

    @Test
    fun `an untouched ledger produces an empty plan`() {
        val plan = BudgetMigration.plan(emptyList())

        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `an overall budget with no category migrates like any other`() {
        val plan = BudgetMigration.plan(listOf(pinned(1, null, millis(2026, 8, 31))))

        assertEquals(millis(2026, 9, 1), plan.upserts.single().periodStart)
    }

    @Test
    fun `every migrated row lands on a real month boundary`() {
        val plan = BudgetMigration.plan(
            listOf(
                pinned(1, food, millis(2026, 1, 30)),
                pinned(2, bills, millis(2026, 2, 26)),
                pinned(3, 3L, millis(2026, 12, 29)),
            ),
        )

        plan.upserts.forEach { budget ->
            val start = budget.periodStart!!
            assertEquals(start, Periods.monthStartOf(start))
        }
    }
}
