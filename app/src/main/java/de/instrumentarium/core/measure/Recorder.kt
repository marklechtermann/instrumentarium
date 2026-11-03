package de.instrumentarium.core.measure

import de.instrumentarium.core.model.RecorderConfig
import de.instrumentarium.core.model.TriggerCondition
import de.instrumentarium.core.model.TriggerMode
import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Recording(
    val id: String = newId(),
    val name: String,
    val startedAtMillis: Long,
    val variables: List<VariableInfo>,
    /** Time of the trigger event relative to the first sample, null for immediate start. */
    val triggerTime: Double?,
) {
    val times = DoubleList()
    val columns = List(variables.size) { DoubleList() }
    val duration get() = if (times.size < 2) 0.0 else times[times.size - 1] - times[0]
    val samples get() = times.size

    fun add(t: Double, row: DoubleArray) {
        times.add(t)
        for (i in row.indices) columns[i].add(row[i])
    }
}

enum class RecorderState { IDLE, ARMED, RECORDING }

/** Recorder with optional start trigger, pre-trigger history and fixed duration. */
class Recorder {
    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings
    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed

    private var config = RecorderConfig()
    private var vars: List<VariableInfo> = emptyList()
    private var current: Recording? = null
    private var t0 = 0.0
    private var prevTrigger: Double? = null
    private val history = ArrayDeque<Pair<Double, DoubleArray>>()
    private var counter = 0

    val recordedNames: List<String> get() = vars.map { it.name } + listOfNotNull(config.triggerVariable.takeIf { config.mode == TriggerMode.TRIGGERED && it.isNotBlank() })

    fun start(cfg: RecorderConfig, variables: List<VariableInfo>) {
        config = cfg
        vars = variables
        history.clear()
        prevTrigger = null
        if (cfg.mode == TriggerMode.IMMEDIATE) begin(null) else _state.value = RecorderState.ARMED
    }

    fun stop() {
        finish()
        _state.value = RecorderState.IDLE
    }

    fun delete(id: String) {
        _recordings.value = _recordings.value.filterNot { it.id == id }
    }

    fun add(rec: Recording) {
        _recordings.value = _recordings.value + rec
    }

    private fun begin(triggerTime: Double?) {
        counter++
        // With a trigger, the recording starts with the pre-trigger history; times are relative to its first sample.
        val first = if (triggerTime != null) history.firstOrNull()?.first ?: triggerTime else Double.NaN
        val rec = Recording(
            name = "${config.name} #$counter",
            startedAtMillis = System.currentTimeMillis(),
            variables = vars,
            triggerTime = triggerTime?.let { it - first },
        )
        if (triggerTime != null) history.forEach { (t, row) -> rec.add(t - first, row) }
        history.clear()
        t0 = first
        current = rec
        _state.value = RecorderState.RECORDING
        _elapsed.value = 0.0
    }

    private fun finish() {
        current?.let { if (it.samples > 0) _recordings.value = _recordings.value + it }
        current = null
    }

    private fun fired(prev: Double?, v: Double): Boolean {
        val l = config.level
        return when (config.condition) {
            TriggerCondition.RISING -> prev != null && prev < l && v >= l
            TriggerCondition.FALLING -> prev != null && prev > l && v <= l
            TriggerCondition.ABOVE -> v > l
            TriggerCondition.BELOW -> v < l
        }
    }

    /** Called by the measurement engine for every raster sample. */
    fun onSample(t: Double, values: Map<String, Double>) {
        when (_state.value) {
            RecorderState.IDLE -> return
            RecorderState.ARMED -> {
                val row = DoubleArray(vars.size) { values[vars[it].name] ?: Double.NaN }
                history.addLast(t to row)
                while (history.isNotEmpty() && t - history.first().first > config.preTriggerSec) history.removeFirst()
                val tv = values[config.triggerVariable] ?: return
                val hit = fired(prevTrigger, tv)
                prevTrigger = tv
                if (hit) begin(t)
            }
            RecorderState.RECORDING -> {
                val rec = current ?: return
                if (t0.isNaN()) t0 = t
                if (rec.samples > 0 && rec.times[rec.samples - 1] >= t - t0) return // sample already copied from history
                rec.add(t - t0, DoubleArray(vars.size) { values[vars[it].name] ?: Double.NaN })
                val since = t - t0 - (rec.triggerTime ?: 0.0)
                _elapsed.value = since
                if (config.durationSec > 0 && since >= config.durationSec) {
                    finish()
                    _state.value = RecorderState.IDLE
                }
            }
        }
    }
}
