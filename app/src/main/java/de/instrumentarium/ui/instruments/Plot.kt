package de.instrumentarium.ui.instruments

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.axisLabel
import de.instrumentarium.ui.niceStep
import kotlin.math.ceil
import kotlin.math.floor

/** A plottable signal: [forEach] calls back with (x, y) for samples with from <= x <= to (plus one before). */
class PlotSeries(
    val name: String,
    val color: Color,
    val forEach: (from: Double, to: Double, fn: (Double, Double) -> Unit) -> Unit,
)

fun seriesRange(series: List<PlotSeries>, from: Double, to: Double): Pair<Double, Double>? {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    series.forEach { s -> s.forEach(from, to) { x, y -> if (x >= from && !y.isNaN()) { if (y < lo) lo = y; if (y > hi) hi = y } } }
    if (lo == Double.POSITIVE_INFINITY) return null
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
    val pad = (hi - lo) * 0.08
    return (lo - pad) to (hi + pad)
}

/** Plot area margins in px, derived from density. */
class PlotGeometry(val left: Float, val top: Float, val right: Float, val bottom: Float, val size: Size) {
    val width get() = size.width - left - right
    val height get() = size.height - top - bottom
    fun x(v: Double, from: Double, to: Double) = left + ((v - from) / (to - from) * width).toFloat()
    fun y(v: Double, min: Double, max: Double) = top + ((1 - (v - min) / (max - min)) * height).toFloat()
    fun xValue(px: Float, from: Double, to: Double) = from + (px - left) / width * (to - from)
}

fun plotGeometry(size: Size, density: Float, rightAxis: Boolean = false) =
    PlotGeometry(40 * density, 6 * density, (if (rightAxis) 40 else 8) * density, 18 * density, size)

/** One auto-scaled range per series (separate Y axes). */
fun seriesRanges(series: List<PlotSeries>, from: Double, to: Double): List<Pair<Double, Double>> =
    series.map { seriesRange(listOf(it), from, to) ?: (0.0 to 1.0) }

private fun DrawScope.dp(v: Int) = v * density

/**
 * Draws grid, axes labels and min/max-decimated signal lines.
 * [cursorX] draws a vertical measurement cursor, [markerX] a dashed trigger marker.
 */
fun DrawScope.drawPlot(
    tm: TextMeasurer,
    series: List<PlotSeries>,
    xFrom: Double,
    xTo: Double,
    yMin: Double,
    yMax: Double,
    cursorX: Double? = null,
    markerX: Double? = null,
    xIsTime: Boolean = true,
    /** Separate Y range per series; the first two get labelled axes (left/right) in their colors. */
    ranges: List<Pair<Double, Double>>? = null,
) {
    val separate = ranges != null && ranges.size == series.size && series.isNotEmpty()
    val g = plotGeometry(size, density, rightAxis = separate && series.size > 1)
    val (axMin, axMax) = if (separate) ranges!![0] else yMin to yMax
    if (g.width <= 0 || g.height <= 0 || xTo <= xFrom || axMax <= axMin) return
    val label = TextStyle(color = Palette.textDim, fontSize = 9.sp)

    // Grid + labels (left axis), optional right axis for the second series
    val ys = niceStep(axMax - axMin, (g.height / dp(28)).toInt().coerceIn(2, 8))
    var yv = ceil(axMin / ys) * ys
    val leftStyle = if (separate) label.copy(color = series[0].color) else label
    while (yv <= axMax + 1e-9) {
        val y = g.y(yv, axMin, axMax)
        drawLine(Palette.grid, Offset(g.left, y), Offset(g.left + g.width, y), 1f)
        val t = tm.measure(axisLabel(yv, ys), leftStyle)
        drawText(t, topLeft = Offset(g.left - t.size.width - dp(3), y - t.size.height / 2f))
        yv += ys
    }
    if (separate && series.size > 1) {
        val (rMin, rMax) = ranges!![1]
        val rs = niceStep(rMax - rMin, (g.height / dp(28)).toInt().coerceIn(2, 8))
        var rv = ceil(rMin / rs) * rs
        val st = label.copy(color = series[1].color)
        while (rv <= rMax + 1e-9) {
            val y = g.y(rv, rMin, rMax)
            val t = tm.measure(axisLabel(rv, rs), st)
            drawText(t, topLeft = Offset(g.left + g.width + dp(3), y - t.size.height / 2f))
            rv += rs
        }
    }
    val xs = niceStep(xTo - xFrom, (g.width / dp(60)).toInt().coerceIn(2, 10))
    var xv = ceil(xFrom / xs) * xs
    while (xv <= xTo + 1e-9) {
        val x = g.x(xv, xFrom, xTo)
        drawLine(Palette.grid, Offset(x, g.top), Offset(x, g.top + g.height), 1f)
        val t = tm.measure(axisLabel(xv, xs) + if (xIsTime) "s" else "", label)
        drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(g.left, size.width - t.size.width), g.top + g.height + dp(2)))
        xv += xs
    }
    drawRect(Palette.grid, Offset(g.left, g.top), Size(g.width, g.height), style = Stroke(1f))

    clipRect(g.left, g.top, g.left + g.width, g.top + g.height) {
        series.forEachIndexed { si, s ->
            val (yMin, yMax) = if (separate) ranges!![si] else yMin to yMax
            // Min/max decimation per pixel column keeps peaks visible at any zoom level.
            val path = Path()
            var started = false
            var col = Int.MIN_VALUE
            var cMin = 0.0; var cMax = 0.0; var cFirst = 0.0; var cLast = 0.0
            fun flush() {
                if (col == Int.MIN_VALUE) return
                val x = g.left + col.toFloat()
                val p1 = g.y(cFirst, yMin, yMax)
                if (!started) { path.moveTo(x, p1); started = true } else path.lineTo(x, p1)
                if (cMax != cMin) { path.lineTo(x, g.y(cMin, yMin, yMax)); path.lineTo(x, g.y(cMax, yMin, yMax)) }
                path.lineTo(x, g.y(cLast, yMin, yMax))
            }
            s.forEach(xFrom, xTo) { x, y ->
                if (y.isNaN()) return@forEach
                val c = floor((x - xFrom) / (xTo - xFrom) * g.width).toInt()
                if (c != col) {
                    flush()
                    col = c; cMin = y; cMax = y; cFirst = y
                } else {
                    if (y < cMin) cMin = y
                    if (y > cMax) cMax = y
                }
                cLast = y
            }
            flush()
            drawPath(path, s.color, style = Stroke(width = dp(1.5f).coerceAtLeast(1.5f)))
        }
        markerX?.let {
            val x = g.x(it, xFrom, xTo)
            drawLine(Palette.warn, Offset(x, g.top), Offset(x, g.top + g.height), dp(1.5f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        }
        cursorX?.let {
            val x = g.x(it, xFrom, xTo)
            drawLine(Color.White, Offset(x, g.top), Offset(x, g.top + g.height), dp(1f))
        }
    }
}

private fun DrawScope.dp(v: Float) = v * density

/** Value of a series at x (sample at or before x). */
fun PlotSeries.valueAt(x: Double, windowFrom: Double): Double? {
    var last: Double? = null
    forEach(windowFrom, x) { t, v -> if (t <= x) last = v }
    return last
}
