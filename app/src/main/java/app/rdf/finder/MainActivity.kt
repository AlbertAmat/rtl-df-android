package app.rdf.finder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // GPS_PROVIDER needs FINE; on Android 12+ the user may grant only approximate (COARSE)
            if (hasLocation()) vm.startSensors(true) else vm.postMessage("Precise location permission denied: no GPS")
        }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // capture stops if the screen turns off
        configureOsmdroid()
        if (!hasLocation()) permLauncher.launch(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
        setContent { RdfApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.startSensors(hasLocation())
    }

    override fun onPause() {
        super.onPause()
        if (vm.mode.value == Mode.Idle) vm.stopSensors() // keep GPS/compass while capturing in the background
    }

    /** Volume keys record a point (touching the screen moves the antenna). */
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
