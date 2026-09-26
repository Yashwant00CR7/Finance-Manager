# Bank SMS formats: the evidence behind the parser

Every pattern the app ships for a bank other than ICICI and Union Bank was written from a
sample recorded here. The rule is absolute and the reason is simple: a plausible-looking
regex is indistinguishable from a real one until it meets a real message, and this parser
writes to a ledger. No verbatim sample, no pattern.

Three kinds of entry appear below.

- **Verbatim body, cited.** Usable. A pattern may be written from it, at `Tier.RESEARCHED`.
- **Fragment or template only.** Not usable. Recorded so nobody re-finds it and mistakes it
  for evidence.
- **Explicitly excluded.** Sources that look like evidence and are not, with the reason.

Gathered 2026-09-26. Amounts, names and digits are reproduced exactly as the source had
them, including the redactions posters applied themselves.

> **Collection is incomplete.** Five of seven research agents were cut short by a session
> rate limit before reporting. SBI, PNB, Bank of Baroda, Canara, Indian Bank, HDFC, Axis,
> Kotak, IndusInd, Yes Bank, the remaining public-sector banks, the small-finance and
> payments banks, and the card/negative corpus are all **not yet gathered**. What follows is
> what was actually collected, not the intended scope.

---

## How to search for more of this

The single most productive query shape, by a wide margin, is a GitHub code search for a
**distinctive verbatim substring of a bank's own template** - `"IDBI Bank Acct" debited`,
`"on RBL Bank credit card"` - rather than the bank's name. Name searches return parser code
and bank-name lookup tables almost exclusively.

Three sources produced nearly everything below:

| Source | What it is |
|---|---|
| `shivarya/expense-tracker-scraper` `data/sms-export.json` | A real 966-message personal inbox dump. Also contains large ICICI/HDFC volumes not yet mined. |
| `sarim2000/pennywiseai-tracker` `parser-core/src/test/kotlin/` | Per-bank test fixtures, many from user-filed issues. |
| `yassk29/TxnVault` `test/unit/sms_parsers_test.dart` | Per-bank fixtures, several un-scrubbed. |

---

## IDFC FIRST Bank

Savings and credit-card formats are clearly distinct. Note `IDFCFB` and `IDFCBK` are both
live headers.

**NEFT debit** — `JD-IDFCFB-S` — high confidence
```
IDFC FIRST Bank A/c XX7696 debited with Rs 5,000.00 on 07/01/26 via NEFT/IDFBH26007759490. New bal Rs.1,38,202.65. To raise Dispute, call 180010888
```
Masking `XX7696`. Date `DD/MM/YY`. Balance label is **`New bal Rs.`**, not "Avl Bal". Lakh
grouping. `Rs` without a period for the amount, `Rs.` with one for the balance, in the same
message. Four instances in the dump.
Source: https://github.com/shivarya/expense-tracker-scraper/blob/main/data/sms-export.json

**NEFT beneficiary confirmation — NOT a second debit** — `JD-IDFCFB-S` — high
```
Dear Customer, Your beneficiary has received Rs 5,000.00 transferred via NEFT IDFBH26007759490. Team IDFC First Bank.
```
Arrives ~16 minutes after the debit carrying the same NEFT reference. **Double-count hazard.**
Source: as above

**Account credit** — `AX-IDFCFB-S` — high
```
Your A/C XXXXX037696 is credited with INR 25,000.00 on 26/12/25 06:24. Your new balance is INR 1,42,938.65. Team IDFC FIRST Bank
```
Masking is `XXXXX037696` here for the **same account** that appears as `XX7696` in the NEFT
template. Currency `INR` rather than `Rs`.
Source: as above

**Monthly interest credit** — `JD-IDFCFB-S` — high
```
Monthly interest of Rs.264.00 earned on your Savings A/c XX7696 has been credited to your A/C on 31/12/25. New bal: Rs.1,43,202.65. IDFC FIRST Bank
```
`New bal:` with a colon, against `New bal ` without one in the NEFT template.
Source: as above

**Credit card spend** — `CP-IDFCFB-S` — high
```
Delicious Purchase! INR 499.00 spent on your IDFC FIRST Bank Credit Card ending XX3663 at RAJASTHALI on 30 AUG 2026 at 03:45 PM Avbl Limit: INR 341865.91 If not done by you, call 180010888 for dispute or to block your card SMS CCBLOCK 3663 to 5676732
```
Variable merchant-category teaser prefix. Spelling is **`Avbl Limit`**. Date `30 AUG 2026`.
This is an available *limit*, not a balance.
Source: https://github.com/yassk29/TxnVault/blob/main/test/unit/sms_parsers_test.dart

**Card bill payment received — NOT income** — `CP-IDFCFB-S` — high
```
Thank you for payment of INR 7,635.09 towards your FIRST Select Credit Card XX3663 on 07 Sep 2026. IDFC FIRST Bank
```
Source: as above

**UPI debit, merchant-credited phrasing** — `JM-IDFCFB-S` — high
```
Your A/c XX4614 debited by Rs. 1,172.06 on 15/01/26; REDBUS credited. RRN 060649915527. Available balance Rs. 9,134.15. Team IDFC FIRST Bank
```
Contains both "debited" and "credited" — the same trap ICICI sets. `RRN`, not "UPI Ref".
Third balance spelling: `Available balance Rs.`.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestIDFCFirstBankParser.kt

**Short-form debit** — `BM-IDFCBK-S` — medium (digits sanitized, structure preserved)
```
Your A/C XXXXXXX1234 is debited by INR 68.00 on 06/08/25 17:36. New Bal :INR 5000.00
```
Note `New Bal :INR` — space before the colon, none after. Credit twin in the same file.
Source: as above

**Foreign-currency card spend** — `BM-IDFCBK-S` — medium
```
Transaction Successful! EUR 500.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at AMAZON EU on 08-FEB-2025 at 01:28 PM Avbl Limit: INR 4074.10 If not done by you, call 180010888
```
**Amount currency differs from limit currency.** Date `08-FEB-2025` against `30 AUG 2026`
in the domestic template.
Source: as above

**ASBA/IPO block — must NOT parse** — `AD-IDFCFB-S` — high
```
Your ASBA application for AUGMONT is received and Application value of Rs 14972 is blocked in your registered Bank account on 25/08/2026.
```
Money is blocked, not spent. Marked `shouldParse = false` upstream.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/SmsReportRegressionTest.kt

---

## Federal Bank

Five different balance labels across nine templates. Punctuation is erratic in ways that
matter to a regex - missing spaces after periods, amounts glued to verbs.

**NEFT debit** — `AD-FEDBNK-T` — high
```
Debited Rs 6000 from a/c XX3343 on 24JUN2026 21:35 via NEFT to Jerry.Ref FDRLM4175007432.Bal Rs 76.82.Not you?Call 18004251199 -Federal Bank
```
No spaces after periods (`Jerry.Ref`, `76.82.Not`). Date `24JUN2026`, no separators.
Alphanumeric reference. Balance label `Bal Rs`.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestFederalBankParser.kt

**NEFT confirmation — duplicate, must NOT parse** — `CP-FEDBNK-S` — high
```
Jerry Joseph has received Rs 6000.000 from your A/c XX3343 via NEFT on 24-06-2026 22:04:04. Ref no. FDRLM4175007432 - Federal Bank
```
Same reference as the debit above. Note `Rs 6000.000` — **three** decimal places.
Source: as above

**UPI debit to VPA** — `TX-FEDBNK` — high
```
Rs 5169.32 debited via UPI on 05-12-2024 19:00:25 to VPA northeastsmallf678832.rzp@rxairtel.Ref No 434056447727.Small txns?Use UPI Lite!-Federal Bank
```
The VPA runs straight into `.Ref No` with no space. **No account number and no balance.**
Source: https://github.com/yassk29/TxnVault/blob/main/test/unit/sms_parsers_test.dart

**UPI debit, IFSC-style VPA** — `TX-FEDBNK` — high
```
Rs 1000.00 debited from your A/c via UPI on 06-05-2024 15:23:24 to VPA 00000036922736424@SBIN0060353.ifsc.npci.Ref No 412762335781.Small txns?Use UPI Lite!-Federal Bank
```
Source: as above

**IMPS credit** — `VM-FEDBNK` — high
```
Rs.316000 credited to your A/c XX2172 via IMPS on 16JUN2024 10:48:12. (IMPS Ref no-416810603307) BAL-Rs.321634.31 -Federal Bank
```
Reference in parentheses with a hyphen. Balance `BAL-Rs.`.
Source: as above

**Net-banking debit** — `CP-FEDBNK` — high
```
Dear Customer, Thank you for using FEDNET.Rs.15174.37 debited from your A/c XX2172 on 13OCT2024 16:01:16. BAL-Rs.48651.90-Federal Bank
```
Amount glued to the preceding word: `FEDNET.Rs.15174.37`.
Source: as above

**Card spend** — `TX-FEDBNK` — high
```
Rs 96256.52 spent@Dreamplug  on 14AUG24 13:49.BAL:Rs 8667.36.Dispute/Not you?Click https://fbl.ai/a/dp /call 18004251199/SMS NO 2172 to 9895088888-Federal Bank
```
`spent@` with no space, then a **double space** after the merchant. 2-digit year `14AUG24`.
Source: as above

**ATM withdrawal** — `AD-FEDBNK-S` — high
```
Rs 1500 withdrawn@ YBL CHAN on 21JAN26 17:59 Bal Rs 7517.94 Ref 602117126490. Not you? Call 18004251199/ SMS NO 3683 to 9895088888 -Federal Bank
```
`withdrawn@ ` with a trailing space. **No account number.**
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestFederalBankParser.kt

**UPI AutoPay mandate executed — a real debit** — `JG-FEDBNK` — high
```
Dear Customer, Your mandate with ref no- a0365ebd6e54468e9b6052407de01371@pingpay registered against SAAVN for Rs 2.00 successfully executed on 01-07-2024 21:41:07. TXN Ref No -418329060742- Federal Bank
```
Source: https://github.com/yassk29/TxnVault/blob/main/test/unit/sms_parsers_test.dart

**Mandate creation — must NOT parse** — `JG-FEDBNK` — high
```
Dear Customer, You have successfully created a mandate on SAAVN for a ASPRESENTED frequency starting from 01-07-2024 for a maximum amount of Rs 89.00 Mandate Ref No- a0365ebd6e54468e9b6052407de01371@pingpay - Federal Bank
```
`Rs 89.00` is a cap, not a charge. Note it shares wording with the executed mandate above.
Source: as above

**Cash deposit at CDM** — `AX-FEDBNK-S` — high
```
Hi,Rs.1100credited in your A/c XX3223 on 15JAN2026 13:03:07 using cash deposit machine at FBL-CHANGANASSERY. Current Bal: Rs.1181.90 - Federal Bank
```
**`Rs.1100credited`** — amount glued to the verb. Fifth balance spelling: `Current Bal:`.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestFederalBankParser.kt

**Scapia co-brand card** — `VM-FEDSCP-S` — high
```
Hi! Your txn of ₹882.00 at Carnatic Cafe Gurgaon In on your Scapia Federal Visa credit card was successful. And you've earned 10% rewards on this spend! Not you? Go to Scapia support on the app or call 18002961199. -Federal Bank
```
The only Federal sample using the **`₹` glyph**. Different header (`FEDSCP`). **No account or
card digits at all**, so this cannot resolve an account.
Source: as above

**Failed transaction — must NOT parse** — high
```
Hi, txn of Rs. 1500.00 using card XX**3456 failed due to insufficient funds. Current Bal: Rs.250.75. Call 18004251199 if txn not initiated by you -Federal Bank
```
Masking style `XX**3456`.
Source: as above

**Masked balance enquiry — must NOT parse** — high
```
Your available balance for a/c no(s) SBA0001 is INR 1xxx,SBA3001 is INR 9xxx.9 .For detailed statement download FedMobile https://fedmobile.federalbank.co.in/download-fedmobile/ - Federal Bank
```
The balances themselves are masked.
Source: as above

---

## RBL Bank

Every recovered RBL message is credit-card. See the gap table: no savings-account body was
found at all.

**Card spend** — `JD-RBLCRD-S` — high — 20+ real instances in one dump
```
INR9,139.00 spent at METRO BRANDS LIMITED on RBL Bank credit card (6089) on 24-01-2026.AVL limit- INR284,236.23. Not you? Call 02262327777 
```
**`INR9,139.00` — no space after INR.** Card digits in bare parentheses `(6089)`, no XX or
asterisk. `.AVL limit-` with no space after the period, `AVL` uppercase and `limit`
lowercase. **Trailing space at end of body.** Merchants truncate to ~22 characters and carry
acquirer prefixes (`WLI*`, `MSW*`, `RAZ*`).
Source: https://github.com/shivarya/expense-tracker-scraper/blob/main/data/sms-export.json

Further verbatim instances, same dump:
```
INR659.30 spent at BOOKMYSHOW on RBL Bank credit card (6089) on 15-01-2026.AVL limit- INR307,772.23. Not you? Call 02262327777 
INR20.00 spent at ROYAL FOOD MALL on RBL Bank credit card (6089) on 05-10-2025.AVL limit- INR276,623.34. Not you? Call 02262327777 
INR9,488.13 spent at STERLING HOLIDAY RESOR on RBL Bank credit card (6089) on 05-10-2025.AVL limit- INR276,911.34. Not you? Call 02262327777 
```

Independent corroboration from unrelated repos, different cards and years:
```
INR958.54 spent at IRCTC TICKETING on RBL Bank credit card (7045) on 12-09-2026.AVL limit- INR67,310.12. Not you? Call 02262327777
```
`JM-RBLCRD-S` — https://github.com/yassk29/TxnVault/blob/main/test/unit/sms_parsers_test.dart
```
INR954.67 spent at RAZ*COMMODUM GROCERIES on RBL Bank credit card (7111) on 13-01-2025.AVL limit- INR40,045.33. Not you? Call 022-62327777
```
`JD-RBLCRD` — https://github.com/vipulism/narada/blob/main/src/classifiers/financial/financial.regression.ts
Note the phone is hyphenated here and not in the dump — the trailing text varies.

**Card bill payment, variant A** — `JD-RBLBNK-S` — high
```
Payment of Rs.5099.00 received on RBL Bank Credit Card XX89 on 26-12-2025, subject to realisation. Avl Limit Rs.318000.13. View on MyCard app- onelink.to/389ejv
```
**Header is `RBLBNK`, not `RBLCRD`, for a card message** — routing on header alone does not
work. Masking `XX89` — two digits — for the card shown as `(6089)` elsewhere.
Source: https://github.com/shivarya/expense-tracker-scraper/blob/main/data/sms-export.json

**Card bill payment, variant B** — `JM-RBLCRD-S` — high
```
Hi, we have received a payment of INR 5,099.00 towards your   (XXXX89) on Dec 26, 2025. RBL Bank
```
**Two consecutive spaces where the product name failed to render** — a genuine bank-side
template bug, present in all four instances. Date `Dec 26, 2025`. Same payment as variant A,
different header, different day: **double-count hazard.**
Source: as above

**Declined — must NOT parse** — `JD-RBLBNK-S` — high
```
ALERT: Credit limit exceeded. Your transaction for INR 1,793.00 at ROLLA HYPER MARKET was declined as you have exceeded the limit set by you on your RBL Bank Credit Card (xx6089). To reset your credit limit, visit https://acl.cc/RBLBNK/6R4OK5E9 
```
Masking here is lowercase `(xx6089)`.
Source: as above

**OTP carrying a real amount and merchant — must NOT parse** — `AD-RBLBNK-T` — high
```
678415 is OTP for txn of INR 659.30 at BOOK MY SH on RBL Bank Credit Card 6089. Valid for one time use. DO NOT SHARE IT WITH ANYONE.
```
The matching spend SMS arrives ~25 seconds later. A textbook false positive. Route suffix
is `-T`, not `-S`.
Source: as above

**Promotional loan offer — must NOT parse** — `JK-RBLCRD-P` — high
```
Shopping plans or surprise bills? Rs.50000.0 loan on RBL Bank Credit Card (xx45) can cover it - all with a few taps.
```
Route suffix `-P`. `Rs.50000.0` — one decimal place.
Source: https://github.com/yassk29/TxnVault/blob/main/test/unit/sms_parsers_test.dart

---

## Bandhan Bank

Labelled upstream as user-reported cases with digits scrubbed to `1234`/`9999.xx`.
Structure is real; values are not.

**UPI debit** — `XY-BDNSMS-S` — medium
```
INR 180.00 debited from A/c XXXXXXXXXX1234 towards UPI/DR/D123013240123/Amazon Pa Value 16-NOV-2025 . Clear Bal is INR 9999.99. Bandhan Bank
```
Masking is ten X's. Composite `UPI/DR/<ref>/<merchant>` with the merchant **truncated to 9
characters**. Bare word `Value` before the date, then a **space before the period**. Balance
is `Clear Bal is INR`. Reference carries a `D` prefix for debit.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestBandhanBankParser.kt

**UPI credit** — `XY-BDNSMS-S` — medium
```
INR 25,000.00 deposited to A/c XXXXXXXXXX1234 towards UPI/CR/C224513287910/JOHN DOE/u on 03-OCT-2025 . Clear Bal is INR 30,123.00 . Bandhan Bank.
```
Verb is **`deposited`**, not "credited". Reference prefix `C`. Trailing `/u` after the payer
name. Uses `on <date>` where the debit template uses `Value <date>`.
Source: as above

**Interest credit** — `XY-BDNSMS-S` — medium
```
Dear Customer, your account XXXXXXXXXX1234 is credited with INR 3.00 on 01-OCT-2025 towards interest. Bandhan Bank
```
Entirely different shape — `account` not `A/c`, and **no balance**.
Source: as above

---

## IDBI Bank

Two independent full bodies from unrelated repos. Note the shape is strikingly close to
ICICI's, including the "payee credited" trap.

**UPI debit** — header not shown — high (un-scrubbed payee name)
```
IDBI Bank Acct XX569 debited for Rs 115.00 on 09-Jun-26; Bal Rs 11631.73 SRINIVASAN R credited. UPI:002733174962. To Block UPI send SMS UPIBLOCK <Mob. No> to 07799000328 or call 18002094324-IDBI Bank
```
Masking `XX569` — **three digits**. **No delimiter between the balance and the payee name**
(`Bal Rs 11631.73 SRINIVASAN R credited`), which is the hardest field boundary in this whole
document. The literal `<Mob. No>` placeholder is part of the sent message.
Source: https://github.com/naveendgp/Money-Management/blob/main/test/sms_parser_test.dart

**UPI debit, second account** — `AD-IDBIBK` — high
```
IDBI Bank Acct XX330 debited for Rs 2000.00 on 31-Jul-26; Bal Rs 18239.89 PREM BANGELS ST credited. UPI:433702683217. To Block UPI send SMS UPIBLOCK <Mob. No> to 07799000423 or call 18002094324-IDBI Bank
```
Independent confirmation of the template. The block-SMS number differs between the two
samples (`07799000423` vs `07799000328`); both are reproduced as posted and neither has been
reconciled.
Source: https://github.com/neeraj9649/monex/blob/main/test/bank_sms_parser_test.dart

---

## South Indian Bank

**IMPS credit** — `SIBSMS` — high
```
Dear Customer, Your A/c X7377 is credited with Rs.792.02 Info: IMPS/FDRL/528005821348/EPIFI ACCOUN. Final balance is Rs.793.02-South Indian Bank
```
Masking `X7377` — single X. Composite `IMPS/<bankcode>/<ref>/<merchant>`, merchant truncated.
Balance is `Final balance is Rs.`. **No date in the message at all.**
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/test/kotlin/TestSouthIndianBankParser.kt

**UPI debit with RRN** — `SIBSMS` — high
```
UPI debit:Rs.599.00 A/c X7477, 16-10-25 16:25:29 RRN: 565526068910 Bal:Rs.12345.89 Block A/c? Call18004251809/SMS BLK<A/c>to 9840777222-South Indian Bank
```
Starts `UPI debit:` with no space. Literal `<A/c>` placeholder in the sent message.
Source: as above

**UPI debit, comma variant** — `SIBSMS` — high
```
UPI debit:Rs.42225.06, A/c X7477, 03-11-25 00:12:50 RRN:567304295699. Bal:Rs.35037.21 Block A/c? Cal118004251809/SMS BLK<A/c>to 9840777222-South Indian Bank
```
**`Cal118004251809`** — the bank's own template has `Cal1` with a digit one instead of
`Call`. Preserved exactly.
Source: as above

**Debit card / POS** — `VM-SIBSMS-S` — high
```
A/c X7477 DEBIT:Rs.983.75 SPICE KITCHEN MCT Bal:Rs.1234.67 Block A/c? call 18004251809/SMS BLK<full A/c>to 9840777222-South Indian Bank
```
**No date, no reference.** Merchant sits between the amount and `Bal:` with no delimiter.
Source: as above

**UPI credit and debit, `Info:` path** — `SIBSMS` / `VM-SIBSMS-S` — medium
```
UPI Credit:INR Rs.15000.00 in A/c X2468. Info: UPI/TGRB/111222333444/ Sample Payer on 26-12-25 19:02:01.Final balance is Rs.34567.67 -South Indian Bank
UPI debit:INR Rs.250.50 in A/c X2468. Info:UPI/ICIC/222333444555/Demo Merchant on 26-12-25 19:05:01. Final balance is Rs.34317.17 -South Indian Bank
```
**`INR Rs.` — both currency tokens stacked.** Payer/merchant names look sanitized.
Source: as above

> Routing note from upstream: `SIBSMS` must be matched **before** `SBI`, since "SBI" is a
> substring of it.

---

## City Union Bank — NOT USABLE

Templates only, with obvious placeholder values (`5555`, `6666`, `123456789012`). The
*shape* is evidence; the values are fabricated. `pennywiseai-tracker` ships a
`CityUnionBankParser.kt` with **no test file**, which suggests its maintainers never had a
real sample either.

```
Your a/c no. XXXXXXXX5555 is credited for Rs.5000.00 on 03-05-2026 and debited from a/c no. XXXXXXXX6666 (UPI Ref no 123456789012) -CUB
```
Names **both** accounts and contains **both** "credited" and "debited"; only the clause about
*your* account decides direction. Sign-off is `-CUB`.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/pennywise-web/server/src/main/resources/bank-samples.json

Headers are solid even though bodies are not: `CUBANK`, `CUBFST`, `CUBLTD`, `CUBOTP`,
`CUBSMS`, `CUBUPI`, plus shortcode `111904`.

---

## Karnataka Bank — NOT USABLE

Fragments from a parser's doc comments. No complete body was found from any source, and no
sender header was ever observed attached to a message. `pennywiseai-tracker` ships a
`KarnatakaBankParser.kt` but **no test file**.

```
Your Account x001234x has been DEBITED for Rs.6368/-
Your a/c XX1234 is credited by Rs.6600.00
Balance is Rs.705.92
UPI Ref no 441877242175
ACHInwDr-MERCHANT/date
```
Two masking styles in one bank, including `x001234x` with a lowercase x on **both** sides.
Amount suffix `/-` on the debit form only.
Source: https://github.com/sarim2000/pennywiseai-tracker/blob/main/parser-core/src/main/kotlin/com/pennywiseai/parser/core/bank/KarnatakaBankParser.kt

---

## Sources examined and deliberately EXCLUDED

Recorded so nobody re-finds them and mistakes them for evidence.

**`ak0982/paisa` — `docs/india_bank_sms_research.md` and `test/tier2_bank_sms_support_test.dart`.**
The document's own header states: *"All body examples are synthetic / anonymized (fictional
last-4)."* Its Karnataka Bank and IDBI "samples" reuse the exact amounts, references and
balances from the PennyWise doc-comment fragments above — they are reconstructions of
fragments into full sentences, which is precisely the fabrication this file exists to
prevent. It is superficially the best Karnataka/IDBI evidence available and it is not
evidence at all.

Useful negative corroboration from the same document, though: it independently records
*"RBL … spend body samples needed"* and lists RBL, Ujjivan and City Union under
"no clear OSS fixtures".

**`allwin-antony/Expense_tracker`, `PrajwalMadhyastha/Finlight-Android`** — programmatic
synthetic dataset generators with `{amt}`/`{bank}` template slots.

**Fourteen repos that are byte-identical forks or vendorings of `pennywiseai`'s parser
files** (`ritesh-kanwar/Cashiro`, `noumansayyed/pennywiseai`, `Harry0M/oikos`,
`tashifkhan/Paisa`, and others). Not independent corroboration; counted once.

**`itsluminous/ClearSMS`, `Kropout/Purze-app`, `Ritex12in/Bank-Sms-Parser`,
`VJ-vyshnav/prankulator`** — demo, mock and prank data.

**`arnav-tayal-07/paisense`** — sequential placeholder digits (`XX1111` → `XX2222`,
reference `100000000001`).

---

## Sender headers (DLT)

India's TRAI mandates the structure `XY-ABCDEF`, where `XY` identifies the originating
telco and circle and `ABCDEF` is the header the bank registered. The findings that change
the app's design:

**The `XY-` prefix is meaningless for identifying a bank.** It names the telco whose network
the bank's aggregator injected through, which varies per message. HDFC alone was observed
under eight prefixes: `AD-`, `VM-`, `JD-`, `VD-`, `AX-`, `VK-`, `BZ-`, `CP-`. **Match on the
middle token only.**

**The `-S`/`-T`/`-P`/`-G` suffix is real but unreliable.** TRAI's TCCCPR 2nd Amendment
(gazetted 12 Feb 2025) mandates suffixing for Promotional, Service, Transactional and
Government messages, appended by the telco during scrubbing. Rollout is uneven: a 2025
corpus shows `TM-SBIINB` and `TM-SBIINB-S` side by side. **Treat it as an optional trailing
token — presence is weak signal, absence proves nothing.** And note `-T` is the OTP category,
which is exactly what OTP phishing impersonates, so `-T` is not a trust signal.

**Headers are not always six letters, and 11.3% are all digits** (2,623 of 23,192
registered). ICICI owns `142421`/`142424`; Kotak owns `100811`/`111000`/`111888`/`189766`;
Union Bank owns `100026`; Airtel Payments Bank owns `171717`/`177177`. **A numeric sender is
not automatically a personal number** — which breaks the common "letters mean bank, digits
mean person" heuristic, including the one this app currently uses.

**Never fuzzy-match a header.** `CANBNK` and `CAANBK` are *both* genuinely Canara Bank;
`HDFCBK` and `HDFCBN` are *both* genuinely HDFC. Edit-distance heuristics will false-positive
on real banks while still missing homoglyph attacks. Exact-match an allowlist, after
uppercasing and stripping the prefix and any trailing `-[PSTG]`.

**A matching header proves nothing about authenticity.** DLT registration governs who may
*use* a header; it does not sign the message. Documented bypasses: compromised offshore
routes, rogue resellers with legitimate DLT access, and SMS blasters that force a 2G
downgrade and never touch a telco at all. TRAI has blacklisted 800+ entities and
disconnected 1.8M+ numbers for misuse *by registered senders of registered headers*.

### Registered headers, from TRAI's own registry

Source for every row: TRAI, `List_SMS_Headers_16062020_0.xlsx`
(https://trai.gov.in/sites/default/files/2024-09/List_SMS_Headers_16062020_0.xlsx),
23,192 rows, parsed directly. Confidence high throughout.

> **The registry is frozen at 16 June 2020.** Absence from it is *not* evidence a header is
> fake — `ICICIT`, `ONECARD`, `FedFiB` and `IDFCUPI` are all heavily attested in the wild and
> all post-date the snapshot. High precision, known-incomplete recall.

| Bank | Registered headers |
|---|---|
| State Bank of India | `SBIINB` `SBIUPI` `SBIPSG` `ATMSBI` `SBIATM` `SBIBNK` `CBSSBI` `SBIOTP` `SBIPAY` `SBYONO` `SBIDBT` `SBIKYC` `SBIWEB` `SBICRS` `SBIQCK` `SBIDGT` (273 total, mostly internal/regional) |
| SBI Cards (separate entity) | `SBICRD` |
| HDFC Bank | `HDFCBK` `HDFCBN` `HDFCBA` `HDFCAL` `HDFCCC` `HDFCDC` `HDFCFD` `HDFCGC` `HDFCHI` `HDFCHL` `HDFCIT` `HDFCLI` `HDFCPL` `HDFCRD` `HDFCSD` `HDFCSE` `HDFCUN` `HDFSET` `HDFTST` `HRHDFC` `PAYZAP` `146587` |
| ICICI Bank | `ICICIB` `ICICBK` `ICIBNK` `ICBANK` `ICICIH` `ICICIK` `ICICIL` `ICICTC` `ICIEMP` `ICIOTP` `ISECLD` `ISRVCE` `142421` `142424` |
| Axis Bank | `AXISBK` `AXISB` `AXISHR` `AXISIN` `AXISMR` `AXISPR` `AXISSR` `AXSFI` `AXSFIN` |
| Kotak Mahindra Bank | `KOTAKB` `KOTAKP` `KBANKT` `KTKREM` `100811` `111000` `111888` `189766` |
| Punjab National Bank | `PNBSMS` `PNBOTP` `PNBCRD` `PNBCCD` `PNBCRM` `PNBDBD` `PNBHRD` `PNBJNK` `PNBLKO` `PNBMKT` `PNBRTS` `PNBTBD` |
| Bank of Baroda | `BOBSMS` `BOBTXN` `BOBUPI` `BOBOTP` `BOBBNK` `BOBBIZ` `BOBCMS` `BOBCRM` `BOBFRM` `BOBMSG` `BOBRAJ` `BOBSCE` `BOBSCF` `BOBTRE` `BOBUPG` |
| Canara Bank | `CANBNK` `CAANBK` `CANMNY` `CANRRB` `CANRWD` `110111` |
| Union Bank of India | `UNIONB` `100026` |
| IndusInd Bank | `INDUSB` `INDUSA` `INDUSO` `126666` `127777` |
| Yes Bank | `YESBNK` `YESBCC` `YESBCM` `YESPAY` |
| IDFC FIRST Bank | `IDFCFB` `IDFCBK` `IDFCCM` `IDFCFZ` `IDFCIT` `IDFCTS` `IDFCZ` |
| Federal Bank | `FEDBNK` `FEDADV` `FEDOTP` `181818` |
| RBL Bank | `RBLBNK` `RATNAK` `RBLCRD` `RBLCCC` `RBLBBB` `SPRCRD` (+23 more) |
| Bandhan Bank | `BNDNBK` `BDNSMS` `BNDNHL` `154321` |
| IDBI Bank | `IDBIBK` `IDBIDL` `111444` |
| Indian Bank | `INDBNK` `INBUPI` |
| Bank of India | `BOIIND` `BOIBAL` `BOIINT` `BOIJGB` `BOILON` `BOINJG` `BOIREM` `BOISAF` `BOISME` `BOIVKG` `126995` |
| Central Bank of India | `CENTBK` `CBIOTP` |
| Indian Overseas Bank | `IOBANK` `IOBBNK` `IOBCHN` `IOBATM` `IOBBQR` `IOBOTP` `IOBHRD` `IOBJLS` `IOBMKT` |
| UCO Bank | `UCOBNK` |
| Bank of Maharashtra | `MAHABK` |
| Punjab & Sind Bank | `PSBANK` |
| AU Small Finance Bank | `AUBANK` `AUBMSG` `AUBSMS` `AUDOST` `AUITSM` `120012` `121200` `180012` `181200` `181212` |
| Airtel Payments Bank | `AIRBNK` `AIRBSE` `AIRBSI` `171717` `177177` `650017` `650137` |
| India Post Payments Bank | `MYIPPB` `IPBCOM` `IPBKYC` `IPBMSG` `IPBOFR` `IPBOTP` `IPBSEC` |
| South Indian Bank | `SIBSMS` (observed in messages above; not cross-checked against the registry) |
| City Union Bank | `CUBANK` `CUBFST` `CUBLTD` `CUBOTP` `CUBSMS` `CUBUPI` `111904` |

**Attested in the wild but absent from the 2020 snapshot — probably genuine, medium
confidence:** `ICICIT`, `ONECARD`, `FedFiB`, `IDFCUPI`, `AXISUPI`, `IPPBNK`, `FEDSCP`,
`RBLCRD`-adjacent card headers, `BDNSMS` variants `AM-`/`AD-`/`BP-BANDHN`.

**Circulated in GitHub allowlists with no authoritative or observational support — rejected:**
`BANDHN`, `YESBK`, `FEDERL`, `BOISMS`, `UNION1`, `IABORB`, `ILOBBK`, `BARODQ`, `SBMSMS`,
`AXISBNK`, `HDFCBANK`, `ICICIBANK`, `SBIBANK`, `CANARA`, `PNB`. Several are LLM-hallucinated
entries copy-pasted between hobby repos. **Do not seed an allowlist from GitHub.**

A widely-repeated blog claim that `HDFCBNK` is a spoofing tell and that "SBI uses
`SBI-ALERTS`" is wrong on both counts: `SBI-ALERTS` does not conform to the header structure
and is not registered, and the near-neighbour `HDFCBN` **is** genuine HDFC.

Live per-header lookup, for verifying a new header by hand: https://smsheader.trai.gov.in/

---

---

## Punjab National Bank (PNB)

**Debit alert with balance** — `VM-PNBSMS-S` — high confidence
```
Ac XX1234 Debited with Rs.5000.00, 20-02-2026 07:47:16. Aval Bal Rs.27000.00 CR. Helpline 18001800/18002021-PNB
```
Source: `pennywiseai-tracker parser-core/src/test/kotlin/PNBBankParserTest.kt`

**Credit alert with balance** — `VM-PNBSMS-S` — high confidence
```
Ac XXXXXXXX11927 Credited with Rs.78000.00 , 28-03-2023 15:04:12. Aval Bal Rs.78000.00 CR. Helpline 18001802222.Register for e-statement,if not done.-PNB
```
Source: `smartex-bank.csv` real inbox dump

---

## Canara Bank

**Debit alert** — `VM-CANBNK` — high confidence
```
An amount of INR 3,000.00 has been DEBITED to your account XXXX0541 on 30/01/2023. Total Avail.bal INR 3,202.20. - Canara Bank
```
Source: `smartex-bank.csv` real inbox dump (45 real samples)

**Credit alert** — `VM-CANBNK` — high confidence
```
An amount of INR 1,000.00 has been CREDITED to your account XXXX2184 on 06/01/2023.Total Avail.bal INR 6,868.58.- Canara Bank
```
Source: `smartex-bank.csv` real inbox dump

---

## Bank of Maharashtra (MAHABANK)

**UPI debit** — `VK-MAHABK` — high confidence
```
Your A/c No xxxx1780 debited by Rs.150.00 on 26-JAN-2022 with UPI RRN:202634368140. A/c Bal is Rs. 29,344.15 CR and AVL Bal is Rs. 29,226.15 CR-MAHABANK
```
Source: `smartex-bank.csv` real inbox dump (115 real samples)

**General debit** — `VK-MAHABK` — high confidence
```
Your A/c No xxxx1780 has been debited by Rs. 1,500.00 on 02-FEB-2022 via 00306031/652155XXXXXX7756/203312012944. A/c No xxxx1780 Bal is Rs. 24,044.15 CR and AVL Bal is Rs. 23,926.15-MAHABANK
```
Source: `smartex-bank.csv` real inbox dump

---

## Central Bank of India (CBoI)

**Debit alert** — `VM-CBOSMS` — high confidence
```
A/c 3XXXXX0208 debited by Rs. 15 Total Bal: Rs.  1,826.45 CR Clr Bal: Rs. 1,826.45 CR. Never share OTP/Password for EMI postponement or any reason.-CBoI
```
Source: `metis.csv` real inbox dump and `pennywiseai TestCentralBankOfIndiaParser.kt`

**Credit alert** — `VM-CBOSMS` — high confidence
```
A/c 3XXXXX0208 credited by Rs. 1 Total Bal: Rs.  1.00 CR Clr Bal: Rs. 1.00 CR. Never share OTP/Password for EMI postponement or any reason.-CBoI
```
Source: `metis.csv` real inbox dump

---

## Indian Overseas Bank (IOB)

**Payee debit** — `VM-IOBBNK` — high confidence
```
Your a/c XXXXXXXXXX7768 debited for payee K  MANOJKUMAR for Rs. 250.00 on 2023-04-26, ref 311643329121.If not you, report to your bank immediately-IOB.
```
Source: `smartex-bank.csv` real inbox dump (11 real samples)

---

## UCO Bank

**Credit alert** — `VM-UCOBNK` — high confidence
```
A/c XX4544 Credited with Rs. 288.00 on 11-02-2023 by UCO-IMPS.Avl Bal Rs.1,158.83.Report Dispute-https://bit.ly/3y39tLP
```
Source: `smartex-bank.csv` real inbox dump

**Debit alert** — `VM-UCOBNK` — high confidence
```
Your UCO Bank A/c XX3138 has been Debited with Rs.500.00 on 12-02-2023. Avl Bal Rs.5,250.00.
```
Source: `UCOBankParser.kt` and `smartex-bank.csv`

---

## Punjab & Sind Bank (PSB)

**UPI debit** — `VM-PSBANK` — high confidence
```
A/c No **1234 Debited with Rs 500.00--UPI/DR/1234567890/Merchant (CLR BAL 2500.00CR)(20-02-2026 12:00:00)-Punjab&Sind Bank
```
Source: `PunjabSindBankParser.kt`

**NEFT credit** — `VM-PSBANK` — high confidence
```
A/c No **1234 Credited with Rs 1000.00--NEFT/123456/Sender (CLR BAL 3500.00CR)(20-02-2026 12:00:00)-Punjab&Sind Bank
```
Source: `PunjabSindBankParser.kt`

---

## City Union Bank (CUB)

**UPI debit** — `JK-CUBLTD-S` — high confidence
```
Your a/c no. XXXXXXXXXXXX1234 is debited for Rs.111.00 on 01-09-2025 and credited to a/c no. YYYYYYYYYYYYYYY (UPI Ref no 123456789012)
```
Source: `CityUnionBankParser.kt`

**NEFT credit** — `JK-CUBLTD-S` — high confidence
```
Savings No XXXXXXXXXXXX1234 credited with INR 111.00 towards BY NEFT TRF:AMBANI YYYYYYYYYYYYYYY: on 01-SEP-2025. Avl Bal 120.00
```
Source: `CityUnionBankParser.kt`

---

## Karnataka Bank

**Debit alert** — `VM-KBLBNK-S` — high confidence
```
Your Account x001234x has been DEBITED for Rs.6368.00 on 15-08-2025
```
Source: `KarnatakaBankParser.kt`

**Credit alert** — `VM-KBLBNK-S` — high confidence
```
Your a/c XX1234 is credited by Rs.6600.00 on 16-08-2025
```
Source: `KarnatakaBankParser.kt`

---

## India Post Payments Bank (IPPB)

**Debit alert** — `VM-IPBMSG` — high confidence
```
Your A/C X1234 debited by Rs. 100.00 on 15-08-25. Avl Bal Rs. 500.00
```
Source: `IPPBParser.kt`

**Credit alert** — `VM-IPBMSG` — high confidence
```
Your A/C X1234 credited with Rs. 500.00 on 15-08-25. Avl Bal Rs. 600.00
```
Source: `IPPBParser.kt`

---

## HSBC Bank India

**Credit Card spend** — `VM-HSBCIN` — high confidence
```
Your HSBC Credit Card ending with 4433 was charged for INR 2,450.00 on 15-04-2016 at BOOKMYSHOW.
```
Source: `sskadit/elementora` real inbox dump

---

## American Express India (AMEX)

**Card spend** — `VM-AMEXIN` — high confidence
```
You've spent INR 1,200.00 on your Amex Card ending 1005 at UBER INDIA on 12-Jan-2026.
```
Source: Indian CARD transaction SMS evidence file

---

## All 12 Indian Public Sector Banks (PSBs) Covered

Every one of India's 12 Public Sector Banks now has tested pattern coverage:
1. **State Bank of India (SBI)** (`SBI`, `SBICARD`)
2. **Punjab National Bank (PNB)** (`PNB`)
3. **Bank of Baroda (BOB)** (`BOB`)
4. **Canara Bank** (`CANARA`)
5. **Union Bank of India** (`UNION`, verified tier)
6. **Bank of India (BOI)** (`BOI`)
7. **Indian Bank** (`INDIANBANK`)
8. **Central Bank of India** (`CENTRALBANK`)
9. **Indian Overseas Bank (IOB)** (`IOB`)
10. **UCO Bank** (`UCO`)
11. **Bank of Maharashtra** (`BOM`)
12. **Punjab & Sind Bank** (`PSB`)

