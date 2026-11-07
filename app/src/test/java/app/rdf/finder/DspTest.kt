package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DspTest {
    private fun tone(bins: Int, amp: Double, blocks: Int): FloatArray {
        val n = Dsp.NFFT * blocks
        val out = FloatArray(2 * n)
        for (i in 0 until n) {
            val ph = 2.0 * PI * bins * i / Dsp.NFFT
            out[2 * i] = (amp * cos(ph)).toFloat()
            out[2 * i + 1] = (amp * sin(ph)).toFloat()
        }
        return out
    }

    @Test
    fun positiveToneLandsAboveCenter() {
        val p = Dsp.psd(tone(100, 0.5, 8), Dsp.NFFT * 8)
        val peak = p.indices.maxByOrNull { p[it] }!!
        assertEquals(Dsp.NFFT / 2 + 100, peak)
        assertEquals(0.25, p[peak], 1e-3) // amplitud 0.5 → potencia 0.25 (−6 dB)
    }

    @Test
    fun negativeToneLandsBelowCenter() {
        val p = Dsp.psd(tone(-50, 1.0, 4), Dsp.NFFT * 4)
        val peak = p.indices.maxByOrNull { p[it] }!!
        assertEquals(Dsp.NFFT / 2 - 50, peak)
        assertEquals(1.0, p[peak], 1e-3)
    }

    @Test
    fun medianAndPercentile() {
        assertEquals(2.5, Dsp.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 1e-12)
        assertEquals(9.1, Dsp.percentile((1..10).map { it.toDouble() }, 90.0), 1e-9)
    }
}
