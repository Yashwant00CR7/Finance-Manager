package com.yk.finance.parser

/**
 * The v1 parser: on-device, offline, deterministic. Sits behind [TransactionParser]
 * so an LLM fallback can be introduced later for messages that reach the review tray,
 * without touching ingestion, dedup, transfers or the UI.
 *
 * Deliberately free of any database access, and that is what keeps the regression
 * suite - every real message this app has ever been shown - running on the JVM in
 * milliseconds. The sender allowlist is a database question, so it is asked upstream
 * in SmsIngestor and its answer arrives here as a [SenderIdentity].
 */
class RuleBasedParser(
    private val rules: List<BankRule> = ALL_BANK_RULES,
) : TransactionParser {

    override fun parse(
        sender: String,
        body: String,
        receivedAt: Long,
        identity: SenderIdentity?,
    ): ParseResult {
        // The shape guard only applies to senders nobody vetted. An enrolled sender
        // has been vetted by hand, which outranks any regex - it is the whole point of
        // being able to enrol one, and the only way a non-DLT source can ever be
        // tracked deliberately.
        if (identity == null && !isSenderHeader(sender)) {
            return ParseResult.Ignored("sender '$sender' is not a bank header")
        }

        val header = identity?.header ?: SenderHeader.normalize(sender)
        val bankRule = when (val key = identity?.bankKey) {
            null -> rules.firstOrNull { it.ownsHeader(header) }
            else -> rules.firstOrNull { it.bank == key }
        }

        // An enrolled sender is known to be a bank even when no rule can parse it yet,
        // so its messages reach the tray rather than the bin. That is what makes it
        // useful to enrol a bank before its rule has been written.
        val bankKnown = bankRule != null || identity != null

        // Checked before parsing: a mandate notice can be shaped exactly like a
        // completed debit, and booking it would invent money you never spent.
        describesFutureOrOffer(body)?.let { reason ->
            return ParseResult.Ignored(reason)
        }

        val parsed = bankRule?.tryParse(body, receivedAt)
        if (parsed != null) return ParseResult.Parsed(parsed)

        // Financial-looking but unmatched: never dropped, never auto-booked.
        if (looksFinancial(body, bankKnown)) {
            return ParseResult.NeedsReview(
                raw = body,
                sender = sender,
                reason = bankRule?.let { "no rule matched for ${it.bank}" }
                    ?: "enrolled sender '$header' has no rule yet",
            )
        }

        return ParseResult.Ignored("not transaction-shaped")
    }
}
