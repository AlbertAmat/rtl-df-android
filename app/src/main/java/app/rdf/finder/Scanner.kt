package app.rdf.finder

import app.rdf.finder.Dsp.NFFT
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ScanParams(
    val startHz: Double,
    val stopHz: Double,
    val durationS: Double,
    val thrDb: Double,
    val gain: Double?,
)

data class Emitter(
    val freqHz: Double,
    val powerDb: Double,
    val snrDb: Double,
    val activity: Double,
    val bwKhz: Double,
)

data class ScanState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val elapsedS: Double = 0.0,
    val durationS: Double = 0.0,
    val sweeps: Int = 0,
    val results: List<Emitter> = emptyList(),
)

/** Spectrum sweep in 1.4 MHz hops, accumulating maxima and activity %. */
object Scanner {
    const val FS = 2_048_000
    private const val DWELL = 131072   // samples per hop (~64 ms, 128 FFT averages)
    private const val KEEP = 400       // useful bins on each side of the center (±0.8 MHz)
    private const val STEP = 700       // step between centers, in bins (1.4 MHz)
    private const val DC_MASK = 4      // bins next to the center that are discarded (RTL DC spike)

    suspend fun run(sdr: Sdr, p: ScanParams, state: MutableStateFlow<ScanState>) {
        val df = FS.toDouble() / NFFT
        sdr.configure(FS, p.gain)

        val nb = ceil((p.stopHz - p.startHz) / df).toInt() + 1
        val hits = IntArray(nb)
        val cov = IntArray(nb)
        val maxDb = DoubleArray(nb) { -200.0 }
        val maxSnr = DoubleArray(nb) { -200.0 }
        val t0 = System.currentTimeMillis()
        var sweeps = 0
        state.value = ScanState(running = true, durationS = p.durationS)

        try {
            while (sweeps == 0 || (System.currentTimeMillis() - t0) / 1000.0 < p.durationS) {
                currentCoroutineContext().ensureActive()
                // Odd sweeps shift the grid by half a step so the DC spike
                // of one sweep is covered by the other.
                val shift = if (sweeps % 2 == 1) STEP / 2 else 0
                val nh = ceil(((p.stopHz - p.startHz) / df + shift) / STEP).toInt()
                val swDb = DoubleArray(nb) { -200.0 }
                val swSnr = DoubleArray(nb) { -200.0 }
                val swCov = BooleanArray(nb)

                for (j in 0 until nh) {
                    currentCoroutineContext().ensureActive()
                    val center = (p.startHz + (KEEP - shift + j * STEP) * df).roundToLong()
                    val iq = sdr.tuneAndRead(center, DWELL)
                    val pw = Dsp.psdDb(iq, DWELL)

                    // Median of the useful bins (no DC) = noise floor of this hop
                    val usable = DoubleArray(2 * KEEP)
                    var m = 0
                    for (k in 0 until 2 * KEEP) {
                        val bin = NFFT / 2 - KEEP + k
                        if (abs(bin - NFFT / 2) > DC_MASK) usable[m++] = pw[bin]
                    }
                    val floor = Dsp.median(usable.copyOf(m))

                    for (k in 0 until 2 * KEEP) {
                        val bin = NFFT / 2 - KEEP + k
                        if (abs(bin - NFFT / 2) <= DC_MASK) continue
                        val idx = ((center + (bin - NFFT / 2) * df - p.startHz) / df).roundToInt()
                        if (idx < 0 || idx >= nb) continue
                        val v = pw[bin]
                        if (v > swDb[idx]) swDb[idx] = v
                        if (v - floor > swSnr[idx]) swSnr[idx] = v - floor
                        swCov[idx] = true
                    }
                    val el = (System.currentTimeMillis() - t0) / 1000.0
                    state.update { it.copy(elapsedS = el, progress = min(0.99, el / max(p.durationS, 1.0)).toFloat()) }
                }

                for (i in 0 until nb) {
                    if (!swCov[i]) continue
                    cov[i]++
                    if (swSnr[i] >= p.thrDb) hits[i]++
                    if (swDb[i] > maxDb[i]) maxDb[i] = swDb[i]
                    if (swSnr[i] > maxSnr[i]) maxSnr[i] = swSnr[i]
                }
                sweeps++
                val results = cluster(p.startHz, df, hits, cov, maxDb, maxSnr)
                state.update { it.copy(sweeps = sweeps, results = results) }
            }
        } finally {
            val el = (System.currentTimeMillis() - t0) / 1000.0
            state.update { it.copy(running = false, progress = 1f, elapsedS = el) }
        }
    }

    /** Groups neighboring bins above the threshold into emitters. */
    internal fun cluster(
        f0: Double, df: Double, hits: IntArray, cov: IntArray, maxDb: DoubleArray, maxSnr: DoubleArray,
    ): List<Emitter> {
        val groups = ArrayList<MutableList<Int>>()
        var cur: MutableList<Int>? = null
        for (i in hits.indices) {
            if (hits[i] <= 0) continue
            if (cur != null && i - cur.last() <= 6) { // ≤12 kHz gap: same transmission
                cur.add(i)
            } else {
                cur = mutableListOf(i)
                groups.add(cur)
            }
        }
        val out = groups.map { g ->
            val peak = g.maxByOrNull { maxDb[it] }!!
            val act = g.maxOf { hits[it].toDouble() / max(cov[it], 1) }
            Emitter(f0 + peak * df, maxDb[peak], maxSnr[peak], act, g.size * df / 1e3)
        }
        return out.sortedByDescending { it.powerDb }.take(200)
    }
}
