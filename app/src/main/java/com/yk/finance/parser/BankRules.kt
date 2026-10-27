package com.yk.finance.parser

private val AVL_BAL = Regex(
    """Avl\.?\s*(?:able)?\s*Bal(?:ance)?[:\s]*(?:Rs|INR|₹)\.?:?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""",
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
 * ICICI sends NO available balance on these two shapes. See ReconcileState for how that is
 * handled. Its other formats - ATM withdrawals, ACH debits, salary credits - do carry one,
 * and they live in BankPatterns.kt at the researched tier because the samples behind them
 * came from a public corpus rather than from this phone.
 *
 * There used to be a third rule here, a guess at ICICI's "is debited with" ATM wording that
 * no real message had ever confirmed. Real ATM samples turned up during the multi-bank work
 * and the guess was wrong twice over: ICICI writes "Acc" rather than "Acct" for these, and
 * puts no "is" or "for" before "debited". It has been deleted rather than corrected, because
 * a corrected guess is still a guess.
 */
object IciciRule : BankRule {
    override val bank = "ICICI"

    private val MENTIONS = Regex("""ICICI""", RegexOption.IGNORE_CASE)
    private val SENDER = Regex("""ICICI|ICICIB""", RegexOption.IGNORE_CASE)

    // "Acct XX742 debited for Rs 169.00 on 11-Sep-26"  (verified)
    //
    // The sentence is the verified one. What is tolerated around it is only the tokens banks
    // swap without changing the sentence: the rupee sign for "Rs", a four-digit year, a
    // third X in the mask. Any of those used to send a perfectly ordinary payment to the tray.
    private val DEBIT_FOR = Regex(
        """Acct\s+[Xx*]*(\d+)\s+debited\s+for\s+$CURRENCY\s*$AMT\s+on\s+(\d{1,2}-[A-Za-z]{3}-\d{2,4})""",
        RegexOption.IGNORE_CASE,
    )

    // "Acct XX742 is credited with Rs 3000.00 on 17-Sep-26"  (verified)
    private val CREDIT_WITH = Regex(
        """Acct\s+[Xx*]*(\d+)\s+is\s+credited\s+with\s+$CURRENCY\s*$AMT\s+on\s+(\d{1,2}-[A-Za-z]{3}-\d{2,4})""",
        RegexOption.IGNORE_CASE,
    )

    // Payee sits before " credited" on debits, after " from " on credits.
    private val PAYEE_DEBIT = Regex(""";\s*(.+?)\s+credited""", RegexOption.IGNORE_CASE)
    private val PAYEE_CREDIT = Regex("""\bfrom\s+([^.;]+?)\s*[.;]""", RegexOption.IGNORE_CASE)
    private val REF = Regex("""UPI[:\s]*(\d{6,})""", RegexOption.IGNORE_CASE)

    // A transfer between your own accounts goes by IMPS or NEFT and carries its reference
    // here instead: "& Acct XX123 credited. IMPS:626112345678". Without it the debit could
    // never be paired with the credit on the other side, and the move between your own
    // accounts was counted as 5,000 spent and 5,000 earned.
    private val TRANSFER_REF = Regex("""\b(?:IMPS|NEFT|RTGS|RRN)[:\s]*(?=[A-Z]*\d)([A-Z0-9]{6,})""", RegexOption.IGNORE_CASE)
    private val INFO = Regex("""Info[:\s]+([^.]+)""", RegexOption.IGNORE_CASE)

    override fun claims(sender: String, body: String) =
        SENDER.containsMatchIn(sender) || MENTIONS.containsMatchIn(body)

    override fun tryParse(body: String, receivedAt: Long): ParsedSms? {
        // Order matters only in that each pattern is mutually exclusive by shape.
        val (match, direction) =
            DEBIT_FOR.find(body)?.let { it to Direction.DEBIT }
                ?: CREDIT_WITH.find(body)?.let { it to Direction.CREDIT }
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
            reference = (REF.find(body) ?: TRANSFER_REF.find(body))?.groupValues?.get(1),
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

    // The direction word stays bound to our own account clause; what is tolerated is the
    // filler banks put between the two ("is", "has been") and the two-digit year and slashes
    // some of Union's templates use. Each of those used to drop to the generic tier, which
    // never books, so an ordinary Union payment waited in the tray.
    private val TXN = Regex(
        """(?:Union\s+Bank(?:\s+of\s+India)?\s+)?A/c\s+(?:No\.?\s*)?[Xx*]*(\d+)\s+(?:is\s+|has\s+been\s+)?(Debited|Credited)\s+(?:for\s+|with\s+|by\s+)?$CURRENCY\s*$AMT\s+on\s+(\d{2}[-/]\d{2}[-/]\d{2,4})(?:\s+(\d{2}:\d{2}:\d{2}))?""",
        RegexOption.IGNORE_CASE,
    )

    // NEFT references are alphanumeric ("SBIN426303123456"), and a digits-only pattern
    // silently left every NEFT credit with no reference to deduplicate on.
    private val REF = Regex("""ref\s*no\.?\s*(?=[A-Z]*\d)([A-Z0-9]{6,})""", RegexOption.IGNORE_CASE)
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
        val direction = if (m.groupValues[2].equals("Debited", true)) Direction.DEBIT else Direction.CREDIT
        return ParsedSms(
            bank = bank,
            accountToken = m.groupValues[1],
            direction = direction,
            amountPaise = amount,
            occurredAt = occurred,
            // "by Mob Bk" names the channel, not who was paid. Used as a debit's payee it made
            // one category rule out of every Union payment without a Fvg clause - file one as
            // Food and the next fifty were Food. Credits keep it, because a salary tagged
            // while it read "NEFT" is matched against that text when the next one lands.
            payee = extractGenericPayee(body, direction)
                ?: BY_CHANNEL.find(body)?.groupValues?.get(1)?.trim()?.takeIf { direction == Direction.CREDIT },
            reference = REF.find(body)?.groupValues?.get(1),
            availableBalancePaise = AVL_BAL.find(body)?.groupValues?.get(1)?.let(::parseAmountToPaise),
            channel = detectChannel(body),
            raw = body,
        )
    }
}

val ALL_BANK_RULES: List<BankRule> = listOf(IciciRule, UnionRule)
