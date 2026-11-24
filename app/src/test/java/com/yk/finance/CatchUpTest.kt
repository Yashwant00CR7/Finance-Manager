package com.yk.finance

import com.yk.finance.data.PendingReview
import com.yk.finance.domain.CatchUp
import com.yk.finance.domain.CatchUp.Companion.DAY
import com.yk.finance.domain.CatchUp.Companion.MINUTE
import com.yk.finance.domain.CatchUpAlert
import com.yk.finance.domain.CatchUpReport
import com.yk.finance.domain.CatchUpState
import com.yk.finance.domain.InboxSms
import com.yk.finance.domain.InboxSource
import com.yk.finance.domain.IngestOutcome
import com.yk.finance.domain.MessageGate
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.parser.RuleBasedParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch-up after power saving: the inbox scan that recovers messages whose SMS_RECEIVED
 * broadcast a power-saving mode swallowed, without ever filing one twice.
 */
class CatchUpTest {

    private val dao = InMemoryDao()
    private val ingestor = SmsIngestor(dao, RuleBasedParser())
    private val gate = MessageGate(dao, ingestor)
    private val state = object : CatchUpState { override var lastScanAt: Long? = null }
    private val catchUp = CatchUp(dao, gate, state)
    private val now = 1_792_000_000_000L

    private val unionDebit =
        "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

    private val unionDebit2 =
        "Union Bank of India A/c *2209 Debited Rs:40.00 on 23-10-2026 09:00:00 by Mob Bk ref no 629576250806, " +
            "Fvg: TEA STALL Avl Bal Rs:5288.67. Not you?Call 18002333"

    private fun sms(body: String, receivedAt: Long, sentAt: Long = receivedAt - 2_000, sender: String = "AD-UNIONB") =
        InboxSms(sender, body, sentAt, receivedAt)

    /** A fake inbox that answers the way the provider query does: strictly newer, oldest first. */
    private class Inbox(val messages: List<InboxSms>) : InboxSource {
        var askedFrom: Long? = null
        override suspend fun messagesSince(fromMillis: Long): List<InboxSms> {
            askedFrom = fromMillis
            return messages.filter { it.receivedAt > fromMillis }.sortedBy { it.receivedAt }
        }
    }

    @Test
    fun `a message missed live is recovered by the scan and filed once`() = runTest {
        val missed = sms(unionDebit, now - 2 * 60 * MINUTE)

        val first = catchUp.run(Inbox(listOf(missed)), now)
        assertEquals(1, first.recorded)
        assertEquals(1, dao.txns.size)

        state.lastScanAt = null // force the scan to reach back over the same message
        val second = catchUp.run(Inbox(listOf(missed)), now + MINUTE)
        assertEquals(0, second.recorded)
        assertEquals(1, dao.txns.size)
    }

    @Test
    fun `a message already handled live is not handed to the ingestor again`() = runTest {
        val msg = sms(unionDebit, now - 5 * MINUTE)
        assertNotNull(gate.ingestOnce(msg, checkLedger = false))

        val report = catchUp.run(Inbox(listOf(msg)), now)

        // Not even reaching the ledger's own duplicate check: the gate stopped it first.
        assertEquals(1, report.alreadySeen)
        assertEquals(0, report.duplicates)
        assertEquals(1, dao.txns.size)
    }

    @Test
    fun `an inbox row without a sent time still matches the live sighting`() = runTest {
        val live = sms(unionDebit, now - 5 * MINUTE)
        gate.ingestOnce(live, checkLedger = false)

        assertNull(gate.ingestOnce(live.copy(sentAt = 0), checkLedger = true))
    }

    @Test
    fun `an inbox copy whose sent time differs from the radio's is still recognised`() = runTest {
        val live = sms(unionDebit, now - 5 * MINUTE)
        gate.ingestOnce(live, checkLedger = false)

        // Same message, but this phone stored date_sent a few seconds off.
        val report = catchUp.run(Inbox(listOf(live.copy(sentAt = live.sentAt - 7_000))), now)

        assertEquals(1, report.alreadySeen)
        assertEquals(1, dao.txns.size)
    }

    @Test
    fun `the same text sent at two different times is two messages`() = runTest {
        val body = "Your OTP is never shared by the bank"
        assertNotNull(gate.ingestOnce(sms(body, now, sentAt = now - 60_000, sender = "AD-UNIONB"), checkLedger = false))
        assertNotNull(gate.ingestOnce(sms(body, now, sentAt = now - 30_000, sender = "AD-UNIONB"), checkLedger = false))
    }

    @Test
    fun `messages filed before the gate existed are recognised by their text`() = runTest {
        // Processed by an earlier version: on the ledger, but with no seen_messages row.
        val old = sms(unionDebit, now - 3 * 60 * MINUTE)
        val outcome = ingestor.ingest(old.sender, old.body, old.receivedAt)
        assertTrue(outcome is IngestOutcome.Recorded)
        dao.reviews[99] = PendingReview(id = 99, sender = "AD-UNIONB", rawMessage = "tray text", receivedAt = now - DAY, reason = "x")

        val report = catchUp.run(
            Inbox(listOf(old, sms("tray text", now - DAY + 1), sms(unionDebit2, now - MINUTE))),
            now,
        )

        assertEquals(2, report.alreadySeen)
        assertEquals(1, report.recorded)
        assertEquals(2, dao.txns.size)
    }

    @Test
    fun `the first scan starts a day before the newest processed message`() = runTest {
        ingestor.ingest("AD-UNIONB", unionDebit, now - 3 * DAY)
        val newest = dao.latestSmsTxnAt()!!
        assertEquals(maxOf(now - 30 * DAY, newest - DAY), catchUp.windowStartForTest(now))
    }

    @Test
    fun `the first scan never reaches back more than thirty days`() = runTest {
        assertEquals(now - 30 * DAY, catchUp.windowStartForTest(now))
    }

    @Test
    fun `later scans start just before the previous one`() = runTest {
        val inbox = Inbox(emptyList())
        catchUp.run(inbox, now)
        assertEquals(now, state.lastScanAt)

        catchUp.run(inbox, now + DAY)
        assertEquals(now - 10 * MINUTE, inbox.askedFrom)
    }

    @Test
    fun `a long power-saving gap is covered because the scan cannot run during it`() = runTest {
        catchUp.run(Inbox(emptyList()), now)
        // Saver mode for six hours: two alerts land in the inbox, no broadcast, no scan.
        val missed = listOf(sms(unionDebit, now + 2 * 60 * MINUTE), sms(unionDebit2, now + 4 * 60 * MINUTE))

        val report = catchUp.run(Inbox(missed), now + 6 * 60 * MINUTE)

        assertEquals(2, report.recorded)
        assertEquals(2, report.recovered)
    }

    @Test
    fun `the summary says how many came back and where they went`() {
        val said = CatchUpAlert.compose(CatchUpReport(recorded = 5, needReview = 2, needCategory = 1))!!
        assertEquals("Recovered 7 bank messages", said.title)
        assertTrue(said.body.contains("5 recorded"))
        assertTrue(said.body.contains("2 need review"))
        assertTrue(said.body.contains("1 need a category"))
    }

    @Test
    fun `a scan that recovers nothing stays silent`() {
        assertNull(CatchUpAlert.compose(CatchUpReport(scanned = 40, alreadySeen = 30, ignored = 10)))
    }
}
