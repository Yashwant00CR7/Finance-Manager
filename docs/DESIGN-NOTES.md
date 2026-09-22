# Design notes

Reference material for Finance Manager — the parts that matter when you are changing
the code rather than reading about it. The [README](../README.md) covers the idea and
the decisions; this covers the mechanics.

Current as of **v2.4.0** (versionCode 11, schema v5).

---

## Verification status

**343 tests across 43 classes. 0 failures, 0 skipped.**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
gradle :app:testDebugUnitTest
```

```
SplitterTest 33 · CycleHistoryTest 17 · ParserTest 14 · CategoryModelTest 13
DiffTest 13 · PeriodsMonthTest 13 · CsvExportTest 12 · NaiveBayesTest 12
CalculatorTest 11 · BudgetOverrideTest 10 · LedgerTotalsTest 10
BudgetMigrationTest 9 · FeaturesTest 9 · FilteredTotalsTest 9
BackfillBoundsTest 8 · CommitPartitionTest 8 · ImportPipelineTest 8
MigrationTest 8 · MigrationV4Test 8 · MoneyParserTest 8 · SeedKeywordTest 8
CategoryVocabularyTest 7 · CycleCalculatorTest 7 · DialectTest 7
RecordedAlertTest 7 · TimeParserTest 7 · CategoryTierTest 6 · ColumnMapTest 6
MigrationV5Test 6 · QueueExplanationTest 6 · TransferResolverTest 6
DirectionParserTest 5 · QuickAddTest 5 · RealExportTest 5 · CommitTransferTest 4
FingerprintTest 4 · CommitCategoryTest 3 · MatchWindowTest 3 · MergeShapeTest 2
ReportJsonTest 2 · RupeeEntryTest 2 · CashIdentityTest 1 · MigrationVersionTest 1
```

Every ICICI and Union assertion runs against real messages from the phone, and the
importer is tested against the real 226-row export rather than against a file written
to suit it.

`RealExportTest` fails rather than skips when the fixture is missing. A test that passes
by not running reports success for work it never did.

The category vocabulary is data rather than code, so `CategoryVocabularyTest` is the
only thing standing between a typo and a rename that silently creates a duplicate
category — it checks that every rename target exists and that no name is both renamed
away and seeded back, which would rename-then-recreate on every launch forever.

`CategoryTierTest` is the safety argument for the classifier, held as a test: a learned
rule beats a seed keyword beats the model, and an unconfident prediction is never filed.
It runs without a database, which is the point — the ordering is a claim worth being
able to check in isolation.

### Verified against real messages

ICICI debit/credit, Union debit/credit, the ICICI→Union self-transfer, balance
trailers, amount and date parsing, and rejection of fraud warnings, mandates, promos
and OTPs.

### Not verified — no real sample exists

- **ATM withdrawal format.** The layout is a guess (`Acct XX742 is debited with ...`).
  Channel detection keys on "ATM"/"WDL"/"CASH WDL" anywhere in the body, which is far
  more stable than the layout, so the guess is low-risk. If your first real withdrawal
  does not match, it lands in the review tray rather than being lost, and the fix is one
  line in `IciciRule.DEBIT_WITH_SYNTHETIC`.
- **Card/POS and credit-card formats.** No rules written.
- **Salary credit.** Tag your first salary in the ledger ("This is my salary") to teach
  the cycle anchor. Until then the cycle rolls on the computed last working day.
- **The app has never seen a real incoming SMS** — tested by direct invocation, not on a
  device.

---

## Checking an export (Import → pick a file)

Pick a CSV exported from another finance app. The importer runs for real — dialect
sniffing, column mapping, value parsing, transfer expansion, the adaptive matcher, and
the commit planner — and then reports. **Everything above the import button writes
nothing**, so a file can be checked as often as you like.

The point is rehearsal. The real import happens once and cannot be practised afterwards,
so it has to be practicable before, on real files, with no way to do harm.

What the report tells you:

- **Conservation** — `226 rows = 226 understood + 0 rejected`. If those ever fail to add
  up, the file was read wrongly and nothing else in the report is reliable.
- **How the columns were read**, including the ones kept but not used.
- **Accounts in the file**, with a dropdown to bind each name to an account here. The
  binding is saved as an alias on the account, so it holds for every future run.
- **The capture figure** — of the bank payments the other app recorded in the window,
  how many this app saw for itself — and the two lists behind it: missed by this app,
  and only in this app.

Four things that figure deliberately does *not* do:

- It never counts cash. The app cannot see cash, so a missing cash row proves nothing.
- It never measures days before the app was installed; the window opens at the later of
  the cycle start and the first transaction ever recorded.
- It refuses to call itself a measurement while any account name is unbound, because
  rows were being skipped.
- It never merges two candidates on a guess. Ambiguous rows are listed as ambiguous.

---

## Importing (the button at the bottom of that screen)

Two taps, and the second names what it is about to do. Before it will write anything it
checks two things and **refuses** rather than warning: that the row counts add up, and
that every account name in the file is bound. An unbound name means rows would be left
out without saying so.

Each row lands in exactly one outcome, and all of them are counted:

```
IMPORTED   written as a new transaction (a transfer becomes two legs)
MERGED     this app already had it from SMS; the file's note and category are
           copied onto the existing row — into empty fields only
QUEUED     ambiguous, or an account is unbound. Nothing written, reason kept
SKIPPED    already imported by an earlier run, matched on fingerprint
REJECTED   stages 1-4 could not understand it. Kept verbatim with its reason
```

**Merging never overwrites.** The bank's SMS already supplied the amount, date and
payee; the file adds only what the bank never said. Whatever you typed stays, and the
file's version is kept in `imported_rows.rawJson` regardless.

**Re-importing is safe.** Fingerprints are built from the date rather than the
timestamp, because a second export shifts minutes but never days. Repeated rows within
one file are numbered, so two separate ₹50 teas on the same day are two fingerprints,
not one — collapsing them would silently delete a real payment.

**Then set the real balances.** The imported history will not reach today's balance on
its own; the file covers a period, not all of history. Typing what each account actually
holds books the difference as a visible `ADJUSTMENT` row rather than correcting the
number behind your back.

**Undo** removes every transaction the import wrote, reverses the balances, and clears
the notes and categories it filled in. Merged rows are deliberately not tagged with the
batch id — undo must never delete a transaction the phone captured for itself. One
caveat: undoing a merge clears those fields whether or not you have since edited them.

---

## Backup, export, restore

**Drawer → Backup & restore.** Three buttons, two different files:

- **Save a backup** — the entire SQLite database. This is the one that restores. The
  write-ahead log is checkpointed first, so the copy is never missing the most recent
  transactions.
- **Export CSV** — readable, opens in any spreadsheet. It carries transactions only:
  balances, budgets and cycle state are not representable, so it is a report, not a
  backup. The column set deliberately matches what the importer will read.
- **Restore** — validated into a staging file before anything is replaced, so a wrong or
  truncated file cannot destroy a working ledger. Replaces everything and restarts.

**Automatic snapshots.** The app copies its own database aside immediately before it
upgrades the schema, and lists those snapshots under the same screen. Five are kept. An
import can be undone; a bad migration cannot, so it is guarded instead.

The snapshot is taken by opening the file with plain `SQLiteDatabase` to read its
version *without* running any migration — the only moment a pre-migration copy can be
taken. Failure there is deliberately non-fatal: not having a safety net is no reason to
refuse to start.

---

## Categories

The vocabulary is My Money Pro's, verbatim, so the import maps one-to-one and years of
habit carry over. `Groceries` is deliberately absent — fruit and eggs were always filed
under Food. Income categories (Salary, From Parents, Awards, Refunds) are flagged and
never offered as somewhere money went.

The v1 names are renamed in place on first launch — `Food & Drink` → `Food`,
`Transport` → `Transportation`, `Groceries` merged into `Food` — so every transaction
already filed keeps its category without being touched. This is `CategorySync`, not a
Room migration, because it has to converge on every launch rather than fire once.

### Sharing

Two categories carry behaviour rather than just a name, via `Category.sharing`:

| | `sharing` | Counts as spending? | Picking it |
|---|---|---|---|
| **For Others** | `GIVEN` | Yes — exactly like Food | Offers to split the bill |
| **For Friend Return Later** | `LENT` | **No** — carried as owed to you | Offers to split, and asks who owes |

`LENT` rows are written with `countsAsSpending = false`, which is the flag
`Ledger.isExpense` already reads — so money you are owed leaves your expense figure and
your budgets without either having to learn a new concept.

A repayment is a `SETTLEMENT` sharing the split group. It is excluded from income for
the same reason a transfer leg is: a friend paying back the ₹120 you fronted is your own
money returning, not money you earned.

### Seed keywords

Deliberately small. Real payees are mostly UPI strings no generic list can predict
(`paytmqr 1a2b3cd`), so the app is built to learn from one correction rather than to
ship a big dictionary.

Matched as **whole words, longest first**, and both halves are load-bearing. Whole
words, because `contains` filed a gola stall and a Sholapur mess as Transportation —
"OLA" sits inside both — and JioMart as a phone bill. Longest first, because
"UBER EATS" contains "UBER" and a first-match scan would book dinner as a taxi.

The matchers are precompiled: this runs on every unrecognised payee, and rebuilding
them per message is work done for nothing. (The comment above them in `Categorizer.kt`
says "32 regexes"; there are 34 as of v2.4.0.)

---

## Known limits by design

- ICICI sends no balance, so its figure is computed and drifts. Reconcile once a cycle.
- Union messages carry no payee, so those transactions have no text to categorise on.
  The classifier reads amount, hour, weekday, account and channel instead, which is
  enough for the regular ones and not enough for the rest — a payee-free message can
  never be fully automatic.
- The classifier's thresholds (`MIN_LOG_MARGIN = 2.5`, `MIN_CATEGORY_EXAMPLES = 5`,
  `MIN_TOTAL_EXAMPLES = 50`) are a deliberate guess, set conservatively. They are the
  part of that feature most in need of real numbers rather than judgement. Every guess
  is recorded as one, and Settings reports how often it was right — that is what these
  should be tuned against, once there is enough of it to mean anything.
- Quick-add chips are built from what you have actually entered, so they stay thin until
  there is history behind them.
- No app lock. No home-screen widget. No LLM parser fallback.

---

## Where things live

```
parser/BankRules.kt          ICICI + Union formats. Start here to add a bank.
parser/Detectors.kt          Channel detection and the future/promo rejection list.

domain/SmsIngestor.kt        Orchestration: parse → classify → persist → balances.
domain/TransferResolver.kt   Duplicate vs self-transfer vs ATM.
domain/Ledger.kt             The one definition of expense/income every screen reads.
domain/Categorizer.kt        Vocabulary, renames, seeded keywords, and decide().
domain/CategoryModel.kt      The classifier's gate: when a guess may be filed.
domain/NaiveBayes.kt         Bernoulli Naive Bayes. learn / unlearn / score.
domain/Features.kt           What the classifier is allowed to look at.
domain/GuessOutcomes.kt      Was the guess right — recorded without asking.
domain/CategorySync.kt       Reconciles the category table on every launch.
domain/CategoryPrompt.kt     The "ask" notification when no category can be guessed.
domain/RecordedAlert.kt      The "we filed this" notification. Exactly one of the two.
domain/Splitter.kt           One debit → several facts. Pure; touches no balance.
domain/Calculator.kt         BigDecimal keypad arithmetic. Reports rounding.
domain/CycleCalculator.kt    Salary anchor + last-working-day fallback.
domain/Periods.kt            Calendar months for the UI; salary cycles for Settings.
domain/BudgetEvaluator.kt    Progress per cycle, and the alerts.
domain/BudgetResolver.kt     Standing limit vs one pinned to this cycle.
domain/Looks.kt              Icon key + colour per category. Domain, not drawables.

importer/Dialect.kt          Stage 1. Hand-written CSV reader.
importer/ColumnMap.kt        Stage 2. Column synonyms. Add a synonym, not a branch.
importer/ValueParsers.kt     Stage 4. Money, dates, direction. Heavily tested.
importer/CsvImporter.kt      Stages 1-5. No Android, no Room, no writes.
importer/DiffMatcher.kt      The adaptive matcher and the capture figure.
importer/Committer.kt        Stage 6. Pure: decides what a commit would do.
domain/ImportService.kt      Stage 7. The only code that writes an import. One txn.

data/AppDatabase.kt          Schema version, migrations, pre-migration snapshot.
data/SchemaV3/V4/V5.kt       Each migration as a list of statements, not as code.
backup/Snapshot.kt           Database backup and validated restore.
backup/CsvExport.kt          Readable export. No Android types, so it is unit-tested.

ui/FinanceApp.kt             Five tabs, the drawer, and the top bar.
ui/EntryScreen.kt            Hand entry and editing. The locked-amount rule.
ui/SplitSheet.kt             The split sheet.
ui/InboxScreen.kt            Everything the app could not decide, as one queue.
ui/ImportScreen.kt           The rehearsal, the import button, the undo.
ui/AnalysisScreen.kt         The ring, drawn with Canvas.
ui/Permissions.kt            The missing-SMS-permission banner.
ui/Look.kt                   Icon keys → Compose ImageVectors.
```

---

## Schema

**Version 5.** Migrations are additive only — nothing drops, renames or rewrites.

| | What it added | Why it mattered |
|---|---|---|
| **1 → 2** | `import_batches`, `imported_rows`; `fingerprint`, `importBatchId`, `timeWasInferred`, `noSmsCounterpart` on transactions | Everything the importer would eventually need, including columns no feature used at the time. Import day involves no schema change at all — running a migration against a full ledger on the same day you irreversibly rewrite it is exactly the risk this avoids. |
| **2 → 3** | `iconKey`/`colourHex` on categories and accounts; `periodStart` on budgets; the `cycle_history` table | Per-cycle budget overrides, and a record of cycle boundaries — `CycleState` forgets the cycle it just left, so `cycle_history` is the only thing that remembers it happened. |
| **3 → 4** | `sharing` on categories; `splitGroupId`, `owedBy` on transactions | Split bills. Every existing row arrives as `NONE` with no split group, which is precisely what an unsplit transaction in an unshared category is — so no figure moves on the day you install it. |
| **4 → 5** | `categoryWasInferred` on transactions | Marks a guess as a guess. Every existing row arrives as not-inferred, which is accurate rather than merely convenient: nothing was ever guessed before this version shipped, so the whole of history is eligible to train on from the first launch. |

Migrations from v3 onward are **held as data** (`SchemaV3`/`V4`/`V5` — plain lists of
SQL statements) so a JVM test can check them against the schema Room exports, without an
emulator. The classic migration bug is a field added to an entity and forgotten in the
migration; Room only catches that at open time, on a real database — which in this app
means on the one phone holding the only copy of the ledger.

There is deliberately **no `fallbackToDestructiveMigration`**: a missing migration must
fail loudly, because silently wiping a ledger is the one outcome worse than a crash.
Room compares an entity's declared `@ColumnInfo(defaultValue = ...)` against the live
database, so any column added as `NOT NULL DEFAULT 0` must declare that default on the
entity too.

v1 shipped with `exportSchema = false`, so no `1.json` exists and `MIGRATION_1_2` was
verified by hand and by Room's own open-time validation — plus
`python3 tools/verify-migration.py`, which diffs the hand-written SQL against the
`createSql` Room recorded in `app/schemas/.../2.json`. Room's `MigrationTestHelper`
cannot be used there: with no `1.json` there is no "before" database to build. From v2
onward every version is on disk and migrations are tested properly.

---

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
gradle :app:assembleDebug
```

`gradle.properties` forces in-process Kotlin compilation — the separate Kotlin compile
daemon cannot open its socket in some sandboxes.

`gradle check` additionally runs `verifyNoInternetPermission`, which reads the merged
manifest the APK is built from and fails if `android.permission.INTERNET` survived.
See the README for why that is a build task rather than a comment.
