package com.yk.finance.domain

import kotlin.math.ln

/** One category's score for a set of features, and how much the model has seen of it. */
data class Scored(
    val categoryId: Long,
    /** Unnormalised log posterior. Comparable between categories, meaningless alone. */
    val logProbability: Double,
    /** Training examples behind this category. Part of the auto-file gate. */
    val examples: Int,
)

/**
 * Bernoulli Naive Bayes over a set of string features.
 *
 * Chosen for one property above all: it learns from a single example. Incrementing a
 * handful of counters is the whole of training, which is what lets one correction
 * change the next guess - the same premise the learned-rule table is built on.
 *
 * Presence, not counts, and that distinction is load-bearing here. The multinomial
 * form divides by how many features a category's examples happened to carry, so a
 * category whose payees have short names gets a smaller denominator and a higher score
 * for every feature - including on rows with no payee at all. That would decide Union
 * rows on the length of other people's shop names, which is precisely the case this
 * model exists to serve. Counting each feature once per example makes the denominator
 * the example count, and the bias has nowhere to enter.
 *
 * Every feature is just a string, so text and context live in one model and a missing
 * payee costs nothing structural: features that are absent are simply not there to
 * vote. See [Features].
 *
 * Nothing here is persisted. The model is rebuilt from the ledger at launch, so a
 * mistake in the incremental path can only ever be wrong until the next start.
 */
class NaiveBayes {

    /** categoryId -> feature -> how many of that category's examples contained it. */
    private val perCategory = HashMap<Long, HashMap<String, Int>>()

    /** categoryId -> examples learned, which is the prior, the evidence count and the
     * denominator all at once. */
    private val examples = HashMap<Long, Int>()

    /**
     * feature -> times seen anywhere. Exists so the vocabulary can shrink again:
     * Laplace smoothing divides by it, so an [unlearn] that left dead features behind
     * would not be the inverse of [learn].
     */
    private val vocabulary = HashMap<String, Int>()

    var totalExamples: Int = 0
        private set

    val vocabularySize: Int get() = vocabulary.size

    fun examplesFor(categoryId: Long): Int = examples[categoryId] ?: 0

    fun learn(categoryId: Long, features: List<String>) {
        if (features.isEmpty()) return
        val bucket = perCategory.getOrPut(categoryId) { HashMap() }
        // Distinct: a trigram appearing twice in one payee is still one example
        // containing it. Counting it twice is the length bias coming back in.
        features.distinct().forEach { feature ->
            bucket[feature] = (bucket[feature] ?: 0) + 1
            vocabulary[feature] = (vocabulary[feature] ?: 0) + 1
        }
        examples[categoryId] = (examples[categoryId] ?: 0) + 1
        totalExamples++
    }

    /**
     * The exact inverse of [learn] for a set of features that was actually learned:
     * counts that reach zero are removed rather than left at zero, so the vocabulary
     * and the smoothing denominator return to what they were.
     *
     * Unlearning something never learned clamps at zero rather than going negative.
     * That is a guard, not a contract - it keeps a stray call from corrupting the
     * model, and the rebuild at next launch is what actually repairs it.
     */
    fun unlearn(categoryId: Long, features: List<String>) {
        if (features.isEmpty()) return
        val bucket = perCategory[categoryId] ?: return
        features.distinct().forEach { feature ->
            val seenHere = (bucket[feature] ?: 0) - 1
            if (seenHere > 0) bucket[feature] = seenHere else bucket.remove(feature)
            val seen = (vocabulary[feature] ?: 0) - 1
            if (seen > 0) vocabulary[feature] = seen else vocabulary.remove(feature)
        }
        val left = (examples[categoryId] ?: 0) - 1
        if (left > 0) {
            examples[categoryId] = left
        } else {
            examples.remove(categoryId)
            perCategory.remove(categoryId)
        }
        totalExamples = (totalExamples - 1).coerceAtLeast(0)
    }

    fun clear() {
        perCategory.clear()
        examples.clear()
        vocabulary.clear()
        totalExamples = 0
    }

    /**
     * Every known category, best first.
     *
     * Features the model has never seen are dropped rather than smoothed. An unknown
     * feature carries no information about which category is more likely, and letting
     * it through would let the length of a payee string shift the ranking on its own.
     *
     * With no recognised features at all this degrades to the prior - the ranking
     * becomes "which category do you use most", which is a reasonable thing to show
     * and a poor thing to act on. [CategoryModel] is where that distinction is drawn.
     */
    fun score(features: List<String>): List<Scored> {
        if (totalExamples == 0 || vocabulary.isEmpty()) return emptyList()
        val known = features.distinct().filter { vocabulary.containsKey(it) }

        return examples.keys.map { categoryId ->
            val bucket = perCategory[categoryId].orEmpty()
            val count = examples.getValue(categoryId)
            // Laplace over the two outcomes this feature has: present or absent.
            // The denominator is the example count, identical in kind for every
            // category, which is what keeps payee length out of the answer.
            val denominator = (count + 2).toDouble()
            var logProbability = ln(count.toDouble() / totalExamples)
            known.forEach { feature ->
                logProbability += ln(((bucket[feature] ?: 0) + 1).toDouble() / denominator)
            }
            Scored(categoryId, logProbability, count)
        }.sortedByDescending { it.logProbability }
    }
}
