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
    /** Only Union Bank supplies this. ICICI never does - see ReconcileState. */
    val availableBalancePaise: Long?,
    val channel: Channel,
    val raw: String,
)

/** The three things that can happen to an incoming SMS. */
sealed interface ParseResult {
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
