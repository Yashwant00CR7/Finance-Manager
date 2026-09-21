package com.yk.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.yk.finance.domain.CycleCalculator
import com.yk.finance.domain.formatRupees
import com.yk.finance.parser.Direction
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Search across every period, not just the one on screen.
 *
 * Results keep the day grouping so a hit reads in context - "this is the 320 from the
 * Saturday" - rather than as a bare row torn out of the ledger.
 */
@Composable
fun SearchScreen(
    vm: FinanceViewModel,
    state: UiState,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val money = LocalMoneyColors.current

    val grouped = remember(results) {
        results.sortedByDescending { it.occurredAt }
            .groupBy { CycleCalculator.toLocalDate(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }
    }

    Surface(Modifier.fillMaxSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(money.header).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✕", modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = { Text("Payee, note, category, amount") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.trim().length < 2) {
                Text(
                    "Type at least two characters. Search covers every period, not just the one you " +
                        "were looking at.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else if (results.isEmpty()) {
                Text(
                    "Nothing matches \"${query.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn(Modifier.fillMaxWidth()) {
                grouped.forEach { (day, rows) ->
                    item {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            Text(
                                day.format(DAY_HEADER),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider()
                        }
                    }
                    items(rows, key = { it.id }) { txn ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(txn.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBubble(state.iconKeyFor(txn.categoryId), state.colourFor(txn.categoryId))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.categoryName(txn.categoryId))
                                Text(
                                    buildString {
                                        append(state.accountById(txn.accountId)?.displayName.orEmpty())
                                        txn.payee?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = money.muted,
                                )
                            }
                            Text(
                                (if (txn.direction == Direction.DEBIT) "-" else "") +
                                    formatRupees(txn.amountPaise),
                                fontWeight = FontWeight.Bold,
                                color = if (txn.direction == Direction.DEBIT) money.expense else money.income,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

/**
 * The filter sheet, acting on the period already on screen.
 *
 * Everything here runs in memory over rows that are loaded anyway, which is why it can
 * afford to be instant - and why the chip on the Records screen matters: the header
 * totals keep showing the whole period, so a filtered list must announce itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    state: UiState,
    filter: RecordFilter,
    onApply: (RecordFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(filter) }
    var accountMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Filter", style = MaterialTheme.typography.titleLarge)

            // Not fillMaxWidth: four segments forced into one screen width is what
            // broke EXPENSE into EXPENS/E. Sized to its content and allowed to scroll,
            // it fits on a wide phone and stays readable on a narrow one.
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow {
                    TypeFilter.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = draft.type == option,
                            onClick = { draft = draft.copy(type = option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = TypeFilter.entries.size),
                        ) { Text(option.label, maxLines = 1, softWrap = false) }
                    }
                }
            }

            Box {
                OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(draft.accountId?.let { state.accountById(it)?.displayName } ?: "Any account")
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Any account") },
                        onClick = { draft = draft.copy(accountId = null); accountMenu = false },
                    )
                    state.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.displayName) },
                            onClick = { draft = draft.copy(accountId = account.id); accountMenu = false },
                        )
                    }
                }
            }

            Box {
                OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(draft.categoryId?.let { state.categoryName(it) } ?: "Any category")
                }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Any category") },
                        onClick = { draft = draft.copy(categoryId = null); categoryMenu = false },
                    )
                    state.categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            leadingIcon = { IconBubble(category.iconKey, category.colourHex, size = 24.dp) },
                            onClick = { draft = draft.copy(categoryId = category.id); categoryMenu = false },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.uncategorisedOnly,
                    onCheckedChange = { draft = draft.copy(uncategorisedOnly = it) },
                )
                Spacer(Modifier.width(8.dp))
                Text("Uncategorised spending only")
            }

            // Reviewing what the model filed is a filter rather than a screen of its
            // own: it is the same list, narrowed, and the sheet is already where
            // narrowing lives.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.guessedOnly,
                    onCheckedChange = { draft = draft.copy(guessedOnly = it) },
                )
                Spacer(Modifier.width(8.dp))
                Text("Guessed categories only")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Resets the choices in front of you and stays open. Applying an empty
                // filter and dismissing made "clear, then pick something else" two
                // trips through the sheet instead of one.
                TextButton(
                    onClick = { draft = RecordFilter() },
                    enabled = draft.isActive,
                ) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onApply(draft) }) { Text("Apply") }
            }
        }
    }
}
