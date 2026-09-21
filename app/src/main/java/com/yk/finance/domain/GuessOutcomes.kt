package com.yk.finance.domain

/**
 * Where the record of "was the guess right" goes.
 *
 * An interface because the counters live in SharedPreferences and this package has no
 * Android in it - the whole domain layer is testable on the JVM and worth keeping that
 * way for one boolean and two integers.
 *
 * The signal is free and exact. Every guess is marked, so the moment a person touches
 * one the app learns whether it had been right, without asking them anything. That is
 * the only honest basis for moving CategoryModel's thresholds later.
 */
interface GuessOutcomes {

    /** A person looked at a guess and let it stand. */
    fun confirmed()

    /** A person looked at a guess and changed it. */
    fun corrected()

    /** For tests and for anything constructed without a place to put the numbers. */
    object None : GuessOutcomes {
        override fun confirmed() = Unit
        override fun corrected() = Unit
    }
}
