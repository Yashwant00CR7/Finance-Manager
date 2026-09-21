package com.yk.finance

import com.yk.finance.domain.NaiveBayes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier itself, with no transactions anywhere near it.
 *
 * The two properties worth protecting are both here. It must learn from one example,
 * because that is the whole reason this family was chosen over anything trained in
 * batches. And [NaiveBayes.unlearn] must be the exact inverse of [NaiveBayes.learn],
 * because undo goes through it - a decrement that leaves debris behind would make the
 * model quietly disagree with the ledger it was built from.
 */
class NaiveBayesTest {

    private val food = 1L
    private val transport = 2L

    @Test
    fun `one example is enough to prefer a category`() {
        val model = NaiveBayes()
        model.learn(food, listOf("w:SWIGGY", "amt:200-499"))

        val top = model.score(listOf("w:SWIGGY")).first()
        assertEquals(food, top.categoryId)
    }

    @Test
    fun `it prefers the category the evidence actually points at`() {
        val model = NaiveBayes()
        repeat(5) { model.learn(food, listOf("w:SWIGGY", "hr:EVENING")) }
        repeat(5) { model.learn(transport, listOf("w:UBER", "hr:MORNING")) }

        assertEquals(food, model.score(listOf("w:SWIGGY")).first().categoryId)
        assertEquals(transport, model.score(listOf("w:UBER")).first().categoryId)
    }

    @Test
    fun `unlearn is the exact inverse of learn`() {
        val model = NaiveBayes()
        val features = listOf("w:SWIGGY", "amt:200-499", "hr:EVENING")

        model.learn(food, features)
        model.unlearn(food, features)

        assertEquals(0, model.totalExamples)
        assertEquals(0, model.vocabularySize)
        assertEquals(0, model.examplesFor(food))
        assertEquals(emptyList<Any>(), model.score(features))
    }

    @Test
    fun `unlearn leaves the rest of the model untouched`() {
        val model = NaiveBayes()
        repeat(4) { model.learn(food, listOf("w:SWIGGY")) }
        val mistake = listOf("w:UBER")
        model.learn(food, mistake)
        model.unlearn(food, mistake)

        assertEquals(4, model.examplesFor(food))
        // The mistaken feature is gone from the vocabulary entirely, not left at zero,
        // because the vocabulary size is the smoothing denominator.
        assertEquals(1, model.vocabularySize)
    }

    @Test
    fun `unlearning something never learned does not corrupt the counts`() {
        val model = NaiveBayes()
        model.learn(food, listOf("w:SWIGGY"))
        model.unlearn(transport, listOf("w:UBER"))

        assertEquals(1, model.totalExamples)
        assertEquals(1, model.examplesFor(food))
    }

    @Test
    fun `a missing feature costs nothing structural`() {
        // This is the Union case: no payee, so no text features at all. The remaining
        // features still have to produce a usable ranking.
        val model = NaiveBayes()
        repeat(6) { model.learn(food, listOf("w:JUICE", "amt:100-199", "hr:MIDDAY")) }
        repeat(6) { model.learn(transport, listOf("w:UBER", "amt:200-499", "hr:MORNING")) }

        val payeeless = model.score(listOf("amt:100-199", "hr:MIDDAY"))
        assertEquals(food, payeeless.first().categoryId)
    }

    @Test
    fun `unknown features are ignored rather than smoothed`() {
        // Otherwise the length of a payee string would move the ranking on its own,
        // which is a property of the text and not of what was bought.
        val model = NaiveBayes()
        repeat(3) { model.learn(food, listOf("w:JUICE")) }
        repeat(3) { model.learn(transport, listOf("w:UBER")) }

        val clean = model.score(listOf("w:JUICE"))
        val noisy = model.score(listOf("w:JUICE", "t:ZZZ", "t:QQQ", "w:NEVERSEEN"))

        assertEquals(clean.map { it.categoryId }, noisy.map { it.categoryId })
        assertEquals(clean.first().logProbability, noisy.first().logProbability, 1e-9)
    }

    @Test
    fun `with no evidence at all it falls back to the prior`() {
        val model = NaiveBayes()
        repeat(9) { model.learn(food, listOf("w:JUICE")) }
        repeat(1) { model.learn(transport, listOf("w:UBER")) }

        val blind = model.score(listOf("t:NOTHINGKNOWN"))
        assertEquals(food, blind.first().categoryId)
        // The gap is the prior's gap and nothing more, which is what stops a blind
        // guess clearing the auto-file margin.
        assertTrue(blind[0].logProbability - blind[1].logProbability < 2.5)
    }

    @Test
    fun `an empty model scores nothing`() {
        assertEquals(emptyList<Any>(), NaiveBayes().score(listOf("w:SWIGGY")))
    }

    @Test
    fun `clear empties everything`() {
        val model = NaiveBayes()
        model.learn(food, listOf("w:SWIGGY"))
        model.clear()

        assertEquals(0, model.totalExamples)
        assertEquals(0, model.vocabularySize)
        assertEquals(emptyList<Any>(), model.score(listOf("w:SWIGGY")))
    }

    @Test
    fun `results come back best first`() {
        val model = NaiveBayes()
        repeat(8) { model.learn(food, listOf("w:JUICE")) }
        repeat(2) { model.learn(transport, listOf("w:UBER")) }

        val scored = model.score(listOf("w:JUICE"))
        assertEquals(2, scored.size)
        assertTrue(scored[0].logProbability >= scored[1].logProbability)
        assertNotEquals(scored[0].categoryId, scored[1].categoryId)
    }

    @Test
    fun `examples are reported per category, because the gate reads them`() {
        val model = NaiveBayes()
        repeat(7) { model.learn(food, listOf("w:JUICE")) }
        repeat(2) { model.learn(transport, listOf("w:UBER")) }

        assertEquals(7, model.examplesFor(food))
        assertEquals(2, model.examplesFor(transport))
        assertEquals(9, model.totalExamples)
        assertEquals(7, model.score(listOf("w:JUICE")).first { it.categoryId == food }.examples)
    }
}
