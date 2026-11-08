package app.rdf.finder

import app.rdf.finder.Dsp.NFFT
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LiveState(
    val running: Boolean = false,
    val freqHz: Double = 0.0,
    val powerDb: Double? = null,
    val snrDb: Double? = null,
    val smoothDb: Double? = null,
    val peakDb: Double? = null,
    val clipping: Boolean = false,
    val gainDb: Double? = null,
    val history: List<Float> = emptyList(),
    /** How far the followed carrier has drifted from the frequency you entered (Hz). */
    val driftHz: Double = 0.0,
)

data class PointSample(
    val powerDb: Double,
    val snrDb: Double,
    val n: Int,
    val clipping: Boolean,
    val gainDb: Double?,
    val freqHz: Double,
)

/** Continuously measures power at a frequency (channel width is configurable). */
class Tracker {
    private data class Sample(val t: Long, val power: Double, val snr: Double, val clip: Boolean)

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()
    @Volatile private var freqHz = 0.0
    @Volatile private var gainDb: Double? = null
    @Volatile private var sdrRef: Sdr? = null

    suspend fun run(sdr: Sdr, freq: Double, bwHz: Double, gain: Double?, state: MutableStateFlow<LiveState>, follow: Boolean = true) {
        synchronized(lock) { samples.clear() }
        val nominal = freq
        var cur = freq
        val drift = DriftFollower()
        freqHz = freq
        sdrRef = sdr
        sdr.configure(FS, gain)
        gainDb = sdr.gainDb
        sdr.tune(Math.round(freq - OFFSET))

        val df = FS.toDouble() / NFFT
        val k0 = NFFT / 2 + (OFFSET / df).roundToInt()
        val half = max(1, (bwHz / 2 / df).roundToInt())
        val nchan = 2 * half + 1
        val mask = BooleanArray(NFFT) { true }
        for (k in NFFT / 2 - 8..NFFT / 2 + 8) mask[k] = false
        for (k in max(0, k0 - half - 10)..min(NFFT - 1, k0 + half + 10)) mask[k] = false

        var ema: Double? = null
        state.value = LiveState(running = true, freqHz = freq, gainDb = gainDb)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val iq = sdr.read(READ)
                gainDb = sdr.gainDb // can change while tracking (attenuation)
                var clip = false
                for (v in iq) if (abs(v) >= 0.98f) { clip = true; break }

                val p = Dsp.psd(iq, READ)
                var chan = 0.0
                for (k in k0 - half..k0 + half) chan += p[k]
                val rest = DoubleArray(NFFT)
                var m = 0
                for (k in 0 until NFFT) if (mask[k]) rest[m++] = p[k]
                val noiseDb = Dsp.db(Dsp.median(rest.copyOf(m)) * nchan)
                val chanDb = Dsp.db(chan)
                val snr = chanDb - noiseDb
                ema = if (ema == null) chanDb else 0.65 * ema + 0.35 * chanDb

                if (follow && !clip) {
                    // carrier offset inside the channel: power centroid around the strongest bin
                    val win = max(2, (half * 1.5).roundToInt())
                    var kp = k0
                    for (k in max(1, k0 - win)..min(NFFT - 2, k0 + win)) if (p[k] > p[kp]) kp = k
                    val c0 = max(0, kp - 1); val c1 = min(NFFT - 1, kp + 1)
                    var sw = 0.0; var sk = 0.0
                    for (k in c0..c1) { sw += p[k]; sk += p[k] * k }
                    val offsetHz = (sk / sw - k0) * df
                    val corr = drift.update(offsetHz, snr)
                    if (corr != null) {
                        cur += corr
                        freqHz = cur
                        sdr.tune(Math.round(cur - OFFSET))
                        synchronized(lock) { samples.clear() } // measurements before the retune are stale
                        continue
                    }
                }

                val now = System.currentTimeMillis()
                val snapshot = synchronized(lock) {
                    samples.addLast(Sample(now, chanDb, snr, clip))
                    while (samples.size > 900) samples.removeFirst()
                    samples.toList()
                }
                val peak = snapshot.filter { now - it.t <= 10_000 }.maxOf { it.power }
                state.value = LiveState(
                    running = true, freqHz = cur, powerDb = chanDb, snrDb = snr, smoothDb = ema,
                    peakDb = peak, clipping = clip, gainDb = gainDb,
                    history = snapshot.takeLast(150).map { it.power.toFloat() },
                    driftHz = cur - nominal,
                )
            }
        } finally {
            state.value = state.value.copy(running = false)
        }
    }

    /**
     * Power stored with each point: 90th percentile of the latest measurements. The median
     * would fail with intermittent transmissions (e.g. PTT: it is mostly just noise).
     */
    fun sampleForPoint(windowMs: Long = 3000): PointSample? {
        val now = System.currentTimeMillis()
        // Only measurements taken with the current gain: after an attenuation change, wait for fresh ones
        val since = maxOf(now - windowMs, (sdrRef?.gainChangedMs ?: 0L) + GAIN_SETTLE_MS)
        val recent = synchronized(lock) { samples.filter { it.t >= since } }
        if (recent.size < 3) return null
        return PointSample(
            powerDb = Dsp.percentile(recent.map { it.power }, 90.0),
            snrDb = Dsp.percentile(recent.map { it.snr }, 90.0),
            n = recent.size,
            clipping = recent.last().clip,
            gainDb = sdrRef?.gainDb ?: gainDb,
            freqHz = freqHz,
        )
    }

    companion object {
        const val FS = 1_024_000
        const val OFFSET = 200_000.0 // tuned with an offset so the channel does not fall on the DC spike
        const val READ = 65536       // ~64 ms per measurement
        const val GAIN_SETTLE_MS = 250L // ignore measurements right after a gain change
    }
}
