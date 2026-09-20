package com.yk.finance.ui

import com.yk.finance.data.Category
import com.yk.finance.data.QuickAddSuggestion
import com.yk.finance.domain.formatRupees

/**
 * A chip says both what and how much.
 *
 * The amount is on the chip because that is what makes it a one-tap entry rather than a
 * shortcut to a form: you are agreeing to a specific 68.25, not opening "Canteen".
 */
fun chipLabel(suggestion: QuickAddSuggestion): String =
    "${suggestion.label}  ${formatRupees(suggestion.amountPaise)}"

/**
 * Expense categories, most-used first.
 *
 * The picker is forty rows long and the same four categories account for most entries,
 * so ordering by what you actually spend on beats alphabetical - but alphabetical is
 * the tie-break, because an arbitrary order in a list you scan every day is its own
 * small tax.
 *
 * Only rows that count as spending are ranked. A 3,000 transfer between your own
 * accounts must not promote whatever category it happens to carry.
 */
fun rankedCategories(state: UiState): List<Category> {
    val uses = state.transactions
        .filter { it.countsAsSpending }
        .mapNotNull { it.categoryId }
        .groupingBy { it }
        .eachCount()

    return state.expenseCategories.sortedWith(
        compareByDescending<Category> { uses[it.id] ?: 0 }.thenBy { it.name.lowercase() },
    )
}
