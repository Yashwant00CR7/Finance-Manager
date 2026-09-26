package com.yk.finance.parser

/**
 * Every bank shape the app knows, as data.
 *
 * ## The rule that governs this file
 *
 * A pattern may only exist here if a verbatim sample of the message it matches is recorded
 * in `docs/bank-sms-formats.md` with the URL it was found at. No sample, no pattern - not
 * even for a bank whose format "obviously" resembles a neighbour's. Two of the banks that
 * were researched (Karnataka Bank, City Union Bank) are absent for exactly this reason:
 * only fragments and placeholder templates could be found, and writing plausible regex from
 * those would have been fabrication wearing the costume of research.
 *
 * The [PatternSpec.evidence] field carries the source. It is not decoration; it is the only
 * thing separating a researched pattern from an invented one once the samples scroll out of
 * view.
 *
 * ## Why every one of these is RESEARCHED and not VERIFIED
 *
 * The samples are real, but they are other people's, and some are years old. A bank changes
 * a template and the regex that fitted it becomes a regex that fits nothing - or worse, one
 * that fits the wrong part of a new sentence. So a match here never books itself. It waits
 * for the one person who can compare it against the message on their own screen. See
 * [Tier] and ConfirmedPattern.
 *
 * ## The trap every one of these had to be written around
 *
 * A great many Indian banks name the counterparty as "credited" inside a message describing
 * money leaving your account:
 *
 *   "Rs.200.00 debited A/cXX5468 and credited to SAI MISAL via UPI..."   (Bank of India)
 *   "Your A/c XX4614 debited by Rs. 1,172.06 ...; REDBUS credited."      (IDFC FIRST)
 *   "IDBI Bank Acct XX569 debited for Rs 115.00 ...; SRINIVASAN R credited."
 *
 * Direction is therefore bound to the clause containing *our* account, never to a keyword
 * found anywhere in the body. Most specs below fix [PatternSpec.direction] outright; the
 * few that read a `dir` group do so only where the direction word sits against our own
 * account number.
 */

// Masking is not standardised at all: "XX742", "*8317", "XXXXXXXXXX1234", "x2451",
// "...5494", "(6089)". Only the digits matter, so the decoration is consumed and dropped.
private const val MASK = """[Xx*.]*"""

private const val A = """(?<amount>[0-9][0-9,]*(?:\.\d{1,2})?)"""
private const val ACCT = """$MASK(?<acct>\d{3,})"""

/** Dates seen in the wild: 07/01/26, 13Sep25, 09-Jun-26, 24JUN2026, 30 AUG 2026, 17-07-26. */
private const val DATE = """(?<date>\d{1,2}[-/ ]?[A-Za-z]{3}[-/ ]?\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})"""
private const val TIME = """(?<time>\d{1,2}:\d{2}(?::\d{2})?)"""

private fun ci(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private const val DOC = "docs/bank-sms-formats.md"

// ---------------------------------------------------------------------------------------
// HDFC Bank
// ---------------------------------------------------------------------------------------

private val HDFC = listOf(
    PatternSpec(
        id = "HDFC.upi_debit.v1",
        bank = "HDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)"
        regex = ci("""$CURRENCY\s*$A\s+debited\s+from\s+A/c\s+$ACCT\s+on\s+$DATE\s+to\s+(?<payee>[^(]+?)\s*\(UPI\s+Ref\s+No\s+(?<ref>\d+)\)"""),
        evidence = "$DOC - pennywiseai TestHDFCBankParser.kt",
    ),
    PatternSpec(
        id = "HDFC.upi_sent.v1",
        bank = "HDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Sent Rs.15000.00 From HDFC Bank A/C *1234 To TEST MERCHANT PVT LTD On 01/01/26 Ref 567890567890"
        regex = ci("""Sent\s+$CURRENCY\s*$A\s+From\s+HDFC\s+Bank\s+A/C\s+$ACCT\s+To\s+(?<payee>.+?)\s+On\s+$DATE\s+Ref\s+(?<ref>\d+)"""),
        evidence = "$DOC - pennywiseai TestHDFCBankParser.kt",
    ),
    PatternSpec(
        id = "HDFC.neft_credit.v1",
        bank = "HDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Update! INR 1.00 deposited in HDFC Bank A/c XX9999 on 30-MAR-26 for NEFT Cr-...Avl bal INR 8.00."
        regex = ci("""$CURRENCY\s*$A\s+deposited\s+in\s+HDFC\s+Bank\s+A/c\s+$ACCT\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestHDFCBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Axis Bank
// ---------------------------------------------------------------------------------------

private val AXIS = listOf(
    PatternSpec(
        id = "AXIS.card_spend.v1",
        bank = "AXIS",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Spent INR 131 Axis Bank Card no. XX0818 05-10-25 09:43:27 IST Swiggy Limi Avl Limit: INR 217162.72"
        regex = ci("""Spent\s+$CURRENCY\s*$A\s+Axis\s+Bank\s+Card\s+no\.?\s*$ACCT\s+$DATE\s+$TIME\s+IST\s+(?<payee>.+?)\s+Av(?:l|bl)\s*L"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestAxisBankParser.kt",
    ),
    PatternSpec(
        id = "AXIS.card_spend.v2",
        bank = "AXIS",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Spent Card no. XX7441 INR 562 01-09-25 12:04:18 AVENUE SUPE Avl Lmt INR 5120.87"
        regex = ci("""Spent\s+Card\s+no\.?\s*$ACCT\s+$CURRENCY\s*$A\s+$DATE\s+$TIME\s+(?<payee>.+?)\s+Av(?:l|bl)\s*L"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestAxisBankParser.kt",
    ),
    PatternSpec(
        id = "AXIS.account_debit.v1",
        bank = "AXIS",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 2000.00 debited from A/c no. XX589034 on AXIS BANK L 04-11-2025 16:06:39 IST. Avl bal: INR 98919.81."
        regex = ci("""$CURRENCY\s*$A\s+debited\s+from\s+A/c\s+no\.?\s*$ACCT\s+on\s+(?<payee>.+?)\s+$DATE\s+$TIME\s+IST"""),
        evidence = "$DOC - pennywiseai TestAxisBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Kotak Mahindra Bank
// ---------------------------------------------------------------------------------------

private val KOTAK = listOf(
    PatternSpec(
        id = "KOTAK.upi_sent.v1",
        bank = "KOTAK",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Sent Rs.15.00 from Kotak Bank AC X1234 to paytmqr...@paytm on 14-10-25.UPI Ref 1234567890"
        regex = ci("""Sent\s+$CURRENCY\s*$A\s+from\s+Kotak\s+Bank\s+AC\s+$ACCT\s+to\s+(?<payee>\S+?)\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestKotakBankParser.kt",
    ),
    PatternSpec(
        id = "KOTAK.upi_sent.v2",
        bank = "KOTAK",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Sent Rs.51.00 from XXXXXX9722 to DINESHBHAI RAMJIBHAI on 30/04/2026. UPI ref no. 648604626824."
        regex = ci("""Sent\s+$CURRENCY\s*$A\s+from\s+$ACCT\s+to\s+(?<payee>.+?)\s+on\s+$DATE\.\s*UPI\s+ref\s+no\.?\s*(?<ref>\d+)"""),
        evidence = "$DOC - pennywiseai TestKotakBankParser.kt (un-scrubbed payee names)",
    ),
    PatternSpec(
        id = "KOTAK.upi_received.v1",
        bank = "KOTAK",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Received Rs.250.00 in your Kotak Bank AC X3333 from john.doe@oksbi on 14-10-25.UPI Ref 2222222222"
        regex = ci("""Received\s+$CURRENCY\s*$A\s+in\s+your\s+Kotak\s+Bank\s+AC\s+$ACCT\s+from\s+(?<payee>\S+?)\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestKotakBankParser.kt",
    ),
    PatternSpec(
        id = "KOTAK.card_spend.v1",
        bank = "KOTAK",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 20 spent on Kotak Credit Card x5236 on 23-JAN-2026 at UPI-638903921672-CORN. Avl limit INR 73733.02"
        regex = ci("""$CURRENCY\s*$A\s+spent\s+on\s+Kotak\s+Credit\s+Card\s+$ACCT\s+on\s+$DATE\s+at\s+(?<payee>[^.]+?)\.\s*Av"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestKotakBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// State Bank of India
// ---------------------------------------------------------------------------------------

private val SBI = listOf(
    PatternSpec(
        id = "SBI.account_debit.v1",
        bank = "SBI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Rs.500 debited from A/c X1234 on 13Sep25. Avl Bal Rs.999999999"
        regex = ci("""$CURRENCY\s*$A\s+debited\s+from\s+A/c\s+$ACCT\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestSBIParser.kt",
    ),
    PatternSpec(
        id = "SBI.card_spend.v1",
        // SBI Cards is a different legal entity from State Bank of India, and its header
        // (SBICRD) resolves accordingly. The spec must agree or this never matches.
        bank = "SBICARD",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Rs.259.00 spent on your SBI Credit Card ending with 1234 on 15Jan26. Your available limit is Rs.1,235.00."
        regex = ci("""$CURRENCY\s*$A\s+spent\s+on\s+your\s+SBI\s+Credit\s+Card\s+ending\s+(?:with\s+)?$ACCT(?:\s+at\s+(?<payee>.+?))?\s+on\s+$DATE"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestSBIParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// IndusInd Bank
// ---------------------------------------------------------------------------------------

private val INDUSIND = listOf(
    PatternSpec(
        id = "INDUSIND.upi_debit.v1",
        bank = "INDUSIND",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "A/c *XX1234 debited by Rs 1234.00 towards xxxx.yyyy@icici. RRN: 510048508040."
        regex = ci("""A/[cC]\s*$ACCT\s+debited\s+by\s+$CURRENCY\s*$A\s+towards\s+(?<payee>.+?)\s*\.\s*RRN"""),
        evidence = "$DOC - pennywiseai TestIndusIndBankParser.kt",
    ),
    PatternSpec(
        id = "INDUSIND.upi_credit.v1",
        bank = "INDUSIND",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "A/C *XX1234 credited by Rs 25000.00 from xxxx.yyyy@ybl. RRN:510048508040. Avl Bal:105502.12."
        regex = ci("""A/[cC]\s*$ACCT\s+credited\s+by\s+$CURRENCY\s*$A\s+from\s+(?<payee>.+?)\s*\.\s*RRN"""),
        evidence = "$DOC - pennywiseai TestIndusIndBankParser.kt",
    ),
    PatternSpec(
        id = "INDUSIND.card_spend.v1",
        bank = "INDUSIND",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 1,250.00 spent on IndusInd Card XX1234 on 14-06-2026 04:21:45 pm at INSTAMART. Avl Lmt: INR 48,750.00."
        regex = ci("""$CURRENCY\s*$A\s+spent\s+on\s+IndusInd\s+Card\s+$ACCT\s+on\s+$DATE\s+$TIME\s*(?:am|pm)?\s+at\s+(?<payee>[^.]+?)\.\s*Av"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestIndusIndBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Yes Bank
// ---------------------------------------------------------------------------------------

private val YES = listOf(
    PatternSpec(
        id = "YES.card_spend.v1",
        bank = "YES",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 404.36 spent on YES BANK Card X3349 @UPI_C N S FUEL PORT 24-08-2025 06:17:25 pm. Avl Lmt INR 211,476.24."
        regex = ci("""$CURRENCY\s*$A\s+spent\s+on\s+YES\s+BANK\s+Card\s+$ACCT\s+@(?<payee>.+?)\s+$DATE\s+$TIME"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestYesBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Bank of Baroda
// ---------------------------------------------------------------------------------------

private val BOB = listOf(
    PatternSpec(
        id = "BOB.account_debit.v1",
        bank = "BOB",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Rs.80.00 Dr. from A/c XX123456 on 12-11-2024. AvlBal:Rs1234.56cx. Ref:52211012345"
        regex = ci("""$CURRENCY\s*$A\s+Dr\.?\s+from\s+A/c\s+$ACCT\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBankOfBarodaParser.kt",
    ),
    PatternSpec(
        id = "BOB.account_credit.v1",
        bank = "BOB",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Rs.5000.00 Credited to A/c XX112233 on 01-12-2024. AvlBal:Rs12345.67"
        regex = ci("""$CURRENCY\s*$A\s+Credited\s+to\s+A/c\s+$ACCT\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBankOfBarodaParser.kt",
    ),
    PatternSpec(
        id = "BOB.credit_with.v1",
        bank = "BOB",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Your A/c XX987654 is credited with INR 70.00 on 30-11-2024. Total Bal:Rs.5000.00"
        regex = ci("""A/c\s+$ACCT\s+is\s+credited\s+with\s+$CURRENCY\s*$A\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBankOfBarodaParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Bank of India
// ---------------------------------------------------------------------------------------

private val BOI = listOf(
    PatternSpec(
        id = "BOI.upi_debit.v1",
        bank = "BOI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Rs.200.00 debited A/cXX5468 and credited to SAI MISAL via UPI Ref No 315439383341 on 23Aug25."
        // Note "credited" names the payee, exactly as ICICI does. Direction is fixed here.
        regex = ci("""$CURRENCY\s*$A\s+debited\s+A/c\s*$ACCT\s+and\s+credited\s+to\s+(?<payee>.+?)\s+via\s+UPI\s+Ref\s+No\s+(?<ref>\d+)\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBankOfIndiaParser.kt",
    ),
    PatternSpec(
        id = "BOI.neft_credit.v1",
        bank = "BOI",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "BOI - Rs 15,000.00 Credited in your Ac XX5468 on 04-02-2026 By NEFTINWARD ... .Avl Bal 18679.91"
        regex = ci("""$CURRENCY\s*$A\s+Credited\s+in\s+your\s+Ac\s+$ACCT\s+on\s+$DATE(?:\s+By\s+(?<payee>[^.]+?))?\s*\."""),
        evidence = "$DOC - pennywiseai TestBankOfIndiaParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Indian Bank
// ---------------------------------------------------------------------------------------

private val INDIAN_BANK = listOf(
    PatternSpec(
        id = "INDIANBANK.upi_sent.v1",
        bank = "INDIANBANK",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Sent Rs.440.00 from A/c *4512 on 17-07-26 to NARMADA FOODS.RRN 213416112187.Avl Bal Rs.4585.65."
        regex = ci("""Sent\s+$CURRENCY\s*$A\s+from\s+A/c\s+$ACCT\s+on\s+$DATE\s+to\s+(?<payee>[^.]+?)\.\s*RRN\s*(?<ref>\d+)"""),
        evidence = "$DOC - pennywiseai TestIndianBankParser.kt",
    ),
    PatternSpec(
        id = "INDIANBANK.upi_credit.v1",
        bank = "INDIANBANK",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Rs.2.00 credited to a/c *8175 on 07/10/2025 by a/c linked to VPA x@y (UPI Ref no 981408452805).Indian Bank"
        regex = ci("""$CURRENCY\s*$A\s+credited\s+to\s+a/c\s+$ACCT\s+on\s+$DATE(?:\s+by\s+a/c\s+linked\s+to\s+VPA\s+(?<payee>\S+))?"""),
        evidence = "$DOC - pennywiseai TestIndianBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// AU Small Finance Bank
// ---------------------------------------------------------------------------------------

private val AU = listOf(
    PatternSpec(
        id = "AU.account_debit.v1",
        bank = "AU",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Debited INR 500.00 from A/c 1234567890 on 15-FEB-2026. Bal INR 10000.00. -AU Bank"
        regex = ci("""Debited\s+$CURRENCY\s*$A\s+from\s+A/c\s+$ACCT\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestAUBankParser.kt",
    ),
    PatternSpec(
        id = "AU.short_form.v1",
        bank = "AU",
        tier = Tier.RESEARCHED,
        // Direction comes from the leading Dr/Cr token, which sits against our own account.
        direction = null,
        // "Dr INR 29,000.00 from A/c X7661 on 05-MAY-2026" / "Cr INR 5,000.00 to A/c X4541 12-MAY-2026"
        regex = ci("""\b(?<dir>Dr|Cr)\s+$CURRENCY\s*$A\s+(?:from|to)\s+(?:AU\s+)?A/c\s+$ACCT\s+(?:on\s+)?$DATE"""),
        evidence = "$DOC - pennywiseai TestAUBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// Equitas Small Finance Bank
// ---------------------------------------------------------------------------------------

private val EQUITAS = listOf(
    PatternSpec(
        id = "EQUITAS.upi_debit.v1",
        bank = "EQUITAS",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 500.00 debited via UPI from Equitas A/c 1234 -Ref:571987071234 on 19-12-25 to JOHN DOE. Avl Bal is INR 15,000.50."
        regex = ci("""$CURRENCY\s*$A\s+debited\s+via\s+UPI\s+from\s+Equitas\s+A/c\s+$ACCT\s*-?\s*Ref:(?<ref>\w+)\s+on\s+$DATE\s+to\s+(?<payee>[^.]+?)\."""),
        evidence = "$DOC - pennywiseai TestEquitasBankParser.kt",
    ),
    PatternSpec(
        id = "EQUITAS.upi_credit.v1",
        bank = "EQUITAS",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "INR 2,000.00 credited via UPI to Equitas A/c 9012 -Ref:123456789012 on 18-01-26 from EMPLOYER NAME. Avl Bal is INR 25,000.00."
        regex = ci("""$CURRENCY\s*$A\s+credited\s+via\s+UPI\s+to\s+Equitas\s+A/c\s+$ACCT\s*-?\s*Ref:(?<ref>\w+)\s+on\s+$DATE\s+from\s+(?<payee>[^.]+?)\."""),
        evidence = "$DOC - pennywiseai TestEquitasBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// IDFC FIRST Bank
// ---------------------------------------------------------------------------------------

private val IDFC = listOf(
    PatternSpec(
        id = "IDFC.neft_debit.v1",
        bank = "IDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "IDFC FIRST Bank A/c XX7696 debited with Rs 5,000.00 on 07/01/26 via NEFT/IDFBH26007759490. New bal Rs.1,38,202.65."
        regex = ci("""A/c\s+$ACCT\s+debited\s+with\s+$CURRENCY\s*$A\s+on\s+$DATE\s+via\s+(?<payee>[A-Z]+)/(?<ref>\w+)"""),
        evidence = "$DOC - shivarya/expense-tracker-scraper sms-export.json",
    ),
    PatternSpec(
        id = "IDFC.upi_debit.v1",
        bank = "IDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Your A/c XX4614 debited by Rs. 1,172.06 on 15/01/26; REDBUS credited. RRN 060649915527."
        // "credited" here names the merchant. Direction is fixed, not read from the body.
        regex = ci("""A/c\s+$ACCT\s+debited\s+by\s+$CURRENCY\s*$A\s+on\s+$DATE;\s*(?<payee>.+?)\s+credited"""),
        evidence = "$DOC - pennywiseai TestIDFCFirstBankParser.kt",
    ),
    PatternSpec(
        id = "IDFC.account_credit.v1",
        bank = "IDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Your A/C XXXXX037696 is credited with INR 25,000.00 on 26/12/25 06:24."
        regex = ci("""A/C\s+$ACCT\s+is\s+credited\s+with\s+$CURRENCY\s*$A\s+on\s+$DATE(?:\s+$TIME)?"""),
        evidence = "$DOC - shivarya/expense-tracker-scraper sms-export.json",
    ),
    PatternSpec(
        id = "IDFC.card_spend.v1",
        bank = "IDFC",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 499.00 spent on your IDFC FIRST Bank Credit Card ending XX3663 at RAJASTHALI on 30 AUG 2026 at 03:45 PM Avbl Limit: INR 341865.91"
        regex = ci("""$CURRENCY\s*$A\s+spent\s+on\s+your\s+IDFC\s+FIRST\s+Bank\s+Credit\s+Card\s+ending\s+$ACCT\s+at\s+(?<payee>.+?)\s+on\s+$DATE"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - yassk29/TxnVault sms_parsers_test.dart",
    ),
)

// ---------------------------------------------------------------------------------------
// Federal Bank
// ---------------------------------------------------------------------------------------

private val FEDERAL = listOf(
    PatternSpec(
        id = "FEDERAL.neft_debit.v1",
        bank = "FEDERAL",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "Debited Rs 6000 from a/c XX3343 on 24JUN2026 21:35 via NEFT to Jerry.Ref FDRLM4175007432.Bal Rs 76.82."
        regex = ci("""Debited\s+$CURRENCY\s*$A\s+from\s+a/c\s+$ACCT\s+on\s+$DATE\s+$TIME\s+via\s+\w+\s+to\s+(?<payee>[^.]+?)\.\s*Ref\s*(?<ref>\w+)"""),
        evidence = "$DOC - pennywiseai TestFederalBankParser.kt",
    ),
    PatternSpec(
        id = "FEDERAL.imps_credit.v1",
        bank = "FEDERAL",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Rs.316000 credited to your A/c XX2172 via IMPS on 16JUN2024 10:48:12. (IMPS Ref no-416810603307) BAL-Rs.321634.31"
        regex = ci("""$CURRENCY\s*$A\s+credited\s+to\s+your\s+A/c\s+$ACCT\s+via\s+(?<payee>\w+)\s+on\s+$DATE\s+$TIME"""),
        evidence = "$DOC - yassk29/TxnVault sms_parsers_test.dart",
    ),
    PatternSpec(
        id = "FEDERAL.cash_deposit.v1",
        bank = "FEDERAL",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Hi,Rs.1100credited in your A/c XX3223 on 15JAN2026 13:03:07 using cash deposit machine at FBL-CHANGANASSERY."
        // The amount is glued straight onto the verb, hence no whitespace allowed here.
        regex = ci("""$CURRENCY\s*${A}credited\s+in\s+your\s+A/c\s+$ACCT\s+on\s+$DATE\s+$TIME"""),
        evidence = "$DOC - pennywiseai TestFederalBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// RBL Bank - card only. No savings-account sample exists anywhere we could find.
// ---------------------------------------------------------------------------------------

private val RBL = listOf(
    PatternSpec(
        id = "RBL.card_spend.v1",
        bank = "RBL",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR9,139.00 spent at METRO BRANDS LIMITED on RBL Bank credit card (6089) on 24-01-2026.AVL limit- INR284,236.23."
        // Note there is no space after INR, and the card digits are in bare parentheses.
        regex = ci("""$CURRENCY\s*$A\s+spent\s+at\s+(?<payee>.+?)\s+on\s+RBL\s+Bank\s+credit\s+card\s+\((?<acct>\d+)\)\s+on\s+$DATE"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - shivarya/expense-tracker-scraper sms-export.json, 20+ instances",
    ),
)

// ---------------------------------------------------------------------------------------
// Bandhan Bank
// ---------------------------------------------------------------------------------------

private val BANDHAN = listOf(
    PatternSpec(
        id = "BANDHAN.upi_debit.v1",
        bank = "BANDHAN",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 180.00 debited from A/c XXXXXXXXXX1234 towards UPI/DR/D123013240123/Amazon Pa Value 16-NOV-2025 ."
        regex = ci("""$CURRENCY\s*$A\s+debited\s+from\s+A/c\s+$ACCT\s+towards\s+\w+/DR/(?<ref>\w+)/(?<payee>.+?)\s+Value\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBandhanBankParser.kt (digits sanitized upstream)",
    ),
    PatternSpec(
        id = "BANDHAN.upi_credit.v1",
        bank = "BANDHAN",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "INR 25,000.00 deposited to A/c XXXXXXXXXX1234 towards UPI/CR/C224513287910/JOHN DOE/u on 03-OCT-2025 ."
        regex = ci("""$CURRENCY\s*$A\s+deposited\s+to\s+A/c\s+$ACCT\s+towards\s+\w+/CR/(?<ref>\w+)/(?<payee>.+?)(?:/\w)?\s+on\s+$DATE"""),
        evidence = "$DOC - pennywiseai TestBandhanBankParser.kt (digits sanitized upstream)",
    ),
)

// ---------------------------------------------------------------------------------------
// IDBI Bank
// ---------------------------------------------------------------------------------------

private val IDBI = listOf(
    PatternSpec(
        id = "IDBI.upi_debit.v1",
        bank = "IDBI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "IDBI Bank Acct XX569 debited for Rs 115.00 on 09-Jun-26; Bal Rs 11631.73 SRINIVASAN R credited. UPI:002733174962."
        regex = ci("""IDBI\s+Bank\s+Acct\s+$ACCT\s+debited\s+for\s+$CURRENCY\s*$A\s+on\s+$DATE"""),
        // The payee has no delimiter before it - it simply follows the balance. This is the
        // only way to find it without swallowing the balance too.
        payeePatterns = listOf(
            ci("""Bal\s+$CURRENCY\s*[0-9][0-9,]*(?:\.\d{1,2})?\s+(.+?)\s+credited"""),
        ),
        evidence = "$DOC - naveendgp/Money-Management and neeraj9649/monex, two independent samples",
    ),
)

// ---------------------------------------------------------------------------------------
// South Indian Bank
// ---------------------------------------------------------------------------------------

private val SIB = listOf(
    PatternSpec(
        id = "SIB.upi_debit.v1",
        bank = "SIB",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "UPI debit:Rs.599.00 A/c X7477, 16-10-25 16:25:29 RRN: 565526068910 Bal:Rs.12345.89"
        regex = ci("""UPI\s+debit:\s*$CURRENCY\s*$A,?\s+A/c\s+$ACCT,\s+$DATE\s+$TIME"""),
        evidence = "$DOC - pennywiseai TestSouthIndianBankParser.kt",
    ),
    PatternSpec(
        id = "SIB.imps_credit.v1",
        bank = "SIB",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "Your A/c X7377 is credited with Rs.792.02 Info: IMPS/FDRL/528005821348/EPIFI ACCOUN. Final balance is Rs.793.02"
        // No date anywhere in this template; the receipt time stands in for it.
        regex = ci("""A/c\s+$ACCT\s+is\s+credited\s+with\s+$CURRENCY\s*$A\s+Info:\s*\w+/\w+/(?<ref>\w+)/(?<payee>[^.]+?)\."""),
        evidence = "$DOC - pennywiseai TestSouthIndianBankParser.kt",
    ),
)

// ---------------------------------------------------------------------------------------
// ICICI - the shapes beyond the two the author receives
//
// IciciRule in BankRules.kt covers the UPI debit and credit that arrive on this phone, and
// books them without asking. Everything below came out of a public corpus, so it sits at the
// researched tier like any other stranger's sample. Between them they also settle a question
// that had been open in the code: ICICI's ATM wording, previously a guess.
// ---------------------------------------------------------------------------------------

private val ICICI_RESEARCHED = listOf(
    PatternSpec(
        id = "ICICI.acc_debit.v1",
        bank = "ICICI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // Four real shapes, one sentence between them - ATM withdrawals, ACH mandates and
        // bill debits all use it, differing only in the token after the date:
        //   "ICICI Bank Acc XX921 debited Rs. 10,000.00 on 20-Jan-26 NFSCASH WDL. Avb Bal Rs. 3,943.84."
        //   "ICICI Bank Account XX123 debited Rs. 5,000.00 on 19-May-26 InfoACH*BD-ACHKFL.Avl Bal Rs. 3,25,000.04."
        //   "ICICI Bank Acc XX611 debited Rs. 6,500.00 on 19-Jun-26 CAM*62712SRY*. Avb Bal Rs. 10,079.44."
        //   "ICICI Bank Acc XX342 debited Rs. 8,927.00 on 07-Jun-26 InfoBIL*Auto Loan.Avl Bal Rs. 1,248.78."
        // Note "Acc"/"Account", never "Acct" - which is what the deleted guess got wrong.
        regex = ci("""ICICI\s+Bank\s+Acc(?:ount)?\s+$ACCT\s+debited\s+$CURRENCY\s*$A\s+on\s+$DATE\s+(?<payee>[^.]+?)\s*\.\s*Av"""),
        evidence = "$DOC - pennywiseai TestICICIBankParser.kt, four independent samples",
    ),
    PatternSpec(
        id = "ICICI.account_credit.v1",
        bank = "ICICI",
        tier = Tier.RESEARCHED,
        direction = Direction.CREDIT,
        // "ICICI Bank Account XX566 credited:Rs. 18,832.00 on 28-Feb-25. Info INF*000169831922*IQBO SAL FE. Available Balance is Rs. 28,076.14."
        // The Info field is where a salary credit identifies itself, so it is worth keeping.
        regex = ci("""ICICI\s+Bank\s+Account\s+$ACCT\s+credited:\s*$CURRENCY\s*$A\s+on\s+$DATE\.\s*Info\s+(?<payee>[^.]+?)\s*\."""),
        evidence = "$DOC - pennywiseai TestICICIBankParser.kt, three independent samples",
    ),
    PatternSpec(
        id = "ICICI.own_transfer_debit.v1",
        bank = "ICICI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "ICICI Bank Acct XX123 debited with Rs 10 on 20-Dec-25 & Acct XX456 credited.IMPS:ABCDEF123456."
        // Two of the user's own accounts in one sentence. Direction is fixed to the first,
        // which is the one this message is addressed about; the counterpart arrives, if at
        // all, as its own message and is paired by TransferResolver on the shared reference.
        regex = ci("""ICICI\s+Bank\s+Acct\s+$ACCT\s+(?:is\s+)?debited\s+with\s+$CURRENCY\s*$A(?:\s+on\s+$DATE)?\s*(?:&|and)\s*Acct"""),
        evidence = "$DOC - pennywiseai TestICICIBankParser.kt, three independent samples",
    ),
    PatternSpec(
        id = "ICICI.card_spend.v1",
        bank = "ICICI",
        tier = Tier.RESEARCHED,
        direction = Direction.DEBIT,
        // "INR 500.00 spent using ICICI Bank Card XX5678 on 06-Sep-25 on Swiggy. Avl Limit: INR 1,50,000.00."
        //
        // Only rupees. The same template carries foreign spends - "USD 11.80 spent using ICICI
        // Bank Card XX7004 ... Avl Limit: INR 17,95,899.53" - where the amount and the limit
        // are in different currencies. Matching those would book 11.80 rupees for an 11.80
        // dollar purchase, so they are deliberately left to fall through to the tray.
        regex = ci("""$CURRENCY\s*$A\s+spent\s+using\s+ICICI\s+Bank\s+Card\s+$ACCT\s+on\s+$DATE\s+on\s+(?<payee>.+?)\s*\.\s*Av"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
        evidence = "$DOC - pennywiseai TestICICIBankParser.kt",
    ),
)

/**
 * Every researched pattern, in the order they are tried.
 *
 * Order matters only where two specs could match the same message; within a bank the more
 * specific shape is listed first. Across banks it is irrelevant, because the sender header
 * has already narrowed the field to one bank's specs by the time these are reached.
 */
val RESEARCHED_PATTERNS: List<PatternSpec> =
    HDFC + AXIS + KOTAK + SBI + INDUSIND + YES + BOB + BOI + INDIAN_BANK +
        AU + EQUITAS + IDFC + FEDERAL + RBL + BANDHAN + IDBI + SIB + ICICI_RESEARCHED

// ---------------------------------------------------------------------------------------
// The generic tier
// ---------------------------------------------------------------------------------------

/**
 * Cross-bank shapes, for the roughly 1,500 co-operative banks, regional rural banks and
 * foreign banks nobody has written a pattern for.
 *
 * These are looser than anything above and they know nothing about who sent them, so two
 * rules hold them in check. They run **only** when the sender header is in [bankForSender]'s
 * registry, and they are [Tier.GENERIC], which never books no matter how many times a
 * person confirms it - a generic match can only ever pre-fill the tray.
 *
 * That combination is deliberate. Without the registry gate, a phishing SMS shaped like
 * "Your A/c XX1234 is debited Rs.49,999. If not you, call ..." would match the first entry
 * here perfectly. Without the tier, a loose shape could book the wrong number. With both, the
 * worst case is a row in the tray that a person declines, and the best case is that a bank
 * this app has never heard of still shows up as a payment waiting to be confirmed rather
 * than as silence.
 */
val GENERIC_PATTERNS: List<PatternSpec> = listOf(
    PatternSpec(
        id = "GENERIC.amount_first.v1",
        bank = "",
        tier = Tier.GENERIC,
        direction = null,
        // "Rs.500.00 debited from A/c XX1234 on 01-01-26" and its credit twin.
        regex = ci("""$CURRENCY\s*$A\s+(?<dir>debited|credited|deducted|withdrawn|deposited)\s+(?:from|to|in|into)\s+(?:your\s+)?A/[cC](?:\s*no\.?)?\s*$ACCT(?:\s+on\s+$DATE)?"""),
    ),
    PatternSpec(
        id = "GENERIC.account_first.v1",
        bank = "",
        tier = Tier.GENERIC,
        direction = null,
        // "A/c XX1234 is debited with Rs.500.00 on 01-01-26" and its credit twin.
        regex = ci("""A/[cC](?:\s*no\.?)?\s*$ACCT\s+(?:is\s+|has\s+been\s+)?(?<dir>debited|credited)\s+(?:for|with|by)\s+$CURRENCY\s*$A(?:\s+on\s+$DATE)?"""),
    ),
    PatternSpec(
        id = "GENERIC.sent_from.v1",
        bank = "",
        tier = Tier.GENERIC,
        direction = Direction.DEBIT,
        // "Sent Rs.440.00 from A/c *4512 on 17-07-26 to SOMEONE"
        regex = ci("""Sent\s+$CURRENCY\s*$A\s+from\s+(?:A/[cC]\s*)?$ACCT(?:\s+on\s+$DATE)?(?:\s+to\s+(?<payee>[^.]+?)[.,])?"""),
    ),
    PatternSpec(
        id = "GENERIC.card_spend.v1",
        bank = "",
        tier = Tier.GENERIC,
        direction = Direction.DEBIT,
        // "INR 1,250.00 spent on <anything> Card XX1234 ..." - the card wording is stable
        // across issuers even where the rest of the sentence is not.
        regex = ci("""$CURRENCY\s*$A\s+spent\s+(?:at\s+(?<payee>.+?)\s+)?on\s+(?:your\s+)?[A-Za-z ]{0,30}Card\s+(?:no\.?\s*|ending\s+(?:with\s+)?)?$ACCT"""),
        balanceMeaning = BalanceMeaning.AVAILABLE_CREDIT,
        isCard = true,
    ),
)
