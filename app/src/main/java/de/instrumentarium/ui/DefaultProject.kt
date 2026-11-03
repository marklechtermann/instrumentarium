package de.instrumentarium.ui

import de.instrumentarium.core.model.Instrument
import de.instrumentarium.core.model.InstrumentType
import de.instrumentarium.core.model.InstrumentType.*
import de.instrumentarium.core.model.Layout
import de.instrumentarium.core.model.Project
import de.instrumentarium.core.model.StateEntry
import de.instrumentarium.core.source.VehicleModel

/** Demo experiment matching the mock platform and the XCP simulator. */
object DefaultProject {

    private fun i(
        type: InstrumentType, x: Int, y: Int, w: Int, h: Int, vararg vars: String,
        title: String = "", decimals: Int? = null, min: Double? = null, max: Double? = null,
        warn: Double = Double.NaN, alarm: Double = Double.NaN, color: Long = 0xFF2196F3,
        step: Double = 0.0, on: Double = 1.0, off: Double = 0.0, span: Double = 10.0, auto: Boolean = true, text: String = "",
    ): Instrument {
        val info = vars.firstOrNull()?.let { n -> VehicleModel.VARIABLES.firstOrNull { it.name == n } }
        val states = if (type == MULTISTATE && info != null) info.valueTable.entries.mapIndexed { k, e ->
            StateEntry(e.key.toDouble(), e.value, Palette.choices[k % Palette.choices.size])
        } else emptyList()
        return Instrument(
            type = type, x = x, y = y, w = w, h = h, title = title, variables = vars.toList(),
            min = min ?: info?.min ?: 0.0, max = max ?: info?.max ?: 100.0, autoRange = auto,
            decimals = decimals ?: if (info != null && info.max - info.min > 200) 0 else 1,
            warnHigh = warn, alarmHigh = alarm, color = color, step = step, onValue = on, offValue = off,
            timeSpanSec = span, states = states, text = text,
        )
    }

    fun create() = Project(
        name = "Demo Experiment",
        layouts = listOf(
            Layout(
                name = "Cockpit",
                instruments = listOf(
                    i(GAUGE, 0, 0, 4, 4, "Engine.Speed", title = "Engine speed", warn = 5500.0, alarm = 6200.0),
                    i(GAUGE, 4, 0, 4, 4, "Vehicle.Speed", title = "Speed", warn = 180.0, alarm = 220.0),
                    i(MULTISTATE, 8, 0, 2, 2, "Vehicle.Gear", title = "Gear"),
                    i(MULTISTATE, 10, 0, 2, 2, "Engine.State", title = "Engine"),
                    i(LAMP, 8, 2, 2, 2, "Status.OverheatWarning", title = "Overheat", color = 0xFFF44336, warn = 0.5),
                    i(LAMP, 10, 2, 2, 2, "Electrical.FanActive", title = "Fan", color = 0xFF00BCD4, warn = 0.5),
                    i(BAR, 0, 4, 2, 4, "Engine.Load", title = "Load", decimals = 0),
                    i(BAR, 2, 4, 2, 4, "Engine.CoolantTemp", title = "Coolant", min = 40.0, max = 130.0, auto = false, warn = 105.0, alarm = 110.0, decimals = 0, color = 0xFF4CAF50),
                    i(DISPLAY, 4, 4, 3, 2, "Engine.Torque", title = "Torque", decimals = 0),
                    i(DISPLAY, 7, 4, 3, 2, "Electrical.BatteryVoltage", title = "Battery", decimals = 2),
                    i(LAMP, 10, 4, 2, 2, "Status.RevLimiter", title = "Limiter", color = 0xFFFFC107, warn = 0.5),
                    i(DISPLAY, 4, 6, 3, 2, "Engine.OilPressure", title = "Oil pressure", decimals = 2, warn = 5.0),
                    i(DISPLAY, 7, 6, 3, 2, "Vehicle.Odometer", title = "Distance", decimals = 3),
                    i(CHECK_BUTTON, 10, 6, 2, 1, "Driver.Ignition", title = "Ignition"),
                    i(CHECK_BUTTON, 10, 7, 2, 1, "Driver.AutoDrive", title = "AutoDrive"),
                    i(TIME_PLOTTER, 0, 8, 12, 4, "Engine.Speed", "Vehicle.Speed", title = "Engine speed / Vehicle speed", span = 20.0),
                ),
            ),
            Layout(
                name = "Calibration",
                instruments = listOf(
                    i(CHECK_BUTTON, 0, 0, 3, 1, "Driver.Ignition", title = "Ignition"),
                    i(CHECK_BUTTON, 3, 0, 3, 1, "Driver.AutoDrive", title = "AutoDrive"),
                    i(CHECK_BUTTON, 6, 0, 3, 1, "Calib.FanEnable", title = "Fan enable"),
                    i(PUSH_BUTTON, 9, 0, 3, 2, "Driver.ThrottleCmd", title = "Full throttle", on = 100.0, off = 0.0, color = 0xFFF44336),
                    i(SLIDER, 0, 1, 6, 2, "Driver.ThrottleCmd", title = "Throttle", decimals = 0),
                    i(SLIDER, 0, 3, 6, 2, "Driver.BrakeCmd", title = "Brake", decimals = 0, color = 0xFFF44336),
                    i(KNOB, 6, 2, 3, 3, "Driver.SteeringAngle", title = "Steering", decimals = 0, step = 5.0),
                    i(NUMERIC_INPUT, 9, 2, 3, 2, "Calib.IdleSpeed", title = "Idle target", decimals = 0, step = 50.0),
                    i(NUMERIC_INPUT, 9, 4, 3, 2, "Calib.RevLimit", title = "Rev limit", decimals = 0, step = 100.0),
                    i(SLIDER, 0, 5, 6, 2, "Calib.FanThreshold", title = "Fan threshold", decimals = 0, color = 0xFF00BCD4),
                    i(VARIABLE_ARRAY, 6, 6, 6, 4, "Engine.Speed", "Engine.CoolantTemp", "Calib.IdleSpeed", "Calib.RevLimit", "Calib.FanThreshold", "Electrical.FanActive", title = "Variables"),
                    i(STATIC_TEXT, 0, 7, 6, 3, title = "Note", text = "Switch AutoDrive off to control the vehicle with throttle, brake and steering. Disable the fan and apply full throttle to trigger the overheat warning."),
                    i(TIME_PLOTTER, 0, 10, 12, 4, "Engine.CoolantTemp", "Calib.FanThreshold", title = "Cooling", span = 60.0),
                ),
            ),
            Layout(
                name = "Analysis",
                instruments = listOf(
                    i(TIME_PLOTTER, 0, 0, 8, 4, "Engine.Speed", "Engine.Torque", title = "Engine", span = 20.0),
                    i(XY_PLOTTER, 8, 0, 4, 4, "Vehicle.AccelLat", "Vehicle.AccelLong", title = "g-g diagram", min = -10.0, max = 10.0, auto = false, span = 20.0),
                    i(TIME_PLOTTER, 0, 4, 8, 4, "Vehicle.Speed", "Engine.Load", title = "Vehicle", span = 30.0),
                    i(VARIABLE_ARRAY, 8, 4, 4, 4, "Vehicle.AccelLong", "Vehicle.AccelLat", "Vehicle.YawRate", "Vehicle.Gear", "Engine.Torque", title = "Vehicle dynamics"),
                    i(TIME_PLOTTER, 0, 8, 8, 4, "Signals.Sine", "Signals.Square", "Signals.Sawtooth", "Signals.Noise", title = "Test signals", span = 5.0),
                    i(SLIDER, 8, 8, 4, 2, "Signals.Frequency", title = "Frequency", decimals = 2),
                    i(SLIDER, 8, 10, 4, 2, "Signals.Amplitude", title = "Amplitude", decimals = 1),
                ),
            ),
        ),
    )
}
