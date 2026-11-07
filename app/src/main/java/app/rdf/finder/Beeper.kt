package app.rdf.finder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Continuous tone whose pitch follows the signal: lets you aim the antenna without looking at the screen. */
class Beeper {
    /** Tone frequency in Hz; 0 = silence. */
    @Volatile var freqHz = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val sr = 22050
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val buf = ShortArray(1024) // ~46 ms
        var phase = 0.0
        var amp = 0.0
        while (running) {
            val f = freqHz
            val target = if (f > 0) 0.3 else 0.0
            for (i in buf.indices) {
                amp += (target - amp) * 0.002
                phase += 2 * PI * max(f, 1.0) / sr
                if (phase > 2 * PI) phase -= 2 * PI
                buf[i] = (sin(phase) * amp * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(buf, 0, buf.size)
        }
        track.stop()
        track.release()
    }
}
