package app.rdf.finder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class PeakInfo(
    /** True bearing (°) of the strongest direction found while sweeping the antenna. */
    val bearing: Double,
    /** Smoothed power (dB) at the peak. */
    val powerDb: Double,
    /** How far the peak stands above the median direction (dB). Higher = clearer lobe. */
    val prominenceDb: Double,
    /** Angular span (°) of the bins that have measurements. */
    val coverageDeg: Double,
    val reliable: Boolean,
    /** Smoothed power per heading bin (NaN = no data), bin i covers [i*binDeg, (i+1)*binDeg). */
    val profile: DoubleArray = DoubleArray(0),
    /** Width (°) of the contiguous main lobe within 3 dB of the peak. */
    val lobeWidthDeg: Double = 0.0,
    /** A second lobe within 3 dB of the main one, well away from it: typical of reflections (multipath). */
    val multipath: Boolean = false,
    val secondBearing: Double? = null,
)

/**
 * Rotate-and-peak assistant: while the operator sweeps the Yagi around, power is binned by heading;
 * the main-lobe direction is the power-weighted centre of the lobe around the strongest bin.
 * Old samples expire so the result follows a moving situation.
 */
class PeakFinder(
    private val binDeg: Int = 5,
    private val maxAgeMs: Long = 45_000,
    private val minProminenceDb: Double = 4.0,
    private val minCoverageDeg: Double = 60.0,
) {
    private val nBins = 360 / binDeg
    private data class Entry(val t: Long, val bin: Int, val power: Double)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun add(headingDeg: Double, powerDb: Double, nowMs: Long) {
        if (!powerDb.isFinite() || !headingDeg.isFinite()) return
        val h = ((headingDeg % 360.0) + 360.0) % 360.0
        entries.addLast(Entry(nowMs, ((h / binDeg).toInt()) % nBins, powerDb))
        expire(nowMs)
    }

    private fun expire(nowMs: Long) {
        while (entries.isNotEmpty() && nowMs - entries.first().t > maxAgeMs) entries.removeFirst()
        while (entries.size > 4000) entries.removeFirst()
    }

    @Synchronized
    fun estimate(nowMs: Long): PeakInfo? {
        expire(nowMs)
        if (entries.size < 10) return null
        // Per-bin 80th percentile: robust against intermittent transmissions (PTT) and single outliers.
        val per = Array(nBins) { ArrayList<Double>() }
        for (e in entries) per[e.bin].add(e.power)
        val level = DoubleArray(nBins) { Double.NaN }
        for (i in 0 until nBins) if (per[i].size >= 2) level[i] = Dsp.percentile(per[i], 80.0)
        val filled = level.indices.filter { !level[it].isNaN() }
        if (filled.size < 3) return null

        // Light circular smoothing over filled neighbours (1-2-1)
        val sm = DoubleArray(nBins) { Double.NaN }
        for (i in filled) {
            var s = 2 * level[i]; var w = 2.0
            for (d in intArrayOf(-1, 1)) {
                val v = level[(i + d + nBins) % nBins]
                if (!v.isNaN()) { s += v; w += 1.0 }
            }
            sm[i] = s / w
        }
        val best = filled.maxByOrNull { sm[it] } ?: return null
        val median = Dsp.percentile(filled.map { sm[it] }, 50.0)
        val prominence = sm[best] - median

        // Power-weighted circular centroid of the lobe (bins within 3 dB of the peak, contiguous with it)
        val lobe = ArrayList<Int>()
        lobe.add(best)
        // contiguous walk in both directions (stop at first bin below -3 dB or missing)
        for (dir in intArrayOf(-1, 1)) {
            var d = dir
            while (abs(d) < nBins / 2) {
                val v = sm[(best + d + nBins) % nBins]
                if (v.isNaN() || v < sm[best] - 3.0) break
                lobe.add((best + d + nBins) % nBins)
                d += dir
            }
        }
        var sx = 0.0; var sy = 0.0
        for (i in lobe) {
            val w = Math.pow(10.0, (sm[i] - sm[best]) / 10.0)
            val a = (i + 0.5) * binDeg * PI / 180.0
            sx += w * sin(a); sy += w * cos(a)
        }
        val bearing = (Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0
        val coverage = filled.size * binDeg.toDouble()
        // Multipath: a strong second lobe >= 45 deg away from the main one
        val minSep = 45 / binDeg
        var second: Int? = null
        for (i in filled) {
            val d = minOf((i - best + nBins) % nBins, (best - i + nBins) % nBins)
            if (d < minSep) continue
            // must be a local maximum, not just the shoulder of the main lobe
            if (sm[(i - 1 + nBins) % nBins].let { !it.isNaN() && it > sm[i] }) continue
            if (sm[(i + 1) % nBins].let { !it.isNaN() && it > sm[i] }) continue
            if (second == null || sm[i] > sm[second]) second = i
        }
        val multi = second != null && sm[second] >= sm[best] - 3.0 && prominence >= minProminenceDb
        return PeakInfo(
            bearing = bearing,
            powerDb = sm[best],
            prominenceDb = prominence,
            coverageDeg = coverage,
            reliable = prominence >= minProminenceDb && coverage >= minCoverageDeg,
            profile = sm,
            lobeWidthDeg = lobe.size * binDeg.toDouble(),
            multipath = multi,
            secondBearing = if (multi) (second!! + 0.5) * binDeg else null,
        )
    }
}
