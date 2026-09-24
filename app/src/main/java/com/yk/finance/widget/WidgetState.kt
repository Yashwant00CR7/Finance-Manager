package com.yk.finance.widget

import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.Periods
import com.yk.finance.domain.groupIndianDigits
import java.time.temporal.ChronoUnit

/**
 * Everything the widget draws, computed from plain numbers.
 *
 * Pure and Android-free on purpose, exactly like SenderGate. A widget is the one
 * surface in this app nobody looks at deliberately - it is read in passing, believed,
 * and acted on - so the branching behind the figure is worth being able to exercise
 * exhaustively on the JVM rather than by resizing a home screen.
 */
object WidgetState {

    /** Where the ceiling the bar measures against came from. */
    enum class Ceiling {
        /** A cap you set. The bar means what you meant it to mean. */
        BUDGET,

        /** No budget, so what arrived this cycle is the ceiling instead. */
        INCOME,

        /** Neither. There is nothing honest to draw, so no bar is drawn. */
        NONE,
    }

    data class Snapshot(
        /** "September" - the month this salary cycle is labelled with. */
        val periodLabel: String,
        val spentPaise: Long,
        val ceilingPaise: Long,
        val ceiling: Ceiling,
        val daysToPayday: Int,
    ) {
        val hasBar: Boolean get() = ceiling != Ceiling.NONE

        /** Uncapped, so going over is visible as a number rather than a full bar. */
        val percent: Int
            get() = if (ceilingPaise <= 0) 0 else ((spentPaise * 100) / ceilingPaise).toInt()

        /** What the bar itself draws. Clamped, because a trough cannot be 120% full. */
        val barPercent: Int get() = percent.coerceIn(0, 100)

        val exceeded: Boolean get() = hasBar && spentPaise > ceilingPaise

        val remainingPaise: Long get() = (ceilingPaise - spentPaise).coerceAtLeast(0)

        val overPaise: Long get() = (spentPaise - ceilingPaise).coerceAtLeast(0)
    }

    /**
     * [budgetPaise] is the overall cap effective for the month this cycle is labelled
     * with, or null when none is set. [incomePaise] is what actually arrived inside the
     * cycle window.
     *
     * The order matters and is the whole of the fallback: a cap you set always wins,
     * because it is a statement of intent and income is only ever a description of what
     * happened. Income takes over when there is no cap, which makes the widget useful
     * before you have ever opened the Budgets screen. When neither exists there is no
     * denominator, and the honest thing is to show no bar rather than invent one.
     */
    fun of(
        cycleStartMillis: Long,
        nowMillis: Long,
        spentPaise: Long,
        budgetPaise: Long?,
        incomePaise: Long,
    ): Snapshot {
        val ceilingPaise: Long
        val ceiling: Ceiling
        when {
            budgetPaise != null && budgetPaise > 0 -> {
                ceilingPaise = budgetPaise
                ceiling = Ceiling.BUDGET
            }
            incomePaise > 0 -> {
                ceilingPaise = incomePaise
                ceiling = Ceiling.INCOME
            }
            else -> {
                ceilingPaise = 0
                ceiling = Ceiling.NONE
            }
        }

        return Snapshot(
            periodLabel = Periods.labelFor(cycleStartMillis).substringBefore(','),
            spentPaise = spentPaise,
            ceilingPaise = ceilingPaise,
            ceiling = ceiling,
            daysToPayday = daysToPayday(nowMillis),
        )
    }

    /**
     * Days until the next expected salary, from the same last-working-day arithmetic
     * the cycle itself rolls on. It is a prediction, not an observation - payroll moves
     * for holidays - which is why the widget says "8 days" and never a date.
     */
    fun daysToPayday(nowMillis: Long): Int {
        val today = CycleCalculator.toLocalDate(nowMillis)
        val thisMonth = CycleCalculator.lastWorkingDayOf(today)
        val next =
            if (today.isBefore(thisMonth)) thisMonth
            else CycleCalculator.lastWorkingDayOf(today.plusMonths(1))
        return ChronoUnit.DAYS.between(today, next).toInt().coerceAtLeast(0)
    }

    /**
     * Whole rupees, Indian grouping, no paise.
     *
     * The app keeps paise everywhere else and prints them, because a ledger that
     * rounds is a ledger you stop trusting. A widget is read at arm's length while
     * doing something else, and ".00" on every figure costs width that the bar needs
     * more - so this rounds down, and only here.
     */
    fun shortRupees(paise: Long): String = "₹" + groupIndianDigits(paise / 100)
}
