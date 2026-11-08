package app.rdf.finder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Streaming FIR low-pass + decimator for a complex (I/Q) signal. */
internal class DecimFir(private val taps: FloatArray, private val m: Int) {
    private val n = taps.size
    private val bi = FloatArray(2 * n)
    private val bq = FloatArray(2 * n)
    private var pos = 0
    private var count = 0
    var outI = 0f
        private set
    var outQ = 0f
        private set

    fun reset() {
        bi.fill(0f); bq.fill(0f); pos = 0; count = 0
    }

    /** Pushes one sample; returns true when a decimated output sample is available in [outI]/[outQ]. */
    fun push(i: Float, q: Float): Boolean {
        bi[pos] = i; bi[pos + n] = i
        bq[pos] = q; bq[pos + n] = q
        pos = (pos + 1) % n
        if (++count < m) return false
        count = 0
        var si = 0f
        var sq = 0f
        for (j in 0 until n) {
            val t = taps[j]
            si += t * bi[pos + j]
            sq += t * bq[pos + j]
        }
        outI = si; outQ = sq
        return true
    }

    companion object {
        /** Blackman-windowed sinc, [cutoff] in cycles/sample, unity DC gain. */
        fun lowPass(length: Int, cutoff: Double): FloatArray {
            val t = DoubleArray(length)
            val c = (length - 1) / 2.0
            for (k in 0 until length) {
                val x = k - c
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val w = 0.42 - 0.5 * cos(2 * PI * k / (length - 1)) + 0.08 * cos(4 * PI * k / (length - 1))
                t[k] = sinc * w
            }
            val sum = t.sum()
            return FloatArray(length) { (t[it] / sum).toFloat() }
        }
    }
}

/**
 * Narrow-band voice demodulator (NFM or AM) for the channel the [Tracker] is measuring: IQ at
 * [Tracker.FS] with the channel [Tracker.OFFSET] Hz above the tuner centre, down to 16 kHz audio.
 * Pure DSP with no Android audio: [VoiceListener] plays the result.
 */
class Demod(private val fsIn: Int = Tracker.FS, private val offsetHz: Double = Tracker.OFFSET) {
    enum class Mode { NFM, AM }

    @Volatile var mode = Mode.NFM
    @Volatile var squelchOpen = true
    @Volatile var volume = 1f

    private val stage1 = DecimFir(DecimFir.lowPass(65, 48_000.0 / fsIn), 8)         // -> fsIn/8 (128 kHz)
    private val stage2 = DecimFir(DecimFir.lowPass(65, 11_000.0 / (fsIn / 8.0)), 4)  // -> 32 kHz, channel filter

    private val dw = -2 * PI * offsetHz / fsIn
    private val rotC = cos(dw)
    private val rotS = sin(dw)
    private var pc = 1.0
    private var ps = 0.0

    private var havePending = false
    private var pendI = 0f

    // FM discriminator and audio shaping at 32 kHz
    private var prevR = 0f
    private var prevQ = 0f
    private var hpPrevIn = 0f
    private var hpOut = 0f
    private var lp1 = 0f
    private var lp2 = 0f
    private var amMean = 0.1f
    private var phaseOdd = false
    private var gate = 0f

    private val hpA = (1.0 / (1.0 + 2 * PI * 300.0 / 32_000.0)).toFloat()
    private val lpA = (1.0 - exp(-2 * PI * 3_400.0 / 32_000.0)).toFloat()

    fun reset() {
        stage1.reset(); stage2.reset()
        pc = 1.0; ps = 0.0
        havePending = false
        prevR = 0f; prevQ = 0f; hpPrevIn = 0f; hpOut = 0f; lp1 = 0f; lp2 = 0f; amMean = 0.1f
        phaseOdd = false; gate = 0f
    }

    /** Output buffer size needed for [bytes] input bytes. */
    fun maxOut(bytes: Int) = bytes / 2 / (fsIn / AUDIO_RATE) + 4

    /** Demodulates [n] interleaved uint8 I/Q bytes; returns the number of 16-bit audio samples written to [out]. */
    fun process(buf: ByteArray, n: Int, out: ShortArray): Int {
        var o = 0
        val fm = mode == Mode.NFM
        val open = squelchOpen
        val vol = volume
        for (i in 0 until n) {
            val v = ((buf[i].toInt() and 0xFF) - 127.5f) / 127.5f
            if (!havePending) {
                pendI = v
                havePending = true
                continue
            }
            havePending = false
            val si = pendI
            // shift the channel (at +offset) down to 0 Hz
            val re = (si * pc - v * ps).toFloat()
            val im = (si * ps + v * pc).toFloat()
            val npc = pc * rotC - ps * rotS
            ps = pc * rotS + ps * rotC
            pc = npc
            if (!stage1.push(re, im)) continue
            if (!stage2.push(stage1.outI, stage1.outQ)) continue

            val zr = stage2.outI
            val zq = stage2.outQ
            val y: Float
            if (fm) {
                val dr = zr * prevR + zq * prevQ
                val di = zq * prevR - zr * prevQ
                prevR = zr
                prevQ = zq
                y = atan2(di, dr) * FM_SCALE
            } else {
                val e = hypot(zr, zq)
                amMean += 0.0005f * (e - amMean)
                y = (e - amMean) / max(amMean, 0.02f)
            }
            // remove sub-audio (DC, CTCSS tones) and band-limit to voice
            hpOut = hpA * (hpOut + y - hpPrevIn)
            hpPrevIn = y
            lp1 += lpA * (hpOut - lp1)
            lp2 += lpA * (lp1 - lp2)
            phaseOdd = !phaseOdd
            if (phaseOdd) continue // 32 kHz -> 16 kHz
            gate += ((if (open) 1f else 0f) - gate) * 0.02f
            val s = (lp2 * vol * gate * 0.8f).coerceIn(-1f, 1f)
            out[o++] = (s * 32767f).toInt().toShort()
        }
        return o
    }

    companion object {
        const val AUDIO_RATE = 16_000
        private const val FM_SCALE = (32_000.0 / (2 * PI * 5_000.0)).toFloat() // 5 kHz deviation = full scale
    }
}

/**
 * Plays the demodulated channel on the phone's speaker. Taps the rtl_tcp stream (so it hears
 * everything, independent of the power measurement) and runs the DSP on its own thread.
 */
class VoiceListener(private val client: RtlTcpClient) {
    val demod = Demod()
    private val queue = ArrayBlockingQueue<ByteArray>(12) // ~0.4 s at 1.024 MS/s
    @Volatile private var running = false
    private var thread: Thread? = null

    val active: Boolean get() = running

    fun start() {
        if (running) return
        demod.reset()
        queue.clear()
        running = true
        client.tap = { buf, n ->
            val chunk = buf.copyOf(n)
            if (!queue.offer(chunk)) {
                queue.poll() // too slow: drop the oldest audio rather than build up delay
                queue.offer(chunk)
            }
        }
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        client.tap = null
        thread?.join(700)
        thread = null
    }

    private fun loop() {
        val sr = Demod.AUDIO_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val out = ShortArray(demod.maxOut(65536 * 2))
        try {
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val cnt = demod.process(chunk, minOf(chunk.size, 65536 * 2), out)
                if (cnt > 0) track.write(out, 0, cnt)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
