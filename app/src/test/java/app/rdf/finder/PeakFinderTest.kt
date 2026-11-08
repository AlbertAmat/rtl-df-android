package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PeakFinderTest {
    /** Yagi-like lobe: -3 dB at ±20°, floor at -25 dB. */
    private fun gain(angle: Double, target: Double): Double {
        var d = abs(angle - target) % 360.0
        if (d > 180) d = 360 - d
        return maxOf(-25.0, -3.0 * (d / 20.0) * (d / 20.0))
    }

    private fun sweep(pf: PeakFinder, target: Double, from: Int, to: Int, noise: Double = 0.5) {
        val rnd = Random(1)
        var t = 0L
        var a = from.toDouble()
        while (a <= to) {
            pf.add(a, -40.0 + gain(a, target) + rnd.nextDouble(-noise, noise), t)
            a += 1.0; t += 100
        }
    }

    @Test fun findsLobeCentre() {
        val pf = PeakFinder()
        sweep(pf, 130.0, 0, 359)
        val r = pf.estimate(36_000)
        assertNotNull(r)
        assertEquals(130.0, r!!.bearing, 5.0)
        assertTrue(r.reliable)
    }

    @Test fun handlesWrapAroundNorth() {
        val pf = PeakFinder()
        sweep(pf, 358.0, 0, 359)
        val r = pf.estimate(36_000)!!
        val err = abs(((r.bearing - 358.0 + 540) % 360) - 180)
        assertTrue("bearing ${r.bearing}", err < 6.0)
    }

    @Test fun flatSignalIsNotReliable() {
        val pf = PeakFinder()
        val rnd = Random(2)
        for (i in 0 until 360) pf.add(i.toDouble(), -50.0 + rnd.nextDouble(-0.5, 0.5), i * 100L)
        assertFalse(pf.estimate(36_000)!!.reliable)
    }

    @Test fun narrowSweepIsNotReliable() {
        val pf = PeakFinder()
        sweep(pf, 100.0, 90, 110)
        assertFalse(pf.estimate(3_000)!!.reliable)
    }

    @Test fun tooFewSamplesGivesNull() {
        val pf = PeakFinder()
        pf.add(10.0, -40.0, 0)
        assertNull(pf.estimate(100))
    }

    @Test fun oldSamplesExpire() {
        val pf = PeakFinder(maxAgeMs = 10_000)
        sweep(pf, 130.0, 0, 359)
        assertNull(pf.estimate(200_000))
    }
}
