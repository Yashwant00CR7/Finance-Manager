package com.yk.finance.domain

import com.yk.finance.data.Txn
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParsedSms
import java.time.Instant

/**
 * What the classifier is allowed to look at, as a bag of strings.
 *
 * Two halves, and the second is the reason this exists at all. Text features come
 * from the payee and carry most of the signal - but Union sends no payee ("Union
 * names the channel, never a payee", BankRules.kt), which is roughly a quarter of
 * spending and precisely the rows that fall through to Uncategorised today. Those
 * rows still have an amount, a time, an account and a channel, and 120 rupees at
 * half one on a Tuesday is not nothing.
 *
 * Every feature is prefixed with its kind, so "w:CAFE" and a category named CAFE can
 * never collide in the vocabulary.
 */
object Features {

    private val SEPARATORS = Regex("""[^A-Z0-9]+""")

    fun of(
        payee: String?,
        amountPaise: Long,
        occurredAt: Long,
        accountId: Long,
        channel: Channel,
        direction: Direction,
        timeWasInferred: Boolean = false,
    ): List<String> {
        val out = ArrayList<String>(32)

        Categorizer.payeeKey(payee)?.let { key ->
            // Whole words carry the brand: SWIGGY, JUICE, PHARMACY.
            key.split(SEPARATORS).forEach { word ->
                if (word.length >= 2) out.add("w:$word")
            }
            // Trigrams carry the rest. UPI payees are mostly unpronounceable strings
            // ("paytmqr 1a2b3cd") where the only stable thing is a shared fragment,
            // and a fragment is what survives ICICI's 15-character truncation.
            for (i in 0..key.length - 3) out.add("t:${key.substring(i, i + 3)}")
        }

        out.add("amt:${amountBucket(amountPaise)}")
        out.add("acct:$accountId")
        out.add("ch:${channel.name}")
        out.add("dir:${direction.name}")

        val at = Instant.ofEpochMilli(occurredAt).atZone(IST).toLocalDateTime()
        out.add("dow:${at.dayOfWeek.name}")
        out.add("dom:${dayOfMonthBucket(at.dayOfMonth)}")
        // Only when the clock is real. An imported row with no time was placed at
        // midday by the importer, and feeding that in would teach the model that
        // everything imported happened at lunch.
        if (!timeWasInferred) out.add("hr:${hourBucket(at.hour)}")

        return out
    }

    fun of(txn: Txn): List<String> = of(
        payee = txn.payee,
        amountPaise = txn.amountPaise,
        occurredAt = txn.occurredAt,
        accountId = txn.accountId,
        channel = txn.channel,
        direction = txn.direction,
        timeWasInferred = txn.timeWasInferred,
    )

    fun of(sms: ParsedSms, accountId: Long): List<String> = of(
        payee = sms.payee,
        amountPaise = sms.amountPaise,
        occurredAt = sms.occurredAt,
        accountId = accountId,
        channel = sms.channel,
        direction = sms.direction,
    )

    /**
     * Amounts as bands, not numbers.
     *
     * Roughly logarithmic, because the interesting distinction is between a 40 rupee
     * chai and a 400 rupee dinner, not between 400 and 420. Bands are in rupees so
     * they read the same way they are spoken about.
     */
    fun amountBucket(amountPaise: Long): String {
        val rupees = amountPaise / 100
        return when {
            rupees < 50 -> "<50"
            rupees < 100 -> "50-99"
            rupees < 200 -> "100-199"
            rupees < 500 -> "200-499"
            rupees < 1_000 -> "500-999"
            rupees < 2_000 -> "1k-2k"
            rupees < 5_000 -> "2k-5k"
            rupees < 10_000 -> "5k-10k"
            else -> "10k+"
        }
    }

    /** Meal-shaped rather than even, because that is the pattern worth detecting. */
    fun hourBucket(hour: Int): String = when (hour) {
        in 0..5 -> "NIGHT"
        in 6..10 -> "MORNING"
        in 11..15 -> "MIDDAY"
        in 16..20 -> "EVENING"
        else -> "LATE"
    }

    /** Rent, bills and salary cluster at the edges of a month; lunch does not. */
    fun dayOfMonthBucket(day: Int): String = when {
        day <= 5 -> "START"
        day >= 26 -> "END"
        else -> "MID"
    }
}
