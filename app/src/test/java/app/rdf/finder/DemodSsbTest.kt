package app.rdf.finder

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DemodSsbTest {
    private val fs = Tracker.FS

    /** A single carrier at [rfHz] relative to the tuner centre (channel centre = OFFSET). */
    private fun carrier(rfHz: Double, seconds: Double = 1.0): ByteArray {
        val n = (fs * seconds).toInt()
        val b = ByteArray(2 * n)
        val rnd = java.util.Random(3)
        for (k in 0 until n) {
            val ph = 2 * PI * rfHz * k / fs
            b[2 * k] = ((0.4 * cos(ph) + 0.005 * rnd.nextGaussian()) * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
            b[2 * k + 1] = ((0.4 * sin(ph) + 0.005 * rnd.nextGaussian()) * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
        }
        return b
    }

    private fun run(d: Demod, iq: ByteArray): ShortArray {
        val out = ShortArray(d.maxOut(iq.size))
        val c = d.process(iq, iq.size, out)
        return out.copyOf(c)
    }

    private fun level(x: ShortArray, hz: Double): Double {
        val from = 4000
        val w = 2 * PI * hz / Demod.AUDIO_RATE
        var re = 0.0; var im = 0.0
        for (k in from until x.size) { re += x[k] * cos(w * k); im -= x[k] * sin(w * k) }
        return sqrt(re * re + im * im) / (x.size - from)
    }

    @Test fun usbGivesAudioAtTheOffsetAboveTheCarrier() {
        val a = run(Demod().apply { mode = Demod.Mode.USB }, carrier(Tracker.OFFSET + 1000.0))
        val t = level(a, 1000.0)
        assertTrue("USB tone too weak: $t", t > 2000)
        assertTrue("USB leaks at 2.2 kHz", level(a, 2200.0) < t / 10)
    }

    @Test fun usbRejectsTheOppositeSideband() {
        val wanted = level(run(Demod().apply { mode = Demod.Mode.USB }, carrier(Tracker.OFFSET + 1000.0)), 1000.0)
        val image = level(run(Demod().apply { mode = Demod.Mode.USB }, carrier(Tracker.OFFSET - 1000.0)), 1000.0)
        assertTrue("sideband rejection only ${wanted / image}x", image < wanted / 10)
    }

    @Test fun lsbGivesAudioBelowTheCarrier() {
        val a = run(Demod().apply { mode = Demod.Mode.LSB }, carrier(Tracker.OFFSET - 1000.0))
        assertTrue("LSB tone too weak: ${level(a, 1000.0)}", level(a, 1000.0) > 2000)
    }

    @Test fun cwProducesAToneAt700Hz() {
        val a = run(Demod().apply { mode = Demod.Mode.CW }, carrier(Tracker.OFFSET))
        val t = level(a, 700.0)
        assertTrue("CW tone too weak: $t", t > 2000)
        assertTrue("CW leaks at 1.6 kHz", level(a, 1600.0) < t / 10)
    }
}
