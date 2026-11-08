package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FieldToolsTest {
    // ---- trend
    @Test fun trendHotterColderSteady() {
        fun run(f: (Long) -> Double): Trend? {
            val c = TrendCoach()
            for (t in 0..7000 step 200) c.add(t.toLong(), f(t.toLong()))
            return c.trend(7000)
        }
        assertEquals(Trend.HOTTER, run { -50.0 + it / 1000.0 * 1.0 })
        assertEquals(Trend.COLDER, run { -40.0 - it / 1000.0 * 1.0 })
        assertEquals(Trend.STEADY, run { -45.0 })
    }

    @Test fun trendNeedsData() {
        val c = TrendCoach(); c.add(0, -40.0)
        assertNull(c.trend(100))
    }

    // ---- bursts
    private fun feed(b: BurstDetector, periodS: Int, onS: Int, bursts: Int): Long {
        var t = 0L
        for (n in 0 until bursts) {
            val start = n * periodS * 1000L
            while (t < start + onS * 1000L) { b.add(t, 20.0); t += 100 }
            while (t < start + periodS * 1000L) { b.add(t, 0.0); t += 100 }
        }
        return t
    }

    @Test fun detectsRegularBursts() {
        val b = BurstDetector()
        val t = feed(b, 60, 10, 4) - 20_000 // 20 s into the silence after the 4th burst... (end of period)
        val i = b.info(t)
        assertNotNull(i)
        assertEquals(60.0, i!!.periodS, 1.0)
        assertEquals(10.0, i.onS, 1.0)
        assertTrue(!i.onAir)
        assertEquals(60.0 * 3 + 60 - t / 1000.0, i.nextInS!!, 1.5)
    }

    @Test fun irregularBurstsGiveNothing() {
        val b = BurstDetector()
        var t = 0L
        for (gap in listOf(10, 40, 15)) {
            for (i in 0 until 30) { b.add(t, 20.0); t += 100 }
            for (i in 0 until gap * 10) { b.add(t, 0.0); t += 100 }
        }
        assertNull(b.info(t))
    }

    @Test fun carrierThatNeverStopsIsNotABurst() {
        val b = BurstDetector()
        for (t in 0..60_000 step 100) b.add(t.toLong(), 25.0)
        assertNull(b.info(60_000))
    }

    // ---- quality / calibration / drift
    @Test fun qualityRisesWithSnrAndGps() {
        assertTrue(FixQuality.percent(30.0, 5.0) > 90)
        assertTrue(FixQuality.percent(4.0, 40.0) < 15)
        assertTrue(FixQuality.percent(20.0, 5.0) > FixQuality.percent(10.0, 5.0))
    }

    @Test fun normalizedPowerCancelsTheGain() {
        assertEquals(-40.0, PowerNorm.normalized(-30.0, 40.0), 1e-9)
        assertEquals(-30.0, PowerNorm.normalized(-30.0, 30.0), 1e-9)
        assertEquals(-27.0, PowerNorm.normalized(-30.0, null, 3.0), 1e-9)
    }

    @Test fun driftFindsOffsetCarrier() {
        val spec = FloatArray(128) { -60f }
        spec[64 + 3] = -20f; spec[64 + 2] = -45f; spec[64 + 4] = -45f // 3 bins above centre
        val d = Drift.offsetHz(spec, 2000.0)
        assertNotNull(d)
        assertEquals(6000.0, d!!, 300.0)
        assertNull(Drift.offsetHz(FloatArray(128) { -60f }, 2000.0))
    }

    // ---- guidance
    @Test fun distanceAndBearing() {
        val (d, b) = FieldGuide.distBearing(40.0, -3.0, 40.0, -3.0 + 0.01)
        assertEquals(852.0, d, 10.0)
        assertEquals(90.0, b, 0.5)
        val (la, lo) = FieldGuide.offset(40.0, -3.0, 500.0, 0.0)
        assertEquals(40.0 + 500.0 / 111_195.0, la, 1e-5)
        assertEquals(-3.0, lo, 1e-9)
    }

    @Test fun nextFixCrossesTheExistingView() {
        val est = 40.0 to -3.0
        val south = FieldGuide.offset(est.first, est.second, 300.0, 180.0) // existing fix looks north
        val rays = listOf(Geo.Ray(south.first, south.second, 0.0))
        val s = FieldGuide.nextFix(rays, est.first, est.second, south.first, south.second)
        assertNotNull(s)
        assertTrue("angle ${s!!.angleDeg}", s.angleDeg > 70)
        // the new spot must lie east or west of the emitter, not north/south of it
        val (_, brgFromEst) = FieldGuide.distBearing(est.first, est.second, s.lat, s.lon)
        assertTrue("bearing $brgFromEst", abs(abs(((brgFromEst + 360) % 360) - 180) - 90) < 20)
    }

    @Test fun compassNames() {
        assertEquals("N", FieldGuide.compass8(359.0)); assertEquals("SE", FieldGuide.compass8(135.0)); assertEquals("W", FieldGuide.compass8(270.0))
    }

    // ---- import / export
    private fun pt(i: Int) = PointRow(
        id = i.toLong(), sessionId = 1, ts = "2026-11-08T10:0$i:00Z", tsSrc = "gps", lat = 40.0 + i * 0.001, lon = -3.0,
        alt = null, accM = 6.0, freqHz = 446.1e6, powerDb = -20.5, snrDb = 30.0, gain = 30.0,
        bearingMag = 10.0, declination = 1.0, offset = 0.0, bearingTrue = 11.0,
    )

    @Test fun csvImportRoundTrip() {
        val csv = StringBuilder("id,ts,ts_src,lat,lon,alt,acc_m,freq_hz,power_db,snr_db,gain,bearing_mag,declination,offset,bearing_true\n")
        for (i in 1..2) {
            val p = pt(i)
            csv.append("${p.id},${p.ts},${p.tsSrc},${p.lat},${p.lon},,${p.accM},${p.freqHz},-20.50,30.00,${p.gain},${p.bearingMag},${p.declination},${p.offset},${p.bearingTrue}\n")
        }
        csv.append("garbage,line\n")
        val r = FixImport.parseCsv(csv.toString())
        assertEquals(2, r.size)
        assertEquals(40.002, r[1].lat, 1e-9)
        assertEquals(11.0, r[0].bearingTrue, 1e-9)
        assertEquals("import", r[0].tsSrc)
        assertTrue(FixImport.parseCsv("a,b\n1,2").isEmpty())
    }

    @Test fun kmlAndGpxContainTheFixes() {
        val est = Geo.estimate(listOf(Geo.Ray(40.0, -3.0, 0.0), Geo.Ray(40.0, -2.99, 315.0)))
        val k = GeoFiles.kml("Hunt <1>", listOf(pt(1), pt(2)), est)
        assertTrue(k.contains("<kml") && k.contains("Hunt &lt;1&gt;") && k.contains("Estimated emitter") && k.contains("LineString"))
        assertEquals(2, Regex("<Point>").findAll(k).count() - 1)
        val g = GeoFiles.gpx("Hunt", listOf(pt(1), pt(2)), est)
        assertEquals(3, Regex("<wpt ").findAll(g).count())
        assertTrue(g.contains("lat=\"40.0010000\""))
    }
}
