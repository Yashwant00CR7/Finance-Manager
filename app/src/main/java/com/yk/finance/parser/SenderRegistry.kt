package com.yk.finance.parser

/**
 * Which bank a DLT sender header belongs to.
 *
 * ## What a header actually looks like
 *
 * India's TRAI mandates the form `XY-ABCDEF`: `X` names the originating telco, `Y` its
 * licensed service area, and `ABCDEF` is the header the bank registered. Since February
 * 2025 a category suffix may follow - `-P` promotional, `-S` service, `-T` transactional,
 * `-G` government - appended by the telco during scrubbing, so a full header can read
 * `AX-HDFCBK-S`.
 *
 * ## Three findings that shape this file
 *
 * **The `XY-` prefix is noise.** It identifies the network the bank's aggregator happened to
 * inject through, which changes between messages. HDFC alone has been observed as `AD-`,
 * `VM-`, `JD-`, `VD-`, `AX-`, `VK-`, `BZ-` and `CP-HDFCBK`. Only the middle token is matched.
 *
 * **The suffix is optional in practice.** Rollout was uneven and a 2025 corpus shows
 * `TM-SBIINB` and `TM-SBIINB-S` side by side, so it is stripped and never required. It is
 * also no guide to trust: `-T` is the OTP category, which is exactly what OTP phishing wears.
 *
 * **Never fuzzy-match.** `CANBNK` and `CAANBK` are *both* genuinely Canara Bank; `HDFCBK`
 * and `HDFCBN` are *both* genuinely HDFC. Edit distance would accept forgeries while still
 * missing homoglyph attacks, so matching is exact against the list below.
 *
 * ## What a match proves
 *
 * Only that the string was spelled correctly. DLT governs who may *use* a header; it does
 * not sign the message, and forged headers do arrive - through offshore routes, rogue
 * resellers, and SMS blasters that force a 2G downgrade and never touch a telco at all.
 * A recognised header is therefore a precondition for the generic tier, never a reason to
 * trust a message. TRAI has blacklisted 800+ entities for misusing registered headers.
 *
 * Every entry below is from TRAI's own published registry
 * (`List_SMS_Headers_16062020_0.xlsx`, 23,192 rows), except the handful marked as observed
 * in real messages. Headers circulating in hobby-project allowlists with no authoritative
 * or observational backing - `BANDHN`, `YESBK`, `FEDERL`, `CANARA`, `HDFCBANK` and friends -
 * are deliberately absent; several are hallucinations copy-pasted between repositories.
 *
 * The registry snapshot is dated June 2020, so absence here is not evidence a header is
 * fake. Genuine later registrations such as `ICICIT` and `FEDSCP` are included separately
 * where real messages confirmed them.
 */
private val HEADER_TO_BANK: Map<String, String> = buildMap {
    fun bank(name: String, vararg headers: String) = headers.forEach { put(it.uppercase(), name) }

    // Verified banks - the author's own accounts. Their patterns live in BankRules.kt.
    bank("ICICI", "ICICIB", "ICICBK", "ICIBNK", "ICBANK", "ICICIH", "ICICIK", "ICICIL",
        "ICICTC", "ICIEMP", "ICIOTP", "142421", "142424",
        // Observed in a real SMS backup dump under three different prefixes; post-dates
        // the 2020 registry snapshot.
        "ICICIT")
    bank("UNION", "UNIONB", "100026")

    // Researched banks, in the order their patterns appear in BankPatterns.kt.
    bank("HDFC", "HDFCBK", "HDFCBN", "HDFCBA", "HDFCAL", "HDFCCC", "HDFCDC", "HDFCFD",
        "HDFCGC", "HDFCHI", "HDFCHL", "HDFCIT", "HDFCLI", "HDFCPL", "HDFCRD", "HDFCSD",
        "HDFCSE", "HDFCUN", "146587")
    bank("AXIS", "AXISBK", "AXISB", "AXISHR", "AXISIN", "AXISMR", "AXISPR", "AXISSR",
        "AXSFI", "AXSFIN")
    bank("KOTAK", "KOTAKB", "KOTAKP", "KBANKT", "KTKREM", "KOTAKD",
        "100811", "111000", "111888", "189766")
    bank("SBI", "SBIINB", "SBIUPI", "SBIPSG", "ATMSBI", "SBIATM", "SBIBNK", "CBSSBI",
        "SBIOTP", "SBIPAY", "SBYONO", "SBIDBT", "SBIKYC", "SBIWEB", "SBICRS", "SBIQCK",
        "SBIDGT")
    // SBI Cards is a separate legal entity from State Bank of India, and its messages are
    // about a card, not a deposit account. Kept apart so they resolve to different accounts.
    bank("SBICARD", "SBICRD")
    bank("INDUSIND", "INDUSB", "INDUSA", "INDUSO", "126666", "127777")
    bank("YES", "YESBNK", "YESBCC", "YESBCM", "YESPAY")
    bank("BOB", "BOBSMS", "BOBTXN", "BOBUPI", "BOBOTP", "BOBBNK", "BOBBIZ", "BOBCMS",
        "BOBCRM", "BOBFRM", "BOBMSG", "BOBRAJ", "BOBSCE", "BOBSCF", "BOBTRE", "BOBUPG")
    bank("BOI", "BOIIND", "BOIBAL", "BOIINT", "BOIJGB", "BOILON", "BOINJG", "BOIREM",
        "BOISAF", "BOISME", "BOIVKG", "126995")
    bank("INDIANBANK", "INDBNK", "INBUPI")
    bank("AU", "AUBANK", "AUBMSG", "AUBSMS", "AUDOST", "AUITSM",
        "120012", "121200", "180012", "181200", "181212")
    bank("IDFC", "IDFCFB", "IDFCBK", "IDFCCM", "IDFCFZ", "IDFCIT", "IDFCTS", "IDFCZ")
    bank("FEDERAL", "FEDBNK", "FEDADV", "FEDOTP", "181818",
        // Scapia co-brand card; observed in real messages, not in the 2020 snapshot.
        "FEDSCP")
    bank("RBL", "RBLBNK", "RATNAK", "RBLCRD", "RBLCCC", "RBLBBB", "SPRCRD")
    bank("BANDHAN", "BNDNBK", "BDNSMS", "BNDNHL", "154321")
    bank("IDBI", "IDBIBK", "IDBIDL", "111444")
    // Must be matched as a whole token; "SBI" is a substring of "SIBSMS".
    bank("SIB", "SIBSMS")

    // Banks with a known header but no pattern yet. Listing them is still worth doing: it
    // lets the generic tier read their messages into the tray instead of discarding them,
    // and the tray is where the next pattern comes from.
    bank("PNB", "PNBSMS", "PNBOTP", "PNBCRD", "PNBCCD", "PNBCRM", "PNBDBD", "PNBHRD",
        "PNBJNK", "PNBLKO", "PNBMKT", "PNBRTS", "PNBTBD")
    bank("CANARA", "CANBNK", "CAANBK", "CANMNY", "CANRRB", "CANRWD", "110111")
    bank("CENTRALBANK", "CENTBK", "CBIOTP")
    bank("IOB", "IOBANK", "IOBBNK", "IOBCHN", "IOBATM", "IOBBQR", "IOBOTP", "IOBHRD",
        "IOBJLS", "IOBMKT")
    bank("UCO", "UCOBNK")
    bank("BOM", "MAHABK")
    bank("PSB", "PSBANK")
    // Equitas and Karnataka Bank are deliberately absent. No header for either appears in
    // TRAI's registry or in any message that was actually observed, and the candidates
    // circulating in hobby-project allowlists are exactly the kind of entry that turns out to
    // be a guess somebody copied. Equitas messages are still read, via the body mention below.
    bank("CUB", "CUBANK", "CUBFST", "CUBLTD", "CUBOTP", "CUBSMS", "CUBUPI", "111904")
    bank("AIRTELPB", "AIRBNK", "AIRBSE", "AIRBSI", "171717", "177177", "650017", "650137")
    bank("IPPB", "MYIPPB", "IPBCOM", "IPBKYC", "IPBMSG", "IPBOFR", "IPBOTP", "IPBSEC")
    bank("UJJIVAN", "UJJIVN")
    bank("CITI", "CITIBK")
    bank("SCB", "SCBANK")
}

private val PREFIXED = Regex("""^[A-Z]{2}-(.+)$""")
private val CATEGORY_SUFFIX = Regex("""-[PSTG]$""")

/**
 * Reduces a sender as the handset shows it to the token the bank registered.
 *
 * Uppercases first, because real headers arrive mixed-case (`AxisBK`, `FedFiB`) even though
 * the registry stores them uppercase.
 */
internal fun normalizeHeader(sender: String): String =
    sender.trim().uppercase()
        .let { PREFIXED.find(it)?.groupValues?.get(1) ?: it }
        .let { CATEGORY_SUFFIX.replace(it, "") }

/** The bank a sender header belongs to, or null if it is not one we recognise. */
fun bankForSender(sender: String): String? = HEADER_TO_BANK[normalizeHeader(sender)]

/**
 * Whether this sender is a header we have positively identified as a bank's.
 *
 * Needed as its own question because "does it contain a letter" is not a usable test: 11% of
 * registered headers are entirely numeric, including ICICI's `142421`, Kotak's `111000` and
 * Airtel Payments Bank's `171717`. Treating those as personal numbers would drop real alerts.
 */
fun isRegisteredBankHeader(sender: String): Boolean = bankForSender(sender) != null


/**
 * The bank a message names in its own text, for when the header does not settle it.
 *
 * TRAI's published registry is a 2020 snapshot and a bank may register a header at any time,
 * so a message can arrive from a perfectly genuine header this app has never heard of. Most
 * Indian alerts sign themselves - "-Federal Bank", "Team IDFC FIRST Bank", "from Equitas A/c"
 * - and that signature is enough to choose which bank's patterns to try.
 *
 * This is used ONLY to reach the researched tier, never the generic one. The distinction is
 * the whole safety argument: a researched pattern is a tight, bank-specific sentence that a
 * forged message would have to reproduce exactly, whereas the generic shapes are loose by
 * design and stay locked behind a registered header. A phishing SMS can write "Federal Bank"
 * in its body trivially; it gains nothing by doing so, because the pattern it would then face
 * still has to match.
 *
 * Order matters. "Indian Bank" is a substring of "South Indian Bank", and "SBI" appears in
 * every SBI Card message, so the more specific names are tested first.
 */
private val BODY_MENTIONS: List<Pair<Regex, String>> = listOf(
    Regex("""South\s+Indian\s+Bank""", RegexOption.IGNORE_CASE) to "SIB",
    Regex("""SBI\s+(?:Credit\s+)?Card""", RegexOption.IGNORE_CASE) to "SBICARD",
    Regex("""IDFC\s+FIRST\s+Bank""", RegexOption.IGNORE_CASE) to "IDFC",
    Regex("""Bank\s+of\s+Baroda|BOBCARD""", RegexOption.IGNORE_CASE) to "BOB",
    Regex("""Bank\s+of\s+India|\bBOI\b""", RegexOption.IGNORE_CASE) to "BOI",
    Regex("""\bIndian\s+Bank\b""", RegexOption.IGNORE_CASE) to "INDIANBANK",
    Regex("""IndusInd""", RegexOption.IGNORE_CASE) to "INDUSIND",
    Regex("""HDFC\s+Bank""", RegexOption.IGNORE_CASE) to "HDFC",
    Regex("""Axis\s+Bank""", RegexOption.IGNORE_CASE) to "AXIS",
    Regex("""\bKotak\b""", RegexOption.IGNORE_CASE) to "KOTAK",
    Regex("""IDBI\s+Bank""", RegexOption.IGNORE_CASE) to "IDBI",
    Regex("""Federal\s+Bank""", RegexOption.IGNORE_CASE) to "FEDERAL",
    Regex("""RBL\s+Bank""", RegexOption.IGNORE_CASE) to "RBL",
    Regex("""Bandhan\s+Bank""", RegexOption.IGNORE_CASE) to "BANDHAN",
    Regex("""\bEquitas\b""", RegexOption.IGNORE_CASE) to "EQUITAS",
    Regex("""\bAU\s+Bank\b""", RegexOption.IGNORE_CASE) to "AU",
    Regex("""YES\s+BANK""", RegexOption.IGNORE_CASE) to "YES",
    Regex("""\bSBI\b""", RegexOption.IGNORE_CASE) to "SBI",
)

/** The bank a message names in its own text, or null. See [BODY_MENTIONS]. */
fun bankMentionedIn(body: String): String? =
    BODY_MENTIONS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(body) }?.second
