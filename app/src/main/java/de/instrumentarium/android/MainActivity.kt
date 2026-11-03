package de.instrumentarium.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.instrumentarium.core.model.Project
import de.instrumentarium.ui.AppController
import de.instrumentarium.ui.InstrumentariumApp
import de.instrumentarium.ui.PlatformServices
import de.instrumentarium.ui.ProjectJson
import java.io.File

/** Survives rotation, so a running measurement keeps going. */
class AppViewModel : ViewModel() {
    val services = DelegatingServices()
    val app by lazy { AppController(viewModelScope, services) }

    override fun onCleared() {
        app.engine.goOffline()
    }
}

/** Forwards to the current activity instance. */
class DelegatingServices : PlatformServices {
    var target: PlatformServices? = null
    private val t get() = target ?: error("No active activity")
    override fun loadProject() = t.loadProject()
    override fun saveProject(json: String) { target?.saveProject(json) }
    override fun readAsset(name: String) = t.readAsset(name)
    override fun shareFile(fileName: String, mime: String, bytes: ByteArray) = t.shareFile(fileName, mime, bytes)
    override fun pickTextFile(onPicked: (String, String) -> Unit) = t.pickTextFile(onPicked)
}

class MainActivity : ComponentActivity(), PlatformServices {

    private var pickCallback: ((String, String) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val cb = pickCallback ?: return@registerForActivityResult
        pickCallback = null
        if (uri == null) return@registerForActivityResult
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
        cb(displayName(uri), text)
    }

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.services.target = this
        enableEdgeToEdge()
        // A measurement screen should not go dark while watching it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { InstrumentariumApp(vm.app) }
    }

    override fun onDestroy() {
        if (vm.services.target === this) vm.services.target = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Persist immediately when leaving the app (autosave is debounced).
        saveProject(ProjectJson.encodeToString(Project.serializer(), vm.app.project.value))
    }

    private val projectFile get() = File(filesDir, "project.json")

    override fun loadProject(): String? = projectFile.takeIf { it.exists() }?.readText()

    override fun saveProject(json: String) {
        val tmp = File(filesDir, "project.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(projectFile)
    }

    override fun readAsset(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    override fun shareFile(fileName: String, mime: String, bytes: ByteArray) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, fileName))
    }

    override fun pickTextFile(onPicked: (String, String) -> Unit) {
        pickCallback = onPicked
        picker.launch(arrayOf("*/*"))
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
}
