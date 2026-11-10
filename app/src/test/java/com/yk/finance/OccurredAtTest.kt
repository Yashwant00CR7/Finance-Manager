package com.yk.finance

import com.yk.finance.domain.IngestOutcome
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.parser.ParseResult
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.parser.parseIndianDate
import com.yk.finance.parser.resolveOccurredAt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What time a row carries when the bank gave a date and nothing more.
 *
 * Midnight was never the answer: the bank never said it, it sorted the payment before
 * everything else that day, and it taught the model that one bank's spending happens at
 * NIGHT. The receipt time stands in when the date is today's; otherwise midday, flagged.
 */
class OccurredAtTest {

    private val received = parseIndianDate("25-10-2026", "09:00:00")!!

    @Test
    fun `a stated date and time stand as the bank gave them`() {
        val at = resolveOccurredAt("22-10-2026", "10:40:05", received)
        assertEquals(parseIndianDate("22-10-2026", "10:40:05"), at.millis)
        assertFalse(at.timeWasInferred)
    }

    @Test
    fun `no date means the receipt time, which is a real clock`() {
        val at = resolveOccurredAt(null, null, received)
        assertEquals(received, at.millis)
        assertFalse(at.timeWasInferred)
    }

    @Test
    fun `today's date with no time is the receipt time`() {
        val at = resolveOccurredAt("25-Oct-26", null, received)
        assertEquals(received, at.millis)
        assertFalse(at.timeWasInferred)
    }

    @Test
    fun `another day's date with no time is placed at midday and flagged`() {
        val at = resolveOccurredAt("20-Oct-26", null, received)
        assertEquals(parseIndianDate("20-10-2026", "12:00:00"), at.millis)
        assertTrue(at.timeWasInferred)
    }

    @Test
    fun `a date the sanity check refuses falls to the receipt time unflagged`() {
        val future = resolveOccurredAt("30-Oct-26", null, received)
        assertEquals(received, future.millis)
        assertFalse(future.timeWasInferred)
        val ancient = resolveOccurredAt("30-Oct-19", "10:00:00", received)
        assertEquals(received, ancient.millis)
        assertFalse(ancient.timeWasInferred)
    }

    @Test
    fun `an unparseable time does not demote the date to midnight`() {
        val at = resolveOccurredAt("20-Oct-26", "noon", received)
        assertEquals(parseIndianDate("20-10-2026", "12:00:00"), at.millis)
        assertTrue(at.timeWasInferred)
    }

    @Test
    fun `an ICICI alert for an earlier day lands in the ledger flagged, one for today does not`() = runBlocking {
        val dao = InMemoryDao()
        val ingestor = SmsIngestor(dao, RuleBasedParser())
        val earlier = "ICICI Bank Acct XX742 debited for Rs 169.00 on 20-Oct-26; SRI LAKSHMI TRA credited. UPI:412233445566."
        val today = "ICICI Bank Acct XX742 debited for Rs 99.00 on 25-Oct-26; KA 05 JUICE BAR credited. UPI:412233445577."

        val first = ingestor.ingest("VM-ICICIB", earlier, received) as IngestOutcome.Recorded
        val second = ingestor.ingest("VM-ICICIB", today, received) as IngestOutcome.Recorded

        val flagged = dao.txnById(first.txnId)!!
        assertEquals(parseIndianDate("20-10-2026", "12:00:00"), flagged.occurredAt)
        assertTrue(flagged.timeWasInferred)

        val live = dao.txnById(second.txnId)!!
        assertEquals(received, live.occurredAt)
        assertFalse(live.timeWasInferred)
    }

    @Test
    fun `a Union alert with its own timestamp is never flagged`() {
        val body = "A/c *8317 Credited for Rs:3000.00 on 16-09-2026 11:23:34 by Mob Bk ref no 634455667788 Avl Bal Rs:2841.37"
        val sms = (RuleBasedParser().parse("AD-UNIONB", body, received) as ParseResult.Parsed).sms
        assertEquals(parseIndianDate("16-09-2026", "11:23:34"), sms.occurredAt)
        assertFalse(sms.timeWasInferred)
    }
}
