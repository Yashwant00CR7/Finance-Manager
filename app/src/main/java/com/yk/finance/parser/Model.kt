package com.yk.finance.parser

/** Which way money moved relative to *our* account. */
enum class Direction { DEBIT, CREDIT }

/**
 * How the money moved. Detected independently of message layout, because the
 * channel keywords are far more stable across banks than the sentence structure.
 */
enum class Channel { UPI, ATM, POS, MOBILE_BANKING, NET_BANKING, UNKNOWN }

/** A message the parser understood completely. */
data class ParsedSms(
    val bank: String,
    /** Masked digits only, as printed: ICICI gives 3 ("150"), Union gives 4 ("2209"). */
    val accountToken: String,
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    val payee: String?,
    val reference: String?,
    /**
     * Money actually in the account. Null when the bank does not say - ICICI never does,
     * see ReconcileState - and null for every card alert, whose balance-shaped number is
     * a credit limit and belongs in [availableCreditPaise] instead.
     */
    val availableBalancePaise: Long?,
    val channel: Channel,
    val raw: String,

    /**
     * Which [PatternSpec] matched, so the app can remember that a person accepted this
     * exact shape. Defaulted for the hand-written rules and for tests that build a
     * ParsedSms directly.
     */
    val patternId: String = "",

    /** How far the matching pattern may be trusted. See [Tier]. */
    val tier: Tier = Tier.VERIFIED,

    /**
     * Remaining credit limit on a card. Recorded because it is useful to see, and kept
     * apart from [availableBalancePaise] because it is emphatically not money you hold:
     * summed into net worth it would invent an asset out of a debt.
     */
    val availableCreditPaise: Long? = null,

    /** The alert was about a card, so this resolves to a card account, not a deposit one. */
    val isCard: Boolean = false,
)

/** The three things that can happen to an incoming SMS. */
sealed interface ParseResult {
    /**
     * Understood. Whether it may book itself is *not* decided here - that depends on the
     * pattern's tier and on whether this device has confirmed it before, and the trust
     * store is a database concern. The parser stays offline, pure and testable; the
     * ingestor makes the call.
     */
    data class Parsed(val sms: ParsedSms) : ParseResult

    /**
     * Looked financial (amount + direction word + known bank) but no rule matched.
     * Goes to the review tray so we never lose money silently, and so the tray
     * tells us exactly which rule to write next.
     */
    data class NeedsReview(val raw: String, val sender: String, val reason: String) : ParseResult

    /** Not financial, or explicitly rejected. Dropped without trace. */
    data class Ignored(val reason: String) : ParseResult
}

interface TransactionParser {
    fun parse(sender: String, body: String, receivedAt: Long): ParseResult
}
