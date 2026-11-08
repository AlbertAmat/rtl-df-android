package app.rdf.finder

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
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
import kotlin.math.max
import kotlin.math.min
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

    fun f(k: String): String = fields[k] ?: DEFAULTS[k] ?: prefs.getString(k, null) ?: ""
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
    private val peakFinder = PeakFinder()
    private val db = RdfDb(app)
    private val beeper = Beeper()
    private val voice = VoiceListener(client)
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
    private val _peak = MutableStateFlow<PeakInfo?>(null)
    /** Strongest direction found while sweeping the antenna (null until enough data). */
    val peak: StateFlow<PeakInfo?> = _peak.asStateFlow()
    private val _session = MutableStateFlow(SessionData())
    val session: StateFlow<SessionData> = _session.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()
    private val _listen = MutableStateFlow(false)
    val listen: StateFlow<Boolean> = _listen.asStateFlow()
    private val _gainDb = MutableStateFlow<Double?>(null)
    /** Current tuner gain in dB (null = AGC), updated live while tracking. */
    val gainDb: StateFlow<Double?> = _gainDb.asStateFlow()
    private var lastAutoAtt = 0L
    private val trendCoach = TrendCoach()
    private val bursts = BurstDetector()
    private val _trend = MutableStateFlow<Trend?>(null)
    /** Hotter / colder compared with the last few seconds (null = not enough data). */
    val trend: StateFlow<Trend?> = _trend.asStateFlow()
    private val _burst = MutableStateFlow<BurstInfo?>(null)
    /** Regular on/off pattern of the fox, once three bursts have been seen. */
    val burst: StateFlow<BurstInfo?> = _burst.asStateFlow()
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _atMinGain = MutableStateFlow(false)
    /** The tuner is already at its lowest gain: further attenuation has to be external. */
    val atMinGain: StateFlow<Boolean> = _atMinGain.asStateFlow()
    private val _driftHz = MutableStateFlow<Double?>(null)
    /** Carrier offset from the channel centre (Hz), smoothed; null without a clear carrier. */
    val driftHz: StateFlow<Double?> = _driftHz.asStateFlow()
    private val _download = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Offline map download progress (done, total) or null when idle. */
    val download: StateFlow<Pair<Int, Int>?> = _download.asStateFlow()
    fun setDownload(done: Int, total: Int) { _download.value = if (total > 0 && done < total) done to total else null }
    fun clearDownload() { _download.value = null }

    // SDR link supervision: reconnect by itself and resume tracking
    @Volatile private var wantLink = false
    @Volatile private var linkBusy = false
    @Volatile private var activeMode: Mode? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken = 0L
    private var lastSpokenTrend: Trend? = null
    private var batteryTick = 0
    private var batteryWarned = false
    @Volatile private var keepSession = false
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
                _gainDb.value = sdr.gainDb
                updateHeading()
                updatePeak()
                updateGuidance()
                maybeReconnect()
                if (batteryTick++ % 50 == 0) updateBattery()
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
                if (m != Mode.Track) stopListen()
            }
        }
        viewModelScope.launch {
            // Pitch follows SNR: higher pitch = stronger signal
            live.collect { l ->
                val snr = l.snrDb
                // squelch opens on the measured SNR of the tracked channel
                voice.demod.squelchOpen = snr != null && snr > (fd("listen_sql") ?: 6.0)
                voice.demod.volume = ((fd("listen_vol") ?: 100.0) / 100.0).coerceIn(0.0, 4.0).toFloat()
                voice.demod.mode = runCatching { Demod.Mode.valueOf(f("listen_mode")) }.getOrDefault(Demod.Mode.NFM)
                if (l.running) {
                    val now = System.currentTimeMillis()
                    (l.smoothDb ?: l.powerDb)?.takeIf { !l.clipping }?.let { trendCoach.add(now, it) }
                    l.snrDb?.let { bursts.add(now, it) }
                }
                if (f("auto_att") == "true" && l.running && l.clipping) autoAttenuate()
                beeper.freqHz =
                    if (_sound.value && !_listen.value && l.running && snr != null && snr > 3) 250.0 + (snr / 40.0).coerceIn(0.0, 1.0) * 1250.0
                    else 0.0
            }
        }
    }

    private fun msg(s: String) {
        _messages.tryEmit(s)
    }

    fun postMessage(s: String) = msg(s)

    private fun f1(v: Double) = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    /** Trust in a bearing: grows with SNR (a clear signal means a clear lobe), falls with poor GPS accuracy. */
    private fun rayWeight(snrDb: Double?, accM: Double?): Double {
        val snrW = ((snrDb ?: 10.0) / 12.0).coerceIn(0.25, 1.5)
        val gpsW = if (accM == null) 1.0 else (15.0 / max(15.0, accM)).coerceIn(0.3, 1.0)
        return snrW * gpsW
    }

    private fun norm(x: Double) = ((x % 360.0) + 360.0) % 360.0

    private fun updatePeak() {
        val now = System.currentTimeMillis()
        val lv = _live.value
        val h = _heading.value.trueDeg
        if (_mode.value == Mode.Track && lv.running && !lv.clipping && h != null && f("use_sensor") == "true") {
            lv.powerDb?.let { peakFinder.add(h, it, now) }
        }
        _peak.value = if (_mode.value == Mode.Track) peakFinder.estimate(now) else null
    }

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

    private fun updateGuidance() {
        val now = System.currentTimeMillis()
        val tracking = _mode.value == Mode.Track && _live.value.running
        val tr = if (tracking) trendCoach.trend(now) else null
        _trend.value = tr
        _burst.value = if (tracking) bursts.info(now) else null
        val g = sdr.gainDb
        _atMinGain.value = g != null && g <= sdr.gainSteps().first() + 0.05
        val d = if (tracking) _live.value.spectrum?.let { Drift.offsetHz(it, Tracker.SPEC_BIN_HZ) } else null
        val prev = _driftHz.value
        _driftHz.value = if (d == null) null else if (prev == null) d else prev + 0.15 * (d - prev)
        if (tracking && f("coach") == "true" && !_listen.value) coach(tr, now)
    }

    private fun coach(tr: Trend?, now: Long) {
        val t = tts ?: return
        if (!ttsReady || now - lastSpoken < 4000) return
        val snr = _live.value.snrDb
        val text = when {
            tr != null && tr != Trend.STEADY && tr != lastSpokenTrend -> if (tr == Trend.HOTTER) "hotter" else "colder"
            snr != null && now - lastSpoken > 10_000 -> "${snr.roundToInt()}"
            else -> null
        } ?: return
        lastSpokenTrend = tr
        lastSpoken = now
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach")
    }

    /** Voice cues ("hotter", "colder", SNR readout) so the phone can stay in a pocket. */
    fun setCoach(on: Boolean) {
        setField("coach", on.toString())
        if (on && tts == null) {
            tts = TextToSpeech(getApplication<Application>()) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) tts?.setLanguage(Locale.US)
            }
        }
    }

    private fun updateBattery() {
        val i = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = (level * 100f / scale).roundToInt()
        _battery.value = pct
        val charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        if (pct <= 15 && !charging && !batteryWarned) {
            batteryWarned = true
            msg("Battery low ($pct%): capture and GPS drain it, save your fixes")
        }
        if (pct > 25) batteryWarned = false
    }

    /** Re-tunes to where the carrier actually is (cheap dongles drift) and keeps the same hunt. */
    fun retuneToDrift() {
        val d = _driftHz.value ?: return
        val fMhz = fd("trk_freq") ?: return
        setField("trk_freq", f1(fMhz + d / 1e6))
        keepSession = true
        startTrack()
    }

    // ---- fox names: a label per frequency (stored on the phone)
    /** Preference key of the label for a frequency (100 Hz resolution). */
    fun foxKey(freqMhz: Double) = "fox_" + Math.round(freqMhz * 10_000)
    fun foxName(freqMhz: Double?): String = if (freqMhz == null) "" else f(foxKey(freqMhz))

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
        if (linkBusy) return
        linkBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _sdrInfo.value = "Connecting…"
                client.connect(f("sdr_host").trim(), (fd("sdr_port") ?: 1234.0).toInt())
                _sdrConnected.value = true
                wantLink = true
                _sdrInfo.value = "Connected · tuner ${tunerName(client.tunerType)}"
            } catch (e: Exception) {
                _sdrInfo.value = "Disconnected"
                msg("Could not connect (${e.message}). Is SDR driver running (tap \"Start\")?")
            } finally {
                linkBusy = false
            }
        }
    }

    /** The link dropped while we still want it (SDR driver restarted, USB glitch): retry with back-off, then resume tracking. */
    private fun maybeReconnect() {
        if (!wantLink || linkBusy || client.connected) return
        linkBusy = true
        val resume = activeMode
        viewModelScope.launch(Dispatchers.IO) {
            _sdrInfo.value = "Link lost · reconnecting…"
            msg("SDR link lost: reconnecting…")
            var wait = 1000L
            var tries = 0
            try {
                while (wantLink && !client.connected && tries < 30) {
                    delay(wait)
                    try {
                        client.connect(f("sdr_host").trim(), (fd("sdr_port") ?: 1234.0).toInt())
                    } catch (_: Exception) {
                    }
                    wait = min(wait * 2, 5000L)
                    tries++
                }
            } finally {
                linkBusy = false
            }
            if (client.connected) {
                _sdrConnected.value = true
                _sdrInfo.value = "Connected · tuner ${tunerName(client.tunerType)}"
                msg("SDR reconnected")
                if (resume == Mode.Track) withContext(Dispatchers.Main) { keepSession = true; startTrack() }
            } else if (wantLink) {
                wantLink = false
                _sdrInfo.value = "Disconnected"
                msg("Could not reconnect: tap CONNECT SDR")
            }
        }
    }

    fun disconnect() {
        wantLink = false
        activeMode = null
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
            activeMode = newMode
            job = viewModelScope.launch(Dispatchers.Default) {
                try {
                    block()
                    activeMode = null // finished by itself (a scan): nothing to resume
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
        activeMode = null
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
        peakFinder.clear()
        trendCoach.clear()
        bursts.clear()
        lastSpokenTrend = null
        val keep = keepSession
        keepSession = false
        launchSdr(Mode.Track) {
            withContext(Dispatchers.IO) { ensureSession(freq, keep) }
            tracker.run(sdr, freq, bw * 1e3, g, _live)
        }
    }

    // ------------------------------------------------------------ gain / attenuation
    /** One gain step up (+1) or down (-1) on the tuner's real gain table; applied live while tracking. */
    fun stepGain(dir: Int, n: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            val steps = sdr.gainSteps()
            val cur = sdr.gainDb ?: gain("trk_gain") ?: steps[steps.size / 2]
            val i = steps.indices.minByOrNull { abs(steps[it] - cur) } ?: 0
            val next = steps[(i + dir * n).coerceIn(0, steps.size - 1)]
            if (next == steps[i] && sdr.gainDb != null) {
                msg(if (dir < 0) "Minimum gain reached" + if (_live.value.clipping) ": still clipping, move away or add an external attenuator" else ""
                    else "Maximum gain reached")
                return@launch
            }
            setField("trk_gain", next.toString())
            if (_mode.value == Mode.Track) {
                try {
                    sdr.setGain(next)
                } catch (e: Exception) {
                    msg("Could not change the gain: ${e.message}")
                }
            }
        }
    }

    private fun autoAttenuate() {
        val now = System.currentTimeMillis()
        if (now - lastAutoAtt < 900) return // let the new gain take effect before judging again
        lastAutoAtt = now
        val cur = sdr.gainDb
        if (cur != null && cur <= sdr.gainSteps().first()) return // already at minimum
        stepGain(-1, 2) // clipping is already losing the signal: take bigger steps than the manual button
    }

    // ------------------------------------------------------------ listening
    fun setListen(on: Boolean) {
        if (!on) {
            stopListen()
            return
        }
        if (_mode.value != Mode.Track) {
            msg("Start tracking first: the radio listens to the tracked frequency")
            return
        }
        voice.start()
        _listen.value = true
    }

    private fun stopListen() {
        voice.stop()
        _listen.value = false
    }

    // ------------------------------------------------------------ sessions and points
    private fun ensureSession(freq: Double, keep: Boolean = false) {
        val cur = currentSessionId?.let { db.getSession(it) }
        if (cur == null || (!keep && abs(cur.freqHz - freq) > 1000)) currentSessionId = db.createSession(freq)
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
                est = Geo.estimateRobust(pts.map { Geo.Ray(it.lat, it.lon, it.bearingTrue, rayWeight(it.snrDb, it.accM)) })
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
    fun recordPoint(usePeak: Boolean = false) {
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
            if (meas.snrDb < 3.0) {
                msg("No carrier at this frequency (SNR ${meas.snrDb.roundToInt()} dB): fix not recorded")
                return@launch
            }
            val useSensor = f("use_sensor") == "true"
            val peakBearing = if (usePeak) _peak.value?.takeIf { it.reliable }?.bearing else null
            if (usePeak && peakBearing == null) {
                msg("No reliable peak yet: sweep the antenna through a full turn")
                return@launch
            }
            val mag: Double = if (peakBearing != null) {
                norm(peakBearing - declination - (fd("heading_offset") ?: 0.0))
            } else if (useSensor) {
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

    enum class Format(val mime: String) { CSV("text/csv"), GEOJSON("application/geo+json"), KML("application/vnd.google-earth.kml+xml"), GPX("application/gpx+xml") }

    fun export(ctx: Context, fmt: Format) {
        val d = _session.value
        val s = d.session
        if (s == null) {
            msg("No session to export")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val name = "Hunt #${s.id} ${String.format(Locale.US, "%.4f", s.freqHz / 1e6)} MHz"
            val file = when (fmt) {
                Format.CSV -> Export.csv(ctx, s, d.points)
                Format.GEOJSON -> Export.geojson(ctx, s, d.points, d.estimate)
                Format.KML -> Export.write(ctx, "rdf_session_${s.id}.kml", GeoFiles.kml(name, d.points, d.estimate))
                Format.GPX -> Export.write(ctx, "rdf_session_${s.id}.gpx", GeoFiles.gpx(name, d.points, d.estimate))
            }
            withContext(Dispatchers.Main) { Export.share(ctx, file, fmt.mime) }
        }
    }

    /** Merges fixes from another operator (a CSV exported by this app) into the current hunt, skipping duplicates. */
    fun importFixes(ctx: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            val pts = text?.let { FixImport.parseCsv(it) }.orEmpty()
            if (pts.isEmpty()) {
                msg("No fixes found: import a CSV exported by this app")
                return@launch
            }
            var sid = currentSessionId?.takeIf { db.getSession(it) != null }
            if (sid == null) sid = db.createSession(pts.first().freqHz).also { currentSessionId = it }
            val have = db.points(sid).map { Triple(it.ts, it.lat, it.lon) }.toSet()
            var added = 0
            for (p in pts) {
                if (Triple(p.ts, p.lat, p.lon) in have) continue
                db.addPoint(p.copy(sessionId = sid))
                added++
            }
            loadSessionNow()
            val cur = db.getSession(sid)
            val off = cur != null && abs(cur.freqHz - pts.first().freqHz) > 1000
            msg("Imported $added fixes (${pts.size - added} duplicates skipped)" + if (off) " · ⚠ different frequency from this hunt" else "")
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
        voice.stop()
        tts?.shutdown()
        db.close()
    }

    companion object {
        val DEFAULTS = mapOf(
            "scan_start" to "400", "scan_stop" to "470", "scan_dur" to "30", "scan_thr" to "8", "scan_gain" to "38",
            "trk_freq" to "", "trk_bw" to "12.5", "trk_gain" to "30", "trk_bearing" to "",
            "sdr_host" to "127.0.0.1", "sdr_port" to "1234", "sdr_ppm" to "0", "sdr_settle" to "80",
            "mount_axis" to "TOP", "heading_offset" to "0", "use_sensor" to "true", "use_volume" to "true",
            "map_offline" to "false", "map_style" to "TOPO", "listen_mode" to "NFM", "listen_sql" to "6", "listen_vol" to "100", "auto_att" to "false", "night_mode" to "false", "coach" to "false", "pwr_norm" to "false", "pwr_offset" to "0", "dl_zmin" to "12", "dl_zmax" to "16",
        )
    }
}
