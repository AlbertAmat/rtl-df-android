package app.rdf.finder

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.abs
import java.util.Locale
import kotlin.math.roundToInt

enum class Mode { Idle, Scan, Track }

data class SessionData(
    val session: SessionRow? = null,
    val points: List<PointRow> = emptyList(),
    val estimate: Geo.Estimate? = null,
    val estimateError: String? = null,
)

data class HeadingInfo(
    val magnetic: Double? = null,
    val trueDeg: Double? = null,
    val declination: Double = 0.0,
    val accuracy: Int = 0,
    val hasSensor: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("rdf", Context.MODE_PRIVATE)

    /** Persistent UI fields (state observable by Compose). */
    val fields = mutableStateMapOf<String, String>().apply {
        for ((k, v) in DEFAULTS) put(k, prefs.getString(k, v) ?: v)
    }

    fun f(k: String): String = fields[k] ?: DEFAULTS[k] ?: ""
    fun setField(k: String, v: String) {
        fields[k] = v
        prefs.edit().putString(k, v).apply()
    }

    private fun fd(k: String): Double? = f(k).trim().replace(',', '.').toDoubleOrNull()

    /** Gain: empty or "auto" = tuner AGC (null). */
    private fun gain(k: String): Double? {
        val s = f(k).trim()
        if (s.isEmpty() || s.equals("auto", ignoreCase = true)) return null
        return s.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 50.0)
    }

    private val client = RtlTcpClient()
    private val sdr = Sdr(client)
    private val tracker = Tracker()
    private val db = RdfDb(app)
    private val beeper = Beeper()
    val gps = GpsProvider(app)
    val compass = HeadingProvider(app)

    private val _mode = MutableStateFlow(Mode.Idle)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()
    private val _sdrConnected = MutableStateFlow(false)
    val sdrConnected: StateFlow<Boolean> = _sdrConnected.asStateFlow()
    private val _sdrInfo = MutableStateFlow("Disconnected")
    val sdrInfo: StateFlow<String> = _sdrInfo.asStateFlow()
    private val _gpsValid = MutableStateFlow(false)
    val gpsValid: StateFlow<Boolean> = _gpsValid.asStateFlow()
    private val _heading = MutableStateFlow(HeadingInfo())
    val heading: StateFlow<HeadingInfo> = _heading.asStateFlow()
    private val _session = MutableStateFlow(SessionData())
    val session: StateFlow<SessionData> = _session.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()
    private val _sound = MutableStateFlow(false)
    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var currentSessionId: Long? = db.lastSessionId()
    @Volatile private var declination = 0.0
    private var job: Job? = null

    init {
        refreshSession()
        viewModelScope.launch {
            while (true) {
                delay(200)
                _sdrConnected.value = client.connected
                _gpsValid.value = gps.isValid()
                updateHeading()
            }
        }
        viewModelScope.launch {
            gps.fix.collect { fix -> if (fix != null) declination = GpsProvider.declination(fix) }
        }
        viewModelScope.launch {
            // Foreground service while capturing, so Android doesn't kill the SDR socket in the background
            mode.collect { m ->
                val ctx = getApplication<Application>()
                if (m == Mode.Idle) CaptureService.stop(ctx) else CaptureService.start(ctx)
            }
        }
        viewModelScope.launch {
            // Pitch follows SNR: higher pitch = stronger signal
            live.collect { l ->
                val snr = l.snrDb
                beeper.freqHz =
                    if (_sound.value && l.running && snr != null && snr > 3) 250.0 + (snr / 40.0).coerceIn(0.0, 1.0) * 1250.0
                    else 0.0
            }
        }
    }

    private fun msg(s: String) {
        _messages.tryEmit(s)
    }

    fun postMessage(s: String) = msg(s)

    private fun f1(v: Double) = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    private fun norm(x: Double) = ((x % 360.0) + 360.0) % 360.0

    private fun updateHeading() {
        compass.axis = runCatching { MountAxis.valueOf(f("mount_axis")) }.getOrDefault(MountAxis.TOP)
        val mag = compass.magneticHeading
        val off = fd("heading_offset") ?: 0.0
        _heading.value = HeadingInfo(
            magnetic = mag,
            trueDeg = mag?.let { norm(it + declination + off) },
            declination = declination,
            accuracy = compass.accuracy.value,
            hasSensor = compass.available,
        )
    }

    // ------------------------------------------------------------ sensors
    fun startSensors(locationGranted: Boolean) {
        if (locationGranted) gps.start()
        compass.start()
    }

    fun stopSensors() {
        gps.stop()
        compass.stop()
    }

    // ------------------------------------------------------------ SDR connection
    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _sdrInfo.value = "Connecting…"
                client.connect(f("sdr_host").trim(), (fd("sdr_port") ?: 1234.0).toInt())
                _sdrConnected.value = true
                _sdrInfo.value = "Connected · tuner ${tunerName(client.tunerType)}"
            } catch (e: Exception) {
                _sdrInfo.value = "Disconnected"
                msg("Could not connect (${e.message}). Is SDR driver running (tap \"Start\")?")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            job?.cancelAndJoin()
            client.close()
            _sdrConnected.value = false
            _sdrInfo.value = "Disconnected"
        }
    }

    private fun tunerName(t: Int) = when (t) {
        1 -> "E4000"; 2 -> "FC0012"; 3 -> "FC0013"; 4 -> "FC2580"; 5 -> "R820T/2"; 6 -> "R828D"
        else -> "unknown"
    }

    private fun launchSdr(newMode: Mode, block: suspend () -> Unit) {
        if (!client.connected) {
            msg("SDR not connected: tap \"Connect SDR\"")
            return
        }
        val settle = (fd("sdr_settle") ?: 80.0).toInt().coerceIn(20, 1000)
        val ppm = (fd("sdr_ppm") ?: 0.0).toInt()
        viewModelScope.launch {
            job?.cancelAndJoin()
            sdr.settleMs = settle // applied only once the previous job has stopped using the SDR
            sdr.ppm = ppm
            _mode.value = newMode
            job = viewModelScope.launch(Dispatchers.Default) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg("SDR error: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    _mode.value = Mode.Idle
                }
            }
        }
    }

    fun stopAll() {
        viewModelScope.launch { job?.cancelAndJoin() }
    }

    // ------------------------------------------------------------ scan
    fun startScan() {
        val f0 = fd("scan_start")
        val f1 = fd("scan_stop")
        val dur = fd("scan_dur")
        val thr = fd("scan_thr")
        if (f0 == null || f1 == null || dur == null || thr == null) {
            msg("Check the scan values")
            return
        }
        if (f0 < 24 || f1 > 1766 || f1 <= f0) {
            msg("Invalid range (24–1766 MHz and \"To\" greater than \"From\")")
            return
        }
        val p = ScanParams(f0 * 1e6, f1 * 1e6, dur.coerceIn(5.0, 3600.0), thr.coerceIn(3.0, 30.0), gain("scan_gain"))
        launchSdr(Mode.Scan) { Scanner.run(sdr, p, _scan) }
    }

    // ------------------------------------------------------------ tracking
    fun startTrack() {
        val fMhz = fd("trk_freq")
        val bw = fd("trk_bw") ?: 12.5
        if (fMhz == null || fMhz < 24 || fMhz > 1766) {
            msg("Invalid frequency (24–1766 MHz)")
            return
        }
        if (bw < 2 || bw > 300) {
            msg("Channel width: 2–300 kHz")
            return
        }
        val g = gain("trk_gain")
        val freq = fMhz * 1e6
        launchSdr(Mode.Track) {
            withContext(Dispatchers.IO) { ensureSession(freq) }
            tracker.run(sdr, freq, bw * 1e3, g, _live)
        }
    }

    // ------------------------------------------------------------ sessions and points
    private fun ensureSession(freq: Double) {
        val cur = currentSessionId?.let { db.getSession(it) }
        if (cur == null || abs(cur.freqHz - freq) > 1000) currentSessionId = db.createSession(freq)
        loadSessionNow()
    }

    private fun loadSessionNow() {
        _sessions.value = db.sessions()
        val s = currentSessionId?.let { db.getSession(it) }
        if (s == null) {
            _session.value = SessionData()
            return
        }
        val pts = db.points(s.id)
        var est: Geo.Estimate? = null
        var err: String? = null
        if (pts.size >= 2) {
            try {
                est = Geo.estimate(pts.map { Geo.Ray(it.lat, it.lon, it.bearingTrue) })
            } catch (e: Geo.GeoException) {
                err = e.message
            }
        } else {
            err = "Record at least 2 points from different locations"
        }
        _session.value = SessionData(s, pts, est, err)
    }

    fun refreshSession() {
        viewModelScope.launch(Dispatchers.IO) { loadSessionNow() }
    }

    /** Records a point with the GPS position, bearing (phone compass or typed) and power. */
    fun recordPoint() {
        viewModelScope.launch(Dispatchers.Default) {
            if (_mode.value != Mode.Track) {
                msg("Start tracking before recording a point")
                return@launch
            }
            val sid = currentSessionId
            if (sid == null) {
                msg("No active session")
                return@launch
            }
            val fix = gps.fix.value
            if (fix == null || !gps.isValid()) {
                msg("No valid GPS fix: wait for a clear view of the sky")
                return@launch
            }
            val meas = tracker.sampleForPoint()
            if (meas == null) {
                msg("Not enough measurements yet: wait a second")
                return@launch
            }
            val useSensor = f("use_sensor") == "true"
            val mag: Double = if (useSensor) {
                compass.meanHeading() ?: run {
                    msg("No compass bearing (antenna too vertical?)")
                    return@launch
                }
            } else {
                val typed = fd("trk_bearing")
                if (typed == null || typed < 0 || typed > 360) {
                    msg("Enter the compass bearing (0–360°)")
                    return@launch
                }
                typed
            }
            val off = fd("heading_offset") ?: 0.0
            val decl = declination
            val bearingTrue = norm(mag + decl + off)
            val hasGpsTime = fix.timeMs > 0
            val ts = if (hasGpsTime) Instant.ofEpochMilli(fix.timeMs).toString() else Instant.now().toString()
            withContext(Dispatchers.IO) {
                db.addPoint(
                    PointRow(
                        id = 0, sessionId = sid, ts = ts, tsSrc = if (hasGpsTime) "gps" else "system",
                        lat = fix.lat, lon = fix.lon, alt = fix.alt, accM = fix.accM?.toDouble(),
                        freqHz = meas.freqHz, powerDb = meas.powerDb, snrDb = meas.snrDb, gain = meas.gainDb,
                        bearingMag = mag, declination = decl, offset = off, bearingTrue = bearingTrue,
                    ),
                )
                loadSessionNow()
            }
            val warn = ArrayList<String>()
            if (meas.snrDb < 6) warn.add("Low SNR (${meas.snrDb.roundToInt()} dB): this may not be the target transmission")
            fix.accM?.let { if (it > 25f) warn.add("Poor GPS accuracy (±${it.roundToInt()} m)") }
            if (meas.clipping) warn.add("Signal clipping: lower the gain")
            if (useSensor && compass.accuracy.value < 2) warn.add("Compass not calibrated: move the phone in a figure 8")
            msg(if (warn.isEmpty()) "Point recorded · bearing ${bearingTrue.roundToInt()}°" else "⚠ " + warn.joinToString(" · "))
        }
    }

    fun deletePoint(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deletePoint(id)
            loadSessionNow()
        }
    }

    fun newSession() {
        val fMhz = fd("trk_freq")
        if (fMhz == null || fMhz < 24 || fMhz > 1766) {
            msg("Enter the frequency in the Track tab first")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            currentSessionId = db.createSession(fMhz * 1e6)
            loadSessionNow()
            msg("New hunt created")
        }
    }

    fun selectSession(id: Long) {
        currentSessionId = id
        db.getSession(id)?.let { setField("trk_freq", f1(it.freqHz / 1e6)) }
        refreshSession()
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteSession(id)
            if (currentSessionId == id) currentSessionId = db.lastSessionId()
            loadSessionNow()
        }
    }

    fun export(ctx: Context, csv: Boolean) {
        val d = _session.value
        val s = d.session
        if (s == null) {
            msg("No session to export")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val file = if (csv) Export.csv(ctx, s, d.points) else Export.geojson(ctx, s, d.points, d.estimate)
            withContext(Dispatchers.Main) { Export.share(ctx, file, if (csv) "text/csv" else "application/geo+json") }
        }
    }

    // ------------------------------------------------------------ sound
    fun setSound(on: Boolean) {
        _sound.value = on
        if (on) {
            beeper.start()
        } else {
            beeper.freqHz = 0.0
            beeper.stop()
        }
    }

    override fun onCleared() {
        job?.cancel()
        client.close()
        gps.stop()
        compass.stop()
        beeper.stop()
        db.close()
    }

    companion object {
        val DEFAULTS = mapOf(
            "scan_start" to "400", "scan_stop" to "470", "scan_dur" to "30", "scan_thr" to "8", "scan_gain" to "38",
            "trk_freq" to "", "trk_bw" to "12.5", "trk_gain" to "30", "trk_bearing" to "",
            "sdr_host" to "127.0.0.1", "sdr_port" to "1234", "sdr_ppm" to "0", "sdr_settle" to "80",
            "mount_axis" to "TOP", "heading_offset" to "0", "use_sensor" to "true", "use_volume" to "true",
            "map_offline" to "false", "dl_zmin" to "12", "dl_zmax" to "16",
        )
    }
}
