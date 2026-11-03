import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import de.instrumentarium.core.measure.ConnState
import de.instrumentarium.core.measure.RecorderState
import de.instrumentarium.core.model.RecorderConfig
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.InstrumentariumApp
import de.instrumentarium.ui.PlatformServices
import de.instrumentarium.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Renders every screen with live mock data into build/screenshots (tablet and phone). */
class ScreenshotTest {
    private val out = File(System.getProperty("screenshots") ?: "build/screenshots").apply { mkdirs() }

    private class TestServices : PlatformServices {
        val shared = mutableMapOf<String, ByteArray>()
        override fun loadProject(): String? = null
        override fun saveProject(json: String) {}
        override fun readAsset(name: String) = File("../app/src/main/assets/$name").readText()
        override fun shareFile(fileName: String, mime: String, bytes: ByteArray) { shared[fileName] = bytes }
        override fun pickTextFile(onPicked: (String, String) -> Unit) {}
    }

    @Test
    fun renderScreens() = runBlocking(Dispatchers.Swing) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val services = TestServices()
        val app = AppController(scope, services)
        app.updateProject { it.copy(platform = it.platform.copy(rasterMs = 20)) }
        app.goOnline(thenMeasure = true)
        delay(3000)
        assertEquals(ConnState.MEASURING, app.engine.state.value)
        app.saveDataSet("Production")
        app.write("Calib.IdleSpeed", 900.0)
        app.write("Calib.FanThreshold", 92.0)
        app.engine.recorder.start(
            RecorderConfig(name = "Drive cycle", durationSec = 8.0),
            listOf("Engine.Speed", "Vehicle.Speed", "Engine.Load", "Engine.CoolantTemp", "Vehicle.Gear").map { app.engine.variable(it)!! },
        )
        delay(9000)
        assertEquals(RecorderState.IDLE, app.engine.recorder.state.value)
        val rec = app.engine.recorder.recordings.value.single()
        println("recording: ${rec.samples} samples, ${rec.duration} s")
        File(out, "drive_cycle.mf4").writeBytes(de.instrumentarium.core.export.Mdf4Writer.write(rec))
        delay(8000) // fill the plotters
        app.clearToast()

        suspend fun shot(name: String, w: Int, h: Int, density: Float, screen: Screen, layout: Int = 0, withRec: Boolean = false) {
            ImageComposeScene(w, h, Density(density)) {
                InstrumentariumApp(app, screen, layout, if (withRec) rec else null)
            }.use { scene ->
                var t = System.nanoTime()
                repeat(5) { scene.render(t); t += 50_000_000; delay(30) }
                val img = scene.render(t)
                File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        shot("tablet_cockpit", 1920, 1200, 1.5f, Screen.LAYOUTS, 0)
        shot("tablet_calibration", 1920, 1200, 1.5f, Screen.LAYOUTS, 1)
        shot("tablet_analysis", 1920, 1200, 1.5f, Screen.LAYOUTS, 2)
        shot("tablet_variables", 1920, 1200, 1.5f, Screen.VARIABLES)
        shot("tablet_recorder", 1920, 1200, 1.5f, Screen.RECORDER)
        shot("tablet_recording_view", 1920, 1200, 1.5f, Screen.RECORDER, withRec = true)
        shot("tablet_datasets", 1920, 1200, 1.5f, Screen.DATASETS)
        shot("tablet_platform", 1920, 1200, 1.5f, Screen.PLATFORM)
        shot("phone_cockpit", 1080, 2340, 2.75f, Screen.LAYOUTS, 0)
        app.engine.goOffline()
        withContext(Dispatchers.Default) { delay(200) }
    }
}
