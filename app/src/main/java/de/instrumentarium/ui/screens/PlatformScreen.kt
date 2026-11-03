package de.instrumentarium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.measure.ConnState
import de.instrumentarium.core.model.A2lSource
import de.instrumentarium.core.model.PlatformConfig
import de.instrumentarium.core.model.PlatformKind
import de.instrumentarium.core.model.XcpTransport
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.ConfirmDialog
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.TextInputDialog

/** Platform/device configuration (Platform/Device + Measurement Configuration) and project settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformScreen(app: AppController) {
    val project by app.project.collectAsState()
    val conn by app.engine.state.collectAsState()
    val err by app.engine.error.collectAsState()
    val source by app.engine.sourceName.collectAsState()
    val cfg = project.platform
    val online = conn == ConnState.ONLINE || conn == ConnState.MEASURING
    var rename by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }

    fun update(fn: (PlatformConfig) -> PlatformConfig) {
        app.updateProject { it.copy(platform = fn(it.platform)) }
        app.refreshCatalog()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Platform / Device") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(cfg.kind == PlatformKind.MOCK, { update { it.copy(kind = PlatformKind.MOCK) } }, enabled = !online, label = { Text("Mock (no hardware)") })
                FilterChip(cfg.kind == PlatformKind.XCP, { update { it.copy(kind = PlatformKind.XCP) } }, enabled = !online, label = { Text("XCP on Ethernet") })
            }
            if (cfg.kind == PlatformKind.MOCK) {
                Text("Built-in vehicle model with engine, transmission, cooling and test signals. Runs entirely on the device.", color = Palette.textDim, fontSize = 13.sp)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cfg.host, { v -> update { it.copy(host = v.trim()) } }, label = { Text("Host / IP") }, singleLine = true, enabled = !online, modifier = Modifier.width(200.dp))
                    OutlinedTextField(
                        cfg.port.toString(), { v -> v.toIntOrNull()?.let { p -> update { it.copy(port = p.coerceIn(1, 65535)) } } },
                        label = { Text("Port") }, singleLine = true, enabled = !online, modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        XcpTransport.entries.forEach { t -> FilterChip(cfg.transport == t, { update { it.copy(transport = t) } }, enabled = !online, label = { Text(t.name) }) }
                    }
                }
                Text("Variable description (A2L)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(cfg.a2lSource == A2lSource.BUNDLED, { update { it.copy(a2lSource = A2lSource.BUNDLED) } }, enabled = !online, label = { Text("Demo A2L (simulator)") })
                    FilterChip(cfg.a2lSource == A2lSource.URL, { update { it.copy(a2lSource = A2lSource.URL) } }, enabled = !online, label = { Text("URL") })
                    FilterChip(cfg.a2lSource == A2lSource.FILE, { update { it.copy(a2lSource = A2lSource.FILE) } }, enabled = !online, label = { Text("File") })
                }
                when (cfg.a2lSource) {
                    A2lSource.BUNDLED -> Text("Matches simulator/xcp_sim.py. Start the simulator on your PC and enter its IP address above.", color = Palette.textDim, fontSize = 13.sp)
                    A2lSource.URL -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(cfg.a2lUrl, { v -> app.updateProject { p -> p.copy(platform = p.platform.copy(a2lUrl = v.trim())) } }, label = { Text("A2L URL") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !online)
                        OutlinedButton(onClick = { app.loadA2lFromUrl(cfg.a2lUrl); }, enabled = !online) { Text("Load") }
                        TextButton(onClick = { update { it.copy(a2lUrl = "http://${it.host}:8080/demo.a2l") } }, enabled = !online) { Text("From host") }
                    }
                    A2lSource.FILE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.loadA2lFromFile() }, enabled = !online) { Text("Select A2L file…") }
                    }
                }
                if (cfg.a2lSource != A2lSource.BUNDLED) Text(if (cfg.a2lText.isBlank()) "No A2L loaded yet" else "Loaded: ${cfg.a2lName} (${cfg.a2lText.length / 1024} KB)", fontSize = 12.sp, color = if (cfg.a2lText.isBlank()) Palette.warn else Palette.ok)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { app.toggleOnline() }) { Text(if (online) "Go offline" else "Go online") }
                Text(conn.label + if (online) " · $source" else "", color = when (conn) { ConnState.ERROR -> Palette.alarm; ConnState.OFFLINE -> Palette.textDim; else -> Palette.ok }, fontSize = 13.sp)
            }
            err?.let { Text(it, color = Palette.alarm, fontSize = 13.sp) }
        }

        Section("Measurement Configuration") {
            Text("Sampling raster (polling)", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 50, 100, 200, 500).forEach { ms ->
                    FilterChip(cfg.rasterMs == ms, { app.updateProject { it.copy(platform = it.platform.copy(rasterMs = ms)) } }, label = { Text("$ms ms") })
                }
            }
            Text("All variables used on layouts or in the recorder are measured.", color = Palette.textDim, fontSize = 12.sp)
        }

        Section("Project") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(project.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { rename = true }) { Text("Rename") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Operator mode", fontSize = 14.sp)
                    Text("Locks layouts against changes; instruments remain operable.", color = Palette.textDim, fontSize = 12.sp)
                }
                Switch(project.operatorMode, { v -> app.updateProject { it.copy(operatorMode = v) } })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.exportProject() }) { Text("Export project") }
                OutlinedButton(onClick = { app.importProject() }) { Text("Import project/layout") }
                TextButton(onClick = { reset = true }) { Text("Restore demo project", color = Palette.alarm) }
            }
        }
    }
    if (rename) TextInputDialog("Rename project", project.name, onConfirm = { n -> app.updateProject { it.copy(name = n) }; rename = false }, onDismiss = { rename = false })
    if (reset) ConfirmDialog("Restore demo project?", "All layouts and data sets will be replaced by the demo project.", "Restore", onConfirm = { app.resetProject(); reset = false }, onDismiss = { reset = false })
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}
