package com.yk.finance.parser

private val AVL_BAL = Regex(
    """Avl\.?\s*(?:able)?\s*Bal(?:ance)?[:\s]*(?:Rs|INR)\.?:?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""",
    RegexOption.IGNORE_CASE,
)

private const val AMT = """([0-9][0-9,]*(?:\.\d{1,2})?)"""

/**
 * One bank's message formats. A message becomes a transaction ONLY by matching one
 * of these structurally - an allowlist, not a blocklist. This is what rejects fraud
 * warnings ("Rs.5,000 debited? If not you..."), promos and OTPs without needing a
 * single keyword ban: none of them are shaped like a completed transaction.
 */
interface BankRule {
    val bank: String

    /**
     * Normalised DLT header prefixes this rule owns - "ICICI" covers ICICIB, ICICIT
     * and ICICIC alike, which is how one bank's several registered headers reach one
     * parser.
     */
    val headerPrefixes: Set<String>

    /**
     * Whose message is this? The sender header decides, and nothing else does.
     *
     * There used to be a body-mention fallback here, and it was a hole: a message from
     * anybody at all that happened to contain the word "ICICI" was claimed by the ICICI
     * rule and parsed against your account. Identity and content are now separate
     * questions - the header says who sent it, the body only says what it means.
     *
     * Note this is asked of the *normalised* header, never the raw sender, so it cannot
     * be fooled by the operator prefix. See [SenderHeader].
     */
    fun ownsHeader(header: String): Boolean = headerPrefixes.any { header.startsWith(it) }

    fun tryParse(body: String, receivedAt: Long): ParsedSms?
}

/** The bank keys the app can actually parse. Enrolling any other sender is allowed;
 *  its messages reach the review tray instead of a rule. */
fun parsableBankKeys(): Set<String> = ALL_BANK_RULES.map { it.bank }.toSet()

/** Which rule, if any, recognises a header - used to suggest a bank when you enrol. */
fun ruleForHeader(header: String): BankRule? = ALL_BANK_RULES.firstOrNull { it.ownsHeader(header) }

/**
 * ICICI. Verified against 6 real messages.
 *
 * CRITICAL: every ICICI debit contains the word "credited" - the *payee* is credited:
 *   "Acct XX742 debited for Rs 169.00 on 11-Sep-26; SRI LAKSHMI TRA credited."
 * So direction is bound to the account token ("Acct XX742 debited"), never to the
 * presence of a keyword. Keying on keywords would file every spend as income.
 *
 * ICICI masks to 3 digits ("XX742") and truncates payee names at 15 characters
 * ("SRI LAKSHMI TRA", "KA 05 JUICE BAR"). The truncation is stable, so category
 * rules keyed on the truncated string still work.
 *
 * ICICI sends NO available balance. See ReconcileState for how that is handled.
 */
object IciciRule : BankRule {
    override val bank = "ICICI"

    /** ICICIB is the verified one; ICICIT and ICICIC are the same bank on other headers. */
    override val headerPrefixes = setOf("ICICI")

    // "Acct XX742 debited for Rs 169.00 on 11-Sep-26"  (verified)
    private val DEBIT_FOR = Regex(
        """Acct\s+XX(\d+)\s+debited\s+for\s+(?:Rs|INR)\.?:?\s*$AMT\s+on\s+(\d{1,2}-[A-Za-z]{3}-\d{2})""",
        RegexOption.IGNORE_CASE,
    )

    // "Acct XX742 is credited with Rs 3000.00 on 17-Sep-26"  (verified)
    private val CREDIT_WITH = Regex(
        """Acct\s+XX(\d+)\s+is\s+credited\s+with\s+(?:Rs|INR)\.?:?\s*$AMT\s+on\s+(\d{1,2}-[A-Za-z]{3}-\d{2})""",
        RegexOption.IGNORE_CASE,
    )

    // SYNTHETIC - no real sample. ICICI's ATM/other debits are believed to use
    // "is debited with". If your first real ATM message does not match, it lands in
    // the review tray (never lost) and this one line is what needs correcting.
    private val DEBIT_WITH_SYNTHETIC = Regex(
        """Acct\s+XX(\d+)\s+(?:is|has been)\s+debited\s+with\s+(?:Rs|INR)\.?:?\s*$AMT\s+on\s+(\d{1,2}-[A-Za-z]{3}-\d{2})""",
        RegexOption.IGNORE_CASE,
    )

    // Payee sits before " credited" on debits, after " from " on credits.
    private val PAYEE_DEBIT = Regex(""";\s*(.+?)\s+credited""", RegexOption.IGNORE_CASE)
    private val PAYEE_CREDIT = Regex("""\bfrom\s+([^.;]+?)\s*[.;]""", RegexOption.IGNORE_CASE)
    private val REF = Regex("""UPI[:\s]*(\d{6,})""", RegexOption.IGNORE_CASE)
    private val INFO = Regex("""Info[:\s]+([^.]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Long): ParsedSms? {
        // Order matters only in that each pattern is mutually exclusive by shape.
        val (match, direction) =
            DEBIT_FOR.find(body)?.let { it to Direction.DEBIT }
                ?: CREDIT_WITH.find(body)?.let { it to Direction.CREDIT }
                ?: DEBIT_WITH_SYNTHETIC.find(body)?.let { it to Direction.DEBIT }
                ?: return null

        val amount = parseAmountToPaise(match.groupValues[2]) ?: return null
        val occurred = sanityCheckDate(parseIciciDate(match.groupValues[3]), receivedAt)

        val payee = when (direction) {
            Direction.DEBIT -> PAYEE_DEBIT.find(body)?.groupValues?.get(1)?.trim()
                ?: INFO.find(body)?.groupValues?.get(1)?.trim()
            Direction.CREDIT -> PAYEE_CREDIT.find(body)?.groupValues?.get(1)?.trim()
        }?.takeIf { it.isNotBlank() }

        return ParsedSms(
            bank = bank,
            accountToken = match.groupValues[1],
            direction = direction,
            amountPaise = amount,
            occurredAt = occurred,
            payee = payee,
            reference = REF.find(body)?.groupValues?.get(1),
            availableBalancePaise = AVL_BAL.find(body)?.groupValues?.get(1)?.let(::parseAmountToPaise),
            channel = detectChannel(body),
            raw = body,
        )
    }
}

/**
 * Union Bank. Verified against 3 real messages.
 *
 *   "A/c *8317 Credited for Rs:3000.00 on 16-09-2026 11:23:34 by Mob Bk
 *    ref no 634455667788 Avl Bal Rs:2841.37.Never Share OTP/PIN/CVV-Union Bank of India"
 *
 * Note the body contains "OTP" and "If not you, Call" on real transactions - neither
 * may ever be used as a rejection signal. Union masks to 4 digits and, unlike ICICI,
 * does supply Avl Bal, which is treated as ground truth for the balance.
 */
object UnionRule : BankRule {
    override val bank = "UNION"

    /** UNIONB is the verified one; UBIN* headers are the same bank. */
    override val headerPrefixes = setOf("UNION", "UBIN")

    private val TXN = Regex(
        """A/c\s+\*+(\d+)\s+(Debited|Credited)\s+for\s+(?:Rs|INR)\.?:?\s*$AMT\s+on\s+(\d{2}-\d{2}-\d{4})(?:\s+(\d{2}:\d{2}:\d{2}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val REF = Regex("""ref\s*no\.?\s*(\d{6,})""", RegexOption.IGNORE_CASE)
    private val BY_CHANNEL = Regex("""\bby\s+(.+?)\s+ref\s*no""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Long): ParsedSms? {
        val m = TXN.find(body) ?: return null
        val amount = parseAmountToPaise(m.groupValues[3]) ?: return null
        val occurred = sanityCheckDate(
            parseUnionDate(m.groupValues[4], m.groupValues.getOrNull(5)),
            receivedAt,
        )
        return ParsedSms(
            bank = bank,
            accountToken = m.groupValues[1],
            direction = if (m.groupValues[2].equals("Debited", true)) Direction.DEBIT else Direction.CREDIT,
            amountPaise = amount,
            occurredAt = occurred,
            // Union names the channel ("Mob Bk"), never a payee. Nothing to categorise
            // on, which is why these land as Uncategorised until you teach them.
            payee = BY_CHANNEL.find(body)?.groupValues?.get(1)?.trim(),
            reference = REF.find(body)?.groupValues?.get(1),
            availableBalancePaise = AVL_BAL.find(body)?.groupValues?.get(1)?.let(::parseAmountToPaise),
            channel = detectChannel(body),
            raw = body,
        )
    }
}

val ALL_BANK_RULES: List<BankRule> = listOf(IciciRule, UnionRule)
