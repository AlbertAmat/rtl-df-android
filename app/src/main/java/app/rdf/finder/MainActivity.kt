package app.rdf.finder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startSensors(true) else vm.postMessage("Sin permiso de ubicación: no habrá GPS")
        }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // la captura se para si se apaga la pantalla
        configureOsmdroid()
        if (!hasLocation()) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        setContent { RdfApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.startSensors(hasLocation())
    }

    override fun onPause() {
        super.onPause()
        vm.stopSensors()
    }

    /** Las teclas de volumen registran un punto (pulsar la pantalla mueve la antena). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolume && vm.f("use_volume") == "true" && vm.mode.value == Mode.Track) {
            if (event?.repeatCount == 0) vm.recordPoint()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun configureOsmdroid() {
        val cfg = Configuration.getInstance()
        cfg.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        cfg.userAgentValue = packageName
        val base = File(getExternalFilesDir(null) ?: filesDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        cfg.tileFileSystemCacheMaxBytes = 800L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 700L * 1024 * 1024
    }
}
