package app.rdf.finder

import kotlin.math.abs

/** Layer over [RtlTcpClient]: configure, tune and read IQ samples as floats in [-1, 1]. */
class Sdr(private val client: RtlTcpClient) {
    /** Time (ms) discarded after retuning (PLL + in-flight buffers). */
    @Volatile var settleMs = 80
    @Volatile var ppm = 0

    var fs = 2_048_000
        private set
    var gainDb: Double? = null
        private set

    private fun bytesFor(ms: Int): Long = fs.toLong() * 2L * ms / 1000L

    /** gain == null: tuner AGC. Discards ~300 ms of data captured with the previous configuration. */
    fun configure(sampleRate: Int, gain: Double?) {
        client.setSampleRate(sampleRate)
        fs = sampleRate
        client.setPpm(ppm)
        client.setAgc(false)
        if (gain == null) {
            client.setGainMode(false)
            gainDb = null
        } else {
            client.setGainMode(true)
            val tenths = snapGain((gain * 10).toInt())
            client.setGainTenthsDb(tenths)
            gainDb = tenths / 10.0
        }
        discard(300)
    }

    private fun snapGain(tenths: Int): Int {
        // Gain table for the R820T/R820T2/R828D (tuner 5 and 6); other tuners: value as is
        if (client.tunerType != 5 && client.tunerType != 6) return tenths
        return R820T_GAINS.minByOrNull { abs(it - tenths) } ?: tenths
    }

    fun discard(ms: Int) {
        client.capture(wantBytes = 2, skipBytes = bytesFor(ms), timeoutMs = ms + 3000L)
    }

    /** Tunes and reads [nSamples] already-settled samples. */
    fun tuneAndRead(freqHz: Long, nSamples: Int): FloatArray {
        client.setFrequency(freqHz)
        return toIq(client.capture(nSamples * 2, bytesFor(settleMs), timeoutMs = readTimeout(nSamples) + settleMs))
    }

    fun tune(freqHz: Long) {
        client.setFrequency(freqHz)
        discard(maxOf(settleMs, 150))
    }

    /** Reads [nSamples] samples without discarding anything (continuous tracking). */
    fun read(nSamples: Int): FloatArray =
        toIq(client.capture(nSamples * 2, 0, timeoutMs = readTimeout(nSamples)))

    private fun readTimeout(nSamples: Int): Long = nSamples * 1000L / fs * 2 + 2000L

    companion object {
        private val R820T_GAINS = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
            280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
        )

        fun toIq(b: ByteArray): FloatArray {
            val f = FloatArray(b.size)
            for (i in b.indices) f[i] = ((b[i].toInt() and 0xFF) - 127.5f) / 127.5f
            return f
        }
    }
}
