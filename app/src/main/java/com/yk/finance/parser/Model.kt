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

/**
 * A sender the registry has already vouched for, and what it says the sender is.
 *
 * Its presence is the parser's signal that the allowlist has run and let this message
 * through, which changes two things: the header-shape guard is skipped, because a
 * sender enrolled by hand has been vetted more carefully than any regex could, and the
 * sender counts as a known bank even when [bankKey] names no rule yet - an enrolled
 * bank with no parser written for it belongs in the review tray, not in the bin.
 */
data class SenderIdentity(val header: String, val bankKey: String?)

interface TransactionParser {
    /**
     * [identity] is null only when nothing gated this message - the parser then falls
     * back to resolving the bank from the sender header itself, which is what the
     * regression suite exercises and what keeps this function usable on its own.
     */
    fun parse(
        sender: String,
        body: String,
        receivedAt: Long,
        identity: SenderIdentity? = null,
    ): ParseResult
}
