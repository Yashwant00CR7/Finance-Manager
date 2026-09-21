package com.yk.finance

import com.yk.finance.domain.Categorizer
import com.yk.finance.domain.SEED_KEYWORD_RULES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The seed list matched with `contains`, which is how a gola stall became public
 * transport: "OLA" sits inside "GOLA", and the first match won.
 *
 * These are all real payee shapes. A keyword list that files the wrong category
 * silently is worse than one that files nothing, because nothing at least asks.
 */
class SeedKeywordTest {

    @Test
    fun `a keyword inside a longer word does not match`() {
        assertNull(Categorizer.seedCategoryFor("GOLA CENTER"))
        assertNull(Categorizer.seedCategoryFor("SHOLAPUR MESS"))
        assertNull(Categorizer.seedCategoryFor("CHOCOLATE HUT"))
    }

    @Test
    fun `the longest matching keyword wins`() {
        // Both contain a keyword from another category. First-match-wins booked
        // dinner as a taxi and groceries as a phone bill.
        assertEquals("Food", Categorizer.seedCategoryFor("UBER EATS"))
        assertEquals("Food", Categorizer.seedCategoryFor("JIOMART GROCERY"))
        assertEquals("Transportation", Categorizer.seedCategoryFor("UBER TRIP"))
        assertEquals("Bills", Categorizer.seedCategoryFor("JIO RECHARGE"))
    }

    @Test
    fun `the ordinary cases still match`() {
        assertEquals("Food", Categorizer.seedCategoryFor("SWIGGY"))
        assertEquals("Food", Categorizer.seedCategoryFor("KA 05 JUICE BAR"))
        assertEquals("Transportation", Categorizer.seedCategoryFor("OLA CABS"))
        assertEquals("Health and Fitness", Categorizer.seedCategoryFor("APOLLO PHARMACY"))
    }

    @Test
    fun `punctuation is a word boundary`() {
        // UPI payees arrive as "SWIGGY*ORDER", "AMAZON@UPI" and similar.
        assertEquals("Food", Categorizer.seedCategoryFor("SWIGGY*ORDER 4412"))
        assertEquals("Shopping", Categorizer.seedCategoryFor("AMAZON@UPI"))
    }

    @Test
    fun `matching survives the normalisation payeeKey applies`() {
        assertEquals("Food", Categorizer.seedCategoryFor("  ka 05   juice bar "))
    }

    @Test
    fun `EB matches as a word rather than as a prefix`() {
        // The rule used to be "EB " - a trailing space standing in for a boundary,
        // which meant it could never match at the end of a payee.
        assertEquals("Bills", Categorizer.seedCategoryFor("EB"))
        assertEquals("Bills", Categorizer.seedCategoryFor("TNEB EB PAYMENT"))
        assertNull(Categorizer.seedCategoryFor("EBAY ORDER"))
    }

    @Test
    fun `an unknown payee still returns nothing`() {
        assertNull(Categorizer.seedCategoryFor("paytmqr 1a2b3cd"))
        assertNull(Categorizer.seedCategoryFor(null))
        assertNull(Categorizer.seedCategoryFor("   "))
    }

    @Test
    fun `every seed keyword is normalised the same way payees are`() {
        // A lowercase keyword could never match, because the key is uppercased first.
        SEED_KEYWORD_RULES.forEach { (keyword, _) ->
            assertEquals("keyword not normalised: $keyword", keyword.uppercase(), keyword)
            assertEquals("keyword has stray whitespace: '$keyword'", keyword.trim(), keyword)
        }
    }
}
