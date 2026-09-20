package com.yk.finance.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yk.finance.data.BudgetAlert
import com.yk.finance.data.FinanceDao

data class BudgetProgress(
    val budgetId: Long,
    val categoryId: Long?,
    val categoryName: String,
    val limitPaise: Long,
    val spentPaise: Long,
    /** A limit pinned to this cycle rather than the standing one. */
    val isOverride: Boolean = false,
) {
    val percent: Int get() = if (limitPaise <= 0) 0 else ((spentPaise * 100) / limitPaise).toInt()

    /** Never negative: overspending is reported by [percent] and [exceeded], not here. */
    val remainingPaise: Long get() = (limitPaise - spentPaise).coerceAtLeast(0)

    val exceeded: Boolean get() = spentPaise > limitPaise
}

/**
 * Budgets run on the salary cycle, not the calendar month, and count only rows flagged
 * countsAsSpending - so ATM withdrawals and transfers between your own accounts are
 * correctly invisible here.
 *
 * Every read is scoped to a cycle window rather than "since the current cycle start",
 * because the period strip can be pointed at any cycle and the numbers under it have to
 * be that cycle's, not today's.
 */
class BudgetEvaluator(private val dao: FinanceDao) {

    /**
     * The month the app is in now.
     *
     * This used to return the newest salary boundary. Left that way, the 80% alert that
     * interrupts your phone would measure 31 Aug onward while the Budgets screen
     * measured 1-30 September - two different answers for one budget, and the wrong one
     * is the one that buzzes.
     */
    fun currentPeriodStart(now: Long = System.currentTimeMillis()): Long =
        Periods.monthStartOf(now)

    suspend fun progress(periodStart: Long, periodEnd: Long): List<BudgetProgress> {
        val spending = dao.spendingBetween(periodStart, periodEnd)
        val categories = dao.allCategories().associateBy { it.id }

        return BudgetResolver.effective(dao.allBudgets(), periodStart).map { effective ->
            val budget = effective.budget
            val spent = if (budget.categoryId == null) {
                spending.sumOf { it.amountPaise }
            } else {
                spending.filter { it.categoryId == budget.categoryId }.sumOf { it.amountPaise }
            }
            BudgetProgress(
                budgetId = budget.id,
                categoryId = budget.categoryId,
                categoryName = budget.categoryId?.let { categories[it]?.name } ?: "Overall",
                limitPaise = budget.limitPaise,
                spentPaise = spent,
                isOverride = effective.isOverride,
            )
        }
    }

    /**
     * Spending this cycle that has no category yet. Surfaced next to budgets because a
     * budget reading "40% used" is misleading while unlabelled spending is sitting
     * outside it - the number is provisional until the rules are trained.
     */
    suspend fun uncategorisedSpendPaise(periodStart: Long, periodEnd: Long): Long =
        dao.spendingBetween(periodStart, periodEnd)
            .filter { it.categoryId == null }
            .sumOf { it.amountPaise }

    /** Fires once per threshold per calendar month, not once per message. */
    suspend fun evaluateAndNotify(context: Context) {
        val now = System.currentTimeMillis()
        val periodStart = currentPeriodStart(now)
        val periodEnd = CycleCalculator.startOfDayMillis(
            CycleCalculator.toLocalDate(periodStart).plusMonths(1),
        )
        ensureChannel(context)

        // Bounded by the month's end rather than left open: a limit was blown in
        // September only if September's spending blew it, and an open upper bound would
        // keep folding October in.
        progress(periodStart, periodEnd).forEach { p ->
            val threshold = when {
                p.percent >= 100 -> 100
                p.percent >= 80 -> 80
                else -> return@forEach
            }
            if (dao.alertAlreadySent(p.budgetId, periodStart, threshold) > 0) return@forEach
            dao.recordAlert(BudgetAlert(p.budgetId, periodStart, threshold))
            notify(context, p, threshold)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Budget alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Warns at 80% and 100% of a budget for the current salary cycle" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(context: Context, p: BudgetProgress, threshold: Int) {
        // Without POST_NOTIFICATIONS on Android 13+ this silently does nothing, which
        // looks exactly like a broken budget feature - so check rather than assume.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val title = if (threshold >= 100) "${p.categoryName} budget exceeded"
        else "${p.categoryName} budget at ${p.percent}%"

        val text = "${formatRupees(p.spentPaise)} of ${formatRupees(p.limitPaise)} this cycle"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(p.budgetId.toInt() * 10 + threshold, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "budget_alerts"
    }
}

/** Paise -> "1,234.56". Integer arithmetic only; no Double ever touches money. */
fun formatRupees(paise: Long): String {
    val negative = paise < 0
    val abs = kotlin.math.abs(paise)
    val rupees = abs / 100
    val fraction = abs % 100
    val grouped = groupIndianDigits(rupees)
    val sign = if (negative) "-" else ""
    return "$sign₹$grouped.${fraction.toString().padStart(2, '0')}"
}

/** Indian grouping: last three digits, then pairs (12,34,567). */
internal fun groupIndianDigits(value: Long): String {
    val s = value.toString()
    if (s.length <= 3) return s
    val last3 = s.takeLast(3)
    val rest = s.dropLast(3)
    val pairs = StringBuilder()
    var i = rest.length
    while (i > 0) {
        val start = maxOf(0, i - 2)
        if (pairs.isNotEmpty()) pairs.insert(0, ",")
        pairs.insert(0, rest.substring(start, i))
        i = start
    }
    return "$pairs,$last3"
}
