package com.yk.finance.parser

/**
 * A personal 10-digit mobile number as sender. Banks in India send via DLT headers
 * ("VM-ICICIB", "AD-UNIONB"), never from a personal number - so a transaction-shaped
 * message from a bare mobile number is a scam, not a transaction.
 */
private val PERSONAL_NUMBER = Regex("""^\+?\d{10,13}$""")

/**
 * Whether a sender could be a commercial header at all.
 *
 * The "contains a letter" test that used to stand alone here was wrong: 11% of India's
 * registered DLT headers are entirely numeric, ICICI's `142421` and Kotak's `111000` among
 * them, and those were being discarded as personal numbers. A header we can positively
 * identify is now accepted regardless of what it is made of, and the letter test survives
 * only as the fallback for headers not in the registry.
 */
private fun isBankHeader(sender: String): Boolean {
    val s = sender.trim()
    if (s.isEmpty()) return false
    if (isRegisteredBankHeader(s)) return true
    if (PERSONAL_NUMBER.matches(s.replace(" ", ""))) return false
    return s.any { it.isLetter() }
}

/**
 * The parser: on-device, offline, deterministic.
 *
 * Three tiers are tried in order, and the order is the whole design.
 *
 *  1. **Hand-written [BankRule]s** for the banks whose messages the author receives. Written
 *     against real alerts from a real handset and asserted in ParserTest.
 *  2. **[RESEARCHED_PATTERNS]** for the banks whose formats were found in public sources.
 *     Real samples, somebody else's phone. These parse but do not book on their own.
 *  3. **[GENERIC_PATTERNS]**, cross-bank shapes for everyone else, and only when the sender
 *     header is a registered bank's. These never book at all.
 *
 * What the parser deliberately does *not* decide is whether a match may reach the ledger.
 * That depends on the tier and on whether this device has confirmed the pattern before, and
 * the record of confirmations is in the database. Keeping the decision out of here is what
 * lets the whole parser stay a pure function of (sender, body, time) and be tested on the
 * JVM without a device or a database.
 */
class RuleBasedParser(
    private val rules: List<BankRule> = ALL_BANK_RULES,
    private val researched: List<PatternSpec> = RESEARCHED_PATTERNS,
    private val generic: List<PatternSpec> = GENERIC_PATTERNS,
) : TransactionParser {

    override fun parse(sender: String, body: String, receivedAt: Long): ParseResult {
        if (!isBankHeader(sender)) {
            return ParseResult.Ignored("sender '$sender' is not a bank header")
        }

        // The header is the authoritative identifier; a body mention is the fallback, for
        // headers registered after TRAI's published snapshot.
        val headerBank = bankForSender(sender)
        val bankRule = rules.firstOrNull { it.claims(sender, body) }
        // Header first, then a hand-written rule's own claim, then the bank the message names
        // in its text - which is what lets a genuine header registered after TRAI's 2020
        // snapshot still reach the right patterns. The generic tier below deliberately does
        // not accept this fallback.
        val bank = headerBank ?: bankRule?.bank ?: bankMentionedIn(body)
        val bankKnown = bank != null

        // Checked before parsing: a mandate notice can be shaped exactly like a
        // completed debit, and booking it would invent money you never spent.
        describesFutureOrOffer(body)?.let { reason ->
            return ParseResult.Ignored(reason)
        }

        bankRule?.tryParse(body, receivedAt)?.let { return ParseResult.Parsed(it) }

        // Researched patterns are scoped to the bank the header names. Trying another bank's
        // patterns would be guessing, and the shapes are similar enough across banks that
        // the guess would often succeed - with the wrong bank on the row.
        if (bank != null) {
            researched.asSequence()
                .filter { it.bank == bank }
                .firstNotNullOfOrNull { applySpec(it, body, receivedAt) }
                ?.let { return ParseResult.Parsed(it) }
        }

        // The generic tier requires a positively identified sender. An unrecognised header
        // that happens to be shaped like a bank alert is exactly what a phishing SMS is.
        if (headerBank != null) {
            generic.asSequence()
                .firstNotNullOfOrNull { applySpec(it, body, receivedAt, bank = headerBank) }
                ?.let { return ParseResult.Parsed(it) }
        }

        // Financial-looking but unmatched: never dropped, never auto-booked.
        if (looksFinancial(body, bankKnown)) {
            return ParseResult.NeedsReview(
                raw = body,
                sender = sender,
                reason = if (bankKnown) "no rule matched for $bank" else "unknown bank",
            )
        }

        return ParseResult.Ignored("not transaction-shaped")
    }
}

/**
 * A parser for read-only previews, so the review tray can show what a pattern made of a
 * message without going near the ledger.
 *
 * Shared because it is stateless and because every pattern list it closes over is a
 * top-level val compiled once; building one per tray row would be wasteful rather than wrong.
 */
val PREVIEW_PARSER: TransactionParser = RuleBasedParser()
