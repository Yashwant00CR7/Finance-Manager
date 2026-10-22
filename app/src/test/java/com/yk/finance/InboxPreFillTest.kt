package com.yk.finance

import com.yk.finance.parser.extractPreFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPreFillTest {

    @Test
    fun `extracts debit amount, direction and Fvg payee from Union Bank alert`() {
        val msg = "Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, " +
            "Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472"

        val prefill = extractPreFill(msg)
        assertEquals("1755.00", prefill.amountRupees)
        assertTrue("Should be debit (Money out)", prefill.isDebit)
        assertEquals("AONE TRA", prefill.payee)
    }

    @Test
    fun `extracts credit direction and amount from credit alert`() {
        val msg = "A/c *2209 Credited Rs:2500.00 on 22-10-2026 11:15:00 ref 123456 Avl Bal Rs:7828.67"

        val prefill = extractPreFill(msg)
        assertEquals("2500.00", prefill.amountRupees)
        assertFalse("Should be credit (Money in)", prefill.isDebit)
        assertEquals("", prefill.payee)
    }

    @Test
    fun `extracts payee from to format and debit direction`() {
        val msg = "Rs.120.00 sent to CHAI POINT on 21-Oct-26 via UPI ref 998877"

        val prefill = extractPreFill(msg)
        assertEquals("120.00", prefill.amountRupees)
        assertTrue("Should be debit (Money out)", prefill.isDebit)
        assertEquals("CHAI POINT", prefill.payee)
    }

    @Test
    fun `leaves fields blank when uncertain without false positives`() {
        val msg = "Dear Customer, please complete your periodic KYC update at nearest branch."

        val prefill = extractPreFill(msg)
        assertEquals("", prefill.amountRupees)
        assertTrue(prefill.isDebit)
        assertEquals("", prefill.payee)
    }
}
