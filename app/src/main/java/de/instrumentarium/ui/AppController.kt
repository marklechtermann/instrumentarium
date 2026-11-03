package de.instrumentarium.ui

import de.instrumentarium.core.measure.ConnState
import de.instrumentarium.core.measure.MeasurementEngine
import de.instrumentarium.core.model.DataSet
import de.instrumentarium.core.model.Instrument
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.Layout
import de.instrumentarium.core.model.PlatformKind
import de.instrumentarium.core.model.Project
import de.instrumentarium.core.model.A2lSource
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.core.model.XcpTransport
import de.instrumentarium.core.model.newId
import de.instrumentarium.core.source.DataSource
import de.instrumentarium.core.source.MockDataSource
import de.instrumentarium.core.source.XcpDataSource
import de.instrumentarium.core.xcp.A2lParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Everything the UI needs from the host platform (Android activity or desktop preview). */
interface PlatformServices {
    fun loadProject(): String?
    fun saveProject(json: String)
    fun readAsset(name: String): String
    /** Hands a generated file to the user (Android: share sheet). */
    fun shareFile(fileName: String, mime: String, bytes: ByteArray)
    /** Lets the user pick a text file; calls back with (name, content). */
    fun pickTextFile(onPicked: (String, String) -> Unit)
}

val ProjectJson = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    encodeDefaults = true
    prettyPrint = true
}

@OptIn(FlowPreview::class)
class AppController(private val scope: CoroutineScope, val platform: PlatformServices) {
    val engine = MeasurementEngine(scope)

    private val _project = MutableStateFlow(loadInitial())
    val project: StateFlow<Project> = _project

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Set while the variable browser is visible, so it can show live values of everything. */
    var browseAll = false
        set(value) { field = value; updateSubscription() }

    init {
        engine.rasterMs = _project.value.platform.rasterMs
        refreshCatalog()
        updateSubscription()
        scope.launch {
            _project.drop(1).debounce(400).collect { p ->
                try { platform.saveProject(ProjectJson.encodeToString(Project.serializer(), p)) } catch (e: Exception) { toast("Save failed: ${e.message}") }
            }
        }
    }

    private fun loadInitial(): Project = try {
        platform.loadProject()?.let { ProjectJson.decodeFromString(Project.serializer(), it) } ?: DefaultProject.create()
    } catch (e: Exception) {
        DefaultProject.create()
    }

    fun toast(msg: String) { _message.value = msg }
    fun clearToast() { _message.value = null }

    fun updateProject(fn: (Project) -> Project) {
        _project.value = fn(_project.value)
        engine.rasterMs = _project.value.platform.rasterMs
        updateSubscription()
    }

    private fun updateSubscription() {
        val p = _project.value
        engine.subscribed = if (browseAll) engine.variables.value.map { it.name }.toSet() + p.usedVariables() else p.usedVariables()
    }

    // ---------------------------------------------------------------- platform

    fun createSource(): DataSource {
        val cfg = _project.value.platform
        return when (cfg.kind) {
            PlatformKind.MOCK -> MockDataSource()
            PlatformKind.XCP -> {
                val text = when (cfg.a2lSource) {
                    A2lSource.BUNDLED -> platform.readAsset("demo.a2l")
                    else -> cfg.a2lText.ifBlank { error("No A2L loaded") }
                }
                val a2l = A2lParser.parse(text)
                if (a2l.objects.isEmpty()) error("A2L contains no supported variables")
                if (a2l.warnings.isNotEmpty()) toast("A2L: ${a2l.warnings.size} warnings, e.g. ${a2l.warnings.first()}")
                XcpDataSource(cfg.host, cfg.port, cfg.transport == XcpTransport.UDP, a2l)
            }
        }
    }

    /** Updates the offline variable list after the platform configuration changed. */
    fun refreshCatalog() {
        val vars = try { createSource().variables } catch (e: Exception) { emptyList() }
        engine.setCatalog(vars)
    }

    fun goOnline(thenMeasure: Boolean = false) {
        val src = try { createSource() } catch (e: Exception) { toast(e.message ?: "Error"); return }
        engine.goOnline(src) {
            updateSubscription()
            if (thenMeasure) engine.startMeasuring()
        }
    }

    fun toggleMeasuring() {
        when (engine.state.value) {
            ConnState.MEASURING -> engine.stopMeasuring()
            ConnState.ONLINE -> engine.startMeasuring()
            else -> goOnline(thenMeasure = true)
        }
    }

    fun toggleOnline() {
        when (engine.state.value) {
            ConnState.ONLINE, ConnState.MEASURING -> engine.goOffline()
            ConnState.CONNECTING -> {}
            else -> goOnline()
        }
    }

    fun loadA2lFromUrl(url: String) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 5000
                    c.inputStream.bufferedReader().use { it.readText() }
                }
                val n = A2lParser.parse(text).objects.size
                updateProject { it.copy(platform = it.platform.copy(a2lText = text, a2lName = url.substringAfterLast('/'), a2lSource = A2lSource.URL, a2lUrl = url)) }
                refreshCatalog()
                toast("A2L loaded: $n variables")
            } catch (e: Exception) {
                toast("Could not load A2L: ${e.message}")
            }
        }
    }

    fun loadA2lFromFile() {
        platform.pickTextFile { name, text ->
            val n = A2lParser.parse(text).objects.size
            updateProject { it.copy(platform = it.platform.copy(a2lText = text, a2lName = name, a2lSource = A2lSource.FILE)) }
            refreshCatalog()
            toast("A2L $name loaded: $n variables")
        }
    }

    // ---------------------------------------------------------------- layouts

    fun addLayout(name: String): String {
        val l = Layout(name = name)
        updateProject { it.copy(layouts = it.layouts + l) }
        return l.id
    }

    fun renameLayout(id: String, name: String) = updateLayout(id) { it.copy(name = name) }

    fun deleteLayout(id: String) = updateProject { p -> p.copy(layouts = p.layouts.filterNot { it.id == id }) }

    fun duplicateLayout(id: String): String? {
        val src = _project.value.layouts.firstOrNull { it.id == id } ?: return null
        val copy = src.copy(id = newId(), name = src.name + " (copy)", instruments = src.instruments.map { it.copy(id = newId()) })
        updateProject { it.copy(layouts = it.layouts + copy) }
        return copy.id
    }

    fun importLayout(json: String): String? = try {
        val l = ProjectJson.decodeFromString(Layout.serializer(), json)
        val fresh = l.copy(id = newId(), instruments = l.instruments.map { it.copy(id = newId()) })
        updateProject { it.copy(layouts = it.layouts + fresh) }
        fresh.id
    } catch (e: Exception) {
        toast("Invalid layout: ${e.message}"); null
    }

    fun exportLayout(id: String) {
        val l = _project.value.layouts.firstOrNull { it.id == id } ?: return
        platform.shareFile("${l.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.layout.json", "application/json", ProjectJson.encodeToString(Layout.serializer(), l).toByteArray())
    }

    fun updateLayout(id: String, fn: (Layout) -> Layout) =
        updateProject { p -> p.copy(layouts = p.layouts.map { if (it.id == id) fn(it) else it }) }

    fun updateInstrument(layoutId: String, inst: Instrument) =
        updateLayout(layoutId) { l -> l.copy(instruments = l.instruments.map { if (it.id == inst.id) inst else it }) }

    fun removeInstrument(layoutId: String, id: String) =
        updateLayout(layoutId) { l -> l.copy(instruments = l.instruments.filterNot { it.id == id }) }

    fun duplicateInstrument(layoutId: String, inst: Instrument) {
        val l = _project.value.layouts.firstOrNull { it.id == layoutId } ?: return
        val (x, y) = freeSpot(l, inst.w, inst.h)
        updateLayout(layoutId) { it.copy(instruments = it.instruments + inst.copy(id = newId(), x = x, y = y)) }
    }

    /** Adds an instrument at the first free grid position. */
    fun addInstrument(layoutId: String, type: InstrumentType, variables: List<String>): Instrument? {
        val l = _project.value.layouts.firstOrNull { it.id == layoutId } ?: return null
        val info = variables.firstOrNull()?.let { engine.variable(it) }
        var inst = Instrument(type = type, variables = variables)
        if (info != null) {
            inst = inst.copy(min = info.min, max = info.max, decimals = if (info.max - info.min > 200) 0 else 2)
            if (type == InstrumentType.MULTISTATE && info.valueTable.isNotEmpty()) {
                inst = inst.copy(states = info.valueTable.entries.mapIndexed { i, e ->
                    de.instrumentarium.core.model.StateEntry(e.key.toDouble(), e.value, Palette.choices[i % Palette.choices.size])
                })
            }
        }
        if (type == InstrumentType.STATIC_TEXT) inst = inst.copy(text = "Label")
        val (x, y) = freeSpot(l, inst.w, inst.h)
        inst = inst.copy(x = x, y = y)
        updateLayout(layoutId) { it.copy(instruments = it.instruments + inst) }
        return inst
    }

    fun freeSpot(l: Layout, w: Int, h: Int, columns: Int = GRID_COLUMNS): Pair<Int, Int> {
        fun free(x: Int, y: Int) = l.instruments.none { o -> x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h }
        for (y in 0..200) for (x in 0..(columns - w).coerceAtLeast(0)) if (free(x, y)) return x to y
        return 0 to (l.instruments.maxOfOrNull { it.y + it.h } ?: 0)
    }

    // ---------------------------------------------------------------- calibration

    fun write(name: String, value: Double) = engine.write(name, value)

    fun parameters(): List<VariableInfo> = engine.variables.value.filter { it.kind == VariableKind.PARAMETER }

    fun saveDataSet(name: String) {
        val params = parameters()
        if (params.isEmpty()) { toast("Go online first to save parameters"); return }
        val snap = engine.snapshot.value.values
        val values = params.mapNotNull { v -> snap[v.name]?.let { v.name to it } }.toMap()
        updateProject { it.copy(dataSets = it.dataSets + DataSet(name = name, values = values)) }
        toast("Data Set \"$name\" saved with ${values.size} parameters")
    }

    fun activateDataSet(ds: DataSet) {
        if (engine.state.value != ConnState.ONLINE && engine.state.value != ConnState.MEASURING) { toast("Not online"); return }
        val known = parameters().map { it.name }.toSet()
        var n = 0
        ds.values.forEach { (k, v) -> if (k in known) { engine.write(k, v); n++ } }
        toast("Data Set \"${ds.name}\" activated: $n parameters written")
    }

    fun deleteDataSet(id: String) = updateProject { p -> p.copy(dataSets = p.dataSets.filterNot { it.id == id }) }

    // ---------------------------------------------------------------- project

    fun exportProject() {
        val p = _project.value
        platform.shareFile("${p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.cdproj.json", "application/json", ProjectJson.encodeToString(Project.serializer(), p).toByteArray())
    }

    fun importProject() {
        platform.pickTextFile { _, text ->
            try {
                val p = ProjectJson.decodeFromString(Project.serializer(), text)
                engine.goOffline()
                updateProject { p }
                toast("Project \"${p.name}\" loaded")
            } catch (e: Exception) {
                // Maybe it is a single layout.
                importLayout(text)?.let { toast("Layout imported") }
            }
        }
    }

    fun resetProject() {
        engine.goOffline()
        updateProject { DefaultProject.create() }
    }

    companion object {
        const val GRID_COLUMNS = 12
    }
}
