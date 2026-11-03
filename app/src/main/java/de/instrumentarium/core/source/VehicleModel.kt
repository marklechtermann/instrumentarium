package de.instrumentarium.core.source

import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

/**
 * Small longitudinal/lateral vehicle model with engine, automatic gearbox and cooling.
 * The same model runs in simulator/xcp_sim.py so mock and network data look alike.
 */
class VehicleModel(seed: Long = 42) {

    companion object {
        private val RATIOS = doubleArrayOf(0.0, 3.6, 2.1, 1.4, 1.0, 0.8, 0.65)
        private const val FINAL = 3.9
        private const val WHEEL_R = 0.31
        private const val MASS = 1400.0
        private const val WHEELBASE = 2.7
        private const val STEER_RATIO = 15.0

        val GEAR_TABLE = mapOf(0 to "N", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6")
        val ENGINE_STATE_TABLE = mapOf(0 to "Off", 1 to "Idle", 2 to "Load", 3 to "Overrun", 4 to "Limiter")
        val ONOFF = mapOf(0 to "Off", 1 to "On")

        private fun m(name: String, unit: String, min: Double, max: Double, desc: String, table: Map<Int, String> = emptyMap()) =
            VariableInfo(name, desc, unit, VariableKind.MEASUREMENT, min, max, table)

        private fun p(name: String, unit: String, min: Double, max: Double, desc: String, table: Map<Int, String> = emptyMap()) =
            VariableInfo(name, desc, unit, VariableKind.PARAMETER, min, max, table)

        val VARIABLES: List<VariableInfo> = listOf(
            m("Engine.Speed", "rpm", 0.0, 7000.0, "Engine speed"),
            m("Engine.Torque", "Nm", -50.0, 350.0, "Engine torque"),
            m("Engine.Load", "%", 0.0, 100.0, "Actual throttle position"),
            m("Engine.CoolantTemp", "degC", -40.0, 140.0, "Coolant temperature"),
            m("Engine.OilPressure", "bar", 0.0, 6.0, "Oil pressure"),
            m("Engine.State", "-", 0.0, 4.0, "Engine state", ENGINE_STATE_TABLE),
            m("Vehicle.Speed", "km/h", 0.0, 250.0, "Vehicle speed"),
            m("Vehicle.Gear", "-", 0.0, 6.0, "Engaged gear", GEAR_TABLE),
            m("Vehicle.AccelLong", "m/s^2", -10.0, 10.0, "Longitudinal acceleration"),
            m("Vehicle.AccelLat", "m/s^2", -10.0, 10.0, "Lateral acceleration"),
            m("Vehicle.YawRate", "deg/s", -60.0, 60.0, "Yaw rate"),
            m("Vehicle.Odometer", "km", 0.0, 1000.0, "Distance travelled"),
            m("Electrical.BatteryVoltage", "V", 10.0, 15.0, "Supply voltage"),
            m("Electrical.FanActive", "-", 0.0, 1.0, "Fan active", ONOFF),
            m("Status.OverheatWarning", "-", 0.0, 1.0, "Overheat warning", ONOFF),
            m("Status.RevLimiter", "-", 0.0, 1.0, "Rev limiter active", ONOFF),
            m("Signals.Time", "s", 0.0, 1e6, "Simulation time"),
            m("Signals.Sine", "-", -10.0, 10.0, "Sine"),
            m("Signals.Square", "-", -10.0, 10.0, "Square"),
            m("Signals.Sawtooth", "-", -10.0, 10.0, "Sawtooth"),
            m("Signals.Noise", "-", -10.0, 10.0, "Noise"),
            p("Driver.Ignition", "-", 0.0, 1.0, "Ignition", ONOFF),
            p("Driver.AutoDrive", "-", 0.0, 1.0, "Automatic drive cycle", ONOFF),
            p("Driver.ThrottleCmd", "%", 0.0, 100.0, "Accelerator pedal"),
            p("Driver.BrakeCmd", "%", 0.0, 100.0, "Brake pedal"),
            p("Driver.SteeringAngle", "deg", -270.0, 270.0, "Steering wheel angle"),
            p("Calib.IdleSpeed", "rpm", 600.0, 1200.0, "Idle speed setpoint"),
            p("Calib.RevLimit", "rpm", 4000.0, 7000.0, "Rev limit"),
            p("Calib.FanThreshold", "degC", 80.0, 110.0, "Fan switch-on threshold"),
            p("Calib.FanEnable", "-", 0.0, 1.0, "Fan enable", ONOFF),
            p("Signals.Frequency", "Hz", 0.05, 5.0, "Test signal frequency"),
            p("Signals.Amplitude", "-", 0.0, 10.0, "Test signal amplitude"),
        )

        val DEFAULT_PARAMS = mapOf(
            "Driver.Ignition" to 1.0,
            "Driver.AutoDrive" to 1.0,
            "Driver.ThrottleCmd" to 0.0,
            "Driver.BrakeCmd" to 0.0,
            "Driver.SteeringAngle" to 0.0,
            "Calib.IdleSpeed" to 800.0,
            "Calib.RevLimit" to 6200.0,
            "Calib.FanThreshold" to 98.0,
            "Calib.FanEnable" to 1.0,
            "Signals.Frequency" to 0.5,
            "Signals.Amplitude" to 5.0,
        )
    }

    private val rnd = java.util.Random(seed)
    val params = DEFAULT_PARAMS.toMutableMap()

    private var t = 0.0
    private var v = 0.0
    private var gear = 0
    private var rpm = 0.0
    private var load = 0.0
    private var coolant = 75.0
    private var odo = 0.0
    private var accel = 0.0
    private var torque = 0.0
    private var fan = false
    private var shiftCooldown = 0.0
    private var throttle = 0.0
    private var brake = 0.0
    private var steer = 0.0

    private fun maxTorque(n: Double) = 180.0 + 140.0 * exp(-((n - 3500.0) / 2200.0).let { it * it })

    fun step(dt: Double) {
        t += dt
        val ign = params.getValue("Driver.Ignition") >= 0.5
        if (params.getValue("Driver.AutoDrive") >= 0.5) {
            val ph = t % 60.0
            when {
                ph < 15 -> { throttle = 60.0; brake = 0.0 }
                ph < 30 -> { throttle = 25.0; brake = 0.0 }
                ph < 38 -> { throttle = 0.0; brake = 35.0 }
                ph < 44 -> { throttle = 0.0; brake = 0.0 }
                ph < 54 -> { throttle = 90.0; brake = 0.0 }
                else -> { throttle = 0.0; brake = 55.0 }
            }
            steer = 15.0 * sin(2 * PI * t / 20.0)
        } else {
            throttle = params.getValue("Driver.ThrottleCmd")
            brake = params.getValue("Driver.BrakeCmd")
            steer = params.getValue("Driver.SteeringAngle")
        }
        if (!ign) throttle = 0.0
        load += (throttle - load) * min(1.0, dt / 0.15)

        val idle = params.getValue("Calib.IdleSpeed")
        val revLimit = params.getValue("Calib.RevLimit")
        var limiter = false
        val wheelForce: Double
        if (ign) {
            if (gear == 0) gear = 1
            val rpmWheel = v / WHEEL_R * 60.0 / (2 * PI) * RATIOS[gear] * FINAL
            val target = max(idle, rpmWheel)
            rpm += (target - rpm) * min(1.0, dt / 0.1)
            limiter = rpm >= revLimit
            torque = load / 100.0 * maxTorque(rpm) - (10.0 + rpm * 0.004)
            if (limiter) torque = min(torque, 0.0)
            wheelForce = if (rpmWheel < idle * 0.9 && load < 1.0) 0.0 else torque * RATIOS[gear] * FINAL / WHEEL_R * 0.9
        } else {
            gear = 0
            rpm *= exp(-dt / 0.4)
            if (rpm < 1) rpm = 0.0
            torque = 0.0
            wheelForce = 0.0
        }
        val drag = 0.5 * 1.2 * 0.65 * 2.2 * v * v + if (v > 0.1) 0.012 * MASS * 9.81 else 0.0
        val brakeForce = if (v > 0.0) brake / 100.0 * 12000.0 else 0.0
        accel = (wheelForce - drag - brakeForce) / MASS
        v += accel * dt
        if (v <= 0.0) { v = 0.0; if (accel < 0) accel = 0.0 }
        odo += v * dt / 1000.0

        shiftCooldown -= dt
        if (ign && shiftCooldown <= 0) {
            if (rpm > 2500 + load * 30 && gear < 6) { gear++; shiftCooldown = 0.8 }
            else if (rpm < 1300 && gear > 1) { gear--; shiftCooldown = 0.8 }
        }

        // Cooling: first-order lag to a load-dependent target temperature, fan with hysteresis.
        val thr = params.getValue("Calib.FanThreshold")
        val fanEnabled = params.getValue("Calib.FanEnable") >= 0.5
        fan = fanEnabled && ign && (coolant > thr || (fan && coolant > thr - 3.0))
        val tTarget = if (ign) 85.0 + load * 0.25 + rpm / 7000.0 * 15.0 - (if (fan) 15.0 else 0.0) - v * 3.6 / 250.0 * 10.0 else 20.0
        coolant += (tTarget - coolant) * dt / (if (ign) 20.0 else 120.0)
    }

    /** Wheel angle from steering-wheel angle. */
    private fun steerRad() = steer / STEER_RATIO * PI / 180.0

    fun values(): Map<String, Double> {
        val ign = params.getValue("Driver.Ignition") >= 0.5
        val delta = steerRad()
        val latRaw = v * v * tan(delta) / WHEELBASE
        val lat = latRaw.coerceIn(-9.5, 9.5)
        val yaw = (if (abs(latRaw) > 9.5 && v > 0.1) lat / v else v * tan(delta) / WHEELBASE) * 180.0 / PI
        val f = params.getValue("Signals.Frequency")
        val a = params.getValue("Signals.Amplitude")
        val phase = (t * f) % 1.0
        val state = when {
            !ign -> 0.0
            rpm >= params.getValue("Calib.RevLimit") -> 4.0
            torque > 5 && load > 1 -> 2.0
            load < 1 && v > 1 -> 3.0
            else -> 1.0
        }
        return mapOf(
            "Engine.Speed" to rpm,
            "Engine.Torque" to torque,
            "Engine.Load" to load,
            "Engine.CoolantTemp" to coolant,
            "Engine.OilPressure" to if (ign && rpm > 100) (1.0 + rpm / 7000.0 * 4.0 - (coolant - 90.0) * 0.01).coerceIn(0.0, 6.0) else 0.0,
            "Engine.State" to state,
            "Vehicle.Speed" to v * 3.6,
            "Vehicle.Gear" to gear.toDouble(),
            "Vehicle.AccelLong" to accel,
            "Vehicle.AccelLat" to lat,
            "Vehicle.YawRate" to yaw.coerceIn(-60.0, 60.0),
            "Vehicle.Odometer" to odo,
            "Electrical.BatteryVoltage" to (if (ign && rpm > 500) 14.2 else 12.5) - (if (fan) 0.3 else 0.0) + rnd.nextGaussian() * 0.02,
            "Electrical.FanActive" to if (fan) 1.0 else 0.0,
            "Status.OverheatWarning" to if (coolant > 110.0) 1.0 else 0.0,
            "Status.RevLimiter" to if (state == 4.0) 1.0 else 0.0,
            "Signals.Time" to t,
            "Signals.Sine" to a * sin(2 * PI * phase),
            "Signals.Square" to a * sign(sin(2 * PI * phase)).let { if (it == 0.0) 1.0 else it },
            "Signals.Sawtooth" to a * (2 * phase - 1),
            "Signals.Noise" to rnd.nextGaussian() * a * 0.2,
        ) + params
    }
}
