package com.yk.finance.parser

/**
 * Finds the named groups a pattern actually declares.
 *
 * Asking java.util.regex for a group the pattern never declared throws, so the names are
 * collected once per spec rather than guarded by a try/catch on every message. Requiring a
 * letter immediately after `<` is what keeps lookbehind - `(?<=` and `(?<!` - out of the
 * result.
 */
private val GROUP_NAME = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

internal fun declaredGroupNames(pattern: String): Set<String> =
    GROUP_NAME.findAll(pattern).map { it.groupValues[1] }.toSet()

/**
 * Direction words, mapped strictly.
 *
 * An unrecognised word makes the whole pattern fail rather than fall back to a guess. A
 * guess here is not a slightly-wrong label: it is income filed as spending or the reverse,
 * which corrupts every total on every screen.
 */
private val DIRECTION_WORDS: Map<String, Direction> = mapOf(
    "debited" to Direction.DEBIT,
    "dr" to Direction.DEBIT,
    "debit" to Direction.DEBIT,
    "withdrawn" to Direction.DEBIT,
    "deducted" to Direction.DEBIT,
    "spent" to Direction.DEBIT,
    "paid" to Direction.DEBIT,
    "sent" to Direction.DEBIT,
    "credited" to Direction.CREDIT,
    "cr" to Direction.CREDIT,
    "credit" to Direction.CREDIT,
    "received" to Direction.CREDIT,
    "deposited" to Direction.CREDIT,
)

private fun MatchResult.named(name: String, declared: Set<String>): String? {
    if (name !in declared) return null
    val collection = groups as? MatchNamedGroupCollection ?: return null
    return collection[name]?.value
}

private fun firstCapture(patterns: List<Regex>, body: String): String? =
    patterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.getOrNull(1) }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Applies one [PatternSpec] to one message.
 *
 * Returns null whenever anything required is missing or uninterpretable. Null is always
 * the right answer to doubt here: an unmatched message reaches the review tray and costs
 * the user a tap, while a half-understood one becomes a wrong row in the ledger that
 * nobody notices until the month does not add up.
 *
 * [bank] overrides the spec's own name, which is how a [Tier.GENERIC] shape - written
 * without knowing who sent it - gets attributed to the bank the sender header identified.
 */
internal fun applySpec(
    spec: PatternSpec,
    body: String,
    receivedAt: Long,
    bank: String = spec.bank,
): ParsedSms? {
    val match = spec.regex.find(body) ?: return null
    val declared = spec.groupNames

    val amount = match.named("amount", declared)?.let(::parseAmountToPaise) ?: return null
    val account = match.named("acct", declared)?.takeIf { it.isNotBlank() } ?: return null

    val direction = spec.direction
        ?: match.named("dir", declared)?.lowercase()?.let(DIRECTION_WORDS::get)
        ?: return null

    // No date group at all is fine: sanityCheckDate falls back to the receipt time, which
    // for a live alert is within seconds of the truth.
    val occurred = sanityCheckDate(
        match.named("date", declared)?.let { parseIndianDate(it, match.named("time", declared)) },
        receivedAt,
    )

    val payee = match.named("payee", declared)?.trim()?.takeIf { it.isNotBlank() }
        ?: firstCapture(spec.payeePatterns, body)

    val reference = match.named("ref", declared)?.trim()?.takeIf { it.isNotBlank() }
        ?: firstCapture(spec.refPatterns, body)

    val balanceText = match.named("bal", declared) ?: firstCapture(spec.balPatterns, body)
    val balance = balanceText?.let(::parseAmountToPaise)

    return ParsedSms(
        bank = bank,
        accountToken = account,
        direction = direction,
        amountPaise = amount,
        occurredAt = occurred,
        payee = payee,
        reference = reference,
        availableBalancePaise =
            balance.takeIf { spec.balanceMeaning == BalanceMeaning.ACCOUNT_BALANCE },
        channel = detectChannel(body),
        raw = body,
        patternId = spec.id,
        tier = spec.tier,
        availableCreditPaise =
            balance.takeIf { spec.balanceMeaning == BalanceMeaning.AVAILABLE_CREDIT },
        isCard = spec.isCard,
    )
}
