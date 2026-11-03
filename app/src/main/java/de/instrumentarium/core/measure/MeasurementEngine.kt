package de.instrumentarium.core.measure

import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.core.source.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnState(val label: String) {
    OFFLINE("Offline"), CONNECTING("Connecting…"), ONLINE("Online"), MEASURING("Measuring"), ERROR("Error")
}

/** Latest raster sample. [tick] increases with every sample so UI can react to new data. */
data class Snapshot(val time: Double, val values: Map<String, Double>, val tick: Long)

/**
 * Polls the active data source in the measurement raster, keeps 60+ s of history per
 * variable and feeds the recorder. All state is touched from [scope]'s dispatcher only
 * (the main thread on Android), so UI can read buffers without locking.
 */
class MeasurementEngine(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _variables = MutableStateFlow<List<VariableInfo>>(emptyList())
    val variables: StateFlow<List<VariableInfo>> = _variables
    private val _snapshot = MutableStateFlow(Snapshot(0.0, emptyMap(), 0))
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val _sourceName = MutableStateFlow("")
    val sourceName: StateFlow<String> = _sourceName

    val recorder = Recorder()

    private var source: DataSource? = null
    private val buffers = HashMap<String, SignalBuffer>()
    /** Last known value of every variable read so far (keeps parameters visible between reads). */
    private val latest = HashMap<String, Double>()
    private var job: Job? = null
    private var tick = 0L

    /** Variables requested by layouts and the variable browser. */
    var subscribed: Set<String> = emptySet()
    var rasterMs: Int = 50

    /** Variables known before going online (from the platform description), so layouts can be edited offline. */
    fun setCatalog(vars: List<VariableInfo>) {
        if (source == null) _variables.value = vars
    }

    fun variable(name: String): VariableInfo? = _variables.value.firstOrNull { it.name == name }
    fun buffer(name: String): SignalBuffer? = buffers[name]

    fun goOnline(src: DataSource, onDone: () -> Unit = {}) {
        if (_state.value == ConnState.ONLINE || _state.value == ConnState.MEASURING) return
        _state.value = ConnState.CONNECTING
        _error.value = null
        scope.launch {
            try {
                src.connect()
                source = src
                _variables.value = src.variables
                _sourceName.value = src.displayName
                _state.value = ConnState.ONLINE
                // Show current parameter values right away, even before measuring.
                val params = src.variables.filter { it.kind == VariableKind.PARAMETER }.map { it.name }
                latest.clear()
                if (params.isNotEmpty()) {
                    latest.putAll(src.read(params))
                    _snapshot.value = Snapshot(0.0, latest.toMap(), ++tick)
                }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun goOffline() {
        recorder.stop()
        job?.cancel()
        job = null
        val src = source
        source = null
        _state.value = ConnState.OFFLINE
        if (src != null) scope.launch { try { src.disconnect() } catch (_: Exception) {} }
    }

    fun startMeasuring() {
        val src = source ?: return
        if (job?.isActive == true) return
        buffers.values.forEach { it.clear() }
        _state.value = ConnState.MEASURING
        job = scope.launch {
            val start = System.nanoTime()
            try {
                while (isActive) {
                    val t0 = System.nanoTime()
                    val names = subscribed + recorder.recordedNames
                    val values = src.read(names)
                    val t = (System.nanoTime() - start) / 1e9
                    for ((n, v) in values) buffers.getOrPut(n) { SignalBuffer() }.add(t, v)
                    latest.putAll(values)
                    _snapshot.value = Snapshot(t, latest.toMap(), ++tick)
                    recorder.onSample(t, values)
                    val spent = (System.nanoTime() - t0) / 1_000_000
                    delay((rasterMs - spent).coerceAtLeast(1))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun stopMeasuring() {
        recorder.stop()
        job?.cancel()
        job = null
        if (source != null) _state.value = ConnState.ONLINE
    }

    fun write(name: String, value: Double) {
        val src = source ?: run { _error.value = "Not online: cannot write $name"; return }
        scope.launch {
            try {
                src.write(name, value)
                // Reflect the new value immediately; the next raster sample confirms it.
                latest[name] = value
                _snapshot.value = _snapshot.value.let { it.copy(values = latest.toMap(), tick = ++tick) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Writing $name failed: ${e.message}"
            }
        }
    }

    fun clearError() { _error.value = null }

    private fun fail(e: Exception) {
        _error.value = e.message ?: e.toString()
        recorder.stop()
        job = null
        val src = source
        source = null
        _state.value = ConnState.ERROR
        if (src != null) scope.launch { try { src.disconnect() } catch (_: Exception) {} }
    }
}
