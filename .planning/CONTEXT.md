# Phase Context: SMS Parser Fix, Generic Extraction, and Template Learning

## Problem & User Intent
The user reported that the SMS parser fails on real Union Bank transaction alerts (such as `Union Bank of India A/c *2209 Debited Rs:1755.00 on 22-10-2026 10:40:05 by Mob Bk ref no 629576250805, Fvg: AONE TRA Avl Bal Rs:5328.67. Not you?Call 18002333/SMS BLOCK 2209 to 8879365472`), landing in the Inbox tray as `Not recorded: no rule matched for UNION`. Furthermore, when messages land in the inbox unread, the user wants Money in / Money out pre-selected, safe pre-filling of amount/payee, and when recorded manually, the system must learn from the user's inputs so future messages of that format parse automatically across time changes, disclaimer variations, and directions.

## Mental Model & Core Abstractions
- **Transaction Direction**: Transactions are either `Direction.DEBIT` ("Money out") or `Direction.CREDIT` ("Money in"). Both the built-in parser and the learned template engine must distinguish and handle both.
- **Payee Extraction**: Indian bank SMS formats frequently name the payee with `Fvg:` ("Favoring"), `to <payee>`, or `VPA <vpa>`. This must work generically across banks rather than being bank-siloed.
- **Safety / Disclaimer Trailers**: Banks append varying fraud/helpline disclaimers (`Not you? Call...`, `SMS BLOCK...`, `Never share OTP...`). These must not break parsing or become frozen literal substrings in learned regex templates.
- **Conservative Heuristics**: Pre-filling in the UI must start less aggressive (safe/conservative) to avoid high-risk false positives, while still eliminating friction for clear transactions.
- **Template Learning Loop**: When an unparsed message is manually recorded by the user, `TemplateLearner.learn` generates an executable regex pattern. It must slot variables (amount, account token, date, time, ref, payee, bal, direction) so future messages with different timestamps, amounts, and dates are recognized without human intervention.

## Locked Architectural Decisions
- **Union Rule Enhancement**: Support optional `for` in `(Debited|Credited)\s+(?:for\s+)?(?:Rs|INR)\.?:?\s*`, allow `Union Bank of India` or `A/c` prefix, and extract `Fvg:\s*([^,.\n]+)` as payee.
- **Generic Payee & Trailing Cleaner**: Add generic detection helpers in parser primitives for `Fvg:` and common payee structures, and strip or make optional fraud disclaimers.
- **Inbox Review Form Pre-filling**: In `InboxScreen.kt`, safely initialize:
  - `amount`: first well-formed currency amount in the body.
  - `isDebit`: boolean determined by explicit debit vs credit keywords in the body (defaulting to true only if unspecified).
  - `payee`: extracted if clear `Fvg:` or `to` indicator is present, else blank.
- **TemplateLearner Dynamic Slots**:
  - Add `TIME_REGEX` slot so timestamps do not freeze as literal text.
  - Add direction regex slot (`(?<dir>Debited|Credited|Debit|Credit)`) so one learned template can match both Money in and Money out.
  - Relax or make optional trailing disclaimer text after standard balance/reference.
- **TDD Verification**: All parser additions, inbox heuristics, and template learner cases must have automated unit tests.

## Verification & Backpressure Seams
- Unit Test Suite: `gradle testDebugUnitTest`
- Debug APK Build: `gradle assembleDebug`
