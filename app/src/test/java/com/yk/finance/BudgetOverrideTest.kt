package com.yk.finance

import com.yk.finance.data.Budget
import com.yk.finance.domain.BudgetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standing limits versus one-off overrides.
 *
 * The distinction is invisible on screen except for one line of text, so it has to be
 * exactly right underneath: a limit raised for one month that silently becomes the new
 * normal is a budget that stops meaning anything.
 */
class BudgetOverrideTest {

    private val september = 1_759_000_000_000L
    private val october = 1_761_600_000_000L

    private val food = 1L
    private val bills = 2L

    @Test
    fun `a pre-v3 budget with no period applies to every cycle`() {
        val budgets = listOf(Budget(id = 1, categoryId = food, limitPaise = 450_000, periodStart = null))

        listOf(september, october).forEach { cycle ->
            val effective = BudgetResolver.effective(budgets, cycle)
            assertEquals(1, effective.size)
            assertEquals(450_000L, effective.single().budget.limitPaise)
            assertFalse(effective.single().isOverride)
        }
    }

    @Test
    fun `an override wins for its own cycle only`() {
        val budgets = listOf(
            Budget(id = 1, categoryId = food, limitPaise = 450_000, periodStart = null),
            Budget(id = 2, categoryId = food, limitPaise = 500_000, periodStart = september),
        )

        val inSeptember = BudgetResolver.effective(budgets, september).single()
        assertEquals(500_000L, inSeptember.budget.limitPaise)
        assertTrue(inSeptember.isOverride)

        val inOctober = BudgetResolver.effective(budgets, october).single()
        assertEquals(450_000L, inOctober.budget.limitPaise)
        assertFalse(inOctober.isOverride)
    }

    @Test
    fun `overriding one category leaves the others on their standing limits`() {
        val budgets = listOf(
            Budget(id = 1, categoryId = food, limitPaise = 450_000, periodStart = null),
            Budget(id = 2, categoryId = bills, limitPaise = 850_000, periodStart = null),
            Budget(id = 3, categoryId = food, limitPaise = 500_000, periodStart = september),
        )

        val effective = BudgetResolver.effective(budgets, september).associateBy { it.budget.categoryId }
        assertEquals(500_000L, effective[food]!!.budget.limitPaise)
        assertEquals(850_000L, effective[bills]!!.budget.limitPaise)
        assertFalse(effective[bills]!!.isOverride)
    }

    @Test
    fun `an override with no standing limit applies to its cycle and no other`() {
        val budgets = listOf(Budget(id = 1, categoryId = food, limitPaise = 500_000, periodStart = september))

        assertEquals(1, BudgetResolver.effective(budgets, september).size)
        assertTrue(BudgetResolver.effective(budgets, october).isEmpty())
    }

    @Test
    fun `another cycle's override never leaks into this one`() {
        val budgets = listOf(Budget(id = 1, categoryId = food, limitPaise = 500_000, periodStart = october))
        assertTrue(BudgetResolver.effective(budgets, september).isEmpty())
    }

    @Test
    fun `the overall cap resolves alongside category limits`() {
        val budgets = listOf(
            Budget(id = 1, categoryId = null, limitPaise = 1_950_000, periodStart = null),
            Budget(id = 2, categoryId = food, limitPaise = 450_000, periodStart = null),
        )
        val effective = BudgetResolver.effective(budgets, september)
        assertEquals(2, effective.size)
        assertEquals(1_950_000L, effective.first { it.budget.categoryId == null }.budget.limitPaise)
    }

    // ----- what an edit writes -----

    @Test
    fun `editing without this-cycle-only rewrites the standing limit in place`() {
        val budgets = listOf(Budget(id = 7, categoryId = food, limitPaise = 450_000, periodStart = null))
        val row = BudgetResolver.rowToWrite(budgets, food, 470_000, september, thisCycleOnly = false)

        assertEquals("the existing standing row must be updated, not duplicated", 7L, row.id)
        assertNull(row.periodStart)
        assertEquals(470_000L, row.limitPaise)
    }

    @Test
    fun `a this-cycle-only edit writes a new pinned row and leaves the standing one alone`() {
        val budgets = listOf(Budget(id = 7, categoryId = food, limitPaise = 450_000, periodStart = null))
        val row = BudgetResolver.rowToWrite(budgets, food, 500_000, september, thisCycleOnly = true)

        assertEquals(0L, row.id)
        assertEquals(september, row.periodStart)

        // And the standing limit is untouched, so next cycle reverts.
        val next = BudgetResolver.effective(budgets + row.copy(id = 8), october).single()
        assertEquals(450_000L, next.budget.limitPaise)
    }

    @Test
    fun `editing an existing override updates it rather than stacking another`() {
        val budgets = listOf(
            Budget(id = 7, categoryId = food, limitPaise = 450_000, periodStart = null),
            Budget(id = 8, categoryId = food, limitPaise = 500_000, periodStart = september),
        )
        val row = BudgetResolver.rowToWrite(budgets, food, 520_000, september, thisCycleOnly = true)
        assertEquals(8L, row.id)
        assertEquals(520_000L, row.limitPaise)
    }

    @Test
    fun `a first budget for a category creates a standing row`() {
        val row = BudgetResolver.rowToWrite(emptyList(), food, 450_000, september, thisCycleOnly = false)
        assertEquals(0L, row.id)
        assertNull(row.periodStart)
        assertEquals(food, row.categoryId)
    }
}
