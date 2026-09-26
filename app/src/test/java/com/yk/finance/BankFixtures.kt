package com.yk.finance

import com.yk.finance.parser.Direction

/**
 * One researched message, with what the parser must make of it.
 *
 * This table is simultaneously the test corpus and the evidence record. [source] is not a
 * comment - it is the citation that makes the pattern legitimate, and a fixture without one
 * has no business here. The prose version, with the full provenance of each sample and the
 * sources that were examined and rejected, is in `docs/bank-sms-formats.md`.
 */
data class Fixture(
    val patternId: String,
    val sender: String,
    val body: String,
    val bank: String,
    val direction: Direction,
    val amountPaise: Long,
    val account: String,
    val payee: String? = null,
    val isCard: Boolean = false,
    val source: String,
)

/** A message that must never become a transaction, and why. */
data class NegativeFixture(
    val sender: String,
    val body: String,
    val why: String,
)

private const val PW = "pennywiseai-tracker parser-core test fixtures"
private const val DUMP = "shivarya/expense-tracker-scraper data/sms-export.json"
private const val TXNVAULT = "yassk29/TxnVault test/unit/sms_parsers_test.dart"

val BANK_FIXTURES: List<Fixture> = listOf(
    // ----- HDFC -----
    Fixture(
        "HDFC.upi_debit.v1", "AD-HDFCBK",
        "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)",
        "HDFC", Direction.DEBIT, 50_000, "1234", payee = "merchant@upi", source = PW,
    ),
    Fixture(
        "HDFC.upi_sent.v1", "AD-HDFCBK",
        "Sent Rs.15000.00 From HDFC Bank A/C *1234 To TEST MERCHANT PVT LTD On 01/01/26 " +
            "Ref 567890567890 Not You? Call 18005556789/SMS BLOCK UPI to 7305556789",
        "HDFC", Direction.DEBIT, 1_500_000, "1234", payee = "TEST MERCHANT PVT LTD", source = PW,
    ),
    Fixture(
        "HDFC.neft_credit.v1", "VM-HDFCBK",
        "Update! INR 1.00 deposited in HDFC Bank A/c XX9999 on 30-MAR-26 for NEFT " +
            "Cr-CITI0100000-ACME TECHNOLOGIES-PERSON NAME-CITIN99999999999.Avl bal INR 8.00. " +
            "Cheque deposits in A/C are subject to clearing",
        "HDFC", Direction.CREDIT, 100, "9999", source = PW,
    ),

    // ----- Axis -----
    Fixture(
        "AXIS.card_spend.v1", "JD-AXISBK-S",
        "Spent INR 131 Axis Bank Card no. XX0818 05-10-25 09:43:27 IST Swiggy Limi " +
            "Avl Limit: INR 217162.72 Not you? SMS BLOCK 0818 to 919951860002",
        "AXIS", Direction.DEBIT, 13_100, "0818", payee = "Swiggy Limi", isCard = true, source = PW,
    ),
    Fixture(
        "AXIS.card_spend.v2", "JD-AXISBK-S",
        "Spent Card no. XX7441 INR 562 01-09-25 12:04:18 AVENUE SUPE Avl Lmt INR 5120.87 " +
            "SMS BLOCK 7441 to 919951860002, if not you - Axis Bank",
        "AXIS", Direction.DEBIT, 56_200, "7441", payee = "AVENUE SUPE", isCard = true, source = PW,
    ),
    Fixture(
        "AXIS.account_debit.v1", "AD-AXISBK-S",
        "INR 2000.00 debited from A/c no. XX589034 on AXIS BANK L 04-11-2025 16:06:39 IST. " +
            "Avl bal: INR 98919.81. Not you? SMS BLOCKCARD XX0192 to +919951860002 - Axis Bank",
        "AXIS", Direction.DEBIT, 200_000, "589034", payee = "AXIS BANK L", source = PW,
    ),

    // ----- Kotak -----
    Fixture(
        "KOTAK.upi_sent.v1", "VM-KOTAKB",
        "Sent Rs.15.00 from Kotak Bank AC X1234 to paytmqr288005050101t74afkchmxjd@paytm " +
            "on 14-10-25.UPI Ref 1234567890. Not you, https://kotak.com/KBANKT/Fraud",
        "KOTAK", Direction.DEBIT, 1_500, "1234",
        payee = "paytmqr288005050101t74afkchmxjd@paytm", source = PW,
    ),
    Fixture(
        "KOTAK.upi_sent.v2", "VM-KOTAKD",
        "Sent Rs.51.00 from XXXXXX9722 to DINESHBHAI RAMJIBHAI on 30/04/2026. " +
            "UPI ref no. 648604626824. Not you? Tap https://kotk.in/KOTAKD/Eg9Cu0 to report -Kotak",
        "KOTAK", Direction.DEBIT, 5_100, "9722", payee = "DINESHBHAI RAMJIBHAI", source = PW,
    ),
    Fixture(
        "KOTAK.upi_received.v1", "VM-KOTAKB",
        "Received Rs.250.00 in your Kotak Bank AC X3333 from john.doe@oksbi on 14-10-25." +
            "UPI Ref 2222222222. Not you, https://kotak.com/KBANKT/Fraud",
        "KOTAK", Direction.CREDIT, 25_000, "3333", payee = "john.doe@oksbi", source = PW,
    ),
    Fixture(
        "KOTAK.card_spend.v1", "VM-KOTAKB",
        "INR 20 spent on Kotak Credit Card x5236 on 23-JAN-2026 at UPI-638903921672-CORN. " +
            "Avl limit INR 73733.02 Fraud? https://www.kotak.bank.in/KBANKT/querytxn",
        "KOTAK", Direction.DEBIT, 2_000, "5236", isCard = true, source = PW,
    ),

    // ----- SBI -----
    Fixture(
        "SBI.account_debit.v1", "AD-SBIINB-S",
        "Rs.500 debited from A/c X1234 on 13Sep25. Avl Bal Rs.999999999",
        "SBI", Direction.DEBIT, 50_000, "1234", source = PW,
    ),
    Fixture(
        "SBI.card_spend.v1", "AD-SBICRD",
        "Rs.259.00 spent on your SBI Credit Card ending with 1234 on 15Jan26. " +
            "Your available limit is Rs.1,235.00. If not done by you, call 39 02 02 02.",
        "SBICARD", Direction.DEBIT, 25_900, "1234", isCard = true, source = PW,
    ),

    // ----- IndusInd -----
    Fixture(
        "INDUSIND.upi_debit.v1", "TM-INDUSB-S",
        "A/c *XX1234 debited by Rs 1234.00 towards xxxx.yyyy@icici. RRN: 510048508040. " +
            "Not You? call 18602677777- IndusInd Bank.",
        "INDUSIND", Direction.DEBIT, 123_400, "1234", payee = "xxxx.yyyy@icici", source = PW,
    ),
    Fixture(
        "INDUSIND.upi_credit.v1", "TM-INDUSB-S",
        "A/C *XX1234 credited by Rs 25000.00 from xxxx.yyyy@ybl. RRN:510048508040. " +
            "Avl Bal:105502.12. Not you? Call 18602677777 - IndusInd bank.",
        "INDUSIND", Direction.CREDIT, 2_500_000, "1234", payee = "xxxx.yyyy@ybl", source = PW,
    ),
    Fixture(
        "INDUSIND.card_spend.v1", "TM-INDUSB-S",
        "INR 1,250.00 spent on IndusInd Card XX1234 on 14-06-2026 04:21:45 pm at INSTAMART. " +
            "Avl Lmt: INR 48,750.00. To dispute, call 18602677777/SMS BLOCK 1234 to 5676757",
        "INDUSIND", Direction.DEBIT, 125_000, "1234", payee = "INSTAMART", isCard = true, source = PW,
    ),

    // ----- Yes Bank -----
    Fixture(
        "YES.card_spend.v1", "VM-YESBNK",
        "INR 404.36 spent on YES BANK Card X3349 @UPI_C N S FUEL PORT 24-08-2025 06:17:25 pm. " +
            "Avl Lmt INR 211,476.24. SMS BLKCC 3349 to 9840909000 if not you",
        "YES", Direction.DEBIT, 40_436, "3349", isCard = true, source = PW,
    ),

    // ----- Bank of Baroda -----
    Fixture(
        "BOB.account_debit.v1", "AD-BOBTXN",
        "Rs.80.00 Dr. from A/c XX123456 on 12-11-2024. AvlBal:Rs1234.56cx. Ref:52211012345 -Bank of Baroda",
        "BOB", Direction.DEBIT, 8_000, "123456", source = PW,
    ),
    Fixture(
        "BOB.account_credit.v1", "AD-BOBTXN",
        "Rs.5000.00 Credited to A/c XX112233 on 01-12-2024. AvlBal:Rs12345.67",
        "BOB", Direction.CREDIT, 500_000, "112233", source = PW,
    ),
    Fixture(
        "BOB.credit_with.v1", "AD-BOBSMS",
        "Your A/c XX987654 is credited with INR 70.00 on 30-11-2024. Total Bal:Rs.5000.00 -Bank of Baroda",
        "BOB", Direction.CREDIT, 7_000, "987654", source = PW,
    ),

    // ----- Bank of India -----
    Fixture(
        "BOI.upi_debit.v1", "JD-BOIIND",
        "Rs.200.00 debited A/cXX5468 and credited to SAI MISAL via UPI Ref No 315439383341 " +
            "on 23Aug25. Call 18001031906, if not done by you. -BOI",
        "BOI", Direction.DEBIT, 20_000, "5468", payee = "SAI MISAL", source = PW,
    ),
    Fixture(
        "BOI.neft_credit.v1", "JD-BOIIND",
        "BOI - Rs 15,000.00 Credited in your Ac XX5468 on 04-02-2026 By NEFTINWARD " +
            "HDFCH00778553836/HDFC MUTUAL F .Avl Bal 18679.91",
        "BOI", Direction.CREDIT, 1_500_000, "5468", source = PW,
    ),

    // ----- Indian Bank -----
    Fixture(
        "INDIANBANK.upi_sent.v1", "AD-INDBNK",
        "Sent Rs.440.00 from A/c *4512 on 17-07-26 to NARMADA FOODS.RRN 213416112187." +
            "Avl Bal Rs.4585.65.Not you? SMS BLOCK to 9289592895-Indian Bank",
        "INDIANBANK", Direction.DEBIT, 44_000, "4512", payee = "NARMADA FOODS", source = PW,
    ),
    Fixture(
        "INDIANBANK.upi_credit.v1", "AD-INBUPI",
        "Rs.2.00 credited to a/c *8175 on 07/10/2025 by a/c linked to VPA " +
            "poweraccess.paytm3@axisbank (UPI Ref no 981408452805).Indian Bank",
        "INDIANBANK", Direction.CREDIT, 200, "8175",
        payee = "poweraccess.paytm3@axisbank", source = PW,
    ),

    // ----- AU Small Finance Bank -----
    Fixture(
        "AU.account_debit.v1", "JM-AUBANK-S",
        "Debited INR 500.00 from A/c 1234567890 on 15-FEB-2026. Bal INR 10000.00. -AU Bank",
        "AU", Direction.DEBIT, 50_000, "1234567890", source = PW,
    ),
    Fixture(
        "AU.short_form.v1", "JM-AUBANK-S",
        "Dr INR 29,000.00 from A/c X7661 on 05-MAY-2026",
        "AU", Direction.DEBIT, 2_900_000, "7661", source = PW,
    ),

    // ----- Equitas -----
    Fixture(
        "EQUITAS.upi_debit.v1", "VM-EQUITS",
        "INR 500.00 debited via UPI from Equitas A/c 1234 -Ref:571987071234 on 19-12-25 " +
            "to JOHN DOE. Avl Bal is INR 15,000.50.Not U?Call 18001031222/SMS BLOCK UPI/BLOCK ACT 1233 to 7045030000.",
        "EQUITAS", Direction.DEBIT, 50_000, "1234", payee = "JOHN DOE", source = PW,
    ),
    Fixture(
        "EQUITAS.upi_credit.v1", "VM-EQUITS",
        "INR 2,000.00 credited via UPI to Equitas A/c 9012 -Ref:123456789012 on 18-01-26 " +
            "from EMPLOYER NAME. Avl Bal is INR 25,000.00.Not U?Call 18001031222.",
        "EQUITAS", Direction.CREDIT, 200_000, "9012", payee = "EMPLOYER NAME", source = PW,
    ),

    // ----- IDFC FIRST -----
    Fixture(
        "IDFC.neft_debit.v1", "JD-IDFCFB-S",
        "IDFC FIRST Bank A/c XX7696 debited with Rs 5,000.00 on 07/01/26 via " +
            "NEFT/IDFBH26007759490. New bal Rs.1,38,202.65. To raise Dispute, call 180010888",
        "IDFC", Direction.DEBIT, 500_000, "7696", source = DUMP,
    ),
    Fixture(
        "IDFC.upi_debit.v1", "JM-IDFCFB-S",
        "Your A/c XX4614 debited by Rs. 1,172.06 on 15/01/26; REDBUS credited. " +
            "RRN 060649915527. Available balance Rs. 9,134.15. Team IDFC FIRST Bank",
        "IDFC", Direction.DEBIT, 117_206, "4614", payee = "REDBUS", source = PW,
    ),
    Fixture(
        "IDFC.account_credit.v1", "AX-IDFCFB-S",
        "Your A/C XXXXX037696 is credited with INR 25,000.00 on 26/12/25 06:24. " +
            "Your new balance is INR 1,42,938.65. Team IDFC FIRST Bank",
        "IDFC", Direction.CREDIT, 2_500_000, "037696", source = DUMP,
    ),
    Fixture(
        "IDFC.card_spend.v1", "CP-IDFCFB-S",
        "Delicious Purchase! INR 499.00 spent on your IDFC FIRST Bank Credit Card ending " +
            "XX3663 at RAJASTHALI on 30 AUG 2026 at 03:45 PM Avbl Limit: INR 341865.91 " +
            "If not done by you, call 180010888 for dispute or to block your card SMS CCBLOCK 3663 to 5676732",
        "IDFC", Direction.DEBIT, 49_900, "3663", payee = "RAJASTHALI", isCard = true, source = TXNVAULT,
    ),

    // ----- Federal -----
    Fixture(
        "FEDERAL.neft_debit.v1", "AD-FEDBNK-T",
        "Debited Rs 6000 from a/c XX3343 on 24JUN2026 21:35 via NEFT to Jerry." +
            "Ref FDRLM4175007432.Bal Rs 76.82.Not you?Call 18004251199 -Federal Bank",
        "FEDERAL", Direction.DEBIT, 600_000, "3343", payee = "Jerry", source = PW,
    ),
    Fixture(
        "FEDERAL.imps_credit.v1", "VM-FEDBNK",
        "Rs.316000 credited to your A/c XX2172 via IMPS on 16JUN2024 10:48:12. " +
            "(IMPS Ref no-416810603307) BAL-Rs.321634.31 -Federal Bank",
        "FEDERAL", Direction.CREDIT, 31_600_000, "2172", payee = "IMPS", source = TXNVAULT,
    ),
    Fixture(
        "FEDERAL.cash_deposit.v1", "AX-FEDBNK-S",
        "Hi,Rs.1100credited in your A/c XX3223 on 15JAN2026 13:03:07 using cash deposit " +
            "machine at FBL-CHANGANASSERY. Current Bal: Rs.1181.90 - Federal Bank",
        "FEDERAL", Direction.CREDIT, 110_000, "3223", source = PW,
    ),

    // ----- RBL (card only) -----
    Fixture(
        "RBL.card_spend.v1", "JD-RBLCRD-S",
        "INR9,139.00 spent at METRO BRANDS LIMITED on RBL Bank credit card (6089) " +
            "on 24-01-2026.AVL limit- INR284,236.23. Not you? Call 02262327777 ",
        "RBL", Direction.DEBIT, 913_900, "6089", payee = "METRO BRANDS LIMITED",
        isCard = true, source = DUMP,
    ),

    // ----- Bandhan -----
    Fixture(
        "BANDHAN.upi_debit.v1", "XY-BDNSMS-S",
        "INR 180.00 debited from A/c XXXXXXXXXX1234 towards UPI/DR/D123013240123/Amazon Pa " +
            "Value 16-NOV-2025 . Clear Bal is INR 9999.99. Bandhan Bank",
        "BANDHAN", Direction.DEBIT, 18_000, "1234", payee = "Amazon Pa", source = PW,
    ),
    Fixture(
        "BANDHAN.upi_credit.v1", "XY-BDNSMS-S",
        "INR 25,000.00 deposited to A/c XXXXXXXXXX1234 towards UPI/CR/C224513287910/JOHN DOE/u " +
            "on 03-OCT-2025 . Clear Bal is INR 30,123.00 . Bandhan Bank.",
        "BANDHAN", Direction.CREDIT, 2_500_000, "1234", source = PW,
    ),

    // ----- IDBI -----
    Fixture(
        "IDBI.upi_debit.v1", "AD-IDBIBK",
        "IDBI Bank Acct XX569 debited for Rs 115.00 on 09-Jun-26; Bal Rs 11631.73 " +
            "SRINIVASAN R credited. UPI:002733174962. To Block UPI send SMS UPIBLOCK " +
            "<Mob. No> to 07799000328 or call 18002094324-IDBI Bank",
        "IDBI", Direction.DEBIT, 11_500, "569", payee = "SRINIVASAN R",
        source = "naveendgp/Money-Management test/sms_parser_test.dart",
    ),

    // ----- South Indian Bank -----
    Fixture(
        "SIB.upi_debit.v1", "VM-SIBSMS-S",
        "UPI debit:Rs.599.00 A/c X7477, 16-10-25 16:25:29 RRN: 565526068910 Bal:Rs.12345.89 " +
            "Block A/c? Call18004251809/SMS BLK<A/c>to 9840777222-South Indian Bank",
        "SIB", Direction.DEBIT, 59_900, "7477", source = PW,
    ),
    Fixture(
        "SIB.imps_credit.v1", "VM-SIBSMS-S",
        "Dear Customer, Your A/c X7377 is credited with Rs.792.02 Info: " +
            "IMPS/FDRL/528005821348/EPIFI ACCOUN. Final balance is Rs.793.02-South Indian Bank",
        "SIB", Direction.CREDIT, 79_202, "7377", payee = "EPIFI ACCOUN", source = PW,
    ),
    // ----- ICICI, the shapes beyond the two that arrive on this phone -----
    Fixture(
        "ICICI.acc_debit.v1", "VM-ICICIB",
        "ICICI Bank Acc XX921 debited Rs. 10,000.00 on 20-Jan-26 NFSCASH WDL. Avb Bal Rs. 3,943.84. " +
            "To dispute Call 18002662 or SMS BLOCK 921 to 9215676766 .",
        "ICICI", Direction.DEBIT, 1_000_000, "921", payee = "NFSCASH WDL", source = PW,
    ),
    Fixture(
        "ICICI.account_credit.v1", "VM-ICICIB",
        "ICICI Bank Account XX566 credited:Rs. 18,832.00 on 28-Feb-25. " +
            "Info INF*000169831922*IQBO SAL FE. Available Balance is Rs. 28,076.14.",
        "ICICI", Direction.CREDIT, 1_883_200, "566",
        payee = "INF*000169831922*IQBO SAL FE", source = PW,
    ),
    Fixture(
        "ICICI.own_transfer_debit.v1", "VM-ICICIB",
        "ICICI Bank Acct XX123 debited with Rs 10 on 20-Dec-25 & Acct XX456 credited." +
            "IMPS:ABCDEF123456. Call 18002662 for dispute or SMS BLOCK 700 to 9215676766",
        "ICICI", Direction.DEBIT, 1_000, "123", source = PW,
    ),
    Fixture(
        "ICICI.card_spend.v1", "AD-ICICTC",
        "INR 500.00 spent using ICICI Bank Card XX5678 on 06-Sep-25 on Swiggy. Avl Limit: INR 1,50,000.00.",
        "ICICI", Direction.DEBIT, 50_000, "5678", payee = "Swiggy", isCard = true, source = PW,
    ),
)

/**
 * Messages that must never reach the ledger.
 *
 * Every one is a real message, and every one is shaped enough like a transaction that some
 * plausible regex would take it. The OTP entry is the sharpest: RBL sends it about
 * twenty-five seconds before the genuine spend alert for the same purchase, carrying the
 * same amount and the same merchant, so accepting it would double every card transaction.
 */
val NEGATIVE_FIXTURES: List<NegativeFixture> = listOf(
    NegativeFixture(
        "AD-RBLBNK-T",
        "678415 is OTP for txn of INR 659.30 at BOOK MY SH on RBL Bank Credit Card 6089. " +
            "Valid for one time use. DO NOT SHARE IT WITH ANYONE.",
        "an OTP quoting the amount it authorises; the real spend alert follows 25s later",
    ),
    NegativeFixture(
        "JD-RBLBNK-S",
        "ALERT: Credit limit exceeded. Your transaction for INR 1,793.00 at ROLLA HYPER MARKET " +
            "was declined as you have exceeded the limit set by you on your RBL Bank Credit Card " +
            "(xx6089). To reset your credit limit, visit https://acl.cc/RBLBNK/6R4OK5E9 ",
        "declined - no money moved",
    ),
    NegativeFixture(
        "JK-RBLCRD-P",
        "Shopping plans or surprise bills? Rs.50000.0 loan on RBL Bank Credit Card (xx45) " +
            "can cover it - all with a few taps.",
        "a loan offer, not a loan",
    ),
    NegativeFixture(
        "AD-HDFCBK",
        "New Bill Alert: Your XUBA00000TST1A Bill 1234567890 of Rs.1500.00 is due on 15-Jan-2026. " +
            "To pay, login to HDFC Bank Net/Mobile Banking>BillPay T&C. Ignore if paid",
        "a bill that is due, not one that was paid",
    ),
    NegativeFixture(
        "AD-HDFCBK",
        "Auto Pay HDFC Bank NACH Mandate : Rs. 100000.00 UMRN:HDFC7031703262015557 " +
            "To:NationalSecuritiesClearin Freq ADHO received today for processing.",
        "a mandate registration; the ceiling is not a charge",
    ),
    NegativeFixture(
        "JG-FEDBNK",
        "Dear Customer, You have successfully created a mandate on SAAVN for a ASPRESENTED " +
            "frequency starting from 01-07-2024 for a maximum amount of Rs 89.00 Mandate Ref No- " +
            "a0365ebd6e54468e9b6052407de01371@pingpay - Federal Bank",
        "mandate creation - Rs 89.00 is a cap",
    ),
    NegativeFixture(
        "AD-FEDBNK-S",
        "Hi, txn of Rs. 1500.00 using card XX**3456 failed due to insufficient funds. " +
            "Current Bal: Rs.250.75. Call 18004251199 if txn not initiated by you -Federal Bank",
        "the transaction failed",
    ),
    NegativeFixture(
        "AD-IDFCFB-S",
        "Your ASBA application for AUGMONT is received and Application value of Rs 14972 " +
            "is blocked in your registered Bank account on 25/08/2026.",
        "money blocked for a share application, not spent",
    ),
    NegativeFixture(
        "JD-IDFCFB-S",
        "Dear Customer, Your beneficiary has received Rs 5,000.00 transferred via NEFT " +
            "IDFBH26007759490. Team IDFC First Bank.",
        "the counterparty's copy of a debit already recorded, same reference",
    ),
    NegativeFixture(
        "CP-FEDBNK-S",
        "Jerry Joseph has received Rs 6000.000 from your A/c XX3343 via NEFT on " +
            "24-06-2026 22:04:04. Ref no. FDRLM4175007432 - Federal Bank",
        "confirmation of a transfer already booked from our side",
    ),
    NegativeFixture(
        "VM-YESBNK",
        "Payment request of INR 500.00 from merchant@upi. Ignore if already paid.",
        "a request for money, not a movement of it",
    ),
    NegativeFixture(
        "VM-YESBNK",
        "Your Yes Bank Credit Card payment of INR 10,000 is due by 25-08-2025",
        "a due date",
    ),
    NegativeFixture(
        "+919876543210",
        "Your A/c XX1234 is debited Rs.49,999.00. If not you, call 9876543210 immediately.",
        "transaction-shaped, but from a personal number - the classic smishing shape",
    ),
    NegativeFixture(
        "VK-FAKEBK",
        "Your A/c XX1234 is debited with Rs.49,999.00 on 01-01-26. If not you, call us.",
        "an unregistered header must not reach even the generic tier",
    ),
    NegativeFixture(
        "VM-ICICIB",
        "Payment of Rs 26,266.00 has been received on your ICICI Bank Credit Card XX9006 " +
            "through Bharat Bill Payment System on 06-DEC-25.",
        "a card bill being paid is not income",
    ),
    NegativeFixture(
        "VM-ICICIB",
        "ICICI BANK NEFT Transaction with reference number IN12603221231681 for Rs. 22050.00 " +
            "has been credited to the beneficiary account on 01-02-2026 at 10:32:51",
        "the counterparty's copy of a transfer we already recorded",
    ),
    NegativeFixture(
        "VM-ICICIB",
        "Your account will be debited with Rs 649.00 on 03-Oct-25 towards Netflix Entertainment " +
            "Ser for AutoPay MERCHANTMANDATE, RRN 421723106963-ICICI Bank.",
        "an autopay that has not run yet",
    ),
    NegativeFixture(
        "VM-ICICIB",
        "USD 11.80 spent using ICICI Bank Card XX7004 on 03-Sep-25 on 1xJetBrains AI . " +
            "Avl Limit: INR 17,95,899.53. If not you, call 1800 2662/SMS BLOCK 7004 to 9215676766.",
        "a foreign-currency spend must not be booked as rupees",
    ),
)
