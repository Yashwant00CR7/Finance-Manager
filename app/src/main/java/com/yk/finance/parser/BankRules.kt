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
     * Does this rule own the message? The DLT sender header is the authoritative
     * identifier ("VM-ICICIB"), with a body mention as fallback. Relying on the body
     * alone is fragile: a bank is not obliged to name itself in every message, and one
     * that does not would otherwise be ignored entirely.
     */
    fun claims(sender: String, body: String): Boolean

    fun tryParse(body: String, receivedAt: Long): ParsedSms?
}

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

    private val MENTIONS = Regex("""ICICI""", RegexOption.IGNORE_CASE)
    private val SENDER = Regex("""ICICI|ICICIB""", RegexOption.IGNORE_CASE)

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

    override fun claims(sender: String, body: String) =
        SENDER.containsMatchIn(sender) || MENTIONS.containsMatchIn(body)

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

    private val MENTIONS = Regex("""Union\s+Bank""", RegexOption.IGNORE_CASE)
    private val SENDER = Regex("""UNIONB|UNION|UBIN""", RegexOption.IGNORE_CASE)

    private val TXN = Regex(
        """A/c\s+\*+(\d+)\s+(Debited|Credited)\s+for\s+(?:Rs|INR)\.?:?\s*$AMT\s+on\s+(\d{2}-\d{2}-\d{4})(?:\s+(\d{2}:\d{2}:\d{2}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val REF = Regex("""ref\s*no\.?\s*(\d{6,})""", RegexOption.IGNORE_CASE)
    private val BY_CHANNEL = Regex("""\bby\s+(.+?)\s+ref\s*no""", RegexOption.IGNORE_CASE)

    override fun claims(sender: String, body: String) =
        SENDER.containsMatchIn(sender) || MENTIONS.containsMatchIn(body)

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
