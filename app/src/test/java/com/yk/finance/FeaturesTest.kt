package com.yk.finance

import com.yk.finance.data.Txn
import com.yk.finance.domain.Features
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model is allowed to see.
 *
 * The case that matters is the payee-less one. Union sends no payee, so roughly a
 * quarter of spending arrives with nothing to read - and if these rows produced no
 * features, the whole feature would be improving the transactions that already worked.
 */
class FeaturesTest {

    private fun txn(
        payee: String?,
        rupees: Long = 150,
        at: Long = TUESDAY_LUNCH,
        channel: Channel = Channel.UPI,
        direction: Direction = Direction.DEBIT,
        timeWasInferred: Boolean = false,
    ) = Txn(
        accountId = 7,
        direction = direction,
        amountPaise = rupees * 100,
        occurredAt = at,
        payee = payee,
        reference = null,
        channel = channel,
        countsAsSpending = true,
        timeWasInferred = timeWasInferred,
    )

    @Test
    fun `a payee-less row still produces context features`() {
        val features = Features.of(txn(null))

        assertTrue(features.none { it.startsWith("w:") || it.startsWith("t:") })
        assertTrue(features.contains("amt:100-199"))
        assertTrue(features.contains("acct:7"))
        assertTrue(features.contains("ch:UPI"))
        assertTrue(features.contains("dow:TUESDAY"))
        assertTrue(features.contains("hr:MIDDAY"))
        assertTrue(features.contains("dir:DEBIT"))
    }

    @Test
    fun `a payee contributes both words and trigrams`() {
        val features = Features.of(txn("KA 05 JUICE BAR"))

        assertTrue(features.contains("w:KA"))
        assertTrue(features.contains("w:JUICE"))
        assertTrue(features.contains("w:BAR"))
        assertTrue(features.contains("t:JUI"))
        // 05 is two characters; single characters are dropped as noise.
        assertFalse(features.contains("w:0"))
    }

    @Test
    fun `payee features survive the same normalisation rules use`() {
        // ICICI truncates at 15 characters but does it consistently, so the truncated
        // string is a stable key - the same reasoning CategoryRule is built on.
        assertEquals(
            Features.of(txn("KA 05 JUICE BAR")).toSet(),
            Features.of(txn("  ka 05   juice bar ")).toSet(),
        )
    }

    @Test
    fun `an inferred time is not offered as evidence`() {
        // The importer places a dateless row at midday. Feeding that in would teach the
        // model that everything imported happened at lunch.
        val features = Features.of(txn("KA 05 JUICE BAR", timeWasInferred = true))
        assertTrue(features.none { it.startsWith("hr:") })
        assertTrue("the rest of the date is still real", features.contains("dow:TUESDAY"))
    }

    @Test
    fun `amounts are banded, not counted`() {
        assertEquals("<50", Features.amountBucket(4_900))
        assertEquals("50-99", Features.amountBucket(5_000))
        assertEquals("100-199", Features.amountBucket(12_000))
        assertEquals("200-499", Features.amountBucket(34_000))
        assertEquals("500-999", Features.amountBucket(50_000))
        assertEquals("1k-2k", Features.amountBucket(100_000))
        assertEquals("2k-5k", Features.amountBucket(250_000))
        assertEquals("5k-10k", Features.amountBucket(700_000))
        assertEquals("10k+", Features.amountBucket(1_500_000))
    }

    @Test
    fun `hours are bucketed around meals`() {
        assertEquals("NIGHT", Features.hourBucket(3))
        assertEquals("MORNING", Features.hourBucket(9))
        assertEquals("MIDDAY", Features.hourBucket(13))
        assertEquals("EVENING", Features.hourBucket(20))
        assertEquals("LATE", Features.hourBucket(23))
    }

    @Test
    fun `the month has edges, because bills live there`() {
        assertEquals("START", Features.dayOfMonthBucket(1))
        assertEquals("START", Features.dayOfMonthBucket(5))
        assertEquals("MID", Features.dayOfMonthBucket(6))
        assertEquals("MID", Features.dayOfMonthBucket(25))
        assertEquals("END", Features.dayOfMonthBucket(26))
        assertEquals("END", Features.dayOfMonthBucket(31))
    }

    @Test
    fun `every feature is namespaced`() {
        // Otherwise a category named CAFE and the word CAFE would be the same token.
        Features.of(txn("KA 05 JUICE BAR")).forEach { feature ->
            assertTrue("un-namespaced feature: $feature", feature.contains(':'))
        }
    }

    @Test
    fun `direction is recorded, so credits cannot be confused with debits`() {
        assertTrue(Features.of(txn(null, direction = Direction.CREDIT)).contains("dir:CREDIT"))
        assertTrue(Features.of(txn(null, direction = Direction.DEBIT)).contains("dir:DEBIT"))
    }

    private companion object {
        /** 2026-09-15 13:20 IST, a Tuesday. */
        const val TUESDAY_LUNCH = 1_789_458_600_000L
    }
}
