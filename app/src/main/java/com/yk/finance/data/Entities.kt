package com.yk.finance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction

enum class AccountKind { BANK, CASH }

/**
 * Where a transaction came from. ADJUSTMENT exists so a reconcile never silently
 * rewrites history - the gap between computed and real balance is itself a visible
 * row you can look at.
 */
enum class TxnSource { SMS, MANUAL, ADJUSTMENT, TRANSFER_LEG, IMPORT, SETTLEMENT }

/**
 * Whether a category means money that was somebody else's.
 *
 * [LENT] is the load-bearing one: it is the difference between spending 320 on lunch
 * and spending 200 on lunch while holding 120 of someone else's money. Rows in a LENT
 * category are carried as owed to you rather than as expenditure, which is why they
 * are written with countsAsSpending = false.
 *
 * [GIVEN] is ordinary spending that happens to have been for someone else. It counts
 * in every total and every budget, exactly like Food does - the only thing it changes
 * is that picking it offers to split the bill, because a payment made for someone else
 * is very often only partly for them.
 */
enum class Sharing { NONE, GIVEN, LENT }

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bank", "accountToken"], unique = true)],
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** "ICICI", "UNION", or "CASH". */
    val bank: String,
    /** Masked digits exactly as the bank prints them: 3 for ICICI, 4 for Union. */
    val accountToken: String,
    val kind: AccountKind = AccountKind.BANK,
    val openingBalancePaise: Long = 0,
    val currentBalancePaise: Long = 0,
    /**
     * True only if this bank sends "Avl Bal". Union does; ICICI does not.
     * When false the balance is computed and drifts, so the account is prompted
     * for reconciliation once per salary cycle.
     */
    val providesBalance: Boolean = false,
    /** Set when auto-created from an unseen SMS; clears once you confirm or rename it. */
    val needsConfirmation: Boolean = false,
    val lastReconciledAt: Long? = null,
    /**
     * Newline-separated names this account is known by in other apps ("Salary",
     * "Card"). Lets an import bind to the right account every time rather than once.
     */
    val aliases: String? = null,
    /** Key into the shipped icon set. Null until seeded; see domain/Looks.kt. */
    val iconKey: String? = null,
    /** "#RRGGBB". Null until seeded. */
    val colourHex: String? = null,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("accountId"), Index("reference"), Index("occurredAt"),
        Index("transferGroupId"), Index("fingerprint"), Index("importBatchId"),
        Index("splitGroupId"),
    ],
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    val payee: String?,
    val reference: String?,
    val channel: Channel = Channel.UNKNOWN,
    val categoryId: Long? = null,
    /**
     * False for transfers (ATM withdrawals, self-transfers between your accounts)
     * and adjustments. Only true rows count toward spending and budgets - this single
     * flag is what stops a 3,000 move between your own accounts reading as expenditure.
     */
    val countsAsSpending: Boolean,
    /** Both legs of a transfer share this. Null for ordinary transactions. */
    val transferGroupId: String? = null,
    /**
     * Every part of one split bill shares this, and so does the repayment that settles
     * it. Null for ordinary transactions.
     *
     * The group is the unit of truth: its parts must always sum to what the bank
     * actually took, which is why parts are edited through the split sheet rather than
     * one at a time. See domain/Splitter.kt.
     */
    val splitGroupId: String? = null,
    /**
     * Who owes you this part, as you typed it. Only meaningful on a row in a LENT
     * category; null everywhere else.
     *
     * Free text rather than a contact or a row in a people table, because the only
     * thing the app needs it for is grouping - and a name you type is a name you can
     * still read in two years when the contact is gone.
     */
    val owedBy: String? = null,
    val source: TxnSource = TxnSource.SMS,
    val note: String? = null,
    val rawMessage: String? = null,
    /** Set on rows written by an import. Null for everything the app recorded itself. */
    val importBatchId: String? = null,
    /** Stable hash used to recognise a row we have already imported. See ImportedRow. */
    val fingerprint: String? = null,
    /**
     * True when the source gave a date but no usable time and we placed it at midday.
     * Kept explicit so nothing downstream mistakes a guess for a timestamp.
     */
    @ColumnInfo(defaultValue = "0") val timeWasInferred: Boolean = false,
    /**
     * Imported bank row that had no matching SMS transaction. Counted per month it is
     * a direct measure of what SMS capture missed, which is why it is a column and not
     * a note.
     */
    @ColumnInfo(defaultValue = "0") val noSmsCounterpart: Boolean = false,
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false,
    /** Income categories are hidden from expense pickers and quick-add chips. */
    @ColumnInfo(defaultValue = "0") val isIncome: Boolean = false,
    /**
     * Whether picking this category means part of the money was someone else's.
     * Seeded by CategorySync and editable, because which categories you use that way
     * is your habit rather than the app's opinion.
     */
    @ColumnInfo(defaultValue = "NONE") val sharing: Sharing = Sharing.NONE,
    /** Key into the shipped icon set. Null until seeded; see domain/Looks.kt. */
    val iconKey: String? = null,
    /** "#RRGGBB". Null until seeded. */
    val colourHex: String? = null,
)

/**
 * Learned payee -> category mapping. Keyed on the payee string exactly as the bank
 * prints it, including ICICI's 15-character truncation ("KA 05 JUICE BAR"). The
 * truncation is stable, so matching on it is reliable.
 */
@Entity(tableName = "category_rules", indices = [Index(value = ["payeeKey"], unique = true)])
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payeeKey: String,
    val categoryId: Long,
    /** Seeded rules ship with the app; learned ones come from your corrections. */
    val learned: Boolean = true,
)

/** Null categoryId means the overall cap for the cycle. */
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,
    val limitPaise: Long,
    /**
     * Null means a standing limit that applies to every cycle - which is what every
     * pre-v3 budget becomes. A value pins this limit to the one cycle starting at that
     * millisecond, and it wins over the standing limit for that cycle only.
     */
    val periodStart: Long? = null,
)

/** Financial-looking messages no rule matched. Never auto-booked, never discarded. */
@Entity(tableName = "pending_reviews")
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val rawMessage: String,
    val receivedAt: Long,
    val reason: String,
)

/**
 * The salary-cycle anchor. Cycles roll on the real salary credit rather than on
 * arithmetic, because "last working day" ignores bank holidays and because payroll
 * announced on a date is not always credited on it.
 */
@Entity(tableName = "cycle_state")
data class CycleState(
    @PrimaryKey val id: Long = 1,
    val cycleStartMillis: Long,
    /** Learned when you tag a credit as salary. Null until then - fallback rule applies. */
    val salaryBank: String? = null,
    val salaryPayee: String? = null,
    val salaryAmountPaise: Long? = null,
    val lastRollMillis: Long,
)

/**
 * One salary cycle boundary. [CycleState] only ever knows the cycle you are in, so
 * without this table the app cannot say when any earlier cycle began - and the period
 * arrows would have nothing to walk back through.
 *
 * [inferred] is the honest part: true means no salary credit was found for that month
 * and the boundary came from CycleCalculator's last-working-day arithmetic, which is
 * wrong whenever payroll moved for a holiday. The UI says so rather than presenting a
 * guess as a fact.
 */
@Entity(tableName = "cycle_history", indices = [Index(value = ["startMillis"], unique = true)])
data class CycleBoundary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    /** SALARY | FALLBACK | MANUAL */
    val source: String,
    val inferred: Boolean,
    /** The credit this boundary was read from, when there was one. */
    val txnId: Long? = null,
)

/** Budget alert bookkeeping, so 80%/100% notifications fire once per cycle, not per SMS. */
@Entity(tableName = "budget_alerts", primaryKeys = ["budgetId", "cycleStartMillis", "threshold"])
data class BudgetAlert(
    val budgetId: Long,
    val cycleStartMillis: Long,
    val threshold: Int,
)

/**
 * One import run. Holds enough to explain and undo itself: the counts must satisfy
 * rowsImported + rowsQueued + rowsRejected == rowsTotal, and deleting every
 * transaction carrying this id returns the ledger to its prior state.
 */
@Entity(tableName = "import_batches")
data class ImportBatch(
    @PrimaryKey val id: String,
    val fileName: String,
    val importedAt: Long,
    val profileName: String,
    val rowsTotal: Int,
    val rowsImported: Int,
    val rowsQueued: Int,
    val rowsRejected: Int,
    /** Full report: mapping decisions, and every rejected row with its reason. */
    val reportJson: String,
    /** False for a dry run, which writes a batch row and nothing else. */
    val committed: Boolean = true,
)

/**
 * The verbatim source row behind an imported transaction - every column, mapped or
 * not. This is what makes an import recoverable from a bad mapping without asking
 * for the file again, so it is stored even for rows that were rejected.
 */
@Entity(tableName = "imported_rows", indices = [Index("batchId"), Index("txnId")])
data class ImportedRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val lineNumber: Int,
    val rawLine: String,
    val rawJson: String,
    val txnId: Long? = null,
    /** IMPORTED | MERGED | QUEUED | REJECTED */
    val outcome: String,
    val reason: String? = null,
)

/**
 * One quick-add chip, derived from what you have actually entered before rather than
 * from a list you have to maintain. Not a table: it is a projection over transactions,
 * so it stays true as habits change and there is nothing to keep in sync.
 */
data class QuickAddSuggestion(
    val label: String,
    val amountPaise: Long,
    val categoryId: Long?,
    val accountId: Long,
    val uses: Int,
    val lastUsedAt: Long,
)
