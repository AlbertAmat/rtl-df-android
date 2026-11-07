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
)

data class PointSample(
    val powerDb: Double,
    val snrDb: Double,
    val n: Int,
    val clipping: Boolean,
    val gainDb: Double?,
    val freqHz: Double,
)

/** Mide de forma continua la potencia en una frecuencia (canal de ancho configurable). */
class Tracker {
    private data class Sample(val t: Long, val power: Double, val snr: Double, val clip: Boolean)

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()
    @Volatile private var freqHz = 0.0
    @Volatile private var gainDb: Double? = null

    suspend fun run(sdr: Sdr, freq: Double, bwHz: Double, gain: Double?, state: MutableStateFlow<LiveState>) {
        synchronized(lock) { samples.clear() }
        freqHz = freq
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

                val now = System.currentTimeMillis()
                val snapshot = synchronized(lock) {
                    samples.addLast(Sample(now, chanDb, snr, clip))
                    while (samples.size > 900) samples.removeFirst()
                    samples.toList()
                }
                val peak = snapshot.filter { now - it.t <= 10_000 }.maxOf { it.power }
                state.value = LiveState(
                    running = true, freqHz = freq, powerDb = chanDb, snrDb = snr, smoothDb = ema,
                    peakDb = peak, clipping = clip, gainDb = gainDb,
                    history = snapshot.takeLast(150).map { it.power.toFloat() },
                )
            }
        } finally {
            state.value = state.value.copy(running = false)
        }
    }

    /**
     * Potencia que se guarda con cada punto: percentil 90 de las últimas medidas. La mediana
     * fallaría con emisiones intermitentes (p. ej. PTT: casi siempre solo hay ruido).
     */
    fun sampleForPoint(windowMs: Long = 3000): PointSample? {
        val now = System.currentTimeMillis()
        val recent = synchronized(lock) { samples.filter { now - it.t <= windowMs } }
        if (recent.size < 3) return null
        return PointSample(
            powerDb = Dsp.percentile(recent.map { it.power }, 90.0),
            snrDb = Dsp.percentile(recent.map { it.snr }, 90.0),
            n = recent.size,
            clipping = recent.last().clip,
            gainDb = gainDb,
            freqHz = freqHz,
        )
    }

    companion object {
        const val FS = 1_024_000
        const val OFFSET = 200_000.0 // se sintoniza desplazado para que el canal no caiga en el pico DC
        const val READ = 65536       // ~64 ms por medida
    }
}
