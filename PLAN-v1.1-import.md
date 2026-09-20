# V1.1 — Universal Transaction Importer

Status: **plan, not yet built**
Target DB: schema **v1 → v2** (destructive migration is forbidden; see §5)
Trigger: migrating ~75 days of history out of *My Money Pro* without losing a byte.

---

## 1. Goal

Import a transaction export from **any** finance app — not just My Money Pro — into
this app's ledger, such that:

- every row in the file is accounted for, and
- nothing in the file is silently discarded, and
- the import can be undone completely.

The My Money Pro CSV is the first *profile*, not the design target. The importer is
built column-agnostic so that the next export — a different app, a bank statement,
a future My Money Pro version with extra columns — needs a profile entry, not a rewrite.

## 2. What "no data loss" means here

Five invariants. Each is testable, and each gets a test.

**I1 — Conservation.** `imported + queued + rejected == total data rows`.
Asserted at the end of every run. A row can never just vanish.

**I2 — Verbatim retention.** Every imported transaction stores its **entire original
row** — all columns, including ones we don't understand — as JSON in `ImportedRow.rawJson`.
If the mapping was wrong, the source of truth is still in the database and we can
re-derive without asking you to export again.

**I3 — No lossy coercion.** An unrecognised category is **created**, not folded into
"Other". An unrecognised account is **created** (flagged `needsConfirmation`), not
dropped. Unknown enum-ish values are preserved as text in `extras`.

**I4 — Exact money.** Amounts parse through `BigDecimal` to `Long` paise. Any amount
that cannot round-trip exactly (`paise / 100.0` back to the source string) is rejected
into the review queue rather than rounded. `Double` never touches money.

**I5 — Reversibility.** Every run has an `importBatchId`. One tap deletes the batch and
restores the prior balances. A failed or mis-mapped import costs nothing but a retry.

A sixth, softer one: **I6 — Explainability.** Every rejected row carries a
human-readable reason and its 1-based line number, shown in the import report.

## 3. Pipeline

Seven stages. Stages 1–5 are pure functions with no Android and no I/O, so the whole
thing is JVM-unit-testable, the same way `parser/` already is.

```
  file
   │
   1. Sniff        dialect: delimiter, quote, BOM, line endings, trailing junk
   │
   2. Map          header row -> canonical fields, via synonyms + profile override
   │
   3. Decode       each row -> RawRecord (typed values + extras map + line no.)
   │
   4. Normalise    direction, money->paise, time->epoch IST, account, category
   │
   5. Resolve      expand transfers into legs; fingerprint; detect SMS overlap
   │
   6. Preview      dry-run report; YOU approve; nothing written yet
   │
   7. Commit       single Room transaction; batch-tagged; balances anchored
```

Stage 6 is the important one: **the importer never writes without a preview you have
seen and approved.** The preview shows counts, the account/category mapping it intends
to use, the date range, the computed balance effect, and every rejected row with its reason.

### Stage 1 — Dialect sniffing

The My Money Pro file already demonstrates why this is needed: every line ends with a
**trailing space after the closing quote**, and the header is `"NOTES" ` — naive
`csv` parsing yields a field literally named `NOTES" `. Sniffing handles:

- UTF-8 BOM
- delimiter detection (`,` `;` `\t` `|`) by consistency of field count across the first 20 lines
- quote char (`"` `'`) and doubled-quote escaping
- `\r\n` / `\n` / `\r`
- trailing whitespace per line and per field
- blank lines, and a preamble of non-tabular lines before the real header (bank statements do this)

### Stage 2 — Column mapping

No fixed column order, no fixed names. Each canonical field has a synonym set matched
case/space/punctuation-insensitively:

| Canonical | Synonyms matched |
|---|---|
| `time` | time, date, transaction date, txn date, value date, datetime, timestamp, when |
| `type` | type, kind, direction, dr/cr, debit/credit |
| `amount` | amount, amt, value, sum, money |
| `debit` | debit, withdrawal, paid out, dr *(split-column exports)* |
| `credit` | credit, deposit, paid in, cr |
| `category` | category, tag, label, head |
| `account` | account, wallet, source, from, paid by |
| `toAccount` | to, to account, destination, transfer to |
| `notes` | notes, note, description, remark, remarks, narration, particulars, memo, payee |
| `reference` | reference, ref, ref no, utr, rrn, cheque no, transaction id |
| `balance` | balance, running balance, closing balance, avl bal |
| `currency` | currency, ccy |

Rules:
- `time` and `amount` (or `debit`+`credit`) are **required**; absent → refuse the file
  with a clear message, before writing anything.
- Every column **not** matched is not an error. It goes into `extras` verbatim and is
  retained under **I2**. That is what makes the importer accept "any fields that come in".
- A `profile` may override the mapping when heuristics get it wrong. Profiles live in
  `ImportProfiles.kt` and are chosen by matching the exact header signature.

### Stage 3 — Decode

Produces:

```kotlin
data class RawRecord(
    val lineNumber: Int,
    val values: Map<String, String>,   // canonical field -> raw text
    val extras: Map<String, String>,   // unmapped column -> raw text
    val rawLine: String,               // the original line, byte-for-byte
)
```

Note `rawLine` alongside the parsed map — belt and braces for I2.

### Stage 4 — Normalise

Independent, individually-tested value parsers. Each returns a result, never throws,
and a failure demotes the row to the review queue with a reason.

**Money.** Strips `₹ Rs. INR $ ,` and NBSP; accepts `1,234.56`, `1.234,56` (EU),
`(500)` and `-500` as negative, `1234` as whole rupees. Through `BigDecimal`,
scale-2 check, to `Long` paise. Round-trip verified (I4).

**Date/time.** Ordered pattern list, first match wins, all resolved in `Asia/Kolkata`:
`MMM d, yyyy h:mm a` (this file) · `dd-MM-yyyy HH:mm:ss` · `dd/MM/yyyy` ·
`yyyy-MM-dd['T'HH:mm[:ss]]` · `dd-MMM-yy` · `dd MMM yyyy` · epoch millis/seconds.
Date-only rows get 12:00 IST, and are flagged `timeWasInferred = true` rather than
pretending to precision we don't have. Ambiguous `03/04/2026` resolves day-first
(Indian convention) and says so in the preview.

**Direction.** From `type` (`(-) Expense`, `(+) Income`, `(*) Transfer`, `DR`, `CR`,
`debit`, `credit`, `withdrawal`, `deposit`), or from split debit/credit columns, or
from the sign of the amount. Transfers are recognised here and handed to stage 5.

**Account.** Looked up by alias table, then by name, then created. Never coerced.

**Category.** Same: alias, then name, then created.

### Stage 5 — Resolve

- **Transfer expansion.** A row like `(*) Transfer | 3000.00 | Salary->Card` becomes
  **two** `Txn` rows sharing a `transferGroupId`, both `countsAsSpending = false` —
  matching exactly what `TransferResolver` already produces for SMS pairs. The
  `A->B` route is parsed from the account cell, or from a separate `toAccount` column
  if the profile has one.
- **Fingerprint.** `sha1(accountId | direction | amountPaise | occurredAtDay)` stored
  on the txn. Re-importing the same file finds the same fingerprints and reports
  "already imported" instead of duplicating.
- **SMS overlap.** For imported rows in the window where both sources were live,
  a match on `(account, amount, direction, ±36h)` against an existing `TxnSource.SMS`
  row is **flagged, not auto-dropped**. You decide in the preview. Auto-dropping is
  how imports quietly lose real transactions.

### Stage 7 — Commit

One Room `@Transaction`. Either the whole batch lands or none of it does. Then
balances are recomputed (§8) and the report is persisted so you can read it later.

## 4. New files

```
app/src/main/java/com/yk/finance/importer/
    Dialect.kt          stage 1
    ColumnMap.kt        stage 2 - synonyms, header signature
    RawRecord.kt        stage 3 types
    ValueParsers.kt     stage 4 - money, datetime, direction  (heavily tested)
    ImportProfiles.kt   known exports; MyMoneyPro is profile #1
    CsvImporter.kt      stages 1-5 orchestration -> ImportPlan
    ImportCommitter.kt  stages 6-7, the only file that touches Room
    ImportReport.kt     counts, rejects, mapping decisions

app/src/main/java/com/yk/finance/ui/
    ImportScreen.kt     file picker -> preview -> commit -> report -> undo

app/src/test/java/com/yk/finance/
    ImporterTest.kt     ~40 tests incl. the real CSV as a fixture
```

`CsvImporter` depends on nothing Android. `ImportCommitter` is the seam where it meets Room.

## 5. Schema changes (v1 → v2)

Additive only. **No `fallbackToDestructiveMigration`** — you have live SMS-captured
data on your phone now, and losing it to a migration would be the exact failure this
plan exists to prevent. A hand-written `Migration(1, 2)` with `ALTER TABLE ADD COLUMN`,
plus a test that opens a v1 DB and migrates it.

New table:

```kotlin
@Entity(tableName = "import_batches")
data class ImportBatch(
    @PrimaryKey val id: String,          // uuid, the importBatchId
    val fileName: String,
    val importedAt: Long,
    val profileName: String,             // "MyMoneyPro" or "Generic"
    val rowsTotal: Int,
    val rowsImported: Int,
    val rowsQueued: Int,
    val rowsRejected: Int,
    val reportJson: String,              // full report incl. every reject + reason
)

@Entity(tableName = "imported_rows")     // I2 - verbatim retention
data class ImportedRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val lineNumber: Int,
    val rawLine: String,
    val rawJson: String,                 // every column, mapped and unmapped
    val txnId: Long?,                    // null if queued or rejected
    val outcome: String,                 // IMPORTED | QUEUED | REJECTED
    val reason: String?,
)
```

New columns on `transactions`:

| Column | Type | Why |
|---|---|---|
| `importBatchId` | `TEXT?` | I5 — undo, and provenance |
| `fingerprint` | `TEXT?` | re-import detection |
| `timeWasInferred` | `INTEGER` default 0 | honesty about date-only rows |
| `notes` | `TEXT?` | carries "Lunch", "Fruits" — 84 rows have these |

`notes` is worth calling out: without it we lose the single most human part of your
existing data. Adding it also means SMS-captured rows can take a note later.

New column on `accounts`: `aliases TEXT?` (delimited) so `Salary` resolves to the
ICICI account forever, not just this once.

`TxnSource` gains `IMPORT`. Existing `MANUAL` keeps its meaning.

## 6. The My Money Pro profile

Concrete mapping for your file, derived from the actual data:

**Accounts** — `Salary` → ICICI XX742, `Card` → Union \*8317, `Cash` → Cash wallet.
The Salary/ICICI link is evidenced by the Sep 16 ₹3,000 `Salary->Card` transfer matching
the ICICI/Union SMS pair on reference `634455667788`. **Both links need your confirmation
in the preview before commit** — this is the one mapping the importer cannot prove by itself.

**Categories** — 17 in the file. Proposed: keep Food, Transportation, Shopping, Bills,
Entertainment, Education, Health and Fitness, Home, Sport as-is; `.Clothing` → Clothing
(leading dot is a sort hack); `From Parents`, `Awards`, `Refunds`, `Salary` become
**income** categories; `  -  ` is the transfer placeholder and is dropped in favour of
the transfer legs; `For Others` and `For Friend Return Later` stay verbatim — they are
yours and mean something to you. Every one of these is created, none merged away (I3).

If you want the categories to match what the app shows you, send those screenshots —
it changes the alias table, not the code.

**Known quirks encoded in the profile:** trailing space per line, header `"NOTES" `,
`MMM d, yyyy h:mm a` times, `(-)/(+)/(*)` type prefixes, `A->B` transfer routes.

## 7. What this file cannot give us

Stated plainly so the plan doesn't over-promise:

- **No balances** — the export has none. Handled in §8.
- **No reference numbers** — so imported rows can't dedupe against SMS by reference,
  only by the fuzzy fingerprint, which is why overlaps are flagged for you rather than auto-resolved.
- **No raw SMS text** — My Money Pro is manual entry. The ATM format stays unverified.
  Still need that first real withdrawal SMS.

## 8. Balance anchoring

The import gives deltas, not positions. Procedure:

1. You enter **today's real balance** for each of the three accounts.
2. The importer sums the full ledger per account — imported rows *and* the SMS rows
   already captured since install.
3. `openingBalance = todaysBalance − sumOfAllLedgerMovement`.
4. Written as the account's `openingBalancePaise`, so every historical balance in the
   app is now correct back to 5 Jul 2026.

This also fixes the standing ICICI drift problem in one move: ICICI sends no `Avl Bal`,
so its figure is pure arithmetic from zero. Anchoring it here is the reconcile you
were going to have to do anyway.

Over the export window the net movement is Salary **+₹5,721.65**, Card **+₹4,507.50**,
Cash **+₹1,331.00** — so the arithmetic is checkable by hand if you want to verify it.

## 9. Overlap cutoff

Your export ends **18 Sep 2026, 11:10 AM**; the app started capturing SMS around
18–19 Sep. Only four rows sit in the contested window (Sep 17–18). Rather than pick a
cutoff date blind, the preview lists those four beside any matching SMS rows and you
tick which to keep. Four rows is a ten-second decision and it beats any heuristic.

## 10. UI

New **Import** entry in Settings:

1. **Pick file** — SAF document picker, `text/*` and `*/*`.
2. **Preview** — profile detected, column mapping (with any unmapped columns listed
   as "retained, not used"), row counts, date range, account mapping *with dropdowns
   to correct it*, category list, balance effect, overlap conflicts, rejected rows.
3. **Import** — commits; shows the report.
4. **Report** — persisted; reachable later; carries an **Undo this import** button.

Rejected rows land in the existing review tray, so they are recoverable by hand and
nothing needs a new recovery UI.

## 11. Tests

Target ~40, added to the existing 27. All JVM, no instrumentation.

- **Value parsers**: ~15 — every money format, every date pattern, negatives,
  parentheses, EU decimals, the exact round-trip assertion for I4.
- **Dialect**: BOM, `;` delimiter, tab, CRLF, trailing spaces, preamble lines.
- **Mapping**: synonym hits, split debit/credit columns, missing required column,
  unmapped columns survive into `extras`.
- **The real file as a fixture**: asserts 226 rows in, 226 accounted for (I1),
  4 transfers → 8 legs, totals match ₹66,166.85 / ₹77,727 to the paise.
- **Conservation property test**: random malformed rows injected; the sum invariant
  must hold every time.
- **Idempotence**: importing the same file twice adds nothing the second time.
- **Undo**: import → undo → the DB is byte-identical to before.
- **Migration**: a v1 database opens, migrates to v2, and keeps all its rows.

## 12. Phases

| Phase | Content | Risk |
|---|---|---|
| A | `ValueParsers` + `Dialect` + `ColumnMap` + tests | none — pure, no schema change |
| B | Room v2 migration + `ImportBatch`/`ImportedRow` + migration test | **highest** — touches live data |
| C | `CsvImporter` → `ImportPlan`, MyMoneyPro profile, real-file test | none |
| D | `ImportCommitter`, balance anchoring, undo | medium |
| E | `ImportScreen`, preview UI | none |
| F | Build, install, import your file, verify against your real balances | — |

Phase B is where care is needed. Before it runs on your phone, **export a backup of
the current app state**, because there is no undo for a bad migration — only for a
bad import.

## 13. Open items

1. Confirm `Salary` = ICICI XX742 and `Card` = Union \*8317.
2. Your three current balances, for §8.
3. Category screenshots, if you want the app's categories to mirror My Money Pro's.
4. Still outstanding from v1: the first real ATM withdrawal SMS.

Items 1 and 2 are needed at **phase F**, not before. Phases A–E can be built now.

---

# Decisions locked — grilling session, 19 Sep 2026

**These supersede anything above that contradicts them.** Sections 6, 9 and 12 in
particular were written before this session and are amended here.

| # | Decision |
|---|---|
| 1 | Import is **deferred, one-shot, one-way**. It runs only when reliability is proven, and it runs once. The `export_19_09_26_1014.csv` in this folder is a **test fixture**, not the payload. |
| 2 | **Cash rows import in full**, any date — the app can never capture cash, so they can never collide. **Bank rows in the overlap merge their notes into the existing SMS transaction** rather than creating a second one. |
| 3 | A bank row with **no SMS counterpart is imported and tagged `noSmsCounterpart`**. The tag is a capture-health diagnostic, not just provenance. |
| 4 | The **v1→v2 migration ships early**, decoupled from the importer UI, so it soaks for weeks before import day. |
| 5 | Backup is a **raw SQLite snapshot with restore**, plus a **separate CSV export button**. An export without restore is theatre. |
| 6 | Migration gate is **reliability over one full salary cycle**: the app's bank ledger must match My Money Pro's. **Daily-use parity gets built during that cycle.** |
| 7 | Manual entry is a **quick-add sheet with history-ranked chips** (`Fruits ₹50`, `Canteen ₹68.25`). Goal is to beat My Money Pro on speed, not match it. Widget deferred. |
| 8 | Build the **dry-run diff**: importer stages 1–5 with commit disabled. Dissolves the no-rehearsal problem — the real pipeline can be exercised on real exports indefinitely. |
| 9 | **Adopt My Money Pro's category names verbatim.** App seeds renamed to match. Drop `Groceries` (user files fruit/egg under Food). Strip the `.` from `.Clothing`. Add income/expense marking. Keep `Uncategorised`. Screenshots pending for categories defined-but-unused. |
| 10 | **`Salary` = ICICI XX742, `Card` = Union \*8317, `Cash` = cash wallet.** Confirmed by the user. Union does SMS for ₹22 fares. |
| 11 | Payee-less Union transactions: **guess when confident, ask when not.** One-tap notification below the threshold. Imported history is the training set — everything prompts until then. |
| 12 | **Salary credits on the last working day** (Aug 31, Sep 30, Oct 30). App's computed boundary is correct. My Money Pro's `Aug 01`/`Sep 01` salary rows are **entry timestamps, not credit timestamps** — its times are when the user typed, not when money moved. |
| 13 | Matcher uses an **adaptive time window** keyed on amount rarity: unique amounts match up to a week out, frequent amounts (₹50 appears 20×) same-day only, ±36h default in between. Ambiguity → review, never silent merge. |
| 14 | Import-day residual is booked as a **visible `ADJUSTMENT` transaction**, not folded into the opening balance. Reuses the existing reconcile mechanism. |

## Amendments to earlier sections

- **§6** — account mapping is now confirmed (decision 10), not pending.
- **§9** — the "four contested rows" figure is obsolete. The overlap is however long the
  evaluation cycle runs, and is resolved by decisions 2, 3 and 13 rather than by a cutoff date.
- **§12 phase table** — replaced by the roadmap below.
- **§5** — the instruction to "export a backup before phase B" was unbuildable when written:
  v1 has **no export code at all** (verified by grep; only `exportSchema = false` matches).
  Decision 5 fixes this, and the app now snapshots itself automatically before any migration.
- **§11** — `AppDatabase` has `exportSchema = false`, so no v1 schema JSON exists on disk and
  Room's `MigrationTestHelper` cannot be auto-generated. The v1 fixture must be hand-written.
  Turning `exportSchema = true` from v2 onward fixes this for every future migration.

## Roadmap

**Build 1 — safety. Shipped, v1.1 (versionCode 2).** Schema v2 + auto-snapshot immediately before migration +
SQLite backup/restore + CSV export button + missing-permission banner.
Ships first and alone, so the migration starts soaking. `exportSchema = true`.

**Build 2 — daily-use parity. Shipped, v1.2 (versionCode 3).** Quick-add sheet with
history chips · `notes` field surfaced in the ledger · My Money Pro category names
with income/expense marking · Union categorisation (guess-or-ask).

Built as:

| Piece | Where |
|---|---|
| Category vocabulary + renames from v1 | `domain/Categorizer.kt` |
| Rename/merge reconciler, run every launch | `domain/CategorySync.kt` |
| History-ranked chips (SQL `GROUP BY`) | `FinanceDao.observeQuickAdd` |
| Quick-add sheet, FAB, undo snackbar | `ui/QuickAdd.kt`, `ui/FinanceApp.kt` |
| The ask: notification + Home queue | `domain/CategoryPrompt.kt`, `NeedsCategoryCard` |

Two decisions worth recording. **Chips are drawn only from `MANUAL` and `IMPORT` rows**,
never from SMS-captured ones — a chip for a payment the bank already reported would
invite entering it twice. **`CategorySync` is not a Room migration**: migrations fire
once and cannot be corrected afterwards, and categories are user-editable data, so it
has to converge on every launch rather than fire on one.

The chip list is thin until Build 4 lands; that is the expected state, not a defect.
The 226 imported rows are what make it good.

**Build 3 — measurement. Shipped, v1.3 (versionCode 4).** Dry-run diff: dialect
sniffing, column mapping, value parsers, adaptive matcher, report of matched /
My-Money-Pro-only / app-only. Ready well before **29 Oct 2026**, which is the point -
the measurement has to run *during* the cycle, not at the end of it.

Built as:

| Stage | Where |
|---|---|
| 1 · dialect sniffing, hand-written CSV reader | `importer/Dialect.kt` |
| 2 · header → canonical fields via synonyms | `importer/ColumnMap.kt` |
| 3 · decode, with `extras` + `rawLine` retained | `importer/RawRecord.kt` |
| 4 · money / time / direction parsers | `importer/ValueParsers.kt` |
| 5 · orchestration → `ImportPlan` | `importer/CsvImporter.kt` |
| known formats | `importer/ImportProfiles.kt` |
| adaptive matcher + diff (decision 13) | `importer/DiffMatcher.kt` |
| account binding, result types | `importer/DryRun.kt` |
| the screen | `ui/ImportScreen.kt` |

**There is no committer and no commit button.** Stages 6 and 7 do not exist in this
build, so the rehearsal cannot write even by mistake. That is the whole value of
decision 8: the import happens once and cannot be rehearsed afterwards, so it has to
be rehearsable before.

Four things the diff had to get right, each of which would otherwise have produced a
flattering number:

1. **Cash is excluded by construction.** The app can never see cash, so a cash row it
   is missing proves nothing. Counting those would have reported a capture failure
   that is not one.
2. **The window opens at the later of the cycle start and the first transaction the
   app ever recorded.** Measuring across days before install would blame the parser
   for an app that was not there.
3. **An unbound account name makes the report declare itself untrustworthy**
   (`DiffReport.trustworthy`). A capture percentage computed while rows were being
   silently skipped is not a measurement.
4. **Ambiguity counts as captured, and is never merged.** Two candidates means the app
   did record a payment of that amount that day and cannot prove which - counting it
   as missed would overstate the damage, and merging it would be a guess.

**Build 4 — import day. Shipped, v1.4 (versionCode 5).** Committer, note-merge,
`noSmsCounterpart` tagging, balance anchoring with visible adjustment, undo.

Built as:

| Stage | Where |
|---|---|
| 6 · decide what a commit would do (pure) | `importer/Committer.kt` |
| 7 · write it, in one transaction | `domain/ImportService.kt` |
| row identity across re-exports | `importer/Fingerprint` (in `Committer.kt`) |
| commit / undo / anchor, wired | `domain/Repository.kt`, `ui/FinanceViewModel.kt` |
| the button, the preview, the undo | `ui/ImportScreen.kt` |

**No schema change.** `MIGRATION_1_2` already created `importBatchId`, `fingerprint`,
`noSmsCounterpart`, `timeWasInferred`, `import_batches` and `imported_rows`. That was
the point of landing them early: import day involves no migration at all, and running a
second migration against a full ledger on the day it is irreversibly rewritten is
exactly the risk that avoids.

The load-bearing decisions:

1. **The planner is pure and the applier is thin.** `Committer.plan` takes an
   `ImportPlan`, a `DiffReport` and a binding function, and returns a `CommitPlan` — no
   Room, no Android, no clock beyond the one handed in. That is what makes import day
   testable before it happens, and it is why the rehearsal can show the real decisions
   rather than a summary of them: the screen renders the same object the committer
   consumes.
2. **The partition is total.** Every staged row lands in exactly one of IMPORTED,
   MERGED, QUEUED or SKIPPED — plus REJECTED from stages 1-4. `conserved` asserts
   `rowsImported + rowsQueued + rowsRejected == rowsTotal`, and the commit refuses if it
   fails, because a conservation failure means the file was read wrongly and no count
   downstream is real.
3. **Merging only fills blanks** (decision 2). The SMS already supplied amount, date and
   payee; the file adds only what the bank never said. An import may not overwrite
   something you typed, and the file's version survives in `imported_rows.rawJson`
   regardless, so declining to overwrite loses nothing.
4. **Merged rows are not stamped with the batch id.** `importBatchId` means "created by
   this import", and undo deletes every row carrying it. Stamping a merge would make
   undo delete a transaction the phone captured for itself — the one thing an undo must
   never do.
5. **Fingerprints are numbered within a file.** Built from the date rather than the
   timestamp, because a re-export shifts minutes but never days. Numbering repeats is
   what stops two genuinely separate ₹50 teas on one day from collapsing into one and
   silently deleting a real payment. A second export of an overlapping period is the
   expected case here, not an error.
6. **`noSmsCounterpart` is false outside the window and false for cash** (decision 3).
   Before the app existed the flag would be a statement about nothing; for cash it would
   be a lie, since no bank messages about a cash payment. It is meaningful only where
   both ledgers were recording.
7. **Balance anchoring books a visible ADJUSTMENT** (decision 14), carrying the batch id
   so an undo of the import takes the anchor with it. The gap is expected — the file
   covers a period, not all of history — and its size is the honest measure of what is
   still unaccounted for.

**The gate has not moved.** The code is ready; the decision is not. The capture figure
available today spans only the few days between install and the 18 Sep export. The run
that matters is a fresh export checked near **29 Oct 2026**, covering a full cycle in
which both ledgers were recording. Shipping the committer commits nothing: the app
writes only when the button is pressed, and it can be undone afterwards.

Cycle arithmetic: salary lands **30 Sep** (Wed) → evaluation cycle runs
**30 Sep – 29 Oct**, next salary **30 Oct** (Fri, since 31 Oct is a Saturday).

## Still owed by the user

1. Category screenshots — including categories defined but never spent against, which
   the export cannot reveal.
2. The **ATM withdrawal SMS**. Strong lead: `Aug 31, 11:01 PM — ₹1,000 — Card->Cash`
   in the export is almost certainly a Union ATM run. The real message would replace
   the last synthetic parser in the codebase.
3. Three real balances — at import day, not before.
