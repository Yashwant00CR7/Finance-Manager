package com.yk.finance

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind
import com.yk.finance.data.Category
import com.yk.finance.data.QuickAddSuggestion
import com.yk.finance.data.Txn
import com.yk.finance.domain.CATEGORY_RENAMES
import com.yk.finance.domain.SEED_CATEGORIES
import com.yk.finance.domain.SEED_KEYWORD_RULES
import com.yk.finance.domain.UNCATEGORISED
import com.yk.finance.parser.Direction
import com.yk.finance.ui.UiState
import com.yk.finance.ui.chipLabel
import com.yk.finance.ui.rankedCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category vocabulary is data, not code, so these are the only thing standing
 * between a typo and a rename that silently creates a second category.
 */
class CategoryVocabularyTest {

    private val names = SEED_CATEGORIES.map { it.name }

    @Test
    fun `no category is seeded twice`() {
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every rename points at a category that exists`() {
        CATEGORY_RENAMES.forEach { (old, new) ->
            assertTrue("rename target missing: $old -> $new", new in names)
        }
    }

    @Test
    fun `no category is renamed away and seeded back`() {
        // Would produce an endless rename-then-recreate loop on every launch.
        CATEGORY_RENAMES.keys.forEach { old ->
            assertFalse("$old is both renamed and seeded", old in names)
        }
    }

    @Test
    fun `every keyword rule names a seeded expense category`() {
        val expense = SEED_CATEGORIES.filter { !it.isIncome }.map { it.name }.toSet()
        SEED_KEYWORD_RULES.forEach { (keyword, category) ->
            assertTrue("$keyword -> $category is not an expense category", category in expense)
        }
    }

    @Test
    fun `the fallback category exists and is not income`() {
        val fallback = SEED_CATEGORIES.firstOrNull { it.name == UNCATEGORISED }
        assertTrue("Uncategorised must be seeded", fallback != null)
        assertFalse(fallback!!.isIncome)
    }

    @Test
    fun `salary is income, food is not`() {
        assertTrue(SEED_CATEGORIES.first { it.name == "Salary" }.isIncome)
        assertFalse(SEED_CATEGORIES.first { it.name == "Food" }.isIncome)
    }

    @Test
    fun `groceries is gone, folded into food`() {
        assertFalse("Groceries" in names)
        assertEquals("Food", CATEGORY_RENAMES["Groceries"])
    }
}

class QuickAddTest {

    private val food = Category(id = 1, name = "Food")
    private val bills = Category(id = 2, name = "Bills")
    private val salary = Category(id = 3, name = "Salary", isIncome = true)

    private fun txn(categoryId: Long?, countsAsSpending: Boolean = true) = Txn(
        id = 0, accountId = 1, direction = Direction.DEBIT, amountPaise = 5000,
        occurredAt = 1_758_000_000_000, payee = null, reference = null,
        categoryId = categoryId, countsAsSpending = countsAsSpending,
    )

    private fun state(transactions: List<Txn>) = UiState(
        accounts = listOf(
            Account(id = 1, displayName = "Cash", bank = "CASH", accountToken = "CASH", kind = AccountKind.CASH),
        ),
        transactions = transactions,
        categories = listOf(food, bills, salary),
    )

    @Test
    fun `a chip says both what and how much`() {
        val suggestion = QuickAddSuggestion(
            label = "Canteen", amountPaise = 6825, categoryId = 1,
            accountId = 1, uses = 25, lastUsedAt = 0,
        )
        assertEquals("Canteen  ₹68.25", chipLabel(suggestion))
    }

    @Test
    fun `income categories are never offered as somewhere money went`() {
        assertFalse(salary in rankedCategories(state(emptyList())))
        assertFalse(salary in state(emptyList()).expenseCategories)
    }

    @Test
    fun `the category you use most comes first`() {
        val ranked = rankedCategories(state(List(5) { txn(categoryId = bills.id) } + txn(food.id)))
        assertEquals(bills, ranked.first())
    }

    @Test
    fun `with no history the order is alphabetical rather than arbitrary`() {
        assertEquals(listOf(bills, food), rankedCategories(state(emptyList())))
    }

    @Test
    fun `transfers do not count toward category ranking`() {
        // A 3,000 move between your own accounts must not promote its category.
        val transfers = List(9) { txn(categoryId = bills.id, countsAsSpending = false) }
        val ranked = rankedCategories(state(transfers + txn(food.id)))
        assertEquals(food, ranked.first())
    }
}
