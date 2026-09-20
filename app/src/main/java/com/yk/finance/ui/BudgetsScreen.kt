package com.yk.finance.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.yk.finance.domain.BudgetProgress
import com.yk.finance.domain.formatRupees
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Limits for the selected cycle.
 *
 * Every card says which cycle it is talking about, because a limit can be standing (it
 * applies to every cycle) or pinned to this one - and a number that means two different
 * things depending on a flag you cannot see is worse than no number.
 */
@Composable
fun BudgetsScreen(vm: FinanceViewModel, state: UiState) {
    val progress by vm.progress.collectAsState()
    val uncategorised by vm.uncategorisedSpend.collectAsState()
    val period by vm.period.collectAsState()
    var editing by remember { mutableStateOf<BudgetProgress?>(null) }
    var adding by remember { mutableStateOf(false) }

    val categoryBudgets = progress.filter { it.categoryId != null }
    val overall = progress.firstOrNull { it.categoryId == null }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Budgeted categories: ${period?.label ?: "-"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { adding = true }) { Text("Add") }
            }
            HorizontalDivider()
        }

        overall?.let {
            item {
                // Pinned, and outside the TOTAL BUDGET sum in the header: adding an
                // overall cap to the per-category limits would count the same money twice.
                BudgetCard(
                    progress = it,
                    state = state,
                    periodLabel = period?.label,
                    isOverallCap = true,
                    onEdit = { editing = it },
                    onDelete = { vm.deleteBudget(it.budgetId) },
                )
            }
        }

        if (uncategorised > 0) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${formatRupees(uncategorised)} uncategorised", fontWeight = FontWeight.Medium)
                        Text(
                            "This spending is real but sits outside every category budget, so the " +
                                "percentages below read lower than the truth until you label it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMoneyColors.current.muted,
                        )
                    }
                }
            }
        }

        if (categoryBudgets.isEmpty()) {
            item {
                Text(
                    "No budgets for this cycle yet. Add tracks a category against a limit and warns at 80%.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        items(categoryBudgets, key = { it.budgetId }) { p ->
            BudgetCard(
                progress = p,
                state = state,
                periodLabel = period?.label,
                isOverallCap = false,
                onEdit = { editing = p },
                onDelete = { vm.deleteBudget(p.budgetId) },
            )
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    if (adding) {
        BudgetEditor(
            title = "Add a budget",
            state = state,
            initialCategoryId = null,
            initialLimit = "",
            periodLabel = period?.label,
            onDismiss = { adding = false },
            onSave = { categoryId, paise, thisCycleOnly ->
                vm.setBudget(categoryId, paise, thisCycleOnly)
                adding = false
            },
        )
    }

    editing?.let { target ->
        BudgetEditor(
            title = "Edit ${target.categoryName}",
            state = state,
            initialCategoryId = target.categoryId,
            initialLimit = (target.limitPaise / 100).toString(),
            periodLabel = period?.label,
            lockCategory = true,
            onDismiss = { editing = null },
            onSave = { categoryId, paise, thisCycleOnly ->
                vm.setBudget(categoryId, paise, thisCycleOnly)
                editing = null
            },
        )
    }
}

@Composable
private fun BudgetCard(
    progress: BudgetProgress,
    state: UiState,
    periodLabel: String?,
    isOverallCap: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val money = LocalMoneyColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val category = state.categoryById(progress.categoryId)

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOverallCap) {
                    IconBubble("savings", "#546E7A")
                } else {
                    IconBubble(
                        category?.iconKey ?: state.iconKeyFor(progress.categoryId),
                        category?.colourHex ?: state.colourFor(progress.categoryId),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isOverallCap) "Overall cap" else progress.categoryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            append("(").append(periodLabel ?: "this cycle").append(")")
                            if (progress.isOverride) append(" · this cycle only")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = money.muted,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Budget options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit limit") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Limit:  ${formatRupees(progress.limitPaise)}")
                    Text(
                        "Spent:  ${formatRupees(progress.spentPaise)}",
                        color = if (progress.exceeded) money.expense else money.income,
                    )
                    Text(
                        "Remaining:  ${formatRupees(progress.remainingPaise)}",
                        color = if (progress.remainingPaise == 0L) money.expense else money.income,
                    )
                }
                // The flag from the app this was modelled on: the limit, repeated where
                // the eye lands after the bar.
                Box(
                    Modifier
                        .background(money.income.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(formatRupees(progress.limitPaise), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = if (progress.exceeded) money.expense else money.income,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )

            if (progress.exceeded) {
                Text(
                    "*Limit exceeded",
                    style = MaterialTheme.typography.bodySmall,
                    color = money.expense,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * One editor for both adding and changing a limit.
 *
 * The "this cycle only" switch is the whole point of the screen: off rewrites the
 * standing limit that every future cycle inherits, on writes a one-off that expires
 * with this cycle. Defaulting to off keeps the common case - a limit you mean to keep.
 */
@Composable
private fun BudgetEditor(
    title: String,
    state: UiState,
    initialCategoryId: Long?,
    initialLimit: String,
    periodLabel: String?,
    lockCategory: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Long?, Long, Boolean) -> Unit,
) {
    var limit by remember { mutableStateOf(initialLimit) }
    var categoryId by remember { mutableStateOf(initialCategoryId) }
    var thisCycleOnly by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val paise = rupeesToPaise(limit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!lockCategory) {
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) {
                            Text(
                                categoryId?.let { state.categoryName(it) } ?: "Overall cap",
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Overall cap") },
                                onClick = { categoryId = null; menuOpen = false },
                            )
                            state.expenseCategories.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    leadingIcon = { IconBubble(c.iconKey, c.colourHex, size = 24.dp) },
                                    onClick = { categoryId = c.id; menuOpen = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text("Limit (₹)") },
                    isError = limit.isNotBlank() && paise == null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = thisCycleOnly, onCheckedChange = { thisCycleOnly = it })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (thisCycleOnly) {
                            "Applies to ${periodLabel ?: "this cycle"} only"
                        } else {
                            "Applies to every cycle"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { paise?.let { onSave(categoryId, it, thisCycleOnly) } },
                enabled = paise != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
