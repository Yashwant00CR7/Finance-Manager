package com.yk.finance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.data.Txn
import com.yk.finance.data.TxnSource
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.formatRupees
import com.yk.finance.parser.Direction
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * The ledger, grouped by day.
 *
 * Rows lead with the category, as the app this was modelled on does - but the subtitle
 * carries the account *and* the payee, because this ledger is built from bank messages
 * and the payee is the only thing that distinguishes two 320 Food rows from each other.
 */
@Composable
fun RecordsScreen(
    vm: FinanceViewModel,
    state: UiState,
    onOpenInbox: () -> Unit,
    onOpenRecord: (Long) -> Unit,
) {
    val rows by vm.visibleTransactions.collectAsState()
    val all by vm.periodTransactions.collectAsState()
    val inbox by vm.inboxCount.collectAsState()
    val filter by vm.filter.collectAsState()

    val grouped = remember(rows) {
        rows.sortedByDescending { it.occurredAt }
            .groupBy { com.yk.finance.domain.CycleCalculator.toLocalDate(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        if (inbox > 0) {
            item { InboxBanner(inbox, onOpenInbox) }
        }

        if (filter.isActive) {
            item { FilterChipRow(shown = rows.size, total = all.size) }
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    if (all.isEmpty()) {
                        "Nothing in this month. Transactions appear the moment a bank message arrives."
                    } else {
                        "No records match this filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        grouped.forEach { (day, dayRows) ->
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                    Text(
                        day.format(DAY_HEADER),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                }
            }
            items(dayRows, key = { it.id }) { txn ->
                RecordRow(vm, state, txn, onOpenRecord)
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun InboxBanner(count: Int, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, null, tint = LocalMoneyColors.current.expense)
            Spacer(Modifier.width(12.dp))
            Text(
                if (count == 1) "1 thing needs your attention" else "$count things need your attention",
                Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

/**
 * How much of the month you are looking at.
 *
 * The header names *what* is filtered and offers the way out; this says *how many*
 * rows survived it. Two different facts, so both are worth having - but the Clear
 * button lives upstairs now, next to the figures it changes.
 */
@Composable
private fun FilterChipRow(shown: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Showing $shown of ${total} in this month",
            style = MaterialTheme.typography.bodySmall,
            color = LocalMoneyColors.current.muted,
        )
    }
}

@Composable
private fun RecordRow(
    vm: FinanceViewModel,
    state: UiState,
    txn: Txn,
    onOpen: (Long) -> Unit,
) {
    val money = LocalMoneyColors.current
    var menuOpen by remember { mutableStateOf(false) }
    var splitWith by remember { mutableStateOf<Long?>(null) }
    val account = state.accountById(txn.accountId)
    val category = state.categoryById(txn.categoryId)
    val isTransfer = txn.transferGroupId != null || txn.source == TxnSource.TRANSFER_LEG
    val uncategorised = category == null && Ledger.isExpense(txn)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(txn.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isTransfer) {
            IconBubble("atm", "#546E7A")
        } else {
            IconBubble(
                iconKey = category?.iconKey ?: state.iconKeyFor(txn.categoryId),
                colourHex = category?.colourHex ?: state.colourFor(txn.categoryId),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        isTransfer -> "Transfer"
                        else -> state.categoryName(txn.categoryId)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (uncategorised) money.muted else MaterialTheme.colorScheme.onSurface,
                )
                // Never dressed up as a decision. A category the model chose reads
                // differently from one you chose, for the same reason a cycle boundary
                // says "start estimated" - a guess presented as a fact is the one way
                // a ledger stops being believed.
                if (txn.categoryWasInferred) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "guessed",
                        style = MaterialTheme.typography.labelSmall,
                        color = money.muted,
                    )
                }
            }
            Text(
                buildString {
                    append(account?.displayName ?: "Unknown account")
                    val detail = txn.payee?.takeIf { it.isNotBlank() && it != "Transfer" }
                        ?: txn.note?.takeIf { it.isNotBlank() }
                    detail?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = money.muted,
            )
            // Categorising from the list is one tap; opening the record to do it would
            // be four, which is how spending goes unlabelled for a month. A guess gets
            // the same treatment - accepting one has to be cheaper than ignoring it,
            // or the review queue is where guesses go to be forgotten.
            if (uncategorised || txn.categoryWasInferred) {
                Box {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (txn.categoryWasInferred) {
                            // Deliberately does not mint a payee rule: the model earned
                            // this row, and a glance should not become a commitment.
                            AssistChip(
                                onClick = { vm.confirmGuess(txn) },
                                label = { Text("Keep") },
                            )
                        }
                        AssistChip(
                            onClick = { menuOpen = true },
                            label = { Text(if (txn.categoryWasInferred) "Change" else "Set category") },
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // The model's best few float to the top; everything below keeps
                        // the order it always had, because a list that reshuffles
                        // completely is harder to use than one that never moves.
                        val suggested = remember(menuOpen, txn.id) {
                            if (menuOpen) vm.suggestedCategoryIds(txn) else emptyList()
                        }
                        val ranked = remember(suggested, state.expenseCategories) {
                            val order = suggested.withIndex().associate { (at, id) -> id to at }
                            state.expenseCategories.sortedBy { order[it.id] ?: Int.MAX_VALUE }
                        }
                        ranked.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                leadingIcon = {
                                    IconBubble(option.iconKey, option.colourHex, size = 24.dp)
                                },
                                onClick = {
                                    menuOpen = false
                                    // Picking a category that means somebody else's
                                    // money is already half of "how much of it was
                                    // theirs", so the sheet opens rather than filing
                                    // the whole amount under it. Every other category
                                    // stays the single tap it has always been.
                                    if (state.splitsOnPick(option.id)) {
                                        splitWith = option.id
                                    } else {
                                        vm.categorise(txn, option.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (txn.direction == Direction.DEBIT) "-" else "") + formatRupees(txn.amountPaise),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    isTransfer || txn.source == TxnSource.ADJUSTMENT -> money.muted
                    txn.direction == Direction.DEBIT -> money.expense
                    else -> money.income
                },
            )
            if (!txn.countsAsSpending && txn.direction == Direction.DEBIT) {
                // Makes the transfer model legible: a 3,000 move between your own
                // accounts must not read as a 3,000 expense.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        null,
                        tint = money.muted,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        when {
                            txn.source == TxnSource.TRANSFER_LEG -> "transfer"
                            txn.source == TxnSource.ADJUSTMENT -> "correction"
                            // Naming the person is the whole point: "not spending" on a
                            // 120 debit invites you to wonder what it was, when the app
                            // already knows it is money Arun owes you.
                            txn.owedBy != null -> "owed by ${txn.owedBy}"
                            else -> "not spending"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)

    splitWith?.let { categoryId ->
        SplitSheet(
            state = state,
            anchor = txn,
            totalPaise = txn.amountPaise,
            initialCategoryId = categoryId,
            existingParts = listOf(txn),
            onApply = { parts ->
                vm.applySplit(txn, parts)
                splitWith = null
            },
            onDismiss = { splitWith = null },
        )
    }
}
