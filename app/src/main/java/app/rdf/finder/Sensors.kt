package app.rdf.finder

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val accM: Float?,
    val timeMs: Long,        // hora UTC del GPS
    val sats: Int?,
    val elapsedMs: Long,     // SystemClock.elapsedRealtime() al recibirlo (para medir su antigüedad)
)

/** GPS del propio móvil (sin Play Services: funciona sin datos ni red). */
class GpsProvider(ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix
    private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            started = true
        } catch (e: SecurityException) {
            started = false
        } catch (e: IllegalArgumentException) {
            started = false // el móvil no tiene proveedor GPS
        }
    }

    fun stop() {
        if (!started) return
        lm.removeUpdates(this)
        started = false
    }

    /** Fix válido = recibido hace menos de 5 s. */
    fun isValid(): Boolean {
        val f = _fix.value ?: return false
        return SystemClock.elapsedRealtime() - f.elapsedMs < 5000
    }

    override fun onLocationChanged(l: Location) {
        _fix.value = GpsFix(
            lat = l.latitude,
            lon = l.longitude,
            alt = l.altitude,
            accM = if (l.hasAccuracy()) l.accuracy else null,
            timeMs = l.time,
            sats = l.extras?.getInt("satellites"),
            elapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    // Necesarios en Android 8-10, donde estos métodos aún no son «default» de la interfaz
    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        /** Declinación magnética (grados, Este positivo) para la posición dada. */
        fun declination(f: GpsFix): Double =
            GeomagneticField(f.lat.toFloat(), f.lon.toFloat(), f.alt.toFloat(), System.currentTimeMillis())
                .declination.toDouble()
    }
}

/** Qué cara/borde del móvil apunta en la misma dirección que la Yagi. */
enum class MountAxis(val label: String, val vx: Float, val vy: Float, val vz: Float) {
    TOP("Borde superior del móvil (+Y) — móvil plano, pantalla arriba", 0f, 1f, 0f),
    BOTTOM("Borde inferior (−Y)", 0f, -1f, 0f),
    RIGHT("Borde derecho (+X)", 1f, 0f, 0f),
    LEFT("Borde izquierdo (−X)", -1f, 0f, 0f),
    BACK("Cara trasera (−Z) — móvil vertical, pantalla hacia ti", 0f, 0f, -1f),
}

/**
 * Rumbo magnético hacia donde apunta la Yagi, a partir del vector de rotación (magnetómetro +
 * giroscopio + acelerómetro). Guarda un historial para promediar justo ANTES de pulsar «registrar»
 * (el pulsar mueve la antena).
 */
class HeadingProvider(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val hist = ArrayDeque<Triple<Long, Double, Double>>() // (ms, sen, cos)
    private val lock = Any()
    private var sinAvg = 0.0
    private var cosAvg = 0.0
    private var init = false
    private var started = false

    @Volatile var axis: MountAxis = MountAxis.TOP
    val available: Boolean get() = sensor != null

    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)
    val accuracy: StateFlow<Int> = _accuracy

    /** Último rumbo magnético suavizado (0-360) o null si apunta casi vertical / sin datos. */
    @Volatile var magneticHeading: Double? = null
        private set

    fun start() {
        if (started || sensor == null) return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        started = true
    }

    fun stop() {
        if (!started) return
        sm.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val a = axis
        // rot: dispositivo → mundo (X = este, Y = norte, Z = arriba)
        val wx = rot[0] * a.vx + rot[1] * a.vy + rot[2] * a.vz
        val wy = rot[3] * a.vx + rot[4] * a.vy + rot[5] * a.vz
        if (hypot(wx, wy) < 0.3f) {
            magneticHeading = null
            return
        }
        val h = atan2(wx.toDouble(), wy.toDouble())
        // Media móvil circular (evita el salto 359°→0°)
        if (!init) {
            sinAvg = sin(h); cosAvg = cos(h); init = true
        } else {
            sinAvg += 0.2 * (sin(h) - sinAvg)
            cosAvg += 0.2 * (cos(h) - cosAvg)
        }
        val deg = (Math.toDegrees(atan2(sinAvg, cosAvg)) + 360.0) % 360.0
        magneticHeading = deg
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            hist.addLast(Triple(now, sinAvg, cosAvg))
            while (hist.isNotEmpty() && now - hist.first().first > 5000) hist.removeFirst()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
        _accuracy.value = accuracy
    }

    /** Rumbo magnético medio entre hace [fromAgoMs] y hace [toAgoMs]; si no hay datos, el último. */
    fun meanHeading(fromAgoMs: Long = 2500, toAgoMs: Long = 500): Double? {
        val now = SystemClock.elapsedRealtime()
        var s = 0.0
        var c = 0.0
        var n = 0
        synchronized(lock) {
            for ((t, si, co) in hist) {
                val ago = now - t
                if (ago in toAgoMs..fromAgoMs) { s += si; c += co; n++ }
            }
        }
        if (n == 0) return magneticHeading
        return (Math.toDegrees(atan2(s, c)) + 360.0) % 360.0
    }
}
