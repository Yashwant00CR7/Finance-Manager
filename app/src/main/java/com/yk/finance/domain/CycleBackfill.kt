package com.yk.finance.domain

import com.yk.finance.data.CycleBoundary
import com.yk.finance.data.CycleState
import com.yk.finance.data.FinanceDao
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reconstructs the salary-cycle boundaries that were never written down.
 *
 * Until v3 the app kept only the cycle you were in, so every earlier boundary has to be
 * recovered from the ledger itself. Credits matching the learned salary signature are
 * the real thing and are used verbatim; months where no such credit is found fall back
 * to CycleCalculator's last-working-day arithmetic and are flagged inferred, because
 * that rule cannot see a bank holiday and will be a day or two out when payroll moved.
 *
 * Runs once - the moment the table has rows it stops, so a correction you make later is
 * never overwritten by a second launch.
 */
object CycleBackfill {

    /** How far a real salary credit may sit from the arithmetic date and still be it. */
    const val MATCH_WINDOW_DAYS = 10L

    data class SalaryCandidate(
        val txnId: Long,
        val occurredAt: Long,
        val amountPaise: Long,
        val payee: String?,
        val bank: String?,
    )

    data class Boundary(
        val startMillis: Long,
        val source: String,
        val inferred: Boolean,
        val txnId: Long? = null,
    )

    suspend fun run(dao: FinanceDao, now: Long) {
        if (dao.boundaryCount() > 0) return

        val credits = dao.allCredits()
        val accounts = dao.allAccounts().associateBy { it.id }
        val candidates = credits.map { txn ->
            SalaryCandidate(
                txnId = txn.id,
                occurredAt = txn.occurredAt,
                amountPaise = txn.amountPaise,
                payee = txn.payee,
                bank = accounts[txn.accountId]?.bank,
            )
        }

        val earliest = dao.allTransactions().minOfOrNull { it.occurredAt }
        derive(candidates, dao.cycleState(), earliest, now).forEach {
            dao.insertBoundary(
                CycleBoundary(
                    startMillis = it.startMillis,
                    source = it.source,
                    inferred = it.inferred,
                    txnId = it.txnId,
                ),
            )
        }
    }

    /** Writes the boundary a live roll just crossed. Idempotent: the table ignores repeats. */
    suspend fun record(dao: FinanceDao, startMillis: Long, fromSalary: Boolean, txnId: Long? = null) {
        dao.insertBoundary(
            CycleBoundary(
                startMillis = CycleCalculator.startOfDayMillis(
                    CycleCalculator.toLocalDate(startMillis),
                ),
                source = if (fromSalary) "SALARY" else "FALLBACK",
                inferred = !fromSalary,
                txnId = txnId,
            ),
        )
    }

    /**
     * The pure half: given the credits and what the app has learned about salary,
     * which boundaries exist.
     *
     * One boundary per month from the month before the ledger starts up to now, so
     * every transaction lands inside some period. The current cycle start always wins
     * over whatever the arithmetic proposed near it - it is the one boundary the app
     * observed directly rather than reconstructed.
     */
    fun derive(
        credits: List<SalaryCandidate>,
        state: CycleState?,
        earliestTxnMillis: Long?,
        now: Long,
    ): List<Boundary> {
        val salaries = detectSalaries(credits, state)
        val today = CycleCalculator.toLocalDate(now)
        val firstDate = CycleCalculator.toLocalDate(
            earliestTxnMillis ?: state?.cycleStartMillis ?: now,
        )

        val found = mutableListOf<Boundary>()
        var month = firstDate.withDayOfMonth(1).minusMonths(1)
        while (!month.isAfter(today.withDayOfMonth(1))) {
            val fallback = CycleCalculator.lastWorkingDayOf(month.year, month.monthValue)
            val matched = salaries.firstOrNull {
                abs(ChronoUnit.DAYS.between(fallback, CycleCalculator.toLocalDate(it.occurredAt))) <=
                    MATCH_WINDOW_DAYS
            }
            val boundary = if (matched != null) {
                Boundary(
                    startMillis = CycleCalculator.startOfDayMillis(
                        CycleCalculator.toLocalDate(matched.occurredAt),
                    ),
                    source = "SALARY",
                    inferred = false,
                    txnId = matched.txnId,
                )
            } else {
                Boundary(
                    startMillis = CycleCalculator.startOfDayMillis(fallback),
                    source = "FALLBACK",
                    inferred = true,
                )
            }
            if (boundary.startMillis <= now) found += boundary
            month = month.plusMonths(1)
        }

        state?.cycleStartMillis?.let { current ->
            val authoritative = Boundary(
                startMillis = current,
                source = if (state.salaryAmountPaise != null) "SALARY" else "FALLBACK",
                inferred = state.salaryAmountPaise == null,
            )
            // Anything the arithmetic put near the live boundary is the same event
            // described worse, so it is replaced rather than kept alongside.
            found.removeAll { near(it.startMillis, current) }
            found += authoritative
        }

        return found.distinctBy { it.startMillis }.sortedBy { it.startMillis }
    }

    private fun near(a: Long, b: Long): Boolean = abs(
        ChronoUnit.DAYS.between(CycleCalculator.toLocalDate(a), CycleCalculator.toLocalDate(b)),
    ) <= MATCH_WINDOW_DAYS

    /**
     * Credits that look like salary, at most one per 20 days.
     *
     * Mirrors CycleCalculator.isSalaryCredit deliberately: a boundary recovered by a
     * looser rule than the one that rolls the live cycle would put history and the
     * present on different footings.
     */
    private fun detectSalaries(
        credits: List<SalaryCandidate>,
        state: CycleState?,
    ): List<SalaryCandidate> {
        val learned = state?.salaryAmountPaise ?: return emptyList()
        val accepted = mutableListOf<SalaryCandidate>()
        credits.sortedBy { it.occurredAt }.forEach { candidate ->
            if (!matches(candidate, state, learned)) return@forEach
            val last = accepted.lastOrNull()
            if (last != null) {
                val gap = ChronoUnit.DAYS.between(
                    CycleCalculator.toLocalDate(last.occurredAt),
                    CycleCalculator.toLocalDate(candidate.occurredAt),
                )
                if (gap < MIN_DAYS_BETWEEN_ROLLS) return@forEach
            }
            accepted += candidate
        }
        return accepted
    }

    private fun matches(candidate: SalaryCandidate, state: CycleState, learned: Long): Boolean {
        if (state.salaryBank != null && !state.salaryBank.equals(candidate.bank, ignoreCase = true)) {
            return false
        }
        state.salaryPayee?.let { learnedPayee ->
            val incoming = candidate.payee?.trim().orEmpty()
            if (incoming.isBlank()) return false
            val hit = incoming.equals(learnedPayee, ignoreCase = true) ||
                incoming.contains(learnedPayee, ignoreCase = true) ||
                learnedPayee.contains(incoming, ignoreCase = true)
            if (!hit) return false
        }
        if (learned <= 0) return false
        val drift = abs(candidate.amountPaise - learned).toDouble() / learned.toDouble()
        return drift <= SALARY_TOLERANCE
    }
}
