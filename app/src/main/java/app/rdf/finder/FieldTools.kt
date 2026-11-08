package app.rdf.finder

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Small pure helpers used while walking toward the fox. No Android dependencies, all unit-tested. */

// ------------------------------------------------------------------ hotter / colder
enum class Trend { HOTTER, COLDER, STEADY }

/**
 * Compares the signal of the last [recentMs] with the [olderMs] before it, so the operator can walk
 * with the phone in a pocket and listen to "hotter" / "colder".
 */
class TrendCoach(
    private val recentMs: Long = 3000,
    private val olderMs: Long = 4000,
    private val thresholdDb: Double = 1.5,
) {
    private data class S(val t: Long, val db: Double)
    private val s = ArrayDeque<S>()

    fun clear() = s.clear()

    fun add(tMs: Long, db: Double) {
        if (!db.isFinite()) return
        s.addLast(S(tMs, db))
        while (s.isNotEmpty() && tMs - s.first().t > recentMs + olderMs) s.removeFirst()
    }

    fun trend(nowMs: Long): Trend? {
        val recent = s.filter { nowMs - it.t <= recentMs }
        val older = s.filter { nowMs - it.t > recentMs && nowMs - it.t <= recentMs + olderMs }
        if (recent.size < 5 || older.size < 5) return null
        val d = recent.map { it.db }.average() - older.map { it.db }.average()
        return when {
            d >= thresholdDb -> Trend.HOTTER
            d <= -thresholdDb -> Trend.COLDER
            else -> Trend.STEADY
        }
    }
}

// ------------------------------------------------------------------ timed transmitters
data class BurstInfo(val periodS: Double, val onS: Double, val nextInS: Double?, val onAir: Boolean)

/**
 * Detects a fox that transmits in a regular on/off pattern (e.g. 1 min on, 4 min off) from the SNR
 * history, and predicts the next burst. Needs three bursts with a steady period.
 */
class BurstDetector(private val onSnrDb: Double = 6.0, private val debounceMs: Long = 400) {
    private var on = false
    private var candidateSince = 0L
    private var candidateState = false
    private var onSince = 0L
    private val rises = ArrayList<Long>()
    private val onLens = ArrayList<Long>()

    fun clear() {
        on = false; candidateSince = 0; candidateState = false; onSince = 0
        rises.clear(); onLens.clear()
    }

    fun add(tMs: Long, snrDb: Double) {
        val now = snrDb >= onSnrDb
        if (now != candidateState) {
            candidateState = now
            candidateSince = tMs
        }
        if (candidateState != on && tMs - candidateSince >= debounceMs) {
            on = candidateState
            if (on) {
                onSince = candidateSince
                rises.add(candidateSince)
                if (rises.size > 8) rises.removeAt(0)
            } else if (onSince > 0) {
                onLens.add(candidateSince - onSince)
                if (onLens.size > 8) onLens.removeAt(0)
            }
        }
    }

    fun info(nowMs: Long): BurstInfo? {
        if (rises.size < 3) return null
        val d = rises.zipWithNext { a, b -> (b - a).toDouble() }
        val med = d.sorted()[d.size / 2]
        if (med < 2000 || med > 600_000) return null
        if (d.any { abs(it - med) > 0.2 * med }) return null // not regular
        val last = rises.last()
        val next = last + med - nowMs
        // more than one period overdue: the pattern is gone
        if (next < -med) return null
        val onS = if (onLens.isEmpty()) 0.0 else onLens.average() / 1000.0
        return BurstInfo(med / 1000.0, onS, if (on) null else max(0.0, next / 1000.0), on)
    }
}

// ------------------------------------------------------------------ quality, calibration, drift
object FixQuality {
    /** 0–100 confidence in a fix from its SNR and the GPS accuracy at that moment. */
    fun percent(snrDb: Double?, accM: Double?): Int {
        val s = (((snrDb ?: 10.0) - 3.0) / 22.0).coerceIn(0.0, 1.0)
        val g = if (accM == null) 0.7 else (1.0 - (accM - 5.0) / 45.0).coerceIn(0.0, 1.0)
        return (100 * (0.65 * s + 0.35 * g)).toInt()
    }
}

object PowerNorm {
    /** Power compensated for tuner gain (so readings at different gains compare) plus a user offset. Approximate. */
    fun normalized(powerDb: Double, gainDb: Double?, offsetDb: Double = 0.0, refGainDb: Double = 30.0): Double =
        powerDb - ((gainDb ?: refGainDb) - refGainDb) + offsetDb
}

object Drift {
    /**
     * Offset (Hz) of the carrier from the channel centre, from a spectrum of [spec].size bins of
     * [binHz] each with the centre at index size/2. Null if there is no clear carrier near the centre.
     */
    fun offsetHz(spec: FloatArray, binHz: Double, searchHz: Double = 8000.0): Double? {
        if (spec.size < 16) return null
        val c = spec.size / 2
        val r = max(1, (searchHz / binHz).toInt())
        var best = c
        for (i in max(1, c - r)..min(spec.size - 2, c + r)) if (spec[i] > spec[best]) best = i
        val median = spec.sorted()[spec.size / 2]
        if (spec[best] - median < 8f) return null
        if (best <= 0 || best >= spec.size - 1) return null
        // parabolic interpolation around the peak
        val a = spec[best - 1].toDouble(); val b = spec[best].toDouble(); val cc = spec[best + 1].toDouble()
        val den = a - 2 * b + cc
        val frac = if (abs(den) < 1e-9) 0.0 else 0.5 * (a - cc) / den
        return (best - c + frac) * binHz
    }
}

// ------------------------------------------------------------------ geometry for guidance
object FieldGuide {
    private const val R = 6371008.8

    /** Distance (m) and initial true bearing (°) from point 1 to point 2 (local, fine for a few km). */
    fun distBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val dn = Math.toRadians(lat2 - lat1) * R
        val de = Math.toRadians(lon2 - lon1) * R * cos(Math.toRadians((lat1 + lat2) / 2))
        val brg = (Math.toDegrees(atan2(de, dn)) + 360.0) % 360.0
        return hypot(dn, de) to brg
    }

    fun offset(lat: Double, lon: Double, distM: Double, bearingDeg: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        val dLat = Math.toDegrees(distM * cos(b) / R)
        val dLon = Math.toDegrees(distM * sin(b) / (R * cos(Math.toRadians(lat))))
        return (lat + dLat) to (lon + dLon)
    }

    data class Suggestion(val lat: Double, val lon: Double, val distFromMeM: Double, val bearingFromMe: Double, val angleDeg: Double)

    /**
     * Where to take the next fix: a spot around the estimate whose view of the emitter is as
     * different as possible from the views already used (the 90° crossing shrinks the error the most),
     * preferring the one closest to the operator.
     */
    fun nextFix(rays: List<Geo.Ray>, estLat: Double, estLon: Double, meLat: Double, meLon: Double): Suggestion? {
        if (rays.isEmpty()) return null
        val views = rays.map { distBearing(it.lat, it.lon, estLat, estLon) }
        val radius = views.map { it.first }.average().coerceIn(60.0, 1000.0)
        data class C(val lat: Double, val lon: Double, val score: Double, val dist: Double, val brg: Double)
        val cands = (0 until 360 step 15).map { b ->
            val (la, lo) = offset(estLat, estLon, radius, b.toDouble())
            val look = (b + 180.0) % 360.0 // bearing from the candidate to the emitter
            val score = views.minOf { abs(sin(Math.toRadians(look - it.second))) }
            val (d, br) = distBearing(meLat, meLon, la, lo)
            C(la, lo, score, d, br)
        }
        val top = cands.maxOf { it.score }
        val pick = cands.filter { it.score >= top - 0.05 }.minByOrNull { it.dist } ?: return null
        return Suggestion(pick.lat, pick.lon, pick.dist, pick.brg, Math.toDegrees(asin(pick.score.coerceIn(0.0, 1.0))))
    }

    fun compass8(deg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}

// ------------------------------------------------------------------ importing another operator's fixes
object FixImport {
    /** Parses a CSV exported by this app (see [Export.csv]); bad lines are skipped. */
    fun parseCsv(text: String): List<PointRow> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()
        val head = lines[0].split(',')
        fun idx(n: String) = head.indexOf(n)
        val need = listOf("ts", "lat", "lon", "freq_hz", "power_db", "bearing_true")
        if (need.any { idx(it) < 0 }) return emptyList()
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(',')
            fun d(n: String) = c.getOrNull(idx(n))?.toDoubleOrNull()
            val lat = d("lat"); val lon = d("lon"); val f = d("freq_hz"); val p = d("power_db"); val b = d("bearing_true")
            if (lat == null || lon == null || f == null || p == null || b == null) return@mapNotNull null
            if (abs(lat) > 90 || abs(lon) > 180) return@mapNotNull null
            PointRow(
                id = 0, sessionId = 0, ts = c.getOrNull(idx("ts")) ?: "", tsSrc = "import",
                lat = lat, lon = lon, alt = d("alt"), accM = d("acc_m"), freqHz = f, powerDb = p,
                snrDb = d("snr_db"), gain = d("gain"),
                bearingMag = d("bearing_mag") ?: b, declination = d("declination") ?: 0.0, offset = d("offset") ?: 0.0,
                bearingTrue = b,
            )
        }
    }
}

/** KML / GPX writers (strings, so they can be tested on the JVM). */
object GeoFiles {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun n(v: Double) = String.format(Locale.US, "%.7f", v)

    fun kml(name: String, pts: List<PointRow>, est: Geo.Estimate?): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>""").append(esc(name)).append("</name>\n")
        pts.forEachIndexed { i, p ->
            sb.append("<Placemark><name>Fix ${i + 1} · ${p.bearingTrue.toInt()}°</name><description>")
                .append(esc(String.format(Locale.US, "%s, %.1f dB, SNR %s", p.ts, p.powerDb, p.snrDb?.let { "%.0f".format(Locale.US, it) } ?: "?")))
                .append("</description><Point><coordinates>${n(p.lon)},${n(p.lat)},0</coordinates></Point></Placemark>\n")
            val len = est?.let { FieldGuide.distBearing(p.lat, p.lon, it.lat, it.lon).first } ?: 800.0
            val (la, lo) = FieldGuide.offset(p.lat, p.lon, max(len, 100.0), p.bearingTrue)
            sb.append("<Placemark><name>Ray ${i + 1}</name><LineString><tessellate>1</tessellate><coordinates>${n(p.lon)},${n(p.lat)},0 ${n(lo)},${n(la)},0</coordinates></LineString></Placemark>\n")
        }
        if (est != null) {
            sb.append("<Placemark><name>Estimated emitter</name><Point><coordinates>${n(est.lon)},${n(est.lat)},0</coordinates></Point></Placemark>\n")
        }
        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    fun gpx(name: String, pts: List<PointRow>, est: Geo.Estimate?): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="RDF Finder" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>""")
            .append(esc(name)).append("</name></metadata>\n")
        pts.forEachIndexed { i, p ->
            sb.append("""<wpt lat="${n(p.lat)}" lon="${n(p.lon)}">""")
            if (p.ts.isNotEmpty()) sb.append("<time>${esc(p.ts)}</time>")
            sb.append("<name>Fix ${i + 1}</name><desc>bearing ${p.bearingTrue.toInt()}° true, ${"%.1f".format(Locale.US, p.powerDb)} dB</desc></wpt>\n")
        }
        if (est != null) sb.append("""<wpt lat="${n(est.lat)}" lon="${n(est.lon)}"><name>Estimated emitter</name></wpt>""").append('\n')
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
