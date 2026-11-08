package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AimAssistTest {
    private val goodPeak = PeakInfo(100.0, -40.0, 9.0, 360.0, true)

    @Test fun signedErrorWrapsAndHasSign() {
        assertEquals(20.0, AimMath.signedError(350.0, 10.0), 1e-9)
        assertEquals(-20.0, AimMath.signedError(10.0, 350.0), 1e-9)
        assertEquals(-90.0, AimMath.signedError(180.0, 90.0), 1e-9)
        assertEquals(180.0, AimMath.signedError(0.0, 180.0), 1e-9)
    }

    @Test fun cuesGetFasterWhenCloser() {
        fun count(err: Double): Int {
            val s = CueScheduler(); var n = 0
            var t = 1000L
            while (t < 11_000) { if (s.next(err, t) != null) n++; t += 50 }
            return n
        }
        assertTrue(count(10.0) > count(90.0))
        assertEquals(Cue.LEFT, CueScheduler().next(-30.0, 5000))
        assertEquals(Cue.RIGHT, CueScheduler().next(30.0, 5000))
        assertEquals(Cue.ON_TARGET, CueScheduler().next(1.0, 5000))
        assertNull(CueScheduler().next(null, 5000))
    }

    private fun steady(g: AutoRecordGate, from: Long, heading: Double, peak: PeakInfo?, lat: Double = 40.0, lon: Double = -3.0): Boolean {
        var fired = false
        var t = from
        while (t <= from + 3000) { if (g.check(t, heading + (if ((t / 200) % 2 == 0L) 0.5 else -0.5), peak, true, lat, lon)) fired = true; t += 200 }
        return fired
    }

    @Test fun autoRecordFiresWhenSteadyOnPeak() {
        assertTrue(steady(AutoRecordGate(), 0, 101.0, goodPeak))
    }

    @Test fun autoRecordNeedsAimOnPeak() {
        assertFalse(steady(AutoRecordGate(), 0, 140.0, goodPeak))
    }

    @Test fun autoRecordNeedsReliableFreshPeak() {
        assertFalse(steady(AutoRecordGate(), 0, 100.0, goodPeak.copy(reliable = false)))
        assertFalse(steady(AutoRecordGate(), 0, 100.0, goodPeak.copy(held = true)))
        assertFalse(steady(AutoRecordGate(), 0, 100.0, null))
    }

    @Test fun autoRecordNeedsSteadyAntenna() {
        val g = AutoRecordGate()
        var fired = false
        var t = 0L
        while (t <= 3000) { if (g.check(t, 100.0 + (if ((t / 200) % 2 == 0L) 10.0 else -10.0), goodPeak, true, 40.0, -3.0)) fired = true; t += 200 }
        assertFalse(fired)
    }

    @Test fun autoRecordNotTwiceAtSameSpot() {
        val g = AutoRecordGate()
        assertTrue(steady(g, 0, 100.0, goodPeak))
        assertFalse(steady(g, 30_000, 100.0, goodPeak)) // same position
        assertTrue(steady(g, 60_000, 100.0, goodPeak, lat = 40.001)) // ~110 m away
    }

    @Test fun driftFollowerIgnoresSmallAndWeak() {
        val d = DriftFollower()
        repeat(20) { assertNull(d.update(500.0, 20.0)) }
        repeat(20) { assertNull(d.update(5000.0, 3.0)) }
    }

    @Test fun driftFollowerCorrectsPersistentOffsetAndCaps() {
        val d = DriftFollower(maxTotalHz = 10_000.0)
        var corr: Double? = null
        repeat(8) { corr = d.update(4000.0, 20.0) }
        assertEquals(4000.0, corr!!, 1.0)
        repeat(8) { corr = d.update(4000.0, 20.0) }
        assertEquals(4000.0, corr!!, 1.0)
        repeat(8) { corr = d.update(4000.0, 20.0) } // would exceed the cap
        assertNull(corr)
        assertEquals(8000.0, d.totalHz, 1.0)
    }

    @Test fun driftFollowerIgnoresOutliers() {
        val d = DriftFollower()
        var corr: Double? = null
        val v = doubleArrayOf(9000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -9000.0)
        for (x in v) corr = d.update(x, 20.0)
        assertNull(corr)
    }

    private val meLat = 40.0
    private val meLon = -3.0

    @Test fun nextSpotFromOneBearingIsOffTheLine() {
        val r = Geo.Ray(meLat, meLon, 0.0) // emitter north
        val s = NextSpot.suggest(listOf(r), null, meLat, meLon)
        assertNotNull(s)
        assertTrue(s!!.assumedRange)
        assertTrue("sep ${s.expectedSepDeg}", s.expectedSepDeg >= 45.0)
        assertTrue(s.distM < 500.0)
    }

    @Test fun nextSpotWithEstimateWidensAngle() {
        val txLat = 40.01; val txLon = -3.0
        val rays = listOf(
            Geo.Ray(40.0, -3.0, Geo.initialBearing(40.0, -3.0, txLat, txLon)),
            Geo.Ray(40.0, -2.9985, Geo.initialBearing(40.0, -2.9985, txLat, txLon)),
        )
        val est = Geo.estimate(rays)
        val s = NextSpot.suggest(rays, est, 40.0, -2.9985)!!
        assertFalse(s.assumedRange)
        assertTrue("sep ${s.expectedSepDeg}", s.expectedSepDeg > est.bestAngleDeg)
        assertTrue(abs(AimMath.signedError(0.0, 0.0)) < 1e-9)
    }

    @Test fun nextSpotNullWithoutRays() {
        assertNull(NextSpot.suggest(emptyList(), null, meLat, meLon))
    }
}
