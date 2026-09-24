package com.yk.finance.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.yk.finance.FinanceApplication
import com.yk.finance.MainActivity
import com.yk.finance.R
import com.yk.finance.domain.BudgetResolver
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.Periods
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Where a tap on the widget should land. Read by MainActivity. */
object WidgetRoute {
    const val EXTRA = "com.yk.finance.widget.ROUTE"
    const val BUDGETS = "budgets"
    const val ADD_EXPENSE = "add_expense"
}

/**
 * Asks every placed widget to redraw.
 *
 * Called from the places that can move the figures rather than on a timer. A widget
 * showing a payment you made two minutes ago as not-yet-recorded is the fastest way to
 * stop believing it, and a 30-minute poll guarantees that happens several times a day.
 *
 * There are only three such places, and between them they are exhaustive:
 *
 *  - SmsReceiver, for everything that lands while the app is closed.
 *  - MainActivity.onStop, for everything you did inside it. This is why no mutation in
 *    the interface needs its own call: the widget lives on the home screen, so you
 *    cannot look at it without backgrounding the app first, and onStop has already run
 *    by then. Plumbing a Context into the ViewModel to beat a redraw nobody can see
 *    would be indirection bought with nothing.
 *  - The date rolling over, which is the one figure that moves with no action at all.
 */
object WidgetRefresh {

    fun request(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context.applicationContext, SpendingWidget::class.java),
        )
        if (ids.isEmpty()) return
        context.applicationContext.sendBroadcast(
            Intent(context.applicationContext, SpendingWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            },
        )
    }
}

/**
 * The home screen widget: what this salary cycle has cost, against a ceiling.
 *
 * Reads the ledger directly rather than caching a figure anywhere. The database is the
 * only thing that knows, and a widget with its own copy of the truth is a widget that
 * can disagree with the app - which is worse than one that is occasionally a second
 * late.
 */
class SpendingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, manager, appWidgetIds)
    }

    /**
     * The date changing is the one thing that moves a figure with no user action behind
     * it: "payday in 8 days" is wrong from midnight until something else happens to
     * trigger a redraw, which over a quiet weekend can be two days.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> WidgetRefresh.request(context)
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext as? FinanceApplication ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = build(context, snapshot(app))
                ids.forEach { manager.updateAppWidget(it, views) }
            } catch (t: Throwable) {
                // A widget that fails to draw keeps whatever it last drew, which is
                // stale but readable. Crashing the provider would leave a grey box.
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Reads the cycle window, then the three figures that fill it.
     *
     * The budget looked up is the one effective for the month this cycle is *labelled*
     * with, not the month its start date falls in - a cycle opening on 31 Aug is
     * September's, and September's cap is the one you set while looking at it.
     */
    private suspend fun snapshot(app: FinanceApplication): WidgetState.Snapshot {
        val now = System.currentTimeMillis()
        val cycleStart = app.dao.cycleState()?.cycleStartMillis
            ?: CycleCalculator.seed(now).cycleStartMillis

        val spent = Ledger.totals(app.dao.spendingBetween(cycleStart, now)).expensePaise
        val income = app.dao.incomeBetween(cycleStart, now)
            .filter(Ledger::isIncome)
            .sumOf { it.amountPaise }

        val budget = BudgetResolver
            .effective(app.dao.allBudgets(), Periods.labelledMonthStart(cycleStart))
            .firstOrNull { it.budget.categoryId == null }
            ?.budget
            ?.limitPaise

        return WidgetState.of(cycleStart, now, spent, budget, income)
    }

    private fun build(context: Context, state: WidgetState.Snapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_spending).apply {
            setTextViewText(R.id.widget_period, state.periodLabel)

            setTextViewText(
                R.id.widget_amount,
                when (state.ceiling) {
                    // "in" is doing real work: it is the only thing on the widget that
                    // says the ceiling is what arrived rather than what you decided.
                    WidgetState.Ceiling.INCOME ->
                        "${WidgetState.shortRupees(state.spentPaise)} of " +
                            "${WidgetState.shortRupees(state.ceilingPaise)} in"
                    WidgetState.Ceiling.BUDGET ->
                        "${WidgetState.shortRupees(state.spentPaise)} of " +
                            WidgetState.shortRupees(state.ceilingPaise)
                    WidgetState.Ceiling.NONE ->
                        "${WidgetState.shortRupees(state.spentPaise)} spent"
                },
            )

            setViewVisibility(R.id.widget_bar_row, if (state.hasBar) View.VISIBLE else View.GONE)
            if (state.hasBar) {
                setProgressBar(R.id.widget_bar, 100, state.barPercent, false)
                setInt(
                    R.id.widget_bar,
                    "setProgressDrawableTiled",
                    if (state.exceeded) R.drawable.widget_progress_over else R.drawable.widget_progress,
                )
                setTextViewText(R.id.widget_percent, "${state.percent}%")
            }

            val days = "${state.daysToPayday} days"
            setTextViewText(
                R.id.widget_footer,
                when {
                    // Said plainly rather than shown as an empty trough: a bar at zero
                    // reads as "you have spent nothing", which is a different claim.
                    state.ceiling == WidgetState.Ceiling.NONE -> "waiting for salary · $days"
                    state.exceeded -> "over by ${WidgetState.shortRupees(state.overPaise)} · $days"
                    else -> "${WidgetState.shortRupees(state.remainingPaise)} left · $days"
                },
            )

            setOnClickPendingIntent(R.id.widget_root, open(context, WidgetRoute.BUDGETS))
            setOnClickPendingIntent(R.id.widget_add, open(context, WidgetRoute.ADD_EXPENSE))
        }

    /**
     * Distinct request codes per route, because PendingIntents with the same code and
     * matching filters are the *same* intent - both taps would open whichever was
     * registered last, and the bug only shows up on a real home screen.
     */
    private fun open(context: Context, route: String): PendingIntent = PendingIntent.getActivity(
        context,
        route.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(WidgetRoute.EXTRA, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
