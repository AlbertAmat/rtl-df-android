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

    data class Ray(val lat: Double, val lon: Double, val bearingDeg: Double)
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

    /** Throws [GeoException] if there are fewer than 2 rays or they are nearly parallel. */
    fun estimate(rays: List<Ray>, minSepDeg: Double = 5.0): Estimate {
        val n = rays.size
        if (n < 2) throw GeoException("At least 2 points are needed")

        val lat0 = rays.sumOf { it.lat } / n
        val lon0 = rays.sumOf { it.lon } / n

        var aSum = 0.0
        var bSum = 0.0
        var cSum = 0.0
        var bx = 0.0
        var by = 0.0
        val loc = ArrayList<DoubleArray>(n) // px, py, dx, dy
        for (r in rays) {
            val (px, py) = toLocal(r.lat, r.lon, lat0, lon0)
            val th = Math.toRadians(r.bearingDeg)
            val dx = sin(th)
            val dy = cos(th)
            // Projector onto the normal of the ray: P = I - d d^T
            val a = 1 - dx * dx
            val b = -dx * dy
            val c = 1 - dy * dy
            aSum += a
            bSum += b
            cSum += c
            bx += a * px + b * py
            by += b * px + c * py
            loc.add(doubleArrayOf(px, py, dx, dy))
        }

        // det = sum of sin^2(angle between each pair of rays): measures geometry quality
        val det = aSum * cSum - bSum * bSum
        val s = sin(Math.toRadians(minSepDeg))
        if (det < s * s) {
            throw GeoException("The bearings are nearly parallel: move to another spot so they form a wider angle")
        }

        val x = (cSum * bx - bSum * by) / det
        val y = (aSum * by - bSum * bx) / det

        val per = ArrayList<RayStat>(n)
        var sq = 0.0
        for (l in loc) {
            val rx = x - l[0]
            val ry = y - l[1]
            val t = rx * l[2] + ry * l[3]   // distance along the ray (<0: behind it)
            val perp = rx * l[3] - ry * l[2] // perpendicular distance
            sq += perp * perp
            per.add(RayStat(t, perp))
        }
        val rms = sqrt(sq / n)

        var bestAngle = 0.0
        var baseline = 0.0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = abs(rays[i].bearingDeg - rays[j].bearingDeg) % 180.0
                bestAngle = max(bestAngle, min(d, 180.0 - d))
                baseline = max(baseline, hypot(loc[i][0] - loc[j][0], loc[i][1] - loc[j][1]))
            }
        }

        val warnings = ArrayList<String>()
        if (per.any { it.rangeM < 0 }) warnings.add("The estimate lies behind a ray: check the bearings")
        if (bestAngle < 20) warnings.add("Weak geometry (less than 20° between bearings): the error may be large")
        if (baseline < 30) warnings.add("The points are very close together (less than 30 m)")

        val (lat, lon) = toGeo(x, y, lat0, lon0)
        return Estimate(lat, lon, n, rms, bestAngle, baseline, per, warnings)
    }
}
