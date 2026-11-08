package app.rdf.finder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/** Plays an [Cue] as vibration or sound. Left = low and short, right = high and double, on target = long. */
class AimCue(private val ctx: Context) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    private val vibrator: Vibrator? by lazy { findVibrator() }

    @Suppress("DEPRECATION")
    private fun findVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun play(cue: Cue, mode: AimCueMode) {
        when (mode) {
            AimCueMode.NONE -> Unit
            AimCueMode.HAPTIC -> haptic(cue)
            AimCueMode.SOUND -> exec.execute { runCatching { blip(cue) } }
        }
    }

    private fun haptic(cue: Cue) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val timings = when (cue) {
            Cue.LEFT -> longArrayOf(0, 60)
            Cue.RIGHT -> longArrayOf(0, 60, 80, 60)
            Cue.ON_TARGET -> longArrayOf(0, 220)
        }
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, -1)) }
    }

    private fun blip(cue: Cue) {
        val sr = 22050
        val parts = when (cue) { // (Hz, ms) with 60 ms gaps
            Cue.LEFT -> listOf(500.0 to 110)
            Cue.RIGHT -> listOf(1200.0 to 80, 0.0 to 60, 1200.0 to 80)
            Cue.ON_TARGET -> listOf(850.0 to 260)
        }
        val total = parts.sumOf { sr * it.second / 1000 }
        val pcm = ShortArray(total)
        var o = 0
        for ((hz, ms) in parts) {
            val n = sr * ms / 1000
            for (i in 0 until n) {
                val env = minOf(1.0, minOf(i, n - 1 - i) / (sr * 0.008)) // 8 ms fades avoid clicks
                pcm[o + i] = if (hz == 0.0) 0 else (sin(2 * PI * hz * i / sr) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
            }
            o += n
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(total * 1000L / sr + 40)
        track.release()
    }

    fun shutdown() = exec.shutdownNow()
}
