package de.instrumentarium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.ValueInputDialog
import de.instrumentarium.ui.formatValue

/** Variable browser (Variables pane) with live values and "place on layout". */
@Composable
fun VariablesScreen(app: AppController, currentLayoutId: String?) {
    val vars by app.engine.variables.collectAsState()
    val snap by app.engine.snapshot.collectAsState()
    val project by app.project.collectAsState()
    val source by app.engine.sourceName.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<VariableKind?>(null) }
    var selected by remember { mutableStateOf<VariableInfo?>(null) }
    var writing by remember { mutableStateOf<VariableInfo?>(null) }

    DisposableEffect(Unit) {
        app.browseAll = true
        onDispose { app.browseAll = false }
    }

    val list = vars.filter { (filter == null || it.kind == filter) && (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true)) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(source.ifBlank { "Variables of the configured platform (offline)" }, color = Palette.textDim, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search…") }, singleLine = true, modifier = Modifier.weight(1f))
            FilterChip(filter == VariableKind.MEASUREMENT, { filter = if (filter == VariableKind.MEASUREMENT) null else VariableKind.MEASUREMENT }, label = { Text("Measurements") })
            FilterChip(filter == VariableKind.PARAMETER, { filter = if (filter == VariableKind.PARAMETER) null else VariableKind.PARAMETER }, label = { Text("Parameters") })
        }
        Text("${list.size} of ${vars.size} variables · Tap to place on layout", color = Palette.textDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { _, it -> it.name }) { i, v ->
                val group = v.group
                Column {
                    if (i == 0 || list[i - 1].group != group) {
                        Text(group.ifBlank { "(no group)" }, color = Palette.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth().clickable { selected = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (v.kind == VariableKind.PARAMETER) "P" else "M", color = if (v.kind == VariableKind.PARAMETER) Palette.warn else Palette.ok, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.shortName, fontSize = 14.sp)
                            Text(v.description, fontSize = 11.sp, color = Palette.textDim, maxLines = 1)
                        }
                        Text(formatValue(snap.values[v.name], 3, v.valueTable), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text(" " + v.unit.takeIf { it != "-" }.orEmpty(), color = Palette.textDim, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                    }
                    HorizontalDivider(color = Palette.grid, thickness = 0.5.dp)
                }
            }
        }
    }

    selected?.let { v ->
        val layout = project.layouts.firstOrNull { it.id == currentLayoutId } ?: project.layouts.firstOrNull()
        val types = if (v.kind == VariableKind.PARAMETER)
            listOf(InstrumentType.SLIDER, InstrumentType.NUMERIC_INPUT, InstrumentType.KNOB, InstrumentType.CHECK_BUTTON, InstrumentType.PUSH_BUTTON, InstrumentType.DISPLAY, InstrumentType.TIME_PLOTTER)
        else listOf(InstrumentType.DISPLAY, InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.TIME_PLOTTER, InstrumentType.LAMP, InstrumentType.MULTISTATE)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(v.name, fontSize = 16.sp) },
            text = {
                Column {
                    Text("${v.description}\nRange ${formatValue(v.min, 2)} … ${formatValue(v.max, 2)} ${v.unit}", fontSize = 13.sp, color = Palette.textDim)
                    if (layout != null && !project.operatorMode) {
                        Text("Place on \"${layout.name}\" as:", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                        types.forEach { t ->
                            Text(
                                t.label,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    app.addInstrument(layout.id, t, listOf(v.name))
                                    app.toast("${t.label} for ${v.shortName} added to \"${layout.name}\"")
                                    selected = null
                                }.background(Palette.surfaceHigh).padding(10.dp),
                            )
                            HorizontalDivider(color = Palette.background)
                        }
                    }
                }
            },
            confirmButton = {
                if (v.kind == VariableKind.PARAMETER) TextButton(onClick = { writing = v; selected = null }) { Text("Write value…") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    writing?.let { v ->
        ValueInputDialog(v.name, snap.values[v.name], v.unit, v.min, v.max, onConfirm = { app.write(v.name, it); writing = null }, onDismiss = { writing = null })
    }
}
