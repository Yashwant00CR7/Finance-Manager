package com.yk.finance.domain

import com.yk.finance.data.Budget
import com.yk.finance.data.FinanceDao

/**
 * Moves cycle-pinned budgets onto calendar months.
 *
 * A budget with a non-null `periodStart` was pinned to one salary cycle - a value like
 * "31 Aug", written the moment you flipped "Applies to September, 2026 only". Now that
 * every period is a calendar month, that value matches nothing: the row stays in the
 * table, applies to no month, and the category quietly falls back to its standing
 * limit. A limit you deliberately set disappearing without a word is the failure this
 * prevents.
 *
 * The mapping is by *label*, not by date. A cycle opening 31 Aug was shown to you as
 * September, so it becomes 1 September - the month you were looking at, rather than the
 * month the boundary arithmetic happened to fall in.
 *
 * No flag guards it because none is needed. A month start plus seven days is still
 * inside the same month, so [Periods.labelledMonthStart] leaves an already-migrated row
 * exactly where it is and a second pass writes nothing. That makes it safe to run
 * unconditionally on every launch, which is how the other seeders here work.
 */
object BudgetMigration {

    /**
     * What the migration would write. Separated from the writing for the same reason
     * the importer's Committer is: the decisions are the part worth testing, and they can be checked
     * without a database in the way.
     */
    data class Plan(
        val upserts: List<Budget>,
        val deleteIds: List<Long>,
    )

    fun plan(budgets: List<Budget>): Plan {
        val pinned = budgets.filter { it.periodStart != null }
        if (pinned.isEmpty()) return Plan(emptyList(), emptyList())

        val remapped = pinned.map {
            it.copy(periodStart = Periods.labelledMonthStart(it.periodStart!!))
        }

        // Two cycles can carry the same label - a 28 Aug and a 2 Sep boundary are both
        // "September" - so remapping can collide two limits onto one category-month.
        // BudgetResolver picks a single override per category, so one has to go; the
        // newest row wins, being the one you set last.
        val winners = remapped
            .groupBy { it.categoryId to it.periodStart }
            .values
            .map { rows -> rows.maxBy { it.id } }

        val keptIds = winners.map { it.id }.toSet()
        val before = pinned.associateBy { it.id }

        return Plan(
            // Only rows that actually moved are written, so a launch with nothing to do
            // touches the database not at all.
            upserts = winners.filter { before.getValue(it.id).periodStart != it.periodStart },
            deleteIds = remapped.filterNot { it.id in keptIds }.map { it.id },
        )
    }

    suspend fun run(dao: FinanceDao) {
        val plan = plan(dao.allBudgets())
        plan.upserts.forEach { dao.upsertBudget(it) }
        plan.deleteIds.forEach { dao.deleteBudget(it) }
    }
}
