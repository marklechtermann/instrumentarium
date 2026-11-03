package de.instrumentarium.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(8)

@Serializable
enum class InstrumentType(val label: String, val writes: Boolean, val multiVariable: Boolean) {
    DISPLAY("Display", false, false),
    GAUGE("Gauge", false, false),
    BAR("Bar", false, false),
    LAMP("Lamp", false, false),
    MULTISTATE("Multistate Display", false, false),
    TIME_PLOTTER("Time Plotter", false, true),
    XY_PLOTTER("XY Plotter", false, true),
    VARIABLE_ARRAY("Variable Array", false, true),
    SLIDER("Slider", true, false),
    KNOB("Knob", true, false),
    NUMERIC_INPUT("Numeric Input", true, false),
    PUSH_BUTTON("Push Button", true, false),
    CHECK_BUTTON("Check Button", true, false),
    STATIC_TEXT("Static Text", false, false);

    val defaultSize: Pair<Int, Int>
        get() = when (this) {
            DISPLAY -> 3 to 2
            GAUGE -> 3 to 3
            BAR -> 2 to 3
            LAMP -> 2 to 2
            MULTISTATE -> 3 to 2
            TIME_PLOTTER -> 6 to 4
            XY_PLOTTER -> 4 to 4
            VARIABLE_ARRAY -> 4 to 4
            SLIDER -> 4 to 2
            KNOB -> 3 to 3
            NUMERIC_INPUT -> 3 to 2
            PUSH_BUTTON -> 2 to 2
            CHECK_BUTTON -> 3 to 1
            STATIC_TEXT -> 4 to 1
        }
}

@Serializable
data class StateEntry(val value: Double, val label: String, val color: Long)

/**
 * One instrument on a layout. Position and size are in grid cells.
 * The meaning of the optional fields depends on [type].
 */
@Serializable
data class Instrument(
    val id: String = newId(),
    val type: InstrumentType,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = type.defaultSize.first,
    val h: Int = type.defaultSize.second,
    val title: String = "",
    val variables: List<String> = emptyList(),
    val min: Double = 0.0,
    val max: Double = 100.0,
    val autoRange: Boolean = true,
    val decimals: Int = 1,
    val color: Long = 0xFF2196F3,
    /** Gauge/Bar/Display: warning and alarm limits (NaN = off). Lamp: [warnHigh] is the on-threshold. */
    val warnHigh: Double = Double.NaN,
    val alarmHigh: Double = Double.NaN,
    /** Push/Check button values. */
    val onValue: Double = 1.0,
    val offValue: Double = 0.0,
    /** Slider/Knob/Numeric input step size. */
    val step: Double = 0.0,
    /** Time plotter visible span. */
    val timeSpanSec: Double = 10.0,
    val states: List<StateEntry> = emptyList(),
    val text: String = "",
)

@Serializable
data class Layout(
    val id: String = newId(),
    val name: String,
    val instruments: List<Instrument> = emptyList(),
)

@Serializable
data class DataSet(
    val id: String = newId(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val values: Map<String, Double>,
)

@Serializable
enum class PlatformKind { MOCK, XCP }

@Serializable
enum class XcpTransport { TCP, UDP }

@Serializable
enum class A2lSource { BUNDLED, URL, FILE }

@Serializable
data class PlatformConfig(
    val kind: PlatformKind = PlatformKind.MOCK,
    val host: String = "192.168.0.10",
    val port: Int = 5555,
    val transport: XcpTransport = XcpTransport.TCP,
    val a2lSource: A2lSource = A2lSource.BUNDLED,
    val a2lUrl: String = "http://192.168.0.10:8080/demo.a2l",
    /** A2L content loaded from URL or file, cached in the project. */
    val a2lText: String = "",
    val a2lName: String = "",
    val rasterMs: Int = 50,
)

@Serializable
enum class TriggerMode { IMMEDIATE, TRIGGERED }

@Serializable
enum class TriggerCondition(val label: String) {
    RISING("rising edge"), FALLING("falling edge"), ABOVE("above"), BELOW("below")
}

@Serializable
data class RecorderConfig(
    val name: String = "Recorder 1",
    /** Empty = all variables used on layouts. */
    val variables: List<String> = emptyList(),
    val mode: TriggerMode = TriggerMode.IMMEDIATE,
    val triggerVariable: String = "",
    val condition: TriggerCondition = TriggerCondition.RISING,
    val level: Double = 0.0,
    val preTriggerSec: Double = 2.0,
    /** 0 = until stopped. */
    val durationSec: Double = 10.0,
)

@Serializable
data class Project(
    val name: String = "Demo Experiment",
    val layouts: List<Layout> = emptyList(),
    val dataSets: List<DataSet> = emptyList(),
    val platform: PlatformConfig = PlatformConfig(),
    val recorder: RecorderConfig = RecorderConfig(),
    val operatorMode: Boolean = false,
) {
    fun usedVariables(): Set<String> =
        layouts.flatMap { l -> l.instruments.flatMap { it.variables } }.toSet()
}

enum class VariableKind { MEASUREMENT, PARAMETER }

data class VariableInfo(
    val name: String,
    val description: String = "",
    val unit: String = "",
    val kind: VariableKind = VariableKind.MEASUREMENT,
    val min: Double = 0.0,
    val max: Double = 100.0,
    /** Text table for enum-like values (e.g. gear, mode). */
    val valueTable: Map<Int, String> = emptyMap(),
) {
    val group: String get() = name.substringBeforeLast('.', "")
    val shortName: String get() = name.substringAfterLast('.')
}
