package com.yk.finance.parser

private val ATM_HINTS = listOf(
    Regex("""\bATM\b""", RegexOption.IGNORE_CASE),
    Regex("""CASH\s*WDL""", RegexOption.IGNORE_CASE),
    Regex("""\bWDL\b""", RegexOption.IGNORE_CASE),
    Regex("""CASH\s+WITHDRAWAL""", RegexOption.IGNORE_CASE),
    Regex("""\bATW\b""", RegexOption.IGNORE_CASE),
)

/**
 * Channel is detected from keywords anywhere in the body, deliberately decoupled
 * from the sentence layout. We have no real ATM sample, so the ATM *layout* is a
 * guess - but "ATM" or "WDL" appearing in a withdrawal message is not. Getting the
 * channel right is what matters, because ATM debits become transfers, not spending.
 */
fun detectChannel(body: String): Channel {
    // ATM first: a withdrawal message often also mentions the card.
    if (ATM_HINTS.any { it.containsMatchIn(body) }) return Channel.ATM
    return when {
        Regex("""\bUPI\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.UPI
        Regex("""Mob\s*Bk""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.MOBILE_BANKING
        Regex("""(Net\s*Bk|\bINB\b|Internet Bank)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.NET_BANKING
        Regex("""(\bPOS\b|swipe|card ending|ending with)""", RegexOption.IGNORE_CASE).containsMatchIn(body) -> Channel.POS
        else -> Channel.UNKNOWN
    }
}

/**
 * Phrases that mean "no money has moved yet" even when the sentence is otherwise
 * shaped exactly like a completed transaction.
 *
 * Deliberately short. We do NOT blocklist "OTP" - real Union Bank messages end with
 * "Never Share OTP/PIN/CVV", so that would reject every genuine Union transaction.
 * Nor "If not you, Call ..." - that trailer is present in real Union debits.
 * Junk is excluded by the structural allowlist in the bank rules instead: an OTP
 * message simply never matches "A/c *NNNN Debited for Rs:...".
 */
private val NOT_YET_HAPPENED = listOf(
    Regex("""will\s+be\s+(debited|credited|deducted)""", RegexOption.IGNORE_CASE),
    Regex("""will\s+get\s+(debited|credited)""", RegexOption.IGNORE_CASE),
    Regex("""(is|are)\s+due\s+(on|by)""", RegexOption.IGNORE_CASE),
    Regex("""due\s+for\s+payment""", RegexOption.IGNORE_CASE),
    Regex("""(standing instruction|e-?mandate|auto\s*pay)\s+.{0,30}(registered|set up|scheduled)""", RegexOption.IGNORE_CASE),
    Regex("""scheduled\s+(on|for)""", RegexOption.IGNORE_CASE),
    Regex("""(spend|shop)\s+(Rs|INR)""", RegexOption.IGNORE_CASE),
    Regex("""cashback""", RegexOption.IGNORE_CASE),
)

fun describesFutureOrOffer(body: String): String? =
    NOT_YET_HAPPENED.firstOrNull { it.containsMatchIn(body) }?.let { "future/promotional: ${it.pattern}" }

private val AMOUNT_ANYWHERE = Regex("""(?:Rs|INR)\.?:?\s*[0-9][0-9,]*(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE)
private val DIRECTION_ANYWHERE = Regex("""(debit|credit|withdraw|spent|paid)""", RegexOption.IGNORE_CASE)

/**
 * Gate for the review tray. Only messages that look financial AND name a bank we
 * know reach the tray; everything else is dropped silently. Without this, a first
 * launch would bury the tray under OTPs and delivery notifications.
 */
fun looksFinancial(body: String, bankKnown: Boolean): Boolean =
    bankKnown && AMOUNT_ANYWHERE.containsMatchIn(body) && DIRECTION_ANYWHERE.containsMatchIn(body)
