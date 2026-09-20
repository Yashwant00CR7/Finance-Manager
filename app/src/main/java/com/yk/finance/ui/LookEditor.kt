package com.yk.finance.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yk.finance.domain.Looks

/**
 * Name, icon and colour, in one dialog.
 *
 * Shared by categories and accounts because they are the same decision twice: a disc
 * you will recognise in a list of forty rows. Keeping one editor means the two can
 * never drift into offering different icon sets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookEditor(
    title: String,
    initialName: String,
    initialIcon: String,
    initialColour: String,
    nameLabel: String = "Name",
    extraContent: @Composable (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    var colour by remember { mutableStateOf(initialColour) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(nameLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Looks.ICONS.forEach { key ->
                        IconBubble(
                            iconKey = key,
                            colourHex = colour,
                            size = 40.dp,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .then(
                                    if (key == icon) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { icon = key },
                        )
                    }
                }

                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Looks.PALETTE.forEach { hex ->
                        IconBubble(
                            iconKey = icon,
                            colourHex = hex,
                            size = 40.dp,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .then(
                                    if (hex == colour) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { colour = hex },
                        )
                    }
                }

                extraContent?.let {
                    Spacer(Modifier.height(4.dp))
                    it()
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, icon, colour) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
