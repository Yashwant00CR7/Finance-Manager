package com.yk.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yk.finance.domain.Ledger
import com.yk.finance.domain.formatRupees
import com.yk.finance.ui.theme.LocalMoneyColors

/**
 * Where the period's money went, as a ring and a ranked list.
 *
 * Drawn with Canvas rather than a charting library: one arc per slice is a dozen lines,
 * and a dependency that renders differently across versions would be a poor trade for a
 * chart whose colours have to match the category discs exactly.
 */
@Composable
fun AnalysisScreen(vm: FinanceViewModel) {
    val slices by vm.breakdown.collectAsState()
    val income by vm.showIncomeAnalysis.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val money = LocalMoneyColors.current

    LazyColumn(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.ArrowDropDown, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (income) "INCOME OVERVIEW" else "EXPENSE OVERVIEW")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("EXPENSE OVERVIEW") },
                        onClick = { vm.setAnalysisMode(false); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("INCOME OVERVIEW") },
                        onClick = { vm.setAnalysisMode(true); menuOpen = false },
                    )
                }
            }
        }

        if (slices.isEmpty()) {
            item {
                Text(
                    if (income) "No income recorded in this period." else "No spending recorded in this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@LazyColumn
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Donut(slices, if (income) "Income" else "Expenses")
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    slices.take(8).forEach { slice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(colourOf(slice.colourHex), RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                slice.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }

        items(slices) { slice ->
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(slice.iconKey, slice.colourHex)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(slice.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                (if (income) "" else "-") + formatRupees(slice.amountPaise),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (income) money.income else money.expense,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (slice.percent / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = colourOf(slice.colourHex),
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        String.format(java.util.Locale.ENGLISH, "%.2f%%", slice.percent),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun Donut(slices: List<Ledger.Slice>, centreLabel: String) {
    val stroke = 34f
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(160.dp)) {
            val inset = stroke / 2
            var startAngle = -90f
            slices.forEach { slice ->
                // Percentages are shares of the period total, so the sweeps close the
                // circle exactly without needing a separate "other" wedge.
                val sweep = (slice.percent / 100.0 * 360.0).toFloat()
                drawArc(
                    color = colourOf(slice.colourHex),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Text(centreLabel, style = MaterialTheme.typography.bodyMedium)
    }
}
