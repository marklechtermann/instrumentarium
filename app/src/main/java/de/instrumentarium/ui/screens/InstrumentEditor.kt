package de.instrumentarium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.model.Instrument
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.StateEntry
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.VariablePickerDialog
import java.util.Locale

/** Property editor for one instrument (Properties panel). */
@Composable
fun InstrumentEditor(inst: Instrument, variables: List<VariableInfo>, onDismiss: () -> Unit, onSave: (Instrument) -> Unit) {
    var e by remember { mutableStateOf(inst) }
    var pick by remember { mutableStateOf(false) }
    val t = e.type
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${t.label} – Properties", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(e.title, { e = e.copy(title = it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (t != InstrumentType.STATIC_TEXT) {
                    Text("Variables${if (t == InstrumentType.XY_PLOTTER) " (1st = X, 2nd = Y)" else ""}", color = Palette.textDim, fontSize = 12.sp)
                    Column {
                        e.variables.forEach { v -> Text("• $v", fontSize = 13.sp) }
                        TextButton(onClick = { pick = true }) { Text(if (e.variables.isEmpty()) "Connect variable…" else "Change variables…") }
                    }
                }
                if (t == InstrumentType.STATIC_TEXT) {
                    OutlinedTextField(e.text, { e = e.copy(text = it) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
                if (t in RANGE_TYPES) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(e.autoRange, { e = e.copy(autoRange = it) })
                        Text(if (t == InstrumentType.TIME_PLOTTER) "  Auto-scale Y axis" else "  Range from variable description", fontSize = 13.sp)
                    }
                    if (!e.autoRange) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Min", e.min, Modifier.weight(1f)) { e = e.copy(min = it) }
                        NumberField("Max", e.max, Modifier.weight(1f)) { e = e.copy(max = it) }
                    }
                }
                if (t in LIMIT_TYPES) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(if (t == InstrumentType.LAMP) "Threshold (on at)" else "Warning at", e.warnHigh, Modifier.weight(1f), allowEmpty = true) { e = e.copy(warnHigh = it) }
                    if (t != InstrumentType.LAMP) NumberField("Alarm at", e.alarmHigh, Modifier.weight(1f), allowEmpty = true) { e = e.copy(alarmHigh = it) }
                }
                if (t in DECIMAL_TYPES) NumberField("Decimals", e.decimals.toDouble()) { e = e.copy(decimals = it.toInt().coerceIn(0, 6)) }
                if (t in STEP_TYPES) NumberField("Step (0 = continuous)", e.step) { e = e.copy(step = it.coerceAtLeast(0.0)) }
                if (t == InstrumentType.PUSH_BUTTON || t == InstrumentType.CHECK_BUTTON) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(if (t == InstrumentType.PUSH_BUTTON) "Pressed value" else "On value", e.onValue, Modifier.weight(1f)) { e = e.copy(onValue = it) }
                    NumberField(if (t == InstrumentType.PUSH_BUTTON) "Released value" else "Off value", e.offValue, Modifier.weight(1f)) { e = e.copy(offValue = it) }
                }
                if (t == InstrumentType.TIME_PLOTTER || t == InstrumentType.XY_PLOTTER) NumberField("Time window [s]", e.timeSpanSec) { e = e.copy(timeSpanSec = it.coerceIn(0.5, 60.0)) }
                if (t == InstrumentType.MULTISTATE) StatesEditor(e.states, variables.firstOrNull { it.name == e.variables.firstOrNull() }) { e = e.copy(states = it) }
                if (t in COLOR_TYPES) {
                    Text("Color", color = Palette.textDim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Palette.choices.forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                    .border(if (e.color == c) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { e = e.copy(color = c) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", e.w.toDouble(), Modifier.weight(1f)) { e = e.copy(w = it.toInt().coerceIn(1, 12)) }
                    NumberField("Height", e.h.toDouble(), Modifier.weight(1f)) { e = e.copy(h = it.toInt().coerceIn(1, 30)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(e) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pick) VariablePickerDialog(
        variables, e.variables, multi = t.multiVariable, onlyParameters = t.writes,
        onConfirm = { e = e.copy(variables = it); pick = false }, onDismiss = { pick = false },
    )
}

private val RANGE_TYPES = setOf(InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.TIME_PLOTTER, InstrumentType.XY_PLOTTER, InstrumentType.SLIDER, InstrumentType.KNOB, InstrumentType.NUMERIC_INPUT)
private val LIMIT_TYPES = setOf(InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.DISPLAY, InstrumentType.LAMP)
private val DECIMAL_TYPES = setOf(InstrumentType.DISPLAY, InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.TIME_PLOTTER, InstrumentType.XY_PLOTTER, InstrumentType.VARIABLE_ARRAY, InstrumentType.SLIDER, InstrumentType.KNOB, InstrumentType.NUMERIC_INPUT)
private val STEP_TYPES = setOf(InstrumentType.SLIDER, InstrumentType.KNOB, InstrumentType.NUMERIC_INPUT)
private val COLOR_TYPES = setOf(InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.LAMP, InstrumentType.XY_PLOTTER, InstrumentType.SLIDER, InstrumentType.KNOB, InstrumentType.PUSH_BUTTON, InstrumentType.CHECK_BUTTON, InstrumentType.STATIC_TEXT)

@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier = Modifier.fillMaxWidth(), allowEmpty: Boolean = false, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (value.isNaN()) "" else fmt(value)) }
    OutlinedTextField(
        text,
        {
            text = it
            val v = it.replace(',', '.').toDoubleOrNull()
            if (v != null) onChange(v) else if (allowEmpty && it.isBlank()) onChange(Double.NaN)
        },
        label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = text.isNotBlank() && text.replace(',', '.').toDoubleOrNull() == null,
    )
}

private fun fmt(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else String.format(Locale.US, "%s", v)

@Composable
private fun StatesEditor(states: List<StateEntry>, info: VariableInfo?, onChange: (List<StateEntry>) -> Unit) {
    Text("States (value → text, color)", color = Palette.textDim, fontSize = 12.sp)
    states.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("Value", s.value, Modifier.weight(0.8f)) { v -> onChange(states.toMutableList().also { it[i] = s.copy(value = v) }) }
            OutlinedTextField(s.label, { l -> onChange(states.toMutableList().also { it[i] = s.copy(label = l) }) }, singleLine = true, modifier = Modifier.weight(1.2f), label = { Text("Text", fontSize = 12.sp) })
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color(s.color)).clickable {
                    val next = Palette.choices[(Palette.choices.indexOf(s.color) + 1) % Palette.choices.size]
                    onChange(states.toMutableList().also { it[i] = s.copy(color = next) })
                },
            )
            Text("✕", color = Palette.alarm, modifier = Modifier.clickable { onChange(states.filterIndexed { k, _ -> k != i }) }.padding(4.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { onChange(states + StateEntry((states.maxOfOrNull { it.value } ?: -1.0) + 1, "State", Palette.choices[states.size % Palette.choices.size])) }, label = { Text("+ State") })
        if (info?.valueTable?.isNotEmpty() == true) AssistChip(
            onClick = { onChange(info.valueTable.entries.mapIndexed { k, en -> StateEntry(en.key.toDouble(), en.value, Palette.choices[k % Palette.choices.size]) }) },
            label = { Text("From value table") },
        )
    }
}
