package com.yk.finance.data

import android.content.Context

/**
 * The handful of settings that are not ledger state.
 *
 * Deliberately not a Room table. These describe how this install behaves, not what
 * happened to your money, so they have no business travelling inside a backup and
 * being restored onto a different phone - and the accuracy counters in particular are
 * a statement about one model on one device.
 */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences("finance.prefs", Context.MODE_PRIVATE)

    /**
     * Whether the model may file a category on its own.
     *
     * On by default. The gate is conservative, every guess is marked as one and every
     * guess is reversible - and a feature that defaults to off collects no evidence
     * about whether it deserved to be on.
     */
    var autoFileCategories: Boolean
        get() = store.getBoolean(KEY_AUTO_FILE, true)
        set(value) = store.edit().putBoolean(KEY_AUTO_FILE, value).apply()

    /** Guesses a person looked at and accepted. */
    val guessesConfirmed: Int get() = store.getInt(KEY_CONFIRMED, 0)

    /** Guesses a person looked at and changed. */
    val guessesCorrected: Int get() = store.getInt(KEY_CORRECTED, 0)

    fun recordGuessConfirmed() = bump(KEY_CONFIRMED)

    fun recordGuessCorrected() = bump(KEY_CORRECTED)

    private fun bump(key: String) {
        store.edit().putInt(key, store.getInt(key, 0) + 1).apply()
    }

    private companion object {
        const val KEY_AUTO_FILE = "auto_file_categories"
        const val KEY_CONFIRMED = "guesses_confirmed"
        const val KEY_CORRECTED = "guesses_corrected"
    }
}
