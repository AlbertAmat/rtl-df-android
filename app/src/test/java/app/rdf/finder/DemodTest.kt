package app.rdf.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DemodTest {
    private val fs = Tracker.FS
    private val off = Tracker.OFFSET

    /** Channel at +OFFSET carrying a [tone] Hz modulation, as interleaved uint8 IQ. */
    private fun signal(seconds: Double, fm: Boolean, tone: Double = 1000.0, dev: Double = 3000.0, noise: Double = 0.01): ByteArray {
        val n = (fs * seconds).toInt()
        val b = ByteArray(2 * n)
        val rnd = java.util.Random(7)
        var ph = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / fs
            val amp: Double
            if (fm) {
                ph += 2 * PI * (off + dev * sin(2 * PI * tone * t)) / fs
                amp = 0.4
            } else {
                ph = 2 * PI * off * t
                amp = 0.4 * (1 + 0.8 * sin(2 * PI * tone * t))
            }
            val i = amp * cos(ph) + noise * rnd.nextGaussian()
            val q = amp * sin(ph) + noise * rnd.nextGaussian()
            b[2 * k] = (i * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
            b[2 * k + 1] = (q * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        return b
    }

    private fun run(d: Demod, iq: ByteArray): ShortArray {
        val out = ShortArray(d.maxOut(iq.size))
        var total = 0
        var pos = 0
        // odd-sized chunks on purpose: the I/Q pairing must survive arbitrary read boundaries
        while (pos < iq.size) {
            val len = minOf(65535, iq.size - pos)
            val chunk = iq.copyOfRange(pos, pos + len)
            val tmp = ShortArray(d.maxOut(len))
            val c = d.process(chunk, len, tmp)
            System.arraycopy(tmp, 0, out, total, c)
            total += c
            pos += len
        }
        return out.copyOf(total)
    }

    private fun goertzel(x: ShortArray, from: Int, hz: Double): Double {
        val w = 2 * PI * hz / Demod.AUDIO_RATE
        var re = 0.0
        var im = 0.0
        for (k in from until x.size) {
            re += x[k] * cos(w * k)
            im -= x[k] * sin(w * k)
        }
        return sqrt(re * re + im * im) / (x.size - from)
    }

    @Test
    fun nfmRecoversTheTone() {
        val a = run(Demod(), signal(1.0, fm = true))
        assertEquals(16000.0, a.size.toDouble(), 20.0)
        val at1k = goertzel(a, 4000, 1000.0)
        assertTrue("1 kHz tone too weak: $at1k", at1k > 3000)
        for (other in listOf(400.0, 2200.0, 3000.0)) {
            assertTrue("$other Hz leaks: ${goertzel(a, 4000, other)} vs $at1k", goertzel(a, 4000, other) < at1k / 8)
        }
    }

    @Test
    fun amRecoversTheTone() {
        val d = Demod().apply { mode = Demod.Mode.AM }
        val a = run(d, signal(1.0, fm = false))
        val at1k = goertzel(a, 4000, 1000.0)
        assertTrue("1 kHz tone too weak: $at1k", at1k > 2000)
        assertTrue(goertzel(a, 4000, 2200.0) < at1k / 8)
    }

    @Test
    fun closedSquelchIsSilent() {
        val d = Demod().apply { squelchOpen = false }
        val a = run(d, signal(0.6, fm = true))
        val peak = a.drop(2000).maxOf { abs(it.toInt()) }
        assertTrue("squelch leaks: $peak", peak < 200)
    }

    @Test
    fun noiseOnlyFmStaysBoundedAndOtherChannelIsRejected() {
        // a strong carrier 40 kHz away from the wanted channel must not come out as audio
        val n = fs
        val b = ByteArray(2 * n)
        for (k in 0 until n) {
            val ph = 2 * PI * (off + 40_000.0) * k / fs
            b[2 * k] = (0.5 * cos(ph) * 127.5 + 127.5).toInt().toByte()
            b[2 * k + 1] = (0.5 * sin(ph) * 127.5 + 127.5).toInt().toByte()
        }
        val a = run(Demod(), b)
        val rms = sqrt(a.drop(4000).sumOf { it.toDouble() * it } / (a.size - 4000))
        assertTrue("adjacent channel leaks into audio: rms=$rms", rms < 800)
    }
}
