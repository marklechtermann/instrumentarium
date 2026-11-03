package de.instrumentarium.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs

object Palette {
    val background = Color(0xFF15191F)
    val surface = Color(0xFF1E242C)
    val surfaceHigh = Color(0xFF28303A)
    val grid = Color(0xFF34404D)
    val text = Color(0xFFE3E8EE)
    val textDim = Color(0xFF8C99A6)
    val accent = Color(0xFF1E88E5)
    val ok = Color(0xFF43A047)
    val warn = Color(0xFFFFB300)
    val alarm = Color(0xFFE53935)
    val record = Color(0xFFE53935)

    /** Signal colors for plotters (distinct on dark background). */
    val signals = listOf(
        Color(0xFF42A5F5), Color(0xFFFFA726), Color(0xFF66BB6A), Color(0xFFEF5350),
        Color(0xFFAB47BC), Color(0xFF26C6DA), Color(0xFFD4E157), Color(0xFFEC407A),
    )

    /** Colors offered in the instrument editor (ARGB). */
    val choices = listOf(
        0xFF2196F3, 0xFF4CAF50, 0xFFFFC107, 0xFFF44336, 0xFF9C27B0, 0xFF00BCD4, 0xFFFF9800, 0xFF9E9E9E,
    )
}

@Composable
fun InstrumentariumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            secondary = Color(0xFF26A69A),
            background = Palette.background,
            surface = Palette.surface,
            surfaceVariant = Palette.surfaceHigh,
            onSurface = Palette.text,
            onBackground = Palette.text,
            onSurfaceVariant = Palette.textDim,
            error = Palette.alarm,
        ),
        content = content,
    )
}

fun formatValue(v: Double?, decimals: Int = 2, table: Map<Int, String> = emptyMap()): String {
    if (v == null || v.isNaN()) return "–"
    if (table.isNotEmpty()) table[Math.round(v).toInt()]?.let { return it }
    return String.format(Locale.US, "%.${decimals.coerceIn(0, 6)}f", v)
}

/** Picks a tick step of 1, 2 or 5 × 10^n for the given range. */
fun niceStep(range: Double, targetTicks: Int = 5): Double {
    if (range <= 0 || range.isNaN() || range.isInfinite()) return 1.0
    val raw = range / targetTicks
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val n = raw / mag
    return mag * when {
        n < 1.5 -> 1.0
        n < 3.5 -> 2.0
        n < 7.5 -> 5.0
        else -> 10.0
    }
}

fun axisLabel(v: Double, step: Double): String {
    val decimals = if (step >= 1) 0 else (-Math.floor(Math.log10(step))).toInt().coerceAtMost(4)
    val s = String.format(Locale.US, "%.${decimals}f", v)
    return if (abs(v) < step / 1e6) String.format(Locale.US, "%.${decimals}f", 0.0) else s
}

/** Short variable names for legends; falls back to the full name where short names collide. */
fun displayNames(names: List<String>): List<String> {
    val short = names.map { it.substringAfterLast('.') }
    return names.mapIndexed { i, n -> if (short.count { it == short[i] } > 1) n else short[i] }
}
