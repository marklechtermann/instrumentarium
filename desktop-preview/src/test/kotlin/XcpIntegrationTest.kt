import de.instrumentarium.core.source.XcpDataSource
import de.instrumentarium.core.xcp.A2lParser
import de.instrumentarium.core.xcp.XcpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Runs the XCP master against simulator/xcp_sim.py (TCP, UDP, Motorola byte order). */
class XcpIntegrationTest {
    private val procs = mutableListOf<Process>()
    private val a2l = A2lParser.parse(File("../app/src/main/assets/demo.a2l").readText())

    private fun startSim(port: Int, vararg extra: String) {
        val script = System.getProperty("simulator")
        val p = ProcessBuilder("python3", script, "--port", "$port", "--http-port", "0", *extra)
            .redirectErrorStream(true).redirectOutput(File("build/sim-$port.log")).start()
        procs += p
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try { Socket("127.0.0.1", port).close(); Thread.sleep(300); return } catch (_: Exception) { Thread.sleep(100) }
        }
        error("simulator did not start")
    }

    @AfterTest
    fun stop() { procs.forEach { it.destroy() } }

    private fun exercise(port: Int, udp: Boolean) = runBlocking {
        val src = XcpDataSource("127.0.0.1", port, udp, a2l)
        src.connect()
        Thread.sleep(1500)
        val all = src.read(src.variables.map { it.name })
        println("XCP ${if (udp) "UDP" else "TCP"} read ${all.size} variables: " + all.entries.take(8).joinToString { "${it.key}=${"%.2f".format(it.value)}" })
        assertEquals(src.variables.size, all.size)
        assertTrue(all.getValue("Signals.Time") > 1.0)
        assertTrue(all.getValue("Engine.Speed") >= 700.0, "engine running: ${all["Engine.Speed"]}")
        assertEquals(800.0, all.getValue("Calib.IdleSpeed"), 1e-9)
        assertEquals(98.0, all.getValue("Calib.FanThreshold"), 1e-9)

        // Calibration: write, read back (with conversion + quantisation)
        src.write("Calib.IdleSpeed", 950.0)
        src.write("Calib.FanThreshold", 90.0)
        src.write("Driver.SteeringAngle", -12.34)
        src.write("Signals.Amplitude", 2.5)
        val back = src.read(listOf("Calib.IdleSpeed", "Calib.FanThreshold", "Driver.SteeringAngle", "Signals.Amplitude"))
        assertEquals(950.0, back.getValue("Calib.IdleSpeed"), 1e-9)
        assertEquals(90.0, back.getValue("Calib.FanThreshold"), 1e-9)
        assertEquals(-12.3, back.getValue("Driver.SteeringAngle"), 1e-6)
        assertEquals(2.5, back.getValue("Signals.Amplitude"), 1e-6)

        // Polling rate
        val t0 = System.nanoTime()
        val names = listOf("Engine.Speed", "Vehicle.Speed", "Engine.CoolantTemp", "Vehicle.Gear", "Signals.Sine")
        repeat(100) { src.read(names) }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("  ${names.size} variables per poll: %.2f ms".format(ms))
        Thread.sleep(500)
        val rpm = src.read(listOf("Engine.Speed")).getValue("Engine.Speed")
        assertTrue(rpm >= 940.0, "idle speed follows calibration: $rpm")
        src.disconnect()
    }

    @Test
    fun tcp() { startSim(5611); exercise(5611, udp = false) }

    @Test
    fun udp() { startSim(5612); exercise(5612, udp = true) }

    @Test
    fun motorolaByteOrder() {
        startSim(5613, "--big-endian")
        exercise(5613, udp = false)
    }

    @Test
    fun errorsAreReported() {
        startSim(5614)
        val c = XcpClient("127.0.0.1", 5614, udp = false)
        c.connect()
        assertEquals(255, c.maxCto)
        val e = assertFailsWith<Exception> { c.download(0x10000, 0, byteArrayOf(1, 2)) } // measurement area is read-only
        assertTrue(e.message!!.contains("ERR_ACCESS_DENIED"), e.message)
        c.disconnect()
        assertFailsWith<Exception> { XcpClient("127.0.0.1", 1, udp = false).connect() }
    }
}
