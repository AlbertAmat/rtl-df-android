package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class GeoTest {
    private val txLat = 40.4168
    private val txLon = -3.7038

    private fun rays(pts: List<Pair<Double, Double>>, noiseDeg: Double = 0.0): List<Geo.Ray> {
        val rnd = Random(1)
        return pts.map { (la, lo) ->
            Geo.Ray(la, lo, Geo.initialBearing(la, lo, txLat, txLon) + rnd.nextGaussian() * noiseDeg)
        }
    }

    private fun distToTx(lat: Double, lon: Double): Double {
        val dy = Math.toRadians(lat - txLat) * 6371008.8
        val dx = Math.toRadians(lon - txLon) * 6371008.8 * Math.cos(Math.toRadians(txLat))
        return Math.hypot(dx, dy)
    }

    @Test
    fun twoExactRays() {
        val e = Geo.estimate(rays(listOf(40.40 to -3.72, 40.43 to -3.70)))
        assertTrue(distToTx(e.lat, e.lon) < 5.0)
        assertTrue(e.warnings.isEmpty())
    }

    @Test
    fun threeNoisyRays() {
        val e = Geo.estimate(rays(listOf(40.40 to -3.72, 40.43 to -3.70, 40.41 to -3.68), noiseDeg = 3.0))
        assertTrue(distToTx(e.lat, e.lon) < 600.0)
    }

    @Test
    fun parallelRaysRejected() {
        try {
            Geo.estimate(listOf(Geo.Ray(40.40, -3.70, 0.0), Geo.Ray(40.41, -3.70, 1.0)))
            fail("should have rejected parallel rays")
        } catch (e: Geo.GeoException) {
            // expected
        }
    }

    @Test
    fun behindWarning() {
        val e = Geo.estimate(listOf(Geo.Ray(40.40, -3.72, 90.0), Geo.Ray(40.43, -3.72, 225.0)))
        assertTrue(e.warnings.any { it.contains("behind") })
    }

    @Test
    fun needsTwo() {
        try {
            Geo.estimate(listOf(Geo.Ray(40.40, -3.72, 10.0)))
            fail("should have required 2 points")
        } catch (e: Geo.GeoException) {
            // expected
        }
    }

    @Test
    fun destinationRoundTrip() {
        val (la, lo) = Geo.destination(40.0, -3.0, 90.0, 1000.0)
        assertEquals(90.0, Geo.initialBearing(40.0, -3.0, la, lo), 0.1)
    }
}
