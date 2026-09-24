package com.yk.finance

import com.yk.finance.parser.IciciRule
import com.yk.finance.parser.SenderHeader
import com.yk.finance.parser.UnionRule
import com.yk.finance.parser.isSenderHeader
import com.yk.finance.parser.ruleForHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity half of the allowlist. Everything here is about one question: when are
 * two sender strings the same conversation?
 */
class SenderHeaderTest {

    @Test
    fun `the four real senders from this phone all normalise to their bank`() {
        // These are the actual DLT senders on the phone this app runs on. They are the
        // reason the rule is positional: an earlier version took everything after the
        // final hyphen, which turned every one of these into "S" or "T" and collapsed
        // ICICI and Union onto one entry while still looking plausible.
        assertEquals("ICICIT", SenderHeader.normalize("AD-ICICIT-S"))
        assertEquals("ICICIT", SenderHeader.normalize("AX-ICICIT-S"))
        assertEquals("UNIONB", SenderHeader.normalize("JK-UNIONB-S"))
        assertEquals("UNIONB", SenderHeader.normalize("JX-UNIONB-T"))
    }

    @Test
    fun `one bank is one entry however it is routed and whatever it carries`() {
        // Operator, circle and content category all vary on the same bank. If any of
        // them survived normalisation the allowlist would need an entry per variant,
        // and would go quiet the day a new one appeared.
        val icici = listOf("AD-ICICIT-S", "AX-ICICIT-S", "VK-ICICIT-T", "ICICIT")
        assertEquals(1, icici.map(SenderHeader::normalize).toSet().size)
    }

    @Test
    fun `the operator prefix is discarded whatever its length`() {
        assertEquals("ICICIB", SenderHeader.normalize("VM-ICICIB"))
        assertEquals("ICICIB", SenderHeader.normalize("AD-ICICIB"))
        // Four characters, not two. A fixed-width strip would leave "IB-UNIONB" here
        // and the entry would silently never match again.
        assertEquals("UNIONB", SenderHeader.normalize("UNIB-UNIONB"))
    }

    @Test
    fun `the content-category suffix is discarded, whatever letter it is`() {
        listOf("S", "T", "P", "G", "X").forEach { suffix ->
            assertEquals(suffix, "UNIONB", SenderHeader.normalize("JK-UNIONB-$suffix"))
        }
    }

    @Test
    fun `a header typed on its own is never eaten`() {
        // The manual field has to accept the middle value directly, or the only way to
        // add a sender is to have received one and copied it exactly.
        assertEquals("ICICIT", SenderHeader.normalize("ICICIT"))
        assertEquals("ICICIT", SenderHeader.normalize("ICICIT-S"))
        assertEquals("ICICIT", SenderHeader.normalize(" icicit "))
    }

    @Test
    fun `an unknown bank normalises the same way a known one does`() {
        // The rule is about the shape of a DLT sender, not about banks this app can
        // parse - otherwise enrolling a new bank by hand would be guesswork.
        assertEquals("IWRUHU", SenderHeader.normalize("AD-iwruhu-S"))
        assertEquals("IWRUHU", SenderHeader.normalize("iwruhu"))
        assertEquals("HDFCBK", SenderHeader.normalize("VM-HDFCBK-T"))
    }

    @Test
    fun `nothing usable normalises to nothing`() {
        assertEquals("", SenderHeader.normalize(null))
        assertEquals("", SenderHeader.normalize("   "))
        assertEquals("", SenderHeader.normalize("-"))
    }

    @Test
    fun `a mobile number is left alone and is never a header`() {
        assertEquals("9876543210", SenderHeader.normalize("9876543210"))
        assertFalse(isSenderHeader("9876543210"))
        assertFalse(isSenderHeader("+919876543210"))
        assertFalse(isSenderHeader(""))
        assertTrue(isSenderHeader("VM-ICICIB"))
    }

    @Test
    fun `every sender the regression suite uses still reaches its rule`() {
        // The guard against tightening identity into a regression: these are the two
        // senders every real message in ParserTest arrives from.
        assertSame(IciciRule, ruleForHeader(SenderHeader.normalize("VM-ICICIB")))
        assertSame(UnionRule, ruleForHeader(SenderHeader.normalize("AD-UNIONB")))
    }

    @Test
    fun `the real senders reach the right parser once enrolled`() {
        // ICICIT is not ICICIB, and the seed was wrong about that until the real
        // senders were checked. The prefix rule is what makes both route correctly.
        assertSame(IciciRule, ruleForHeader(SenderHeader.normalize("AD-ICICIT-S")))
        assertSame(IciciRule, ruleForHeader(SenderHeader.normalize("AX-ICICIT-S")))
        assertSame(UnionRule, ruleForHeader(SenderHeader.normalize("JK-UNIONB-S")))
        assertSame(UnionRule, ruleForHeader(SenderHeader.normalize("JX-UNIONB-T")))
    }

    @Test
    fun `one bank's other registered headers reach the same rule`() {
        assertSame(IciciRule, ruleForHeader("ICICIT"))
        assertSame(IciciRule, ruleForHeader("ICICIC"))
        assertSame(UnionRule, ruleForHeader("UBINBK"))
    }

    @Test
    fun `a header nobody owns is nobody's`() {
        assertNull(ruleForHeader("HDFCBK"))
        assertNull(ruleForHeader("SWIGGY"))
        assertNull(ruleForHeader(""))
    }
}
