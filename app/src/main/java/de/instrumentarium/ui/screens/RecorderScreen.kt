package de.instrumentarium.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.export.CsvWriter
import de.instrumentarium.core.export.Mdf4Writer
import de.instrumentarium.core.measure.ConnState
import de.instrumentarium.core.measure.Recording
import de.instrumentarium.core.measure.RecorderState
import de.instrumentarium.core.model.TriggerCondition
import de.instrumentarium.core.model.TriggerMode
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.VariablePickerDialog
import de.instrumentarium.ui.formatValue
import de.instrumentarium.ui.instruments.PlotSeries
import de.instrumentarium.ui.instruments.drawPlot
import de.instrumentarium.ui.instruments.plotGeometry
import de.instrumentarium.ui.instruments.seriesRange
import de.instrumentarium.ui.instruments.seriesRanges
import de.instrumentarium.ui.displayNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recorder configuration and Measurement Data Pool (list of recordings). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecorderScreen(app: AppController, onOpen: (Recording) -> Unit) {
    val project by app.project.collectAsState()
    val recState by app.engine.recorder.state.collectAsState()
    val elapsed by app.engine.recorder.elapsed.collectAsState()
    val recordings by app.engine.recorder.recordings.collectAsState()
    val conn by app.engine.state.collectAsState()
    val vars by app.engine.variables.collectAsState()
    val cfg = project.recorder
    var pickVars by remember { mutableStateOf(false) }
    var pickTrigger by remember { mutableStateOf(false) }
    var condMenu by remember { mutableStateOf(false) }

    fun update(fn: (de.instrumentarium.core.model.RecorderConfig) -> de.instrumentarium.core.model.RecorderConfig) =
        app.updateProject { it.copy(recorder = fn(it.recorder)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recorder", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val recVars = cfg.variables.ifEmpty { project.usedVariables().toList().sorted() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signals: ${if (cfg.variables.isEmpty()) "all on layouts (${recVars.size})" else "${recVars.size} selected"}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { pickVars = true }) { Text("Select…") }
                if (cfg.variables.isNotEmpty()) TextButton(onClick = { update { it.copy(variables = emptyList()) } }) { Text("All") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Start:", fontSize = 14.sp)
                FilterChip(cfg.mode == TriggerMode.IMMEDIATE, { update { it.copy(mode = TriggerMode.IMMEDIATE) } }, label = { Text("Immediate") })
                FilterChip(cfg.mode == TriggerMode.TRIGGERED, { update { it.copy(mode = TriggerMode.TRIGGERED) } }, label = { Text("Trigger") })
            }
            if (cfg.mode == TriggerMode.TRIGGERED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { pickTrigger = true }) { Text(cfg.triggerVariable.ifBlank { "Select trigger signal" }) }
                    Column {
                        OutlinedButton(onClick = { condMenu = true }) { Text(cfg.condition.label) }
                        DropdownMenu(condMenu, { condMenu = false }) {
                            TriggerCondition.entries.forEach { c -> DropdownMenuItem(text = { Text(c.label) }, onClick = { update { it.copy(condition = c) }; condMenu = false }) }
                        }
                    }
                    NumField("Threshold", cfg.level) { v -> update { it.copy(level = v) } }
                    NumField("Pre-Trigger [s]", cfg.preTriggerSec) { v -> update { it.copy(preTriggerSec = v.coerceIn(0.0, 30.0)) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField("Duration [s] (0 = until stop)", cfg.durationSec) { v -> update { it.copy(durationSec = v.coerceAtLeast(0.0)) } }
                Text("Raster: ${project.platform.rasterMs} ms", color = Palette.textDim, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recState == RecorderState.IDLE) {
                    Button(
                        onClick = {
                            if (conn != ConnState.MEASURING) app.toggleMeasuring()
                            val list = recVars.mapNotNull { app.engine.variable(it) }
                            if (list.isEmpty()) app.toast("No signals to record") else app.engine.recorder.start(cfg, list)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.record),
                    ) { Text(if (cfg.mode == TriggerMode.TRIGGERED) "● Arm" else "● Start recording") }
                } else {
                    Button(onClick = { app.engine.recorder.stop() }) { Text("■ Stop") }
                }
                Text(
                    when (recState) {
                        RecorderState.IDLE -> "ready"
                        RecorderState.ARMED -> "waiting for trigger (${cfg.triggerVariable} ${cfg.condition.label} ${formatValue(cfg.level, 2)})"
                        RecorderState.RECORDING -> "recording: ${formatValue(elapsed, 1)} s"
                    },
                    color = when (recState) { RecorderState.IDLE -> Palette.textDim; RecorderState.ARMED -> Palette.warn; RecorderState.RECORDING -> Palette.record },
                    fontSize = 13.sp,
                )
            }
        }

        Text("Data pool", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (recordings.isEmpty()) Text("No recordings yet.", color = Palette.textDim)
        val fmt = remember { SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY) }
        recordings.reversed().forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surface).clickable { onOpen(r) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 15.sp)
                    Text("${fmt.format(Date(r.startedAtMillis))} · ${formatValue(r.duration, 1)} s · ${r.variables.size} signals · ${r.samples} samples${if (r.triggerTime != null) " · triggered" else ""}", fontSize = 11.sp, color = Palette.textDim)
                }
                TextButton(onClick = { app.platform.shareFile(fileName(r, "mf4"), "application/octet-stream", Mdf4Writer.write(r)) }) { Text("MDF4") }
                TextButton(onClick = { app.platform.shareFile(fileName(r, "csv"), "text/csv", CsvWriter.write(r).toByteArray()) }) { Text("CSV") }
                TextButton(onClick = { app.engine.recorder.delete(r.id) }) { Text("✕", color = Palette.alarm) }
            }
        }
    }

    if (pickVars) VariablePickerDialog(vars, cfg.variables.ifEmpty { project.usedVariables().toList() }, multi = true, onConfirm = { l -> update { it.copy(variables = l) }; pickVars = false }, onDismiss = { pickVars = false })
    if (pickTrigger) VariablePickerDialog(vars, multi = false, onConfirm = { l -> update { it.copy(triggerVariable = l.first()) }; pickTrigger = false }, onDismiss = { pickTrigger = false })
}

private fun fileName(r: Recording, ext: String): String {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(r.startedAtMillis))
    return "${r.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_$ts.$ext"
}

@Composable
private fun NumField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatValue(value, 3).trimEnd('0').trimEnd('.')) }
    OutlinedTextField(
        text, { text = it; it.replace(',', '.').toDoubleOrNull()?.let(onChange) },
        label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = Modifier.width(150.dp),
    )
}

/** Offline analysis of a recording: pinch to zoom, tap to place the cursor, double tap to reset. */
@Composable
fun RecordingViewer(app: AppController, rec: Recording, onClose: () -> Unit) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val shown = remember(rec.id) { mutableStateListOf<Int>().apply { addAll(rec.variables.indices.take(3)) } }
    val tEnd = if (rec.samples > 0) rec.times[rec.samples - 1] else 1.0
    var from by remember(rec.id) { mutableStateOf(0.0) }
    var to by remember(rec.id) { mutableStateOf(tEnd.coerceAtLeast(0.1)) }
    var cursor by remember(rec.id) { mutableStateOf<Double?>(null) }
    val series = shown.map { c ->
        PlotSeries(rec.variables[c].name, Palette.signals[c % Palette.signals.size]) { a, b, fn ->
            var lo = 0
            var hi = rec.samples
            while (lo < hi) { val m = (lo + hi) ushr 1; if (rec.times[m] < a) lo = m + 1 else hi = m }
            var i = maxOf(0, lo - 1)
            while (i < rec.samples) {
                val t = rec.times[i]
                fn(t, rec.columns[c][i])
                if (t > b) break
                i++
            }
        }
    }
    val (yMin, yMax) = seriesRange(series, from, to) ?: (0.0 to 1.0)
    val separate = series.size > 1
    val ranges = if (separate) seriesRanges(series, from, to) else null
    val names = displayNames(rec.variables.map { it.name })

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back") }
            Text(rec.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { val m = (from + to) / 2; val h = (to - from) / 4; from = (m - h).coerceAtLeast(0.0); to = m + h }) { Text("Zoom +") }
            TextButton(onClick = { val m = (from + to) / 2; val h = (to - from); from = (m - h).coerceAtLeast(0.0); to = (m + h).coerceAtMost(tEnd) }) { Text("Zoom −") }
            TextButton(onClick = { from = 0.0; to = tEnd; cursor = null }) { Text("All") }
            TextButton(onClick = { app.platform.shareFile(fileName(rec, "mf4"), "application/octet-stream", Mdf4Writer.write(rec)) }) { Text("MDF4") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rec.variables.forEachIndexed { c, v ->
                val on = c in shown
                FilterChip(
                    on, { if (on) shown.remove(c) else shown.add(c) },
                    label = { Text(names[c], fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedLabelColor = Palette.signals[c % Palette.signals.size]),
                )
            }
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(rec.id, separate) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val span = to - from
                        val g = plotGeometry(Size(size.width.toFloat(), size.height.toFloat()), density, separate)
                        var nSpan = (span / zoom).coerceIn(0.05, tEnd.coerceAtLeast(0.05))
                        val center = (from + to) / 2 - pan.x / g.width * span
                        var nFrom = center - nSpan / 2
                        if (nFrom < 0) nFrom = 0.0
                        if (nFrom + nSpan > tEnd) nFrom = (tEnd - nSpan).coerceAtLeast(0.0)
                        if (nSpan > tEnd) nSpan = tEnd
                        from = nFrom; to = nFrom + nSpan
                    }
                }
                .pointerInput(rec.id, separate) {
                    detectTapGestures(
                        onTap = { p -> cursor = plotGeometry(Size(size.width.toFloat(), size.height.toFloat()), density, separate).xValue(p.x, from, to).coerceIn(from, to) },
                        onDoubleTap = { from = 0.0; to = tEnd; cursor = null },
                    )
                },
        ) {
            drawPlot(tm, series, from, to, yMin, yMax, cursorX = cursor, markerX = rec.triggerTime, ranges = ranges)
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.surface).padding(8.dp)) {
            Text(
                if (cursor != null) "Cursor t = ${formatValue(cursor, 3)} s" else "Tap to set cursor · Pinch to zoom · Double-tap to show all",
                fontSize = 12.sp, color = Palette.textDim,
            )
            shown.forEach { c ->
                val v = rec.variables[c]
                val atCursor = cursor?.let { x ->
                    var lo = 0; var hi = rec.samples
                    while (lo < hi) { val m = (lo + hi) ushr 1; if (rec.times[m] < x) lo = m + 1 else hi = m }
                    rec.columns[c][lo.coerceAtMost(rec.samples - 1)]
                }
                Row {
                    Text("■ ${v.name}", color = Palette.signals[c % Palette.signals.size], fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (atCursor != null) Text("${formatValue(atCursor, 3, v.valueTable)} ${v.unit}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                HorizontalDivider(color = Palette.grid, thickness = 0.5.dp)
            }
        }
    }
}
