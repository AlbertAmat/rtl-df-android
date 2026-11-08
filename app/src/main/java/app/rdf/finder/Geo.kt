package app.rdf.finder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Emitter position from bearings measured at several points.
 *
 * Each measurement is a ray (lat, lon, true bearing in degrees from north, clockwise).
 * With 2 rays it is the exact intersection; with more, the point minimizing the sum of squared
 * perpendicular distances (least squares). Local plane: valid for a few km.
 */
object Geo {
    private const val R_EARTH = 6371008.8

    /** [weight] is relative trust in this bearing (e.g. from SNR and GPS accuracy); 1.0 = normal. */
    data class Ray(val lat: Double, val lon: Double, val bearingDeg: Double, val weight: Double = 1.0)
    data class RayStat(val rangeM: Double, val perpM: Double)
    data class Estimate(
        val lat: Double,
        val lon: Double,
        val n: Int,
        val rmsM: Double,
        val bestAngleDeg: Double,
        val baselineM: Double,
        val rays: List<RayStat>,
        val warnings: List<String>,
        /** Indices of rays left out of the fit as outliers (still listed in [rays]). */
        val excluded: List<Int> = emptyList(),
        /** ~95% confidence ellipse: semi-axes in metres and the bearing (° from north) of the major axis. */
        val ellipseMajorM: Double = 0.0,
        val ellipseMinorM: Double = 0.0,
        val ellipseAngleDeg: Double = 0.0,
    )

    class GeoException(message: String) : Exception(message)

    private fun toLocal(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val x = Math.toRadians(lon - lon0) * R_EARTH * cos(Math.toRadians(lat0))
        val y = Math.toRadians(lat - lat0) * R_EARTH
        return x to y
    }

    private fun toGeo(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val lat = lat0 + Math.toDegrees(y / R_EARTH)
        val lon = lon0 + Math.toDegrees(x / (R_EARTH * cos(Math.toRadians(lat0))))
        return lat to lon
    }

    /** Initial bearing (degrees, 0 = N) from (lat1, lon1) to (lat2, lon2). */
    fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point distMetros away from (lat, lon) along the given bearing (flat approximation, for drawing rays). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val t = Math.toRadians(bearingDeg)
        val p = Math.toRadians(lat)
        return (lat + Math.toDegrees(distM * cos(t) / R_EARTH)) to
            (lon + Math.toDegrees(distM * sin(t) / (R_EARTH * cos(p))))
    }

    /**
     * Weighted least squares over the rays not in [skip]. Throws [GeoException] if fewer than 2 rays remain
     * or they are nearly parallel. Residual stats are reported for every ray, skipped ones included.
     */
    fun estimate(rays: List<Ray>, minSepDeg: Double = 5.0, skip: Set<Int> = emptySet()): Estimate {
        val used = rays.indices.filter { it !in skip }
        val n = used.size
        if (n < 2) throw GeoException("At least 2 points are needed")

        val lat0 = used.sumOf { rays[it].lat } / n
        val lon0 = used.sumOf { rays[it].lon } / n
        val wMean = used.sumOf { rays[it].weight.coerceAtLeast(0.01) } / n

        // local coordinates and unit directions of every ray (px, py, dx, dy)
        val loc = rays.map { r ->
            val (px, py) = toLocal(r.lat, r.lon, lat0, lon0)
            val th = Math.toRadians(r.bearingDeg)
            doubleArrayOf(px, py, sin(th), cos(th))
        }

        fun solve(w: (Int) -> Double): DoubleArray? {
            var aSum = 0.0; var bSum = 0.0; var cSum = 0.0; var bx = 0.0; var by = 0.0
            for (i in used) {
                val l = loc[i]; val wi = w(i)
                // Projector onto the normal of the ray: P = I - d d^T
                val a = wi * (1 - l[2] * l[2]); val b = wi * (-l[2] * l[3]); val c = wi * (1 - l[3] * l[3])
                aSum += a; bSum += b; cSum += c
                bx += a * l[0] + b * l[1]
                by += b * l[0] + c * l[1]
            }
            val det = aSum * cSum - bSum * bSum
            return doubleArrayOf((cSum * bx - bSum * by) / det, (aSum * by - bSum * bx) / det, det, aSum, bSum, cSum)
        }

        val s = sin(Math.toRadians(minSepDeg))
        val unit = solve { 1.0 }!!
        // det of the unweighted sum = sum of sin^2 of pairwise angles: geometry quality
        if (unit[2] < s * s) {
            throw GeoException("The bearings are nearly parallel: move to another spot so they form a wider angle")
        }
        val sol = solve { rays[it].weight.coerceAtLeast(0.01) / wMean }!!
        val x = sol[0]
        val y = sol[1]

        val per = ArrayList<RayStat>(rays.size)
        var sq = 0.0
        for (i in rays.indices) {
            val l = loc[i]
            val rx = x - l[0]
            val ry = y - l[1]
            val t = rx * l[2] + ry * l[3]    // distance along the ray (<0: behind it)
            val perp = rx * l[3] - ry * l[2] // perpendicular distance
            if (i !in skip) sq += perp * perp
            per.add(RayStat(t, perp))
        }
        val rms = sqrt(sq / n)

        var bestAngle = 0.0
        var baseline = 0.0
        for (ii in used.indices) {
            for (jj in ii + 1 until n) {
                val i = used[ii]; val j = used[jj]
                val d = abs(rays[i].bearingDeg - rays[j].bearingDeg) % 180.0
                bestAngle = max(bestAngle, min(d, 180.0 - d))
                baseline = max(baseline, hypot(loc[i][0] - loc[j][0], loc[i][1] - loc[j][1]))
            }
        }

        // Confidence ellipse: each bearing has ~SIGMA_DEG error, so its cross-range error grows with range.
        var ia = 0.0; var ib = 0.0; var ic = 0.0
        for (i in used) {
            val l = loc[i]
            val range = max(abs(per[i].rangeM), 15.0)
            val sig = range * Math.toRadians(SIGMA_DEG) / sqrt(rays[i].weight.coerceAtLeast(0.05))
            val w = 1.0 / (sig * sig)
            ia += w * (1 - l[2] * l[2]); ib += w * (-l[2] * l[3]); ic += w * (1 - l[3] * l[3])
        }
        val idet = ia * ic - ib * ib
        var major = 0.0; var minor = 0.0; var ang = 0.0
        if (idet > 0) {
            // covariance = inverse of the information matrix
            val ca = ic / idet; val cb = -ib / idet; val cc = ia / idet
            val tr = ca + cc
            val disc = sqrt(max(0.0, (ca - cc) * (ca - cc) / 4 + cb * cb))
            val l1 = tr / 2 + disc; val l2 = max(0.0, tr / 2 - disc)
            major = 2.4477 * sqrt(l1); minor = 2.4477 * sqrt(l2) // 95% for 2 dof
            // major axis direction in (x east, y north): angle from north, clockwise
            ang = (Math.toDegrees(0.5 * atan2(2 * cb, ca - cc)) .let { 90.0 - it } + 360.0) % 180.0
        }

        val warnings = ArrayList<String>()
        if (per.indices.any { it !in skip && per[it].rangeM < 0 }) warnings.add("The estimate lies behind a ray: check the bearings")
        if (bestAngle < 20) warnings.add("Weak geometry (less than 20° between bearings): the error may be large")
        if (baseline < 30) warnings.add("The points are very close together (less than 30 m)")
        for (k in skip.sorted()) warnings.add("Point #${k + 1} looks like an outlier and was left out")

        val (lat, lon) = toGeo(x, y, lat0, lon0)
        return Estimate(lat, lon, n, rms, bestAngle, baseline, per, warnings, skip.sorted(), major, minor, ang)
    }

    /**
     * Like [estimate], but with 4 or more rays drops the single worst one when that clearly improves
     * the fit (typical cause: a reflection, a wrong transmission or a bad compass reading).
     */
    fun estimateRobust(rays: List<Ray>, minSepDeg: Double = 5.0): Estimate {
        val all = estimate(rays, minSepDeg)
        if (rays.size < 4 || all.rmsM < OUTLIER_MIN_RMS_M) return all
        var best: Estimate? = null
        var bestK = -1
        for (k in rays.indices) {
            val e = runCatching { estimate(rays, minSepDeg, setOf(k)) }.getOrNull() ?: continue
            if (best == null || e.rmsM < best.rmsM) { best = e; bestK = k }
        }
        return if (best != null && best.rmsM < all.rmsM * 0.5 && bestK >= 0) best else all
    }

    private const val SIGMA_DEG = 5.0
    private const val OUTLIER_MIN_RMS_M = 40.0
}
