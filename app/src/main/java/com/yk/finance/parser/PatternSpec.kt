package com.yk.finance.parser

/**
 * How much the app trusts a pattern, which decides whether a match may book itself.
 *
 * The distinction is about *evidence*, not about code quality. A researched pattern can
 * be better written than a verified one and still deserve less trust, because nobody has
 * ever seen it match a message that actually arrived on a real phone.
 */
enum class Tier {
    /**
     * Derived from messages the app's author received on their own handset and asserted
     * against in [com.yk.finance.parser.RuleBasedParser]'s regression suite. Books
     * immediately, no confirmation.
     */
    VERIFIED,

    /**
     * Derived from a verbatim sample found in a citable public source - an open-source
     * parser's fixtures, a forum post, a bank's own documentation. The sample was real,
     * but it was somebody else's, possibly years old, and the bank may have changed the
     * format since. Books only after the person holding the phone confirms it once.
     */
    RESEARCHED,

    /**
     * Cross-bank shapes that match the common Indian alert grammar without knowing which
     * bank sent it. Deliberately loose, so it never books on its own no matter how many
     * times it is confirmed - it only ever pre-fills the review tray.
     */
    GENERIC,
}

/**
 * What a balance-looking number in the message actually means.
 *
 * Getting this wrong is the single most expensive mistake available here. "Avl Bal" on a
 * savings alert is money you hold; "Avl Lmt" on a credit card alert is money the bank is
 * willing to lend you. Booking the second as the first inflates the Accounts screen's
 * Overall figure by an amount that does not exist.
 */
enum class BalanceMeaning {
    /** Money in the account. Safe to treat as ground truth for the balance. */
    ACCOUNT_BALANCE,

    /** Remaining credit limit on a card. Recorded, but never written to a balance. */
    AVAILABLE_CREDIT,

    /** The message carries no balance, or one we cannot interpret. */
    NONE,
}

/**
 * One message shape, declared as data rather than code.
 *
 * The two hand-written [BankRule] objects proved the approach but do not scale: every
 * bank repeated the same amount/date/account plumbing around a different sentence. A spec
 * keeps only the part that actually differs - the sentence - and hands the rest to
 * [PatternEngine].
 *
 * The regex uses **named** groups rather than positions. Indices were survivable with two
 * banks and would be unreadable with forty; named groups also mean a pattern can omit a
 * field it does not carry without renumbering everything after it. minSdk is 26, so
 * java.util.regex named groups are available on every device the app runs on.
 *
 * Recognised group names, all optional except where stated:
 *  - `amount`  (REQUIRED) the transaction amount
 *  - `acct`    (REQUIRED) the masked account digits, digits only
 *  - `date`    the transaction date in any format [parseIndianDate] understands
 *  - `time`    a companion time for `date`
 *  - `dir`     the direction word, when and only when [direction] is null
 *  - `payee`   the counterparty, when the main sentence carries it
 *  - `ref`     the reference/UTR number
 *  - `bal`     the balance-looking figure, interpreted per [balanceMeaning]
 */
data class PatternSpec(
    /**
     * Stable identity, `BANK.shape.vN`. This string is what a person confirmed when they
     * accepted a parse, so it must never be reused for a different shape and never be
     * edited in place. Changing a pattern's meaning means a new `vN`, which correctly
     * costs the user one more confirmation.
     */
    val id: String,
    val bank: String,
    val tier: Tier,

    /**
     * Fixed by the pattern's shape, not read from the message.
     *
     * Null means "read the `dir` named group", which is permitted **only** when the
     * direction word sits in the same clause as our own account, as Union Bank's
     * "A/c *8317 Credited for Rs..." does. It is forbidden wherever a direction word can
     * appear that describes somebody else - ICICI's debits famously end
     * "; SRI LAKSHMI TRA credited", and a spec that read a direction word from anywhere
     * in that body would file every payment as income. ICICI therefore gets one spec per
     * direction with this field set.
     */
    val direction: Direction?,

    val regex: Regex,

    /**
     * Tried in order when the main regex has no `payee` group; the first group of the
     * first match wins. Kept separate because the payee is usually in a different clause
     * from the amount, and folding it into the main regex would make the main regex
     * refuse to match whenever the payee clause happened to be absent.
     */
    val payeePatterns: List<Regex> = emptyList(),

    /** Tried in order when the main regex has no `ref` group. */
    val refPatterns: List<Regex> = DEFAULT_REF_PATTERNS,

    val balanceMeaning: BalanceMeaning = BalanceMeaning.ACCOUNT_BALANCE,

    /**
     * Tried in order when the main regex has no `bal` group.
     *
     * Defaults from [balanceMeaning] rather than to one fixed list, so a card spec cannot
     * silently inherit the deposit-balance labels and read an "Avl Bal" it should have
     * ignored. Overriding is still allowed for the banks that label things their own way.
     */
    val balPatterns: List<Regex> = defaultBalancePatternsFor(balanceMeaning),

    /**
     * True when the alert is about a card rather than a deposit account.
     *
     * Deliberately a boolean rather than the persistence layer's AccountKind: the data
     * package already depends on this one for [Direction] and [Channel], and importing
     * back the other way would make the two mutually dependent. The ingestor does the
     * one-line mapping to an account kind.
     */
    val isCard: Boolean = false,

    /**
     * Where the sample that justifies this pattern came from. Required for
     * [Tier.RESEARCHED]; null for [Tier.VERIFIED], whose evidence is the author's own
     * handset, and for [Tier.GENERIC], which is a shape rather than a bank's format.
     *
     * This is not documentation. It is the audit trail that stops a plausible-looking
     * regex nobody ever saw match anything from reaching a stranger's ledger.
     */
    val evidence: String? = null,
) {
    /**
     * The named groups this pattern declares, collected once. Asking the regex engine for
     * an undeclared group throws, and doing that per message per spec would be both slow
     * and exception-driven control flow.
     */
    val groupNames: Set<String> = declaredGroupNames(regex.pattern)

    init {
        require(direction != null || "dir" in groupNames) {
            "$id: a spec with no fixed direction must capture a 'dir' group"
        }
        require(tier != Tier.RESEARCHED || evidence != null) {
            "$id: a researched pattern must cite the sample it came from"
        }
    }
}

/**
 * Reference numbers. UPI transaction IDs are 12 digits, NEFT/IMPS references vary, and
 * several banks print the UTR with no label at all, so the labelled forms are tried first
 * and nothing is inferred from a bare number.
 */
internal val DEFAULT_REF_PATTERNS: List<Regex> = listOf(
    Regex("""\bUPI\s*Ref(?:erence)?\s*(?:No\.?)?[:\s.-]*([A-Z0-9]{6,22})""", RegexOption.IGNORE_CASE),
    Regex("""\bUPI[/:\s-]+(\d{9,22})""", RegexOption.IGNORE_CASE),
    Regex("""\bRRN[:\s.-]*(\d{6,22})""", RegexOption.IGNORE_CASE),
    Regex("""\bUTR[:\s.-]*([A-Z0-9]{6,22})""", RegexOption.IGNORE_CASE),
    Regex("""\bref(?:erence)?\s*(?:no\.?|id|#)?[:\s.-]*([A-Z0-9]{6,22})""", RegexOption.IGNORE_CASE),
    Regex("""\btxn\s*(?:id|no\.?)[:\s.-]*([A-Z0-9]{6,22})""", RegexOption.IGNORE_CASE),
)

/**
 * The word a bank uses for "money you hold", in every spelling the collected samples show.
 *
 * There are more of these than seems reasonable, and several banks use two of them in one
 * message: Federal alone prints "Bal Rs", "BAL-Rs.", "BAL:Rs", "Current Bal:" and
 * "Final balance is" across its templates. IDFC writes "New bal Rs.", "New bal:" and
 * "Available balance Rs." Guessing at a label that was not observed would be inventing
 * evidence, so every alternative below comes from a message in docs/bank-sms-formats.md.
 */
private const val AVL = """(?:Avl|Avbl|Avlbl|Avail|Available)"""

/**
 * Deliberately excludes a bare "Bal" with no currency token after it. "Min Bal", "Bal Due"
 * and "Total Amount Due" all exist, and none of them is money you can spend.
 */
internal val DEFAULT_BALANCE_PATTERNS: List<Regex> = listOf(
    Regex("""$AVL\.?\s*Bal(?:ance)?(?:\s+is)?[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    Regex("""New\s+Bal(?:ance)?[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    Regex("""Your\s+new\s+balance\s+is[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    Regex("""(?:Clear|Clr|Current|Final|Total)\s+Bal(?:ance)?(?:\s+is)?[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    // "BAL-Rs.321634.31", "Bal:Rs.12345.89", "Bal Rs 76.82" - a currency token is required
    // here precisely because the label alone is too weak to trust.
    Regex("""\bBAL[-:.\s]*$CURRENCY\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
)

/**
 * Remaining credit on a card. Kept strictly apart from the balance labels: this is money the
 * bank will lend, and adding it to a net worth would turn a debt into an asset.
 */
internal val DEFAULT_CREDIT_LIMIT_PATTERNS: List<Regex> = listOf(
    Regex("""$AVL\.?\s*(?:Credit\s+)?L(?:i)?m(?:i)?t[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    Regex("""(?:Your\s+)?available\s+credit\s+limit\s+is[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
    Regex("""(?:Your\s+)?available\s+limit\s+is[-:\s]*$CURRENCY?\s*$AMOUNT_GROUP""", RegexOption.IGNORE_CASE),
)

/** Balance labels appropriate to what the number in this kind of message means. */
internal fun defaultBalancePatternsFor(meaning: BalanceMeaning): List<Regex> = when (meaning) {
    BalanceMeaning.ACCOUNT_BALANCE -> DEFAULT_BALANCE_PATTERNS
    BalanceMeaning.AVAILABLE_CREDIT -> DEFAULT_CREDIT_LIMIT_PATTERNS
    BalanceMeaning.NONE -> emptyList()
}
