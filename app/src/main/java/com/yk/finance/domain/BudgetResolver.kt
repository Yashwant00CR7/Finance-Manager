package com.yk.finance.domain

import com.yk.finance.data.Budget

/**
 * Which limit is in force for a given cycle.
 *
 * A budget with a null periodStart is the standing limit and applies to every cycle -
 * that is what every pre-v3 row becomes, and what you get by editing a limit without
 * saying otherwise. A row pinned to a periodStart overrides the standing limit for that
 * one cycle, and for no other.
 *
 * Resolution is per category, so overriding Food for September leaves every other
 * category on its standing limit rather than freezing the whole set.
 */
object BudgetResolver {

    data class Effective(
        val budget: Budget,
        /** True when a cycle-specific row beat the standing limit. */
        val isOverride: Boolean,
    )

    fun effective(budgets: List<Budget>, periodStart: Long): List<Effective> =
        budgets
            .filter { it.periodStart == null || it.periodStart == periodStart }
            .groupBy { it.categoryId }
            .mapNotNull { (_, rows) ->
                val override = rows.firstOrNull { it.periodStart == periodStart }
                when {
                    override != null -> Effective(override, isOverride = true)
                    else -> rows.firstOrNull { it.periodStart == null }
                        ?.let { Effective(it, isOverride = false) }
                }
            }
            .sortedBy { it.budget.categoryId ?: Long.MIN_VALUE }

    /**
     * The row an edit should write for [categoryId].
     *
     * [thisCycleOnly] is the whole decision: false rewrites the standing limit (or
     * creates one), true writes a row pinned to this cycle and leaves the standing
     * limit alone, so next cycle reverts rather than silently inheriting a one-off.
     */
    fun rowToWrite(
        budgets: List<Budget>,
        categoryId: Long?,
        limitPaise: Long,
        periodStart: Long,
        thisCycleOnly: Boolean,
    ): Budget {
        val existing = if (thisCycleOnly) {
            budgets.firstOrNull { it.categoryId == categoryId && it.periodStart == periodStart }
        } else {
            budgets.firstOrNull { it.categoryId == categoryId && it.periodStart == null }
        }
        return existing?.copy(limitPaise = limitPaise)
            ?: Budget(
                categoryId = categoryId,
                limitPaise = limitPaise,
                periodStart = if (thisCycleOnly) periodStart else null,
            )
    }
}
