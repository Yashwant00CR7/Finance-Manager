# Finance Manager (v1.4)

Personal Android app that reads bank SMS as they arrive, keeps per-account balances,
and enforces budgets on a salary cycle. Sideloaded, offline, no server, no account.

## Install

    adb install -r FinanceManager-v1.4-debug.apk

Or copy the APK to the phone and open it (allow "install unknown apps" once).

On first launch, grant **SMS** and **Notifications**. SMS is the whole product;
notifications only gate the budget alerts, which on Android 13+ fail silently without it.

If the SMS toggle is greyed out, Android is blocking it because the app was installed
outside the Play Store. App info -> the three dots at the top right -> **Allow
restricted settings**, and only then open Permissions. The app now says so itself: a
banner appears on Home whenever SMS access is missing, rather than showing a balance
of zero with no explanation.

Then: **Settings → Add account** for each account, with its current balance, plus a
Cash entry. No digits or bank codes — the app attaches messages itself.

## What it does

- Records debits and credits from ICICI and Union Bank SMS the moment they arrive
- Auto-creates an account for any unrecognised sender and records the transaction
  immediately; you name it or attach it afterwards
- ATM withdrawals move money to the Cash wallet instead of counting as spending;
  your manual cash entries are the real expense
- Transfers between your own accounts net to zero spending
- Learns a payee's category from one correction
- **Guesses a category when it can, asks when it cannot.** Union sends no payee, so
  there is nothing to guess from; those land in "Needs a category" on Home with
  one-tap chips, and a quiet notification while you still remember the payment
- **Quick-add sheet** behind the + button: chips for what you enter most often, one
  tap each, undoable. Categories are ordered by how often you use them
- **Checks another app's export against itself**, reports what each side has that the
  other does not, and — only when you press the button — **imports it**, mergeably and
  undoably
- Budgets per category plus an overall cap, on your salary cycle
- **Backup and restore** the whole database, and export transactions as CSV

## Verification status

114 unit tests, all passing (`gradle :app:testDebugUnitTest`). Every ICICI and Union
assertion runs against real messages from the phone, and the importer is tested
against the real 226-row export rather than against a file written to suit it.

    ParserTest 14 · DiffTest 13 · CsvExportTest 12 · ImportPipelineTest 8
    MoneyParserTest 8 · CategoryVocabularyTest 7 · CycleCalculatorTest 7
    DialectTest 7 · TimeParserTest 7 · ColumnMapTest 6 · TransferResolverTest 6
    DirectionParserTest 5 · QuickAddTest 5 · RealExportTest 5 · MatchWindowTest 3
    MigrationVersionTest 1

`RealExportTest` fails rather than skips when the fixture is missing. A test that
passes by not running reports success for work it never did.

The category vocabulary is data rather than code, so `CategoryVocabularyTest` is the
only thing standing between a typo and a rename that silently creates a duplicate
category - it checks that every rename target exists and that no name is both renamed
away and seeded back, which would rename-then-recreate on every launch forever.

The v1 -> v2 migration is checked by `python3 tools/verify-migration.py`, which diffs
the hand-written SQL against the `createSql` Room recorded in `app/schemas/2.json`.
Room's own MigrationTestHelper cannot be used here: v1 shipped without an exported
schema, so there is no 1.json to build a "before" database from. The script verifies
the schema the migration produces, not its execution against a populated v1 file -
which is what the automatic pre-migration snapshot is for.

**Verified against real messages:** ICICI debit/credit, Union debit/credit, the
ICICI→Union self-transfer, balance trailers, amount and date parsing, and rejection
of fraud warnings, mandates, promos and OTPs.

**Not verified — no real sample exists:**

- **ATM withdrawal format.** The layout is a guess (`Acct XX742 is debited with ...`).
  Channel detection keys on "ATM"/"WDL"/"CASH WDL" anywhere in the body, which is far
  more stable than the layout, so the guess is low-risk. If your first real withdrawal
  does not match, it lands in the review tray rather than being lost, and the fix is
  one line in `IciciRule.DEBIT_WITH_SYNTHETIC`.
- **Card/POS and credit-card formats.** No rules written.
- **Salary credit.** Tag your first salary in the ledger ("This is my salary") to
  teach the cycle anchor. Until then the cycle rolls on the computed last working day.
- **The app has never seen a real incoming SMS** — tested by direct invocation, not on
  a device.

## Checking an export (Settings → Check or import an export)

Pick a CSV exported from another finance app. The importer runs for real - dialect
sniffing, column mapping, value parsing, transfer expansion, the adaptive matcher, and
the commit planner - and then reports. **Everything above the import button writes
nothing**, so a file can be checked as often as you like.

The point is rehearsal. The real import happens once and cannot be practised
afterwards, so it has to be practicable before, on real files, with no way to do harm.

What the report tells you:

- **Conservation** - `226 rows = 226 understood + 0 rejected`. If those ever fail to
  add up, the file was read wrongly and nothing else in the report is reliable.
- **How the columns were read**, including the ones kept but not used.
- **Accounts in the file**, with a dropdown to bind each name to an account here. The
  binding is saved as an alias on the account, so it holds for every future run.
- **The capture figure** - of the bank payments the other app recorded in the window,
  how many this app saw for itself - and the two lists behind it: missed by this app,
  and only in this app.

Four things that figure deliberately does *not* do:

- It never counts cash. The app cannot see cash, so a missing cash row proves nothing.
- It never measures days before the app was installed; the window opens at the later
  of the cycle start and the first transaction ever recorded.
- It refuses to call itself a measurement while any account name is unbound, because
  rows were being skipped.
- It never merges two candidates on a guess. Ambiguous rows are listed as ambiguous.

## Importing (the button at the bottom of that screen)

Two taps, and the second names what it is about to do. Before it will write anything it
checks two things and **refuses** rather than warning: that the row counts add up, and
that every account name in the file is bound. An unbound name means rows would be left
out without saying so.

Each row lands in exactly one outcome, and all of them are counted:

    IMPORTED   written as a new transaction (a transfer becomes two legs)
    MERGED     this app already had it from SMS; the file's note and category are
               copied onto the existing row - into empty fields only
    QUEUED     ambiguous, or an account is unbound. Nothing written, reason kept
    SKIPPED    already imported by an earlier run, matched on fingerprint
    REJECTED   stages 1-4 could not understand it. Kept verbatim with its reason

**Merging never overwrites.** The bank's SMS already supplied the amount, date and
payee; the file adds only what the bank never said. Whatever you typed stays, and the
file's version is kept in `imported_rows.rawJson` regardless.

**Re-importing is safe.** Fingerprints are built from the date rather than the
timestamp, because a second export shifts minutes but never days. Repeated rows within
one file are numbered, so two separate ₹50 teas on the same day are two fingerprints,
not one - collapsing them would silently delete a real payment.

**Then set the real balances.** The imported history will not reach today's balance on
its own; the file covers a period, not all of history. Typing what each account actually
holds books the difference as a visible `ADJUSTMENT` row rather than correcting the
number behind your back.

**Undo** removes every transaction the import wrote, reverses the balances, and clears
the notes and categories it filled in. Merged rows are deliberately not tagged with the
batch id - undo must never delete a transaction the phone captured for itself. One
caveat: undoing a merge clears those fields whether or not you have since edited them.

## Backup, export, restore

**Settings -> Backup.** Three buttons, two different files:

- **Save a backup** - the entire SQLite database. This is the one that restores. The
  write-ahead log is checkpointed first, so the copy is never missing the most recent
  transactions.
- **Export CSV** - readable, opens in any spreadsheet. It carries transactions only:
  balances, budgets and cycle state are not representable, so it is a report, not a
  backup. The column set deliberately matches what the v1.2 importer will read.
- **Restore** - validated into a staging file before anything is replaced, so a wrong
  or truncated file cannot destroy a working ledger. Replaces everything and restarts.

**Automatic snapshots.** The app copies its own database aside immediately before it
upgrades the schema, and lists those snapshots under the same screen. Five are kept.
An import can be undone; a bad migration cannot, so it is guarded instead.

## Categories

The vocabulary is My Money Pro's, verbatim, so the import maps one-to-one and years of
habit carry over. `Groceries` is deliberately absent - fruit and eggs were always filed
under Food. Income categories (Salary, From Parents, Awards, Refunds) are flagged and
never offered as somewhere money went.

The v1 names are renamed in place on first launch of v1.2 - `Food & Drink` -> `Food`,
`Transport` -> `Transportation`, `Groceries` merged into `Food` - so every transaction
already filed keeps its category without being touched. This is `CategorySync`, not a
Room migration, because it has to converge on every launch rather than fire once.

## Known limits by design

- ICICI sends no balance, so its figure is computed and drifts. Reconcile once a cycle.
- The importer can write now, but **the decision to use it is still gated** on the
  capture figure holding up over a full salary cycle. Today's figure spans only the days
  between install and the 18 Sep export. Re-export near 29 Oct and check that; see
  `PLAN-v1.1-import.md`.
- Union messages carry no payee, so those transactions cannot be auto-categorised.
  They are asked about rather than guessed at; after the import supplies training data
  the guessing gets better, but a payee-free message can never be fully automatic.
- Quick-add chips are thin until the import lands - they are built from what you have
  actually entered, and there are only a handful of hand-entered rows so far.
- No app lock. No charts. No home-screen widget. No LLM parser fallback.

## Build

    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    export ANDROID_HOME=$HOME/Library/Android/sdk
    gradle :app:assembleDebug

`gradle.properties` forces in-process Kotlin compilation — the separate Kotlin compile
daemon cannot open its socket in some sandboxes.

## Where things live

    parser/BankRules.kt      ICICI + Union formats. Start here to add a bank.
    parser/Detectors.kt      Channel detection and the future/promo rejection list.
    domain/TransferResolver.kt   Duplicate vs self-transfer vs ATM.
    domain/CycleCalculator.kt    Salary anchor + last-working-day fallback.
    domain/SmsIngestor.kt    Orchestration: parse -> classify -> persist -> balances.
    data/AppDatabase.kt      Schema version, migrations, pre-migration snapshot.
    backup/Snapshot.kt       Database backup and validated restore.
    backup/CsvExport.kt      Readable export. No Android types, so it is unit-tested.
    ui/Permissions.kt        The missing-SMS-permission banner.
    domain/Categorizer.kt    Category vocabulary, renames, seeded keyword rules.
    domain/CategorySync.kt   Reconciles the category table on every launch.
    domain/CategoryPrompt.kt The "ask" notification when no category can be guessed.
    ui/QuickAdd.kt           Quick-add sheet and the needs-a-category cards.
    importer/Dialect.kt      Stage 1. Hand-written CSV reader; see the trailing-space note.
    importer/ColumnMap.kt    Stage 2. Column synonyms. Add a synonym here, not a branch.
    importer/ValueParsers.kt Stage 4. Money, dates, direction. Heavily tested.
    importer/CsvImporter.kt  Stages 1-5. No Android, no Room, no writes.
    importer/DiffMatcher.kt  The adaptive matcher and the capture figure.
    importer/Committer.kt    Stage 6. Pure: decides what a commit would do. No Room.
    domain/ImportService.kt  Stage 7. The only code that writes an import. One transaction.
    ui/ImportScreen.kt       The rehearsal, the import button, the undo.

## Schema

Version 2, and **unchanged by v1.2, v1.3 and v1.4**. `MIGRATION_1_2` created everything
the importer would eventually need - the `import_batches` and `imported_rows` tables,
plus `fingerprint`, `importBatchId`, `timeWasInferred` and `noSmsCounterpart` on
transactions - including the columns no feature used at the time.

That was the whole point: import day involves no schema change at all. Running a second
migration against a full ledger on the same day you irreversibly rewrite it is exactly
the risk this avoids. Upgrading from any earlier version therefore runs no migration.

There is deliberately no `fallbackToDestructiveMigration`: a missing migration must
fail loudly, because silently wiping a ledger is worse than a crash. Room compares an
entity's declared `@ColumnInfo(defaultValue = ...)` against the live database, so any
column added as `NOT NULL DEFAULT 0` must declare that default on the entity too.

v1 shipped with `exportSchema = false`, so no v1 schema JSON exists and this migration
was verified by hand and by Room's own open-time validation. From v2 onward schemas are
written to `app/schemas/` and future migrations can be tested properly.
