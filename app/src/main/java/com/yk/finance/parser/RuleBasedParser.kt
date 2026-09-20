package com.yk.finance.parser

/**
 * A personal 10-digit mobile number as sender. Banks in India send via DLT headers
 * ("VM-ICICIB", "AD-UNIONB"), never from a personal number - so a transaction-shaped
 * message from a bare mobile number is a scam, not a transaction.
 */
private val PERSONAL_NUMBER = Regex("""^\+?\d{10,13}$""")

private fun isBankHeader(sender: String): Boolean {
    val s = sender.trim()
    if (s.isEmpty()) return false
    if (PERSONAL_NUMBER.matches(s.replace(" ", ""))) return false
    return s.any { it.isLetter() }
}

/**
 * The v1 parser: on-device, offline, deterministic. Sits behind [TransactionParser]
 * so an LLM fallback can be introduced later for messages that reach the review tray,
 * without touching ingestion, dedup, transfers or the UI.
 */
class RuleBasedParser(
    private val rules: List<BankRule> = ALL_BANK_RULES,
) : TransactionParser {

    override fun parse(sender: String, body: String, receivedAt: Long): ParseResult {
        if (!isBankHeader(sender)) {
            return ParseResult.Ignored("sender '$sender' is not a bank header")
        }

        val bankRule = rules.firstOrNull { it.claims(sender, body) }
        val bankKnown = bankRule != null

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
                reason = if (bankKnown) "no rule matched for ${bankRule?.bank}" else "unknown bank",
            )
        }

        return ParseResult.Ignored("not transaction-shaped")
    }
}
