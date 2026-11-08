package app.rdf.finder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How the app tells you which way to turn the antenna. */
enum class AimCueMode { NONE, HAPTIC, SOUND;
    companion object { fun parse(s: String) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NONE }
}

enum class Cue { LEFT, RIGHT, ON_TARGET }

object AimMath {
    /** Signed turn (°, in (-180, 180]) from the antenna heading to the target: negative = turn left. */
    fun signedError(headingDeg: Double, targetDeg: Double): Double {
        var d = (targetDeg - headingDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Approximate ground distance (m) between two nearby points. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = Math.toRadians(lat2 - lat1) * 6371008.8
        val dx = Math.toRadians(lon2 - lon1) * 6371008.8 * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot(dx, dy)
    }
}

/**
 * Decides when to emit an aim cue: the closer to the target, the faster the pulses.
 * On target it gives a slow, distinct "you are there" cue.
 */
class CueScheduler(private val onTargetDeg: Double = 4.0) {
    private var lastMs = 0L

    fun next(errDeg: Double?, nowMs: Long): Cue? {
        if (errDeg == null) return null
        val a = abs(errDeg)
        val interval = if (a < onTargetDeg) 700L else (250 + a * 8).toLong().coerceIn(250L, 1500L)
        if (nowMs - lastMs < interval) return null
        lastMs = nowMs
        return when {
            a < onTargetDeg -> Cue.ON_TARGET
            errDeg < 0 -> Cue.LEFT
            else -> Cue.RIGHT
        }
    }
}

/**
 * Fires once when the antenna has been held steady on the peak bearing, with a reliable peak and a
 * signal, and the operator is at a new spot (so the same position is not recorded repeatedly).
 */
class AutoRecordGate(
    private val windowMs: Long = 2000,
    private val maxStdDeg: Double = 2.5,
    private val tolDeg: Double = 6.0,
    private val cooldownMs: Long = 15_000,
    private val minMoveM: Double = 20.0,
) {
    private data class H(val t: Long, val deg: Double)
    private val hist = ArrayDeque<H>()
    private var lastFireMs = Long.MIN_VALUE / 2
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    fun reset() { hist.clear() }

    fun check(nowMs: Long, headingDeg: Double?, peak: PeakInfo?, hasSignal: Boolean, lat: Double?, lon: Double?): Boolean {
        if (headingDeg == null) { hist.clear(); return false }
        hist.addLast(H(nowMs, headingDeg))
        while (hist.isNotEmpty() && nowMs - hist.first().t > windowMs) hist.removeFirst()
        if (peak == null || !peak.reliable || peak.held || !hasSignal || lat == null || lon == null) return false
        if (hist.size < 4 || nowMs - hist.first().t < windowMs * 0.8) return false
        if (nowMs - lastFireMs < cooldownMs) return false
        // circular mean and spread of the heading over the window
        var sx = 0.0; var sy = 0.0
        for (h in hist) { sx += sin(Math.toRadians(h.deg)); sy += cos(Math.toRadians(h.deg)) }
        val mean = (Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0
        val r = hypot(sx, sy) / hist.size
        val std = Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(r.coerceIn(1e-9, 1.0))))
        if (std > maxStdDeg) return false
        if (abs(AimMath.signedError(mean, peak.bearing)) > tolDeg) return false
        val la = lastLat; val lo = lastLon
        if (la != null && lo != null && AimMath.distanceM(la, lo, lat, lon) < minMoveM) return false
        lastFireMs = nowMs; lastLat = lat; lastLon = lon
        hist.clear()
        return true
    }
}

/** Follows slow carrier drift (cheap dongles): returns a frequency correction once it is persistent. */
class DriftFollower(
    private val maxTotalHz: Double = 25_000.0,
    private val minStepHz: Double = 1_500.0,
    private val n: Int = 8,
    private val minSnrDb: Double = 10.0,
) {
    private val buf = ArrayList<Double>()
    private var total = 0.0

    val totalHz: Double get() = total

    fun reset() { buf.clear(); total = 0.0 }

    /** [offsetHz]: measured carrier offset from the tuned frequency. Returns the correction to apply, if any. */
    fun update(offsetHz: Double, snrDb: Double): Double? {
        if (snrDb < minSnrDb) { buf.clear(); return null }
        buf.add(offsetHz)
        if (buf.size < n) return null
        val med = buf.sorted()[buf.size / 2]
        buf.clear()
        if (abs(med) < minStepHz) return null
        if (abs(total + med) > maxTotalHz) return null
        total += med
        return med
    }
}

/** Suggests where to stand for the next bearing so that it crosses the existing ones at a wide angle. */
object NextSpot {
    data class Spot(
        val lat: Double,
        val lon: Double,
        val distM: Double,
        /** Direction to walk (° true) from the current position. */
        val walkBearingDeg: Double,
        /** Smallest angle (°) the new bearing is expected to make with the existing ones. */
        val expectedSepDeg: Double,
        /** True when there is only one bearing, so the emitter distance is a guess. */
        val assumedRange: Boolean,
    )

    private const val ASSUMED_RANGE_M = 300.0

    fun suggest(rays: List<Geo.Ray>, est: Geo.Estimate?, meLat: Double, meLon: Double): Spot? {
        if (rays.isEmpty()) return null
        val used = rays.indices.filter { est == null || it !in est.excluded }.map { rays[it] }
        if (used.isEmpty()) return null
        val assumed = est == null
        val (tLat, tLon) = if (est != null) est.lat to est.lon
        else Geo.destination(used[0].lat, used[0].lon, used[0].bearingDeg, ASSUMED_RANGE_M)
        val radius = AimMath.distanceM(meLat, meLon, tLat, tLon).coerceIn(60.0, 1000.0)

        var best: Spot? = null
        var bestScore = -1e9
        for (k in 0 until 24) {
            val theta = k * 15.0
            val (la, lo) = Geo.destination(tLat, tLon, theta, radius)
            val toTarget = (theta + 180.0) % 360.0
            var minSep = 90.0
            for (r in used) {
                val d = abs(toTarget - r.bearingDeg) % 180.0
                minSep = min(minSep, min(d, 180.0 - d))
            }
            val dist = AimMath.distanceM(meLat, meLon, la, lo)
            val score = min(minSep, 70.0) - dist / 40.0
            if (score > bestScore) {
                bestScore = score
                best = Spot(la, lo, dist, Geo.initialBearing(meLat, meLon, la, lo), minSep, assumed)
            }
        }
        return best
    }
}
