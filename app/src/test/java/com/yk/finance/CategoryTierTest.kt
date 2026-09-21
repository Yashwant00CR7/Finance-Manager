package com.yk.finance

import com.yk.finance.domain.Categorizer
import com.yk.finance.domain.Prediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier ordering, which is the entire safety argument for putting a model anywhere
 * near a ledger.
 *
 * The claim being tested is narrow and absolute: the model answers only where the app
 * would otherwise have given up. If that holds, then turning the feature on cannot
 * change any category the app gets right today - so the worst case is that spending
 * which used to sit in Uncategorised now sits in the wrong category, visibly marked,
 * one tap from being fixed.
 */
class CategoryTierTest {

    private val food = 1L
    private val transport = 2L
    private val shopping = 3L

    private fun sure(categoryId: Long) = Prediction(categoryId, margin = 9.0, confident = true)
    private fun unsure(categoryId: Long) = Prediction(categoryId, margin = 0.2, confident = false)

    @Test
    fun `a learned rule beats everything`() {
        val choice = Categorizer.decide(
            learnedRuleCategoryId = food,
            seedCategoryId = transport,
            prediction = sure(shopping),
        )
        assertEquals(food, choice!!.categoryId)
        assertFalse("your own decision is never a guess", choice.inferred)
    }

    @Test
    fun `a seed keyword beats the model`() {
        val choice = Categorizer.decide(
            learnedRuleCategoryId = null,
            seedCategoryId = transport,
            prediction = sure(shopping),
        )
        assertEquals(transport, choice!!.categoryId)
        assertFalse("a keyword match is deterministic, not a guess", choice.inferred)
    }

    @Test
    fun `the model answers only when nothing else did`() {
        val choice = Categorizer.decide(null, null, sure(shopping))
        assertEquals(shopping, choice!!.categoryId)
        assertTrue("and is always marked", choice.inferred)
    }

    @Test
    fun `an unsure model is not an answer`() {
        assertNull(Categorizer.decide(null, null, unsure(shopping)))
    }

    @Test
    fun `no model at all behaves exactly as the app did before`() {
        assertEquals(food, Categorizer.decide(food, null, null)!!.categoryId)
        assertEquals(transport, Categorizer.decide(null, transport, null)!!.categoryId)
        assertNull(Categorizer.decide(null, null, null))
    }

    @Test
    fun `nothing the app files today can be filed differently`() {
        // Exhaustive over the two deterministic tiers crossed with every model state.
        // Whenever either tier has an answer, the result must be that answer, unmarked,
        // regardless of what the model thinks.
        val models = listOf(null, sure(shopping), unsure(shopping))
        listOf(food, null).forEach { rule ->
            listOf(transport, null).forEach { seed ->
                if (rule == null && seed == null) return@forEach
                models.forEach { prediction ->
                    val choice = Categorizer.decide(rule, seed, prediction)
                    assertEquals(
                        "rule=$rule seed=$seed model=$prediction",
                        rule ?: seed,
                        choice!!.categoryId,
                    )
                    assertFalse("rule=$rule seed=$seed", choice.inferred)
                }
            }
        }
    }
}
