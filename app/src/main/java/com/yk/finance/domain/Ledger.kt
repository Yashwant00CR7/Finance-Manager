package com.yk.finance.domain

import com.yk.finance.data.Account
import com.yk.finance.data.Category
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.parser.Direction

/**
 * The figures every screen reads off the ledger.
 *
 * Pure, and in one place, because the same definition of "expense" has to hold on the
 * Records header, in the donut, and in a budget - three screens disagreeing about what
 * counts is how a personal finance app stops being believed.
 *
 * Expense is a debit flagged countsAsSpending, so ATM withdrawals and self-transfers
 * are excluded. Income is the mirror: a credit that is neither a transfer leg nor a
 * balance correction. Adjustments never appear in either.
 */
object Ledger {

    data class Totals(val expensePaise: Long, val incomePaise: Long) {
        val netPaise: Long get() = incomePaise - expensePaise
    }

    data class Slice(
        val categoryId: Long?,
        val name: String,
        val iconKey: String,
        val colourHex: String,
        val amountPaise: Long,
        /** Share of the period's total, 0..100, two decimals kept for display. */
        val percent: Double,
    )

    data class Overall(
        val expenseAllTimePaise: Long,
        val incomeAllTimePaise: Long,
        val totalBalancePaise: Long,
        val openingPaise: Long,
        val correctionsPaise: Long,
    )

    fun isExpense(txn: Txn): Boolean =
        txn.countsAsSpending && txn.direction == Direction.DEBIT

    /**
     * SETTLEMENT is excluded for the same reason TRANSFER_LEG is: a friend paying back
     * the 120 you fronted at lunch is your own money returning, not money you earned.
     * Counting it would inflate income by the exact amount it inflates it every single
     * time somebody settles up.
     */
    fun isIncome(txn: Txn): Boolean =
        txn.direction == Direction.CREDIT &&
            txn.transferGroupId == null &&
            txn.source != TxnSource.TRANSFER_LEG &&
            txn.source != TxnSource.ADJUSTMENT &&
            txn.source != TxnSource.SETTLEMENT

    fun totals(transactions: List<Txn>): Totals = Totals(
        expensePaise = transactions.filter(::isExpense).sumOf { it.amountPaise },
        incomePaise = transactions.filter(::isIncome).sumOf { it.amountPaise },
    )

    /**
     * The donut and the ranked list beneath it.
     *
     * Spending with no category becomes its own grey slice rather than being dropped:
     * a chart that quietly omits a fifth of the money is a chart that lies about the
     * other four fifths.
     */
    fun breakdown(
        transactions: List<Txn>,
        categories: List<Category>,
        income: Boolean,
    ): List<Slice> {
        val relevant = transactions.filter { if (income) isIncome(it) else isExpense(it) }
        val total = relevant.sumOf { it.amountPaise }
        if (total == 0L) return emptyList()

        val byId = categories.associateBy { it.id }
        return relevant
            .groupBy { it.categoryId }
            .map { (categoryId, rows) ->
                val category = categoryId?.let { byId[it] }
                val name = category?.name ?: UNCATEGORISED
                val amount = rows.sumOf { it.amountPaise }
                Slice(
                    categoryId = categoryId,
                    name = name,
                    iconKey = category?.iconKey ?: Looks.categoryIcon(name),
                    colourHex = category?.colourHex ?: Looks.categoryColour(name),
                    amountPaise = amount,
                    percent = amount * 100.0 / total,
                )
            }
            .sortedByDescending { it.amountPaise }
    }

    /**
     * The Accounts tab's Overall card.
     *
     * Expense and income stay pure - money that actually left or arrived - so they do
     * not sum to the balance on their own. Opening balances and reconcile corrections
     * are reported separately instead of being folded into income, which would inflate
     * every income figure the app ever shows with money that was never earned.
     */
    fun overall(transactions: List<Txn>, accounts: List<Account>): Overall {
        val corrections = transactions
            .filter { it.source == TxnSource.ADJUSTMENT }
            .sumOf { if (it.direction == Direction.CREDIT) it.amountPaise else -it.amountPaise }

        return Overall(
            expenseAllTimePaise = transactions.filter(::isExpense).sumOf { it.amountPaise },
            incomeAllTimePaise = transactions.filter(::isIncome).sumOf { it.amountPaise },
            totalBalancePaise = accounts.sumOf { it.currentBalancePaise },
            openingPaise = accounts.sumOf { it.openingBalancePaise },
            correctionsPaise = corrections,
        )
    }
}
