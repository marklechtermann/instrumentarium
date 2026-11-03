package de.instrumentarium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.instrumentarium.core.model.Instrument
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.Layout
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.ConfirmDialog
import de.instrumentarium.ui.Palette
import de.instrumentarium.ui.TextInputDialog
import de.instrumentarium.ui.VariablePickerDialog
import de.instrumentarium.ui.instruments.InstrumentContext
import de.instrumentarium.ui.instruments.InstrumentGlyph
import de.instrumentarium.ui.instruments.InstrumentView
import kotlin.math.roundToInt

@Composable
fun LayoutScreen(app: AppController, selectedLayoutId: String?, onSelectLayout: (String) -> Unit) {
    val project by app.project.collectAsState()
    val snap by app.engine.snapshot.collectAsState()
    val variables by app.engine.variables.collectAsState()
    var editMode by rememberSaveable { mutableStateOf(false) }
    var selectedInstrument by remember { mutableStateOf<String?>(null) }
    var showPalette by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<InstrumentType?>(null) }
    var editing by remember { mutableStateOf<Instrument?>(null) }
    var layoutMenu by remember { mutableStateOf(false) }
    var renameLayout by remember { mutableStateOf(false) }
    var newLayout by remember { mutableStateOf(false) }
    var deleteLayout by remember { mutableStateOf(false) }

    val locked = project.operatorMode
    if (locked && editMode) editMode = false
    val layouts = project.layouts
    val layout = layouts.firstOrNull { it.id == selectedLayoutId } ?: layouts.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (layouts.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = layouts.indexOf(layout).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    edgePadding = 8.dp,
                    containerColor = Palette.background,
                ) {
                    layouts.forEach { l ->
                        Tab(selected = l.id == layout?.id, onClick = { onSelectLayout(l.id); selectedInstrument = null }, text = { Text(l.name) })
                    }
                }
            } else Box(Modifier.weight(1f))
            if (!locked) {
                IconButton(onClick = { editMode = !editMode; selectedInstrument = null }) {
                    Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, contentDescription = "Edit layout", tint = if (editMode) Palette.ok else Palette.text)
                }
                Box {
                    IconButton(onClick = { layoutMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Layout menu") }
                    DropdownMenu(layoutMenu, onDismissRequest = { layoutMenu = false }) {
                        DropdownMenuItem(text = { Text("New layout") }, onClick = { layoutMenu = false; newLayout = true })
                        if (layout != null) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { layoutMenu = false; renameLayout = true })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { layoutMenu = false; app.duplicateLayout(layout.id)?.let(onSelectLayout) })
                            DropdownMenuItem(text = { Text("Export (JSON)") }, onClick = { layoutMenu = false; app.exportLayout(layout.id) })
                        }
                        DropdownMenuItem(text = { Text("Import…") }, onClick = {
                            layoutMenu = false
                            app.platform.pickTextFile { _, text -> app.importLayout(text)?.let(onSelectLayout) }
                        })
                        if (layout != null) DropdownMenuItem(text = { Text("Delete", color = Palette.alarm) }, onClick = { layoutMenu = false; deleteLayout = true })
                    }
                }
            }
        }

        if (layout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { newLayout = true }) { Text("Create first layout") }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LayoutCanvas(
                    app = app, layout = layout, editMode = editMode, selected = selectedInstrument,
                    ctx = InstrumentContext(snap, app.engine, app::write, interactive = !editMode),
                    onSelect = { selectedInstrument = it },
                    onEdit = { editing = it },
                )
                if (editMode) {
                    ExtendedFloatingActionButton(
                        onClick = { showPalette = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Instrument") },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
    }

    if (showPalette) InstrumentPalette(onDismiss = { showPalette = false }) { type ->
        showPalette = false
        if (type == InstrumentType.STATIC_TEXT) layout?.let { app.addInstrument(it.id, type, emptyList())?.let { i -> editing = i } }
        else pendingType = type
    }
    pendingType?.let { type ->
        VariablePickerDialog(
            variables = variables, multi = type.multiVariable, onlyParameters = type.writes,
            onConfirm = { vars -> pendingType = null; layout?.let { app.addInstrument(it.id, type, vars)?.let { i -> selectedInstrument = i.id } } },
            onDismiss = { pendingType = null },
        )
    }
    editing?.let { inst ->
        InstrumentEditor(inst, variables, onDismiss = { editing = null }) { updated ->
            layout?.let { app.updateInstrument(it.id, updated) }
            editing = null
        }
    }
    if (newLayout) TextInputDialog("New layout", "Layout ${layouts.size + 1}", "Create", onConfirm = { onSelectLayout(app.addLayout(it)); newLayout = false; editMode = true }, onDismiss = { newLayout = false })
    if (renameLayout && layout != null) TextInputDialog("Rename layout", layout.name, onConfirm = { app.renameLayout(layout.id, it); renameLayout = false }, onDismiss = { renameLayout = false })
    if (deleteLayout && layout != null) ConfirmDialog("Delete layout?", "\"${layout.name}\" with ${layout.instruments.size} instruments will be deleted.", "Delete", onConfirm = { app.deleteLayout(layout.id); deleteLayout = false }, onDismiss = { deleteLayout = false })
}

/**
 * Grid canvas: 12 columns, cell size follows the screen width (min. 56 dp, then scrolls).
 * In edit mode instruments can be dragged, resized via the corner handle, and edited.
 */
@Composable
private fun LayoutCanvas(
    app: AppController, layout: Layout, editMode: Boolean, selected: String?, ctx: InstrumentContext,
    onSelect: (String?) -> Unit, onEdit: (Instrument) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell = maxOf(maxWidth / AppController.GRID_COLUMNS, 56.dp)
        val rows = maxOf((layout.instruments.maxOfOrNull { it.y + it.h } ?: 0) + if (editMode) 4 else 0, (maxHeight / cell).toInt())
        val cellPx = with(density) { cell.toPx() }
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier.size(cell * AppController.GRID_COLUMNS, cell * rows)
                    .then(if (editMode) Modifier.pointerInput(Unit) { detectTapGestures { onSelect(null) } } else Modifier),
            ) {
                if (editMode) GridBackground(cell, rows)
                layout.instruments.forEach { inst ->
                    key(inst.id) {
                        EditableInstrument(app, layout.id, inst, cellPx, editMode, inst.id == selected, ctx, onSelect, onEdit)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridBackground(cell: androidx.compose.ui.unit.Dp, rows: Int) {
    androidx.compose.foundation.Canvas(Modifier.size(cell * AppController.GRID_COLUMNS, cell * rows)) {
        val c = cell.toPx()
        for (x in 0..AppController.GRID_COLUMNS) drawLine(Palette.grid.copy(alpha = 0.5f), Offset(x * c, 0f), Offset(x * c, size.height), 1f)
        for (y in 0..rows) drawLine(Palette.grid.copy(alpha = 0.5f), Offset(0f, y * c), Offset(size.width, y * c), 1f)
    }
}

@Composable
private fun EditableInstrument(
    app: AppController, layoutId: String, inst: Instrument, cellPx: Float, editMode: Boolean, isSelected: Boolean,
    ctx: InstrumentContext, onSelect: (String?) -> Unit, onEdit: (Instrument) -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    val wPx = (inst.w * cellPx + resize.x).coerceAtLeast(cellPx)
    val hPx = (inst.h * cellPx + resize.y).coerceAtLeast(cellPx)
    val pad = 3.dp
    Box(
        Modifier
            .offset { IntOffset((inst.x * cellPx + drag.x).roundToInt(), (inst.y * cellPx + drag.y).roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .zIndex(if (drag != Offset.Zero || resize != Offset.Zero) 10f else if (isSelected) 5f else 0f)
            .padding(pad),
    ) {
        InstrumentView(inst, ctx, Modifier.fillMaxSize())
        if (editMode) {
            // Overlay catches all gestures in edit mode.
            Box(
                Modifier.fillMaxSize()
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Palette.accent else Palette.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .background(if (isSelected) Palette.accent.copy(alpha = 0.08f) else Color.Transparent)
                    .pointerInput(inst.id) {
                        detectTapGestures(onTap = { onSelect(inst.id) }, onDoubleTap = { onEdit(inst) })
                    }
                    .pointerInput(inst) {
                        detectDragGestures(
                            onDragStart = { onSelect(inst.id) },
                            onDragEnd = {
                                val nx = (inst.x + (drag.x / cellPx).roundToInt()).coerceIn(0, AppController.GRID_COLUMNS - inst.w)
                                val ny = (inst.y + (drag.y / cellPx).roundToInt()).coerceAtLeast(0)
                                drag = Offset.Zero
                                app.updateInstrument(layoutId, inst.copy(x = nx, y = ny))
                            },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, amount -> change.consume(); drag += amount }
                    },
            )
            if (isSelected) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surfaceHigh),
                ) {
                    SmallAction("✎") { onEdit(inst) }
                    SmallAction("⧉") { app.duplicateInstrument(layoutId, inst) }
                    SmallAction("✕", Palette.alarm) { app.removeInstrument(layoutId, inst.id); onSelect(null) }
                }
                // Resize handle (bottom-right corner).
                Box(
                    Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(Palette.accent)
                        .pointerInput(inst) {
                            detectDragGestures(
                                onDragEnd = {
                                    val nw = (inst.w + (resize.x / cellPx).roundToInt()).coerceIn(1, AppController.GRID_COLUMNS - inst.x)
                                    val nh = (inst.h + (resize.y / cellPx).roundToInt()).coerceAtLeast(1)
                                    resize = Offset.Zero
                                    app.updateInstrument(layoutId, inst.copy(w = nw, h = nh))
                                },
                                onDragCancel = { resize = Offset.Zero },
                            ) { change, amount -> change.consume(); resize += amount }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("⤡", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun SmallAction(symbol: String, color: Color = Palette.text, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(symbol, color = color, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstrumentPalette(onDismiss: () -> Unit, onPick: (InstrumentType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add instrument", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    "Displays" to listOf(InstrumentType.DISPLAY, InstrumentType.GAUGE, InstrumentType.BAR, InstrumentType.LAMP, InstrumentType.MULTISTATE, InstrumentType.VARIABLE_ARRAY),
                    "Plotters" to listOf(InstrumentType.TIME_PLOTTER, InstrumentType.XY_PLOTTER),
                    "Control / Calibrate" to listOf(InstrumentType.SLIDER, InstrumentType.KNOB, InstrumentType.NUMERIC_INPUT, InstrumentType.PUSH_BUTTON, InstrumentType.CHECK_BUTTON),
                    "Other" to listOf(InstrumentType.STATIC_TEXT),
                ).forEach { (group, types) ->
                    Text(group, color = Palette.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { t ->
                            Column(
                                Modifier.width(84.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh).clickable { onPick(t) }.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                InstrumentGlyph(t)
                                Text(t.label, fontSize = 11.sp, maxLines = 2, color = Palette.text)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
