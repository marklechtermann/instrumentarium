package de.instrumentarium.ui.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.measure.MeasurementEngine
import de.instrumentarium.core.measure.Snapshot
import de.instrumentarium.core.model.Instrument
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.ValueInputDialog
import de.instrumentarium.ui.axisLabel
import de.instrumentarium.ui.displayNames
import de.instrumentarium.ui.formatValue
import de.instrumentarium.ui.niceStep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What an instrument needs at runtime. */
class InstrumentContext(
    val snap: Snapshot,
    val engine: MeasurementEngine,
    val write: (String, Double) -> Unit,
    /** False in layout edit mode: controls must not react. */
    val interactive: Boolean,
)

private fun InstrumentContext.info(name: String?): VariableInfo? = name?.let { engine.variable(it) }
private fun InstrumentContext.value(name: String?): Double? = name?.let { snap.values[it] }

private fun Instrument.range(info: VariableInfo?): Pair<Double, Double> =
    if (autoRange && info != null) info.min to info.max else min to max

private fun Instrument.label(info: VariableInfo?): String =
    title.ifBlank { info?.shortName ?: variables.firstOrNull()?.substringAfterLast('.') ?: type.label }

private fun levelColor(inst: Instrument, v: Double?, normal: Color): Color = when {
    v == null -> normal
    !inst.alarmHigh.isNaN() && v >= inst.alarmHigh -> Palette.alarm
    !inst.warnHigh.isNaN() && v >= inst.warnHigh -> Palette.warn
    else -> normal
}

@Composable
fun InstrumentView(inst: Instrument, ctx: InstrumentContext, modifier: Modifier = Modifier) {
    val info = ctx.info(inst.variables.firstOrNull())
    val frameless = inst.type == InstrumentType.STATIC_TEXT
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (frameless) Color.Transparent else Palette.surface)
            .border(1.dp, if (frameless) Color.Transparent else Palette.grid, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        if (inst.type !in setOf(InstrumentType.STATIC_TEXT, InstrumentType.CHECK_BUTTON, InstrumentType.PUSH_BUTTON)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    inst.label(info), color = Palette.textDim, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (inst.variables.isNotEmpty() && inst.variables.all { ctx.engine.variable(it) == null } && ctx.engine.variables.value.isNotEmpty()) {
                    Text("not connected", color = Palette.warn, fontSize = 10.sp)
                }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (inst.type) {
                InstrumentType.DISPLAY -> DisplayInstrument(inst, ctx, info)
                InstrumentType.GAUGE -> GaugeInstrument(inst, ctx, info)
                InstrumentType.BAR -> BarInstrument(inst, ctx, info)
                InstrumentType.LAMP -> LampInstrument(inst, ctx)
                InstrumentType.MULTISTATE -> MultistateInstrument(inst, ctx, info)
                InstrumentType.TIME_PLOTTER -> TimePlotterInstrument(inst, ctx)
                InstrumentType.XY_PLOTTER -> XyPlotterInstrument(inst, ctx)
                InstrumentType.VARIABLE_ARRAY -> VariableArrayInstrument(inst, ctx)
                InstrumentType.SLIDER -> SliderInstrument(inst, ctx, info)
                InstrumentType.KNOB -> KnobInstrument(inst, ctx, info)
                InstrumentType.NUMERIC_INPUT -> NumericInputInstrument(inst, ctx, info)
                InstrumentType.PUSH_BUTTON -> PushButtonInstrument(inst, ctx, info)
                InstrumentType.CHECK_BUTTON -> CheckButtonInstrument(inst, ctx, info)
                InstrumentType.STATIC_TEXT -> StaticTextInstrument(inst)
            }
        }
    }
}

@Composable
private fun DisplayInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val v = ctx.value(inst.variables.firstOrNull())
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fs = (min(maxHeight.value * 0.55f, maxWidth.value / 5f)).coerceIn(12f, 64f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text(
                formatValue(v, inst.decimals, info?.valueTable ?: emptyMap()),
                color = levelColor(inst, v, Palette.text), fontSize = fs.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            if (info?.unit?.isNotBlank() == true && info.unit != "-") {
                Text(" " + info.unit, color = Palette.textDim, fontSize = (fs * 0.4f).coerceAtLeast(10f).sp, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

@Composable
private fun GaugeInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val v = ctx.value(inst.variables.firstOrNull())
    val (lo, hi) = inst.range(info)
    val tm = rememberTextMeasurer()
    val color = Color(inst.color)
    Canvas(Modifier.fillMaxSize()) {
        val d = min(size.width, size.height * 1.15f)
        val stroke = d * 0.07f
        val r = d / 2f - stroke
        val c = Offset(size.width / 2f, size.height / 2f + d * 0.06f)
        val start = 135f
        val sweep = 270f
        fun ang(x: Double) = start + (((x - lo) / (hi - lo)).coerceIn(0.0, 1.0) * sweep).toFloat()
        val arcTopLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(2 * r, 2 * r)
        drawArc(Palette.surfaceHigh, start, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        if (!inst.warnHigh.isNaN()) drawArc(Palette.warn.copy(alpha = 0.6f), ang(inst.warnHigh), ang(if (inst.alarmHigh.isNaN()) hi else inst.alarmHigh) - ang(inst.warnHigh), false, arcTopLeft, arcSize, style = Stroke(stroke))
        if (!inst.alarmHigh.isNaN()) drawArc(Palette.alarm.copy(alpha = 0.7f), ang(inst.alarmHigh), ang(hi) - ang(inst.alarmHigh), false, arcTopLeft, arcSize, style = Stroke(stroke))
        if (v != null) drawArc(levelColor(inst, v, color), start, ang(v) - start, false, Offset(c.x - r + stroke * 0.9f, c.y - r + stroke * 0.9f), Size(2 * (r - stroke * 0.9f), 2 * (r - stroke * 0.9f)), style = Stroke(stroke * 0.35f))
        // Ticks and labels
        val step = niceStep(hi - lo, 6)
        var t = Math.ceil(lo / step) * step
        val ls = TextStyle(color = Palette.textDim, fontSize = (d / 22f / density).coerceIn(7f, 12f).sp)
        while (t <= hi + 1e-9) {
            val a = Math.toRadians(ang(t).toDouble())
            val p1 = Offset(c.x + (r - stroke / 2) * cos(a).toFloat(), c.y + (r - stroke / 2) * sin(a).toFloat())
            val p2 = Offset(c.x + (r + stroke / 2) * cos(a).toFloat(), c.y + (r + stroke / 2) * sin(a).toFloat())
            drawLine(Palette.text, p1, p2, 2f)
            val txt = axisLabel(if (hi - lo >= 1000) t / 1000 else t, if (hi - lo >= 1000) step / 1000 else step)
            val l = tm.measure(txt, ls)
            val lr = r - stroke * 1.6f
            drawText(l, topLeft = Offset(c.x + lr * cos(a).toFloat() - l.size.width / 2f, c.y + lr * sin(a).toFloat() - l.size.height / 2f))
            t += step
        }
        // Needle
        if (v != null) {
            rotate(ang(v) + 90f, c) {
                drawLine(Palette.alarm, c, Offset(c.x, c.y - r * 0.85f), stroke * 0.3f, cap = StrokeCap.Round)
            }
        }
        drawCircle(Palette.text, stroke * 0.45f, c)
        val vs = TextStyle(color = levelColor(inst, v, Palette.text), fontSize = (d / 9f / density).coerceIn(10f, 30f).sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        val vl = tm.measure(formatValue(v, inst.decimals), vs)
        drawText(vl, topLeft = Offset(c.x - vl.size.width / 2f, c.y + r * 0.35f))
        val ul = tm.measure(info?.unit?.takeIf { it != "-" } ?: "", ls)
        drawText(ul, topLeft = Offset(c.x - ul.size.width / 2f, c.y + r * 0.35f + vl.size.height))
        if (hi - lo >= 1000) {
            val sl = tm.measure("×1000", ls)
            drawText(sl, topLeft = Offset(c.x - sl.size.width / 2f, c.y - r * 0.45f))
        }
    }
}

@Composable
private fun BarInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val v = ctx.value(inst.variables.firstOrNull())
    val (lo, hi) = inst.range(info)
    val tm = rememberTextMeasurer()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val horizontal = size.width > size.height * 1.4f
            val ls = TextStyle(color = Palette.textDim, fontSize = 9.sp)
            val frac = if (v == null) 0f else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
            val col = levelColor(inst, v, Color(inst.color))
            if (!horizontal) {
                val bw = min(size.width * 0.4f, 36.dp.toPx())
                val x0 = size.width / 2f - bw / 2f + 10.dp.toPx()
                drawRect(Palette.surfaceHigh, Offset(x0, 0f), Size(bw, size.height))
                drawRect(col, Offset(x0, size.height * (1 - frac)), Size(bw, size.height * frac))
                listOf(inst.warnHigh to Palette.warn, inst.alarmHigh to Palette.alarm).forEach { (lim, c) ->
                    if (!lim.isNaN()) { val y = size.height * (1 - ((lim - lo) / (hi - lo)).toFloat()); drawLine(c, Offset(x0 - 4f, y), Offset(x0 + bw + 4f, y), 3f) }
                }
                val step = niceStep(hi - lo, (size.height / 40.dp.toPx()).toInt().coerceIn(2, 6))
                var t = Math.ceil(lo / step) * step
                while (t <= hi + 1e-9) {
                    val y = size.height * (1 - ((t - lo) / (hi - lo)).toFloat())
                    drawLine(Palette.textDim, Offset(x0 - 6f, y), Offset(x0, y), 1.5f)
                    val l = tm.measure(axisLabel(t, step), ls)
                    drawText(l, topLeft = Offset(x0 - 8f - l.size.width, (y - l.size.height / 2f).coerceIn(0f, size.height - l.size.height)))
                    t += step
                }
            } else {
                val bh = min(size.height * 0.5f, 28.dp.toPx())
                drawRect(Palette.surfaceHigh, Offset(0f, 0f), Size(size.width, bh))
                drawRect(col, Offset(0f, 0f), Size(size.width * frac, bh))
                val step = niceStep(hi - lo, 5)
                var t = Math.ceil(lo / step) * step
                while (t <= hi + 1e-9) {
                    val x = size.width * ((t - lo) / (hi - lo)).toFloat()
                    drawLine(Palette.textDim, Offset(x, bh), Offset(x, bh + 5f), 1.5f)
                    val l = tm.measure(axisLabel(t, step), ls)
                    drawText(l, topLeft = Offset((x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), bh + 6f))
                    t += step
                }
            }
        }
        Text("${formatValue(v, inst.decimals)} ${info?.unit ?: ""}", color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LampInstrument(inst: Instrument, ctx: InstrumentContext) {
    val v = ctx.value(inst.variables.firstOrNull())
    val threshold = if (inst.warnHigh.isNaN()) 0.5 else inst.warnHigh
    val on = v != null && v >= threshold
    val c = Color(inst.color)
    Canvas(Modifier.fillMaxSize()) {
        val r = min(size.width, size.height) * 0.36f
        val center = Offset(size.width / 2, size.height / 2)
        if (on) drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center, r * 1.6f), r * 1.6f, center)
        drawCircle(if (on) c else Palette.surfaceHigh, r, center)
        drawCircle(if (on) Color.White.copy(alpha = 0.35f) else Palette.grid, r, center, style = Stroke(2f))
    }
}

@Composable
private fun MultistateInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val v = ctx.value(inst.variables.firstOrNull())
    val state = v?.let { x -> inst.states.minByOrNull { abs(it.value - x) }?.takeIf { abs(it.value - x) < 0.5 } }
    val text = state?.label ?: formatValue(v, 0, info?.valueTable ?: emptyMap())
    val bg = state?.let { Color(it.color) } ?: Palette.surfaceHigh
    BoxWithConstraints(Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(4.dp)).background(bg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        val fs = min(maxHeight.value * 0.5f, maxWidth.value / (text.length.coerceAtLeast(2) * 0.65f)).coerceIn(10f, 56f)
        Text(text, color = Color.White, fontSize = fs.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TimePlotterInstrument(inst: Instrument, ctx: InstrumentContext) {
    val tm = rememberTextMeasurer()
    var frozenAt by remember { mutableStateOf<Double?>(null) }
    var cursor by remember { mutableStateOf<Double?>(null) }
    val latest by rememberUpdatedState(ctx)
    val now = frozenAt ?: ctx.snap.time
    val from = now - inst.timeSpanSec
    val series = inst.variables.mapIndexed { i, n ->
        val color = Palette.signals[i % Palette.signals.size]
        PlotSeries(n, color) { a, _, fn -> ctx.engine.buffer(n)?.forEachSince(a) { t, v -> if (t <= now) fn(t, v) } }
    }
    val separate = inst.autoRange && series.size > 1
    val ranges = if (separate) seriesRanges(series, from, now) else null
    val (yMin, yMax) = if (inst.autoRange) seriesRange(series, from, now) ?: (0.0 to 1.0) else inst.min to inst.max
    val names = displayNames(inst.variables)
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            series.forEachIndexed { i, s ->
                val v = if (cursor != null) s.valueAt(cursor!!, from) else ctx.snap.values[s.name]
                val info = ctx.engine.variable(s.name)
                Text(
                    "■ ${names[i]} ${formatValue(v, inst.decimals, info?.valueTable ?: emptyMap())}",
                    color = s.color, fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.weight(1f))
            if (frozenAt != null) Text("PAUSE", color = Palette.warn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .then(
                    if (ctx.interactive) Modifier.pointerInput(inst, separate) {
                        // Tap: freeze/resume. Drag while frozen: measurement cursor.
                        detectTapGestures(onTap = {
                            if (frozenAt == null) frozenAt = latest.snap.time else { frozenAt = null; cursor = null }
                        })
                    }.pointerInput(inst, separate) {
                        detectDragGestures(onDragStart = { p ->
                            if (frozenAt == null) frozenAt = latest.snap.time
                            val f = frozenAt!!
                            cursor = plotGeometry(Size(size.width.toFloat(), size.height.toFloat()), density, separate).xValue(p.x, f - inst.timeSpanSec, f)
                        }) { change, _ ->
                            val f = frozenAt ?: return@detectDragGestures
                            cursor = plotGeometry(Size(size.width.toFloat(), size.height.toFloat()), density, separate).xValue(change.position.x, f - inst.timeSpanSec, f).coerceIn(f - inst.timeSpanSec, f)
                        }
                    } else Modifier,
                ),
        ) {
            drawPlot(tm, series, from, now, yMin, yMax, cursorX = cursor, ranges = ranges)
        }
    }
}

@Composable
private fun XyPlotterInstrument(inst: Instrument, ctx: InstrumentContext) {
    val tm = rememberTextMeasurer()
    val xn = inst.variables.getOrNull(0)
    val yn = inst.variables.getOrNull(1)
    val xi = ctx.info(xn)
    val yi = ctx.info(yn)
    val now = ctx.snap.time
    Column(Modifier.fillMaxSize()) {
        Text(
            "x: ${xi?.shortName ?: xn ?: "–"} ${formatValue(ctx.value(xn), inst.decimals)}   y: ${yi?.shortName ?: yn ?: "–"} ${formatValue(ctx.value(yn), inst.decimals)}",
            color = Palette.textDim, fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace,
        )
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val xb = xn?.let { ctx.engine.buffer(it) }
            val yb = yn?.let { ctx.engine.buffer(it) }
            val (xLo, xHi) = if (inst.autoRange) (xi?.min ?: 0.0) to (xi?.max ?: 1.0) else inst.min to inst.max
            val (yLo, yHi) = if (inst.autoRange) (yi?.min ?: 0.0) to (yi?.max ?: 1.0) else inst.min to inst.max
            // Draw axes using the generic plot with no series, then the trail on top.
            drawPlot(tm, emptyList(), xLo, xHi, yLo, yHi, xIsTime = false)
            if (xb == null || yb == null) return@Canvas
            val g = plotGeometry(size, density)
            val n = min(xb.size, yb.size)
            val from = now - inst.timeSpanSec
            val col = Color(inst.color)
            var prev: Offset? = null
            for (k in 0 until n) {
                val i = xb.size - n + k
                val j = yb.size - n + k
                val t = xb.time(i)
                if (t < from) continue
                val p = Offset(g.x(xb.value(i), xLo, xHi).coerceIn(g.left, g.left + g.width), g.y(yb.value(j), yLo, yHi).coerceIn(g.top, g.top + g.height))
                val age = ((now - t) / inst.timeSpanSec).toFloat().coerceIn(0f, 1f)
                prev?.let { drawLine(col.copy(alpha = 1f - age * 0.9f), it, p, 2.5f) }
                prev = p
            }
            prev?.let { drawCircle(Color.White, 5.dp.toPx(), it); drawCircle(col, 3.5.dp.toPx(), it) }
        }
    }
}

@Composable
private fun VariableArrayInstrument(inst: Instrument, ctx: InstrumentContext) {
    var editing by remember { mutableStateOf<VariableInfo?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(inst.variables) { n ->
            val info = ctx.engine.variable(n)
            val isParam = info?.kind == VariableKind.PARAMETER
            Row(
                Modifier.fillMaxWidth()
                    .then(if (isParam && ctx.interactive) Modifier.clickable { editing = info } else Modifier)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isParam) "✎" else "", color = Palette.accent, fontSize = 11.sp, modifier = Modifier.width(14.dp))
                Text(info?.shortName ?: n, color = Palette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatValue(ctx.value(n), inst.decimals, info?.valueTable ?: emptyMap()), color = if (isParam) Palette.accent else Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(" " + (info?.unit?.takeIf { it != "-" } ?: ""), color = Palette.textDim, fontSize = 10.sp, modifier = Modifier.width(44.dp), maxLines = 1)
            }
            HorizontalDivider(color = Palette.grid, thickness = 0.5.dp)
        }
    }
    editing?.let { info ->
        ValueInputDialog(
            title = info.name, initial = ctx.value(info.name), unit = info.unit, min = info.min, max = info.max,
            onConfirm = { ctx.write(info.name, it); editing = null }, onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SliderInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val name = inst.variables.firstOrNull()
    val (lo, hi) = inst.range(info)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val current = ctx.value(name)
    val shown = dragging?.toDouble() ?: current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            "${formatValue(shown, inst.decimals)} ${info?.unit ?: ""}", color = Palette.text, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.End),
        )
        val steps = if (inst.step > 0) (((hi - lo) / inst.step).roundToInt() - 1).coerceIn(0, 200) else 0
        Slider(
            value = (shown ?: lo).toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
            onValueChange = { if (ctx.interactive) { dragging = it; if (name != null) ctx.write(name, it.toDouble()) } },
            onValueChangeFinished = { dragging = null },
            valueRange = lo.toFloat()..hi.toFloat(),
            steps = steps,
            enabled = ctx.interactive && name != null,
            colors = SliderDefaults.colors(thumbColor = Color(inst.color), activeTrackColor = Color(inst.color), disabledThumbColor = Color(inst.color).copy(alpha = 0.6f), disabledActiveTrackColor = Color(inst.color).copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun KnobInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val name = inst.variables.firstOrNull()
    val (lo, hi) = inst.range(info)
    var dragValue by remember { mutableStateOf<Double?>(null) }
    val latest by rememberUpdatedState(ctx)
    val shown = dragValue ?: ctx.value(name)
    val tm = rememberTextMeasurer()
    val col = Color(inst.color)
    Canvas(
        Modifier.fillMaxSize().then(
            if (ctx.interactive && name != null) Modifier.pointerInput(lo, hi) {
                detectDragGestures(
                    onDragStart = { dragValue = latest.snap.values[name] ?: lo },
                    onDragEnd = { dragValue?.let { ctx.write(name, it) }; dragValue = null },
                    onDragCancel = { dragValue = null },
                ) { change, amount ->
                    change.consume()
                    var nv = (dragValue ?: lo) + (amount.x - amount.y) / (size.height * 1.5f) * (hi - lo)
                    nv = nv.coerceIn(lo, hi)
                    dragValue = nv
                    val s = inst.step
                    ctx.write(name, if (s > 0) (Math.round(nv / s) * s) else nv)
                }
            } else Modifier,
        ),
    ) {
        val r = min(size.width, size.height) * 0.36f
        val c = Offset(size.width / 2, size.height / 2 - r * 0.1f)
        val frac = shown?.let { ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0) } ?: 0.0
        drawArc(Palette.surfaceHigh, 135f, 270f, false, Offset(c.x - r * 1.15f, c.y - r * 1.15f), Size(r * 2.3f, r * 2.3f), style = Stroke(r * 0.12f, cap = StrokeCap.Round))
        drawArc(col, 135f, (270 * frac).toFloat(), false, Offset(c.x - r * 1.15f, c.y - r * 1.15f), Size(r * 2.3f, r * 2.3f), style = Stroke(r * 0.12f, cap = StrokeCap.Round))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF4A5560), Color(0xFF2A3038)), c, r), r, c)
        val a = Math.toRadians(135 + 270 * frac)
        drawLine(Color.White, Offset(c.x + r * 0.35f * cos(a).toFloat(), c.y + r * 0.35f * sin(a).toFloat()), Offset(c.x + r * 0.85f * cos(a).toFloat(), c.y + r * 0.85f * sin(a).toFloat()), r * 0.1f, cap = StrokeCap.Round)
        val l = tm.measure("${formatValue(shown, inst.decimals)} ${info?.unit ?: ""}", TextStyle(color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
        drawText(l, topLeft = Offset(c.x - l.size.width / 2f, (c.y + r * 1.2f).coerceAtMost(size.height - l.size.height)))
    }
}

@Composable
private fun NumericInputInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val name = inst.variables.firstOrNull()
    val v = ctx.value(name)
    var edit by remember { mutableStateOf(false) }
    val (lo, hi) = inst.range(info)
    val step = if (inst.step > 0) inst.step else niceStep(hi - lo, 20)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (name != null) ctx.write(name, ((v ?: lo) - step).coerceIn(lo, hi)) }, enabled = ctx.interactive && v != null) { Text("−", fontSize = 20.sp) }
        Box(
            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surfaceHigh)
                .then(if (ctx.interactive && name != null) Modifier.clickable { edit = true } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text("${formatValue(v, inst.decimals)} ${info?.unit?.takeIf { it != "-" } ?: ""}", color = Palette.accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp, maxLines = 1)
        }
        TextButton(onClick = { if (name != null) ctx.write(name, ((v ?: lo) + step).coerceIn(lo, hi)) }, enabled = ctx.interactive && v != null) { Text("+", fontSize = 20.sp) }
    }
    if (edit && name != null) {
        ValueInputDialog(name, v, info?.unit ?: "", lo, hi, onConfirm = { ctx.write(name, it); edit = false }, onDismiss = { edit = false })
    }
}

@Composable
private fun PushButtonInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val name = inst.variables.firstOrNull()
    var pressed by remember { mutableStateOf(false) }
    val col = Color(inst.color)
    Box(
        Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (pressed) col else col.copy(alpha = 0.25f))
            .border(2.dp, col, RoundedCornerShape(8.dp))
            .then(
                if (ctx.interactive && name != null) Modifier.pointerInput(name) {
                    detectTapGestures(onPress = {
                        pressed = true
                        ctx.write(name, inst.onValue)
                        tryAwaitRelease()
                        pressed = false
                        ctx.write(name, inst.offValue)
                    })
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(inst.title.ifBlank { info?.shortName ?: "Button" }, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CheckButtonInstrument(inst: Instrument, ctx: InstrumentContext, info: VariableInfo?) {
    val name = inst.variables.firstOrNull()
    val v = ctx.value(name)
    val checked = v != null && abs(v - inst.onValue) < abs(v - inst.offValue)
    Row(
        Modifier.fillMaxSize().then(if (ctx.interactive && name != null) Modifier.clickable { ctx.write(name, if (checked) inst.offValue else inst.onValue) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null, enabled = name != null,
            colors = CheckboxDefaults.colors(checkedColor = Color(inst.color)),
        )
        Text(inst.title.ifBlank { info?.shortName ?: name ?: "" }, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StaticTextInstrument(inst: Instrument) {
    Column(Modifier.fillMaxSize()) {
        if (inst.title.isNotBlank()) Text(inst.title, color = Color(inst.color), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(inst.text, color = Palette.textDim, fontSize = 12.sp)
    }
}

/** Small glyph for an instrument type, used in the palette. */
@Composable
fun InstrumentGlyph(type: InstrumentType, modifier: Modifier = Modifier.size(40.dp)) {
    val c = Palette.accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (type) {
            InstrumentType.DISPLAY -> { drawRect(Palette.grid, Offset(2f, h * 0.25f), Size(w - 4, h * 0.5f), style = Stroke(2f)); drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), 4f) }
            InstrumentType.GAUGE -> { drawArc(c, 135f, 270f, false, Offset(4f, 4f), Size(w - 8, h - 8), style = Stroke(4f)); drawLine(Palette.alarm, Offset(w / 2, h / 2), Offset(w * 0.75f, h * 0.3f), 3f) }
            InstrumentType.BAR -> { drawRect(Palette.grid, Offset(w * 0.35f, 2f), Size(w * 0.3f, h - 4)); drawRect(c, Offset(w * 0.35f, h * 0.4f), Size(w * 0.3f, h * 0.6f - 2)) }
            InstrumentType.LAMP -> drawCircle(Palette.warn, w * 0.3f)
            InstrumentType.MULTISTATE -> { drawRect(Palette.ok, Offset(2f, h * 0.25f), Size(w - 4, h * 0.5f)) }
            InstrumentType.TIME_PLOTTER -> { for (i in 0 until 8) drawLine(c, Offset(w * i / 8f, h / 2 - sin(i * 0.9f) * h * 0.3f), Offset(w * (i + 1) / 8f, h / 2 - sin((i + 1) * 0.9f) * h * 0.3f), 3f); drawLine(Palette.grid, Offset(0f, h - 2), Offset(w, h - 2), 2f) }
            InstrumentType.XY_PLOTTER -> { drawCircle(c, w * 0.3f, style = Stroke(3f)); drawLine(Palette.grid, Offset(w / 2, 0f), Offset(w / 2, h), 2f); drawLine(Palette.grid, Offset(0f, h / 2), Offset(w, h / 2), 2f) }
            InstrumentType.VARIABLE_ARRAY -> for (i in 0 until 4) drawLine(Palette.textDim, Offset(4f, h * (i + 1) / 5f), Offset(w - 4, h * (i + 1) / 5f), 3f)
            InstrumentType.SLIDER -> { drawLine(Palette.grid, Offset(2f, h / 2), Offset(w - 2, h / 2), 4f); drawLine(c, Offset(2f, h / 2), Offset(w * 0.6f, h / 2), 4f); drawCircle(c, w * 0.12f, Offset(w * 0.6f, h / 2)) }
            InstrumentType.KNOB -> { drawCircle(Palette.grid, w * 0.35f); drawLine(Color.White, Offset(w / 2, h / 2), Offset(w * 0.7f, h * 0.25f), 3f) }
            InstrumentType.NUMERIC_INPUT -> { drawRect(Palette.grid, Offset(w * 0.2f, h * 0.3f), Size(w * 0.6f, h * 0.4f), style = Stroke(2f)); drawLine(c, Offset(0f, h / 2), Offset(w * 0.12f, h / 2), 3f) }
            InstrumentType.PUSH_BUTTON -> drawRoundRect(Palette.alarm, Offset(4f, h * 0.2f), Size(w - 8, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(8f))
            InstrumentType.CHECK_BUTTON -> { drawRect(c, Offset(w * 0.25f, h * 0.25f), Size(w * 0.5f, h * 0.5f)); drawLine(Color.White, Offset(w * 0.33f, h * 0.5f), Offset(w * 0.45f, h * 0.62f), 3f); drawLine(Color.White, Offset(w * 0.45f, h * 0.62f), Offset(w * 0.68f, h * 0.36f), 3f) }
            InstrumentType.STATIC_TEXT -> { drawLine(Palette.text, Offset(4f, h * 0.4f), Offset(w - 4, h * 0.4f), 4f); drawLine(Palette.textDim, Offset(4f, h * 0.6f), Offset(w * 0.6f, h * 0.6f), 3f) }
        }
    }
}
