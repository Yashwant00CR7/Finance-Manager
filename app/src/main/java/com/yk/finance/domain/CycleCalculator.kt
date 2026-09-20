package com.yk.finance.domain

import com.yk.finance.data.CycleState
import com.yk.finance.parser.Direction
import com.yk.finance.parser.ParsedSms
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** Minimum gap between cycle rolls, so a refund or a second credit cannot double-roll. */
const val MIN_DAYS_BETWEEN_ROLLS = 20L

/** A salary credit may vary (bonus, arrears, deductions) but not wildly. */
const val SALARY_TOLERANCE = 0.20

object CycleCalculator {

    /**
     * Your stated rule: the last Monday-Friday of the month.
     *
     * This is the FALLBACK only. It cannot see bank holidays - if the last working
     * day is Diwali, payroll lands earlier and this date is wrong. That is exactly
     * why the real anchor is the salary credit itself; this just guarantees the app
     * never stalls in a month where no salary is detected.
     */
    fun lastWorkingDayOf(year: Int, month: Int): LocalDate {
        var date = LocalDate.of(year, month, 1).withDayOfMonth(
            LocalDate.of(year, month, 1).lengthOfMonth(),
        )
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.minusDays(1)
        }
        return date
    }

    fun lastWorkingDayOf(date: LocalDate): LocalDate = lastWorkingDayOf(date.year, date.monthValue)

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(IST).toInstant().toEpochMilli()

    fun toLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(IST).toLocalDate()

    private fun daysBetween(fromMillis: Long, toMillis: Long): Long =
        java.time.temporal.ChronoUnit.DAYS.between(toLocalDate(fromMillis), toLocalDate(toMillis))

    /**
     * Does this incoming credit look like the salary we have learned?
     *
     * Requires the same bank, a recognisable payee, an amount within tolerance, and
     * a minimum gap since the last roll. All four exist to stop an ordinary large
     * credit - a refund, or money from family - from rolling the cycle early.
     */
    fun isSalaryCredit(sms: ParsedSms, state: CycleState, now: Long): Boolean {
        if (sms.direction != Direction.CREDIT) return false
        val learnedAmount = state.salaryAmountPaise ?: return false
        if (state.salaryBank != null && !state.salaryBank.equals(sms.bank, ignoreCase = true)) return false

        state.salaryPayee?.let { learnedPayee ->
            val incoming = sms.payee?.trim().orEmpty()
            if (incoming.isBlank()) return false
            val matches = incoming.equals(learnedPayee, ignoreCase = true) ||
                incoming.contains(learnedPayee, ignoreCase = true) ||
                learnedPayee.contains(incoming, ignoreCase = true)
            if (!matches) return false
        }

        val drift = abs(sms.amountPaise - learnedAmount).toDouble() / learnedAmount.toDouble()
        if (drift > SALARY_TOLERANCE) return false

        return daysBetween(state.lastRollMillis, now) >= MIN_DAYS_BETWEEN_ROLLS
    }

    /**
     * Fallback roll: we have passed this month's computed last working day and the
     * salary credit never arrived (or was never tagged). Keeps budgets moving.
     */
    fun shouldRollByDate(state: CycleState, now: Long): Boolean {
        if (daysBetween(state.lastRollMillis, now) < MIN_DAYS_BETWEEN_ROLLS) return false
        val today = toLocalDate(now)
        val boundary = lastWorkingDayOf(today)
        return !today.isBefore(boundary)
    }

    /** The cycle starts ON salary day - your 30 Sep salary opens October, not closes September. */
    fun rolled(state: CycleState, at: Long): CycleState =
        state.copy(cycleStartMillis = startOfDayMillis(toLocalDate(at)), lastRollMillis = at)

    /** First-run seed: current cycle began at the previous month's last working day. */
    fun seed(now: Long): CycleState {
        val today = toLocalDate(now)
        val thisMonthBoundary = lastWorkingDayOf(today)
        val start = if (!today.isBefore(thisMonthBoundary)) {
            thisMonthBoundary
        } else {
            lastWorkingDayOf(today.minusMonths(1))
        }
        return CycleState(
            cycleStartMillis = startOfDayMillis(start),
            lastRollMillis = startOfDayMillis(start),
        )
    }
}
