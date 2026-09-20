package com.yk.finance

import android.app.Application
import com.yk.finance.data.AppDatabase
import com.yk.finance.data.FinanceDao
import com.yk.finance.domain.BudgetEvaluator
import com.yk.finance.domain.BudgetMigration
import com.yk.finance.domain.CategorySync
import com.yk.finance.domain.CycleBackfill
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.ImportService
import com.yk.finance.domain.LookSync
import com.yk.finance.domain.Repository
import com.yk.finance.domain.SmsIngestor
import com.yk.finance.parser.RuleBasedParser
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
    val ingestor: SmsIngestor by lazy { SmsIngestor(dao, RuleBasedParser()) }
    val budgets: BudgetEvaluator by lazy { BudgetEvaluator(dao) }

    /** Holds the database, not just the dao: an import has to be one transaction. */
    val imports: ImportService by lazy { ImportService(database, dao) }
    val repository: Repository by lazy { Repository(dao, imports) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch { seed() }
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
    }
}
