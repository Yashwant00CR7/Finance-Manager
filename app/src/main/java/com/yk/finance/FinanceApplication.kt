package com.yk.finance

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.yk.finance.data.AppDatabase
import com.yk.finance.data.FinanceDao
import com.yk.finance.data.Prefs
import com.yk.finance.domain.BudgetEvaluator
import com.yk.finance.domain.BudgetMigration
import com.yk.finance.domain.CatchUp
import com.yk.finance.domain.CatchUpAlert
import com.yk.finance.domain.CatchUpState
import com.yk.finance.domain.MessageGate
import com.yk.finance.domain.CategoryModel
import com.yk.finance.domain.CategorySync
import com.yk.finance.domain.GuessOutcomes
import com.yk.finance.domain.CycleBackfill
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.ImportService
import com.yk.finance.domain.LookSync
import com.yk.finance.domain.Repository
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.parser.RuleBasedParser
import com.yk.finance.sms.InboxReader
import com.yk.finance.sms.canReadInbox
import com.yk.finance.widget.WidgetRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual wiring. The graph is four objects deep - a DI framework would add build time
 * and indirection for nothing.
 */
class FinanceApplication : Application() {

    private val database: AppDatabase by lazy { AppDatabase.get(this) }

    val dao: FinanceDao by lazy { database.dao() }
    val prefs: Prefs by lazy { Prefs(this) }

    /**
     * One instance, shared by the ingestor that reads it and the repository that
     * teaches it. A second copy would be a second opinion, and they would diverge the
     * first time you corrected anything.
     */
    val model: CategoryModel by lazy { CategoryModel() }

    val ingestor: SmsIngestor by lazy { SmsIngestor(dao, RuleBasedParser(), model) }
    val budgets: BudgetEvaluator by lazy { BudgetEvaluator(dao) }

    /** The one door every SMS goes through, live or recovered, so none is filed twice. */
    val gate: MessageGate by lazy { MessageGate(dao, ingestor) }

    private val catchUp: CatchUp by lazy {
        CatchUp(dao, gate, object : CatchUpState {
            override var lastScanAt: Long?
                get() = prefs.lastCatchUpAt
                set(value) { prefs.lastCatchUpAt = value }
        })
    }

    /** Completes once seeding has run, so a scan never files against an unloaded model. */
    private val seeded = CompletableDeferred<Unit>()

    /** Holds the database, not just the dao: an import has to be one transaction. */
    val imports: ImportService by lazy { ImportService(database, dao) }
    val repository: Repository by lazy {
        Repository(dao, imports, model, outcomes, atomically = { block -> database.withTransaction { block() } })
    }

    /** Keeps the accuracy counters out of the domain layer, which has no Android in it. */
    private val outcomes = object : GuessOutcomes {
        override fun confirmed() = prefs.recordGuessConfirmed()
        override fun corrected() = prefs.recordGuessCorrected()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try { seed() } finally { seeded.complete(Unit) }
        }
        listenForPowerSaverOff()
    }

    /**
     * Scans the inbox for bank messages the live receiver never got.
     *
     * Called when the app comes to the foreground and when power saving is switched off.
     * Without READ_SMS it does nothing and the app stays live-only, as it always was.
     */
    fun requestCatchUp() {
        if (!canReadInbox(this)) return
        scope.launch {
            seeded.await()
            try {
                val report = catchUp.run(InboxReader(this@FinanceApplication), System.currentTimeMillis())
                if (report.changedLedger) {
                    WidgetRefresh.request(this@FinanceApplication)
                    budgets.evaluateAndNotify(this@FinanceApplication)
                }
                CatchUpAlert.notify(this@FinanceApplication, report)
                Log.d(TAG, "catch-up: $report")
            } catch (t: Throwable) {
                Log.e(TAG, "catch-up failed", t)
            }
        }
    }

    /**
     * Power saving ending is the moment missed messages become recoverable.
     *
     * Registered in code because this broadcast is not delivered to manifest receivers, so
     * it only fires while the process is alive. Some phones' strongest modes do not send it
     * at all; opening the app is the trigger that always works.
     */
    private fun listenForPowerSaverOff() {
        val power = getSystemService(PowerManager::class.java) ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!power.isPowerSaveMode) requestCatchUp()
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            // A system broadcast still arrives; nothing outside the app can send it here.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "FinanceApplication"
    }

    private suspend fun seed() {
        // Unconditional: this also renames the v1 categories into the vocabulary the
        // rest of the app now speaks. See CategorySync for why it is not a migration.
        CategorySync.run(dao)
        LookSync.run(dao)
        // Re-pins any cycle-pinned budget onto the calendar month it was labelled
        // with. Idempotent, so it sits here rather than behind a version flag.
        BudgetMigration.run(dao)
        val now = System.currentTimeMillis()
        if (dao.cycleState() == null) {
            dao.upsertCycle(CycleCalculator.seed(now))
        }
        // Recovers the cycle boundaries v2 never recorded, so the period arrows have
        // something to walk back through. Stops itself once the table has rows.
        CycleBackfill.run(dao, now)

        // The model is a projection over the ledger, so this is the whole of loading
        // it. Nothing is persisted and nothing can be stale: a restore from backup is
        // correct for free, because this reads whichever database is now underneath.
        //
        // An SMS arriving before this finishes finds an empty model, fails the
        // evidence floor and falls through to asking - which is the same thing the app
        // did before the model existed.
        model.autoFileEnabled = prefs.autoFileCategories
        model.rebuildFrom(dao.categorisedNotInferred())

        // After the model, so anything filed here is categorised the way a live message
        // would be. Picks up tray entries an updated parser can now read by itself.
        ingestor.fileQueued()
    }
}
