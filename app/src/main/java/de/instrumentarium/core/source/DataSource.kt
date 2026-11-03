package de.instrumentarium.core.source

import de.instrumentarium.core.model.VariableInfo

/**
 * A measurement & calibration platform ("platform/device").
 * Implementations must be safe to call from one coroutine at a time.
 */
interface DataSource {
    val displayName: String
    val variables: List<VariableInfo>

    /** Opens the connection ("Go Online"). Throws on failure. */
    suspend fun connect()

    suspend fun disconnect()

    /** Reads the current physical values of [names]. Missing names are omitted. */
    suspend fun read(names: Collection<String>): Map<String, Double>

    /** Writes a parameter (physical value). */
    suspend fun write(name: String, value: Double)
}

/** In-app simulation: no hardware, no network. Advances the model in real time. */
class MockDataSource(private val clock: () -> Long = System::nanoTime) : DataSource {
    override val displayName = "Mock platform (vehicle model)"
    override val variables = VehicleModel.VARIABLES

    private val model = VehicleModel()
    private var last = 0L

    override suspend fun connect() {
        last = clock()
    }

    override suspend fun disconnect() {}

    private fun advance() {
        val now = clock()
        var dt = (now - last) / 1e9
        last = now
        // Integrate in small steps for stability; cap pauses (e.g. app in background).
        dt = dt.coerceIn(0.0, 1.0)
        while (dt > 1e-6) {
            val h = minOf(dt, 0.01)
            model.step(h)
            dt -= h
        }
    }

    override suspend fun read(names: Collection<String>): Map<String, Double> {
        advance()
        val all = model.values()
        return names.mapNotNull { n -> all[n]?.let { n to it } }.toMap()
    }

    override suspend fun write(name: String, value: Double) {
        require(name in model.params) { "$name is not a parameter" }
        model.params[name] = value
    }
}
