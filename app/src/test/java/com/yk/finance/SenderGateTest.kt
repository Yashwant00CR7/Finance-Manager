package com.yk.finance

import com.yk.finance.data.GateMode
import com.yk.finance.data.SenderEntry
import com.yk.finance.data.SenderState
import com.yk.finance.domain.SenderGate
import com.yk.finance.domain.SenderGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist deciding. The ACTIVE cases matter most: that branch destroys messages
 * and there is no way to get one back, so every path into it is pinned here.
 */
class SenderGateTest {

    private val icici = "VM-ICICIB"
    private val realDebit =
        "ICICI Bank Acct XX742 debited for Rs 169.00 on 11-Sep-26; SRI LAKSHMI TRA credited. " +
            "UPI:412233445566."

    private fun entry(
        header: String,
        state: SenderState,
        bankKey: String? = null,
    ) = SenderEntry(
        header = header,
        state = state,
        bankKey = bankKey,
        firstSeenAt = 0,
        lastSeenAt = 0,
    )

    private fun decide(
        sender: String,
        body: String,
        row: SenderEntry?,
        mode: GateMode,
    ): Decision = SenderGate.decide(SenderGate.observe(sender, body), body, row, mode)

    // ---------- identity ----------

    @Test
    fun `an enrolled sender is parsed whatever the mode`() {
        val row = entry("ICICIB", SenderState.ENROLLED, "ICICI")
        listOf(GateMode.OBSERVE, GateMode.ACTIVE).forEach { mode ->
            val decision = decide(icici, realDebit, row, mode)
            assertTrue("$mode", decision is Decision.Parse)
            assertEquals("ICICI", (decision as Decision.Parse).identity.bankKey)
        }
    }

    @Test
    fun `enrolment follows the header, not the operator prefix`() {
        // The same entry must answer for every route the message can take, or a bank
        // goes quiet the day its SMS is handed to a different operator.
        val row = entry("ICICIB", SenderState.ENROLLED, "ICICI")
        listOf("VM-ICICIB", "AD-ICICIB", "JD-ICICIB", "ICICIB").forEach { sender ->
            assertTrue(sender, decide(sender, realDebit, row, GateMode.ACTIVE) is Decision.Parse)
        }
    }

    @Test
    fun `a body naming a bank does not make it that bank's message`() {
        // The hole this feature closes. The body says "ICICI Bank" in as many words;
        // the sender is somebody else entirely, so it is somebody else's message.
        val decision = decide("VM-SWIGGY", realDebit, entry("SWIGGY", SenderState.UNKNOWN), GateMode.ACTIVE)
        assertTrue(decision is Decision.Drop)
    }

    // ---------- ACTIVE: the hard gate ----------

    @Test
    fun `ACTIVE drops an unenrolled sender even when the message is plainly money`() {
        val decision = decide(icici, realDebit, entry("ICICIB", SenderState.UNKNOWN), GateMode.ACTIVE)
        assertTrue(decision is Decision.Drop)
    }

    @Test
    fun `ACTIVE drops a sender it has never seen at all`() {
        assertTrue(decide("VM-HDFCBK", realDebit, null, GateMode.ACTIVE) is Decision.Drop)
    }

    @Test
    fun `ACTIVE never sends anything unenrolled to the tray`() {
        // The retention promise: once the gate is on, no body from a sender you did not
        // opt into is written anywhere. A Review decision here would break that.
        listOf(null, entry("X", SenderState.UNKNOWN), entry("X", SenderState.DISMISSED)).forEach { row ->
            assertFalse("$row", decide("VM-HDFCBK", realDebit, row, GateMode.ACTIVE) is Decision.Review)
        }
    }

    // ---------- OBSERVE: nothing is lost ----------

    @Test
    fun `OBSERVE sends an unenrolled money-shaped message to the tray`() {
        // What makes shipping a two-header seed safe: if those are not this phone's
        // headers, the real ones still surface instead of disappearing.
        val decision = decide("VM-ICICIT", realDebit, entry("ICICIT", SenderState.UNKNOWN), GateMode.OBSERVE)
        assertTrue(decision is Decision.Review)
    }

    @Test
    fun `OBSERVE still drops what is not about money`() {
        val decision = decide("VM-SWIGGY", "Your order is on the way!", entry("SWIGGY", SenderState.UNKNOWN), GateMode.OBSERVE)
        assertTrue(decision is Decision.Drop)
    }

    @Test
    fun `OBSERVE does not queue offers and mandates`() {
        // Without this the tray fills with promotional noise on day one and stops
        // being worth opening, which is the same as not having it.
        val offer = "Spend Rs 500 on your card & get Rs 100 cashback!"
        assertTrue(decide("VM-PROMOS", offer, null, GateMode.OBSERVE) is Decision.Drop)
        val mandate = "Your Acct XX742 will be debited with Rs 2500.00 on 25-Sep-26 for SIP."
        assertTrue(decide("VM-ICICIT", mandate, null, GateMode.OBSERVE) is Decision.Drop)
    }

    @Test
    fun `a dismissed sender is treated exactly like an unknown one`() {
        // Dismiss is a UI convenience, never an extra way to be let through.
        val dismissed = entry("SWIGGY", SenderState.DISMISSED)
        assertTrue(decide("VM-SWIGGY", realDebit, dismissed, GateMode.ACTIVE) is Decision.Drop)
        assertTrue(decide("VM-SWIGGY", realDebit, dismissed, GateMode.OBSERVE) is Decision.Review)
    }

    // ---------- the seen list ----------

    @Test
    fun `bare mobile numbers never enter the seen list`() {
        // Otherwise the registry becomes a log of everyone who texts you, which is a
        // category of data this app has never held.
        assertFalse(SenderGate.observe("9876543210", realDebit).listable)
        assertFalse(SenderGate.observe("+919876543210", realDebit).listable)
        assertTrue(SenderGate.observe("VM-ICICIB", realDebit).listable)
    }

    @Test
    fun `a mobile number is unlistable but still addressable`() {
        // The two permissions the ingestor must keep apart: a number never enters the
        // seen list, yet it still normalises to something the registry can be asked
        // about - otherwise enrolling one by hand would look accepted and do nothing.
        val observation = SenderGate.observe("9876543210", realDebit)
        assertFalse(observation.listable)
        assertEquals("9876543210", observation.header)
    }

    @Test
    fun `an enrolled mobile number is still let through`() {
        // The escape hatch: the list will not offer you a personal number, but if you
        // deliberately add one the gate is a plain lookup and honours it.
        val row = entry("9876543210", SenderState.ENROLLED, null)
        assertTrue(decide("9876543210", realDebit, row, GateMode.ACTIVE) is Decision.Parse)
    }

    @Test
    fun `the peek recognises money and ignores everything else`() {
        assertTrue(SenderGate.observe(icici, realDebit).transactional)
        assertFalse(SenderGate.observe(icici, "123456 is your OTP. Do not share it.").transactional)
        assertFalse(SenderGate.observe(icici, "Your order is on the way!").transactional)
    }

    @Test
    fun `an enrolled sender with no rule yet still reaches the parser`() {
        // Enrolling HDFCBK before an HdfcRule exists has to do something useful, or
        // there is no way to start tracking a new bank.
        val row = entry("HDFCBK", SenderState.ENROLLED, null)
        val decision = decide("VM-HDFCBK", realDebit, row, GateMode.ACTIVE)
        assertTrue(decision is Decision.Parse)
        assertNull((decision as Decision.Parse).identity.bankKey)
    }
}
