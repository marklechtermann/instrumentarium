package de.instrumentarium.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.measure.ConnState
import de.instrumentarium.core.measure.Recording
import de.instrumentarium.core.measure.RecorderState
import de.instrumentarium.core.model.TriggerMode
import de.instrumentarium.ui.screens.DataSetsScreen
import de.instrumentarium.ui.screens.LayoutScreen
import de.instrumentarium.ui.screens.PlatformScreen
import de.instrumentarium.ui.screens.RecorderScreen
import de.instrumentarium.ui.screens.RecordingViewer
import de.instrumentarium.ui.screens.VariablesScreen

enum class Screen(val label: String) { LAYOUTS("Layouts"), VARIABLES("Variables"), RECORDER("Recorder"), DATASETS("Data Sets"), PLATFORM("Platform") }

@Composable
private fun ScreenIcon(s: Screen) {
    val icon: ImageVector? = when (s) {
        Screen.LAYOUTS -> Icons.Default.Home
        Screen.VARIABLES -> Icons.Default.List
        Screen.DATASETS -> Icons.Default.Star
        Screen.PLATFORM -> Icons.Default.Settings
        Screen.RECORDER -> null
    }
    if (icon != null) Icon(icon, contentDescription = s.label)
    else Canvas(Modifier.size(22.dp)) { drawCircle(Palette.record, size.minDimension * 0.32f); drawCircle(Palette.text, size.minDimension * 0.45f, style = Stroke(2.dp.toPx())) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentariumApp(app: AppController, initialScreen: Screen = Screen.LAYOUTS, initialLayoutIndex: Int = 0, initialRecording: Recording? = null) {
    InstrumentariumTheme {
        var screen by rememberSaveable { mutableStateOf(initialScreen) }
        var layoutId by rememberSaveable { mutableStateOf(app.project.value.layouts.getOrNull(initialLayoutIndex)?.id) }
        var viewing by remember { mutableStateOf(initialRecording) }
        val project by app.project.collectAsState()
        val conn by app.engine.state.collectAsState()
        val error by app.engine.error.collectAsState()
        val message by app.message.collectAsState()
        val recState by app.engine.recorder.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); app.clearToast() } }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar("⚠ $it"); app.engine.clearError() } }

        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
            val wide = maxWidth > 700.dp
            Scaffold(
                containerColor = Palette.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.surface),
                        title = {
                            Column {
                                Text("Instrumentarium", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(project.name + if (project.operatorMode) " · Operator" else "", fontSize = 11.sp, color = Palette.textDim, maxLines = 1)
                            }
                        },
                        actions = {
                            StateChip(conn, recState, compact = !wide)
                            IconButton(onClick = { app.toggleOnline() }) { PlugGlyph(conn == ConnState.ONLINE || conn == ConnState.MEASURING) }
                            IconButton(onClick = { app.toggleMeasuring() }) { if (conn == ConnState.MEASURING) StopGlyph() else PlayGlyph() }
                            IconButton(onClick = {
                                if (recState == RecorderState.IDLE) {
                                    val cfg = project.recorder
                                    val vars = cfg.variables.ifEmpty { project.usedVariables().toList().sorted() }.mapNotNull { app.engine.variable(it) }
                                    if (conn != ConnState.MEASURING) app.toggleMeasuring()
                                    app.engine.recorder.start(cfg, vars)
                                    app.toast(if (cfg.mode == TriggerMode.TRIGGERED) "Recorder armed: waiting for trigger" else "Recording (${vars.size} signals)")
                                } else app.engine.recorder.stop()
                            }) { RecordGlyph(recState) }
                        },
                    )
                },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = Palette.surface) {
                        Screen.entries.forEach { s ->
                            NavigationBarItem(selected = screen == s, onClick = { screen = s; viewing = null }, icon = { ScreenIcon(s) }, label = { Text(s.label, fontSize = 10.sp) })
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (wide) NavigationRail(containerColor = Palette.surface) {
                        Screen.entries.forEach { s ->
                            NavigationRailItem(selected = screen == s, onClick = { screen = s; viewing = null }, icon = { ScreenIcon(s) }, label = { Text(s.label, fontSize = 11.sp) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        val rec = viewing
                        when {
                            rec != null -> RecordingViewer(app, rec, onClose = { viewing = null })
                            screen == Screen.LAYOUTS -> LayoutScreen(app, layoutId) { layoutId = it }
                            screen == Screen.VARIABLES -> VariablesScreen(app, layoutId)
                            screen == Screen.RECORDER -> RecorderScreen(app) { viewing = it }
                            screen == Screen.DATASETS -> DataSetsScreen(app)
                            screen == Screen.PLATFORM -> PlatformScreen(app)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateChip(conn: ConnState, rec: RecorderState, compact: Boolean) {
    val blink = rememberInfiniteTransition(label = "blink").animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    val (label, color) = when {
        rec == RecorderState.RECORDING -> "REC" to Palette.record
        rec == RecorderState.ARMED -> "ARMED" to Palette.warn
        else -> conn.label to when (conn) {
            ConnState.MEASURING -> Palette.ok
            ConnState.ONLINE -> Palette.accent
            ConnState.ERROR -> Palette.alarm
            else -> Palette.textDim
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val a = if (rec != RecorderState.IDLE || conn == ConnState.CONNECTING) blink.value else 1f
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = a)))
        Text(if (compact && conn == ConnState.MEASURING && rec == RecorderState.IDLE) "Measuring" else label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PlayGlyph() = Canvas(Modifier.size(22.dp)) {
    val p = Path().apply { moveTo(size.width * 0.25f, size.height * 0.15f); lineTo(size.width * 0.85f, size.height / 2); lineTo(size.width * 0.25f, size.height * 0.85f); close() }
    drawPath(p, Palette.ok)
}

@Composable
private fun StopGlyph() = Canvas(Modifier.size(22.dp)) {
    drawRect(Palette.text, Offset(size.width * 0.2f, size.height * 0.2f), Size(size.width * 0.6f, size.height * 0.6f))
}

@Composable
private fun RecordGlyph(state: RecorderState) = Canvas(Modifier.size(22.dp)) {
    if (state == RecorderState.IDLE) drawCircle(Palette.record, size.minDimension * 0.35f)
    else drawRect(Palette.record, Offset(size.width * 0.22f, size.height * 0.22f), Size(size.width * 0.56f, size.height * 0.56f))
}

/** Plug symbol: filled when online. */
@Composable
private fun PlugGlyph(online: Boolean) = Canvas(Modifier.size(22.dp)) {
    val c = if (online) Palette.accent else Palette.textDim
    val w = size.width
    val h = size.height
    drawLine(c, Offset(w * 0.38f, h * 0.08f), Offset(w * 0.38f, h * 0.3f), 2.5.dp.toPx())
    drawLine(c, Offset(w * 0.62f, h * 0.08f), Offset(w * 0.62f, h * 0.3f), 2.5.dp.toPx())
    val body = Path().apply {
        moveTo(w * 0.2f, h * 0.3f); lineTo(w * 0.8f, h * 0.3f); lineTo(w * 0.8f, h * 0.5f)
        quadraticBezierTo(w * 0.8f, h * 0.72f, w * 0.5f, h * 0.72f); quadraticBezierTo(w * 0.2f, h * 0.72f, w * 0.2f, h * 0.5f); close()
    }
    if (online) drawPath(body, c) else drawPath(body, c, style = Stroke(2.dp.toPx()))
    drawLine(c, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.95f), 2.5.dp.toPx())
    if (!online) drawLine(Palette.alarm.copy(alpha = 0.8f), Offset(w * 0.1f, h * 0.9f), Offset(w * 0.9f, h * 0.1f), 2.dp.toPx())
}
