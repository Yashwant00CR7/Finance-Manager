package com.yk.finance

import com.yk.finance.domain.mayBookItself
import com.yk.finance.parser.Tier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what may write money down without being looked at.
 *
 * Small enough to read in one go, which is the point: everything else in the parser exists to
 * classify a message into one of these three cases correctly, and this is what the
 * classification is *for*.
 */
class PatternTrustTest {

    @Test
    fun `a pattern written from the author's own messages books immediately`() {
        assertTrue(mayBookItself(Tier.VERIFIED, confirmed = false))
    }

    @Test
    fun `a researched pattern waits for this device to agree with it`() {
        assertFalse(
            "a sample found online is not evidence about this account",
            mayBookItself(Tier.RESEARCHED, confirmed = false),
        )
        assertTrue(mayBookItself(Tier.RESEARCHED, confirmed = true))
    }

    /**
     * The one that has to hold even when somebody is tapping Confirm without reading.
     *
     * A generic shape matches "Your A/c XX1234 is debited Rs.49,999. If not you, call ..."
     * just as happily as it matches a real alert, so no number of confirmations may promote
     * it. Confirming a generic match records that one transaction and nothing more.
     */
    @Test
    fun `a generic shape never books, however often it is confirmed`() {
        assertFalse(mayBookItself(Tier.GENERIC, confirmed = false))
        assertFalse(
            "confirming a cross-bank shape must not make the next one automatic",
            mayBookItself(Tier.GENERIC, confirmed = true),
        )
    }
}
