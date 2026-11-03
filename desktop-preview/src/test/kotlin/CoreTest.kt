import de.instrumentarium.core.export.CsvWriter
import de.instrumentarium.core.export.Mdf4Writer
import de.instrumentarium.core.measure.Recorder
import de.instrumentarium.core.measure.RecorderState
import de.instrumentarium.core.model.Project
import de.instrumentarium.core.model.RecorderConfig
import de.instrumentarium.core.model.TriggerCondition
import de.instrumentarium.core.model.TriggerMode
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.core.source.MockDataSource
import de.instrumentarium.core.source.VehicleModel
import de.instrumentarium.core.xcp.A2lDataType
import de.instrumentarium.core.xcp.A2lParser
import de.instrumentarium.core.xcp.Conversion
import de.instrumentarium.ui.DefaultProject
import de.instrumentarium.ui.ProjectJson
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    private val a2lText = File("../app/src/main/assets/demo.a2l").readText()

    @Test
    fun a2lMatchesMockVariables() {
        val a2l = A2lParser.parse(a2lText)
        assertTrue(a2l.warnings.isEmpty(), a2l.warnings.toString())
        assertEquals("InstrumentariumDemo", a2l.projectName)
        val names = a2l.objects.map { it.name }.toSet()
        assertEquals(VehicleModel.VARIABLES.map { it.name }.toSet(), names)
        val speed = a2l.objects.first { it.name == "Engine.Speed" }
        assertEquals(A2lDataType.UWORD, speed.dataType)
        assertEquals(0x10000L, speed.address)
        assertEquals(Conversion.Linear(0.25, 0.0), speed.conversion)
        val vs = a2l.objects.first { it.name == "Vehicle.Speed" }
        assertEquals(100.0, vs.conversion.toPhys(12800.0), 1e-9)
        assertEquals(12800.0, vs.conversion.toRaw(100.0), 1e-9)
        val gear = a2l.objects.first { it.name == "Vehicle.Gear" }
        assertEquals("N", gear.valueTable[0])
        val fan = a2l.objects.first { it.name == "Calib.FanThreshold" }
        assertEquals(VariableKind.PARAMETER, fan.kind)
        assertEquals(138.0, fan.conversion.toRaw(98.0), 1e-9)
    }

    @Test
    fun mockSourceProducesPlausibleValues() = runBlocking {
        var now = 0L
        val src = MockDataSource { now }
        src.connect()
        repeat(200) { // 20 s simulated in 100 ms steps
            now += 100_000_000L
            src.read(listOf("Engine.Speed"))
        }
        val v = src.read(src.variables.map { it.name })
        assertEquals(src.variables.size, v.size)
        assertTrue(v.getValue("Vehicle.Speed") > 20.0, "vehicle should drive in AutoDrive: $v")
        assertTrue(v.getValue("Engine.Speed") in 800.0..7000.0)
        src.write("Driver.Ignition", 0.0)
        repeat(50) { now += 100_000_000L; src.read(listOf("Engine.Speed")) }
        assertTrue(src.read(listOf("Engine.Speed")).getValue("Engine.Speed") < 10.0)
    }

    @Test
    fun recorderTriggerWithPretrigger() {
        val rec = Recorder()
        val info = VehicleModel.VARIABLES.filter { it.name == "Signals.Sine" }
        rec.start(RecorderConfig(mode = TriggerMode.TRIGGERED, triggerVariable = "Signals.Sine", condition = TriggerCondition.RISING, level = 0.5, preTriggerSec = 1.0, durationSec = 2.0), info)
        assertEquals(RecorderState.ARMED, rec.state.value)
        var t = 0.0
        while (rec.state.value != RecorderState.IDLE && t < 20) {
            rec.onSample(t, mapOf("Signals.Sine" to Math.sin(2 * Math.PI * 0.25 * t - Math.PI / 2)))
            t += 0.01
        }
        val r = rec.recordings.value.single()
        val trig = assertNotNull(r.triggerTime)
        assertEquals(1.0, trig, 0.02)
        assertEquals(3.0, r.duration, 0.03)
        // Signal crosses the level at the trigger time.
        val i = (0 until r.samples).first { r.times[it] >= trig }
        assertTrue(r.columns[0][i] >= 0.5 && r.columns[0][i - 1] < 0.5)
    }

    @Test
    fun exportsAndProjectRoundTrip() {
        val rec = Recorder()
        val vars = VehicleModel.VARIABLES.take(3)
        rec.start(RecorderConfig(durationSec = 1.0), vars)
        var t = 0.0
        while (rec.state.value != RecorderState.IDLE) { rec.onSample(t, vars.associate { it.name to t * 10 }); t += 0.01 }
        val r = rec.recordings.value.single()
        val csv = CsvWriter.write(r)
        assertTrue(csv.startsWith("time [s],Engine.Speed [rpm]"))
        val mdf = Mdf4Writer.write(r)
        assertEquals("MDF     4.10    ", String(mdf, 0, 16))
        val out = File("build/test-output").apply { mkdirs() }
        File(out, "test.mf4").writeBytes(mdf)

        val p = DefaultProject.create()
        val json = ProjectJson.encodeToString(Project.serializer(), p)
        assertEquals(p, ProjectJson.decodeFromString(Project.serializer(), json))
        val known = VehicleModel.VARIABLES.map { it.name }.toSet()
        assertTrue(p.usedVariables().all { it in known }, (p.usedVariables() - known).toString())
    }
}
