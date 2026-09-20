package com.yk.finance.domain

import com.yk.finance.data.CycleBoundary
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One salary cycle, as the interface presents it.
 *
 * [startMillis] is the real boundary - the day salary landed. [fromInclusive] is what
 * filtering actually uses, and differs for the earliest period only: anything older
 * than the first known boundary belongs to it, so an imported row from before the app
 * existed cannot fall through every period and become invisible.
 */
data class Period(
    val startMillis: Long,
    val fromInclusive: Long,
    val toExclusive: Long,
    val label: String,
    /** The boundary was arithmetic, not an observed salary credit. */
    val inferred: Boolean,
    /** The cycle you are in now: it has no end yet. */
    val isOpen: Boolean,
) {
    fun contains(millis: Long): Boolean = millis >= fromInclusive && millis < toExclusive
}

/**
 * The periods the app can show, in both flavours it knows about.
 *
 * [months] is what the interface walks: plain calendar months, which is what a figure
 * labelled "September" has to mean if it is to be trusted. [from] builds the older
 * salary-cycle periods and is still read by Settings, where the detected cycles are
 * shown as information about your payroll rather than as the app's idea of a month.
 *
 * The cycle label rule is start + 7 days. A cycle opened by salary on 30 Sep is
 * October's, and so is one opened by a late salary on 2 Oct - a week of slack in either
 * direction around the month end, which is the range real payroll actually moves in.
 */
object Periods {

    const val LABEL_OFFSET_DAYS = 7L

    private val LABEL: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM, yyyy", Locale.ENGLISH)

    fun labelFor(startMillis: Long): String =
        CycleCalculator.toLocalDate(startMillis).plusDays(LABEL_OFFSET_DAYS).format(LABEL)

    /**
     * Ascending periods, oldest first, the last one open.
     *
     * Boundaries arrive already unique (the table enforces it) but not necessarily
     * sorted, and a boundary in the future - a salary credit dated ahead - would
     * otherwise open a period nothing can be recorded into, so those are dropped.
     */
    fun from(boundaries: List<CycleBoundary>, now: Long): List<Period> {
        val starts = boundaries
            .filter { it.startMillis <= now }
            .distinctBy { it.startMillis }
            .sortedBy { it.startMillis }

        if (starts.isEmpty()) {
            val seeded = CycleCalculator.seed(now).cycleStartMillis
            return listOf(
                Period(
                    startMillis = seeded,
                    fromInclusive = Long.MIN_VALUE,
                    toExclusive = Long.MAX_VALUE,
                    label = labelFor(seeded),
                    inferred = true,
                    isOpen = true,
                ),
            )
        }

        return starts.mapIndexed { index, boundary ->
            val next = starts.getOrNull(index + 1)
            Period(
                startMillis = boundary.startMillis,
                fromInclusive = if (index == 0) Long.MIN_VALUE else boundary.startMillis,
                toExclusive = next?.startMillis ?: Long.MAX_VALUE,
                label = labelFor(boundary.startMillis),
                inferred = boundary.inferred,
                isOpen = next == null,
            )
        }
    }

    // ----- calendar months, which is what the interface actually walks -----

    /**
     * The months the period strip offers, oldest first.
     *
     * Bounded by the ledger rather than by arithmetic: a month exists because something
     * happened in it, or because it is the one we are in. That is why the arrows can go
     * dead at the ends - "there is nothing older" is a fact worth stating, and is a very
     * different thing from a screen full of zeroes that leaves you guessing whether the
     * app is empty or broken.
     *
     * [latestMillis] extends the range forward so a future-dated entry always has a
     * month you can reach. Without it the row would count in Accounts while being
     * invisible in Records, which is the one way a transaction can go missing here.
     */
    fun months(earliestMillis: Long?, latestMillis: Long?, now: Long): List<Period> {
        val current = YearMonth.from(CycleCalculator.toLocalDate(now))
        val oldest = earliestMillis?.let { YearMonth.from(CycleCalculator.toLocalDate(it)) }
        val newest = latestMillis?.let { YearMonth.from(CycleCalculator.toLocalDate(it)) }

        val first = if (oldest != null && oldest < current) oldest else current
        val last = if (newest != null && newest > current) newest else current

        val out = mutableListOf<Period>()
        var month = first
        while (month <= last) {
            out.add(monthPeriod(month, current))
            month = month.plusMonths(1)
        }
        return out
    }

    private fun monthPeriod(month: YearMonth, current: YearMonth): Period = Period(
        startMillis = CycleCalculator.startOfDayMillis(month.atDay(1)),
        fromInclusive = CycleCalculator.startOfDayMillis(month.atDay(1)),
        toExclusive = CycleCalculator.startOfDayMillis(month.plusMonths(1).atDay(1)),
        label = month.atDay(1).format(LABEL),
        // A calendar month has no estimated start. Nothing here is guessed, so nothing
        // here gets the "start estimated" warning the cycle model needed.
        inferred = false,
        isOpen = month == current,
    )

    /** First of the month containing [millis], at 00:00 IST. */
    fun monthStartOf(millis: Long): Long =
        CycleCalculator.startOfDayMillis(
            CycleCalculator.toLocalDate(millis).withDayOfMonth(1),
        )

    /**
     * The month a cycle starting at [cycleStartMillis] was *labelled* with.
     *
     * A cycle opening on 31 Aug was shown to you as "September, 2026" - the +7 day rule
     * in [labelFor]. When a cycle-pinned thing has to become a month-pinned thing, this
     * is the honest mapping: the month you were looking at when you set it, not the
     * month the boundary arithmetic happened to land in.
     */
    fun labelledMonthStart(cycleStartMillis: Long): Long =
        CycleCalculator.startOfDayMillis(
            CycleCalculator.toLocalDate(cycleStartMillis)
                .plusDays(LABEL_OFFSET_DAYS)
                .withDayOfMonth(1),
        )

    /** Index of the period holding [millis], or the last one if it is newer than all. */
    fun indexContaining(periods: List<Period>, millis: Long): Int {
        if (periods.isEmpty()) return 0
        val found = periods.indexOfFirst { it.contains(millis) }
        return if (found >= 0) found else periods.lastIndex
    }
}
