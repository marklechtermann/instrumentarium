package de.instrumentarium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind

@Composable
fun ValueInputDialog(
    title: String, initial: Double?, unit: String, min: Double, max: Double,
    onConfirm: (Double) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial?.let { formatValue(it, 4).trimEnd('0').trimEnd('.') } ?: "") }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text("Value${if (unit.isNotBlank() && unit != "-") " [$unit]" else ""}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !valid,
                )
                Text("Range ${formatValue(min, 2)} … ${formatValue(max, 2)}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("Write") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String = "OK", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Searchable variable list; [multi] allows selecting several variables. */
@Composable
fun VariablePickerDialog(
    variables: List<VariableInfo>,
    initial: List<String> = emptyList(),
    multi: Boolean,
    onlyParameters: Boolean = false,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val list = variables.filter { (!onlyParameters || it.kind == VariableKind.PARAMETER) && (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multi) "Select variables" else "Select variable", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search…") }, modifier = Modifier.fillMaxWidth())
                if (variables.isEmpty()) Text("No variables: go online first (connect platform).", color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 6.dp)) {
                    items(list, key = { it.name }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (multi) { if (v.name in selected) selected.remove(v.name) else selected.add(v.name) }
                                else onConfirm(listOf(v.name))
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multi) Checkbox(v.name in selected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, fontSize = 14.sp)
                                Text("${if (v.kind == VariableKind.PARAMETER) "Parameter" else "Measurement"} · ${v.description} ${if (v.unit != "-") "[${v.unit}]" else ""}", fontSize = 11.sp, color = Palette.textDim)
                            }
                        }
                        HorizontalDivider(color = Palette.grid, thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = { if (multi) TextButton(onClick = { onConfirm(selected.toList()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
