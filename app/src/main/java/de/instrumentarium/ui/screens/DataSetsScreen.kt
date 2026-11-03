package de.instrumentarium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.instrumentarium.core.model.DataSet
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.ConfirmDialog
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.TextInputDialog
import de.instrumentarium.ui.formatValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Calibration data sets: snapshot all parameters, activate (download) them, compare with the ECU. */
@Composable
fun DataSetsScreen(app: AppController) {
    val project by app.project.collectAsState()
    val snap by app.engine.snapshot.collectAsState()
    var create by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf<DataSet?>(null) }
    var delete by remember { mutableStateOf<DataSet?>(null) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Data Sets", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "A data set is a saved snapshot of all parameters. Activate writes it to the device, Compare shows differences from the current values.",
            color = Palette.textDim, fontSize = 13.sp,
        )
        Button(onClick = { create = true }) { Text("Save current parameters…") }
        if (project.dataSets.isEmpty()) Text("No data sets yet.", color = Palette.textDim)
        project.dataSets.forEach { ds ->
            val diffs = ds.values.count { (k, v) -> snap.values[k]?.let { abs(it - v) > 1e-6 } == true }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ds.name, fontSize = 15.sp)
                    Text(
                        "${fmt.format(Date(ds.createdAt))} · ${ds.values.size} parameters · " + if (diffs == 0) "matches current values" else "$diffs differences",
                        fontSize = 11.sp, color = if (diffs == 0) Palette.ok else Palette.warn,
                    )
                }
                TextButton(onClick = { app.activateDataSet(ds) }) { Text("Activate") }
                TextButton(onClick = { compare = ds }) { Text("Compare") }
                TextButton(onClick = { delete = ds }) { Text("✕", color = Palette.alarm) }
            }
        }
    }

    if (create) TextInputDialog("Save data set", "Data Set ${project.dataSets.size + 1}", "Save", onConfirm = { app.saveDataSet(it); create = false }, onDismiss = { create = false })
    delete?.let { ds -> ConfirmDialog("Delete data set?", "\"${ds.name}\" will be deleted.", "Delete", onConfirm = { app.deleteDataSet(ds.id); delete = null }, onDismiss = { delete = null }) }
    compare?.let { ds ->
        AlertDialog(
            onDismissRequest = { compare = null },
            title = { Text("Compare: ${ds.name} ↔ current", fontSize = 16.sp) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ds.values.entries.sortedBy { it.key }) { (k, v) ->
                        val cur = snap.values[k]
                        val diff = cur != null && abs(cur - v) > 1e-6
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(k, fontSize = 12.sp, modifier = Modifier.weight(1f), color = if (diff) Palette.warn else Palette.text)
                            Text(formatValue(v, 3), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("  →  ", fontSize = 12.sp, color = Palette.textDim)
                            Text(formatValue(cur, 3), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (diff) Palette.warn else Palette.text)
                        }
                        HorizontalDivider(color = Palette.grid, thickness = 0.5.dp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.activateDataSet(ds); compare = null }) { Text("Activate data set") } },
            dismissButton = { TextButton(onClick = { compare = null }) { Text("Close") } },
        )
    }
}
