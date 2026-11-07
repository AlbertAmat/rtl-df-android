package app.rdf.finder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Procesado de señal: espectro de potencia promediado y estadísticos auxiliares. */
object Dsp {
    const val NFFT = 1024
    private const val LOG2 = 10

    // Ventana de Hann idéntica a numpy.hanning(NFFT)
    private val win = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (NFFT - 1)) }
    private val wsum2: Double = win.sum().let { it * it }
    private val cosT = DoubleArray(NFFT / 2) { cos(2.0 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { -sin(2.0 * PI * it / NFFT) } // e^{-j...}
    private val rev = IntArray(NFFT) { Integer.reverse(it) ushr (32 - LOG2) }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until NFFT) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= NFFT) {
            val half = size / 2
            val step = NFFT / size
            var start = 0
            while (start < NFFT) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half]
                    val xi = im[j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /**
     * Espectro de potencia lineal, centrado (bin NFFT/2 = frecuencia central), promediando
     * nSamples/NFFT bloques. iq = I,Q intercalados en [-1, 1]. Un tono de amplitud 1 da potencia 1.
     */
    fun psd(iq: FloatArray, nSamples: Int): DoubleArray {
        val blocks = nSamples / NFFT
        require(blocks > 0) { "Muy pocas muestras" }
        val acc = DoubleArray(NFFT)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        for (b in 0 until blocks) {
            val base = b * NFFT * 2
            for (i in 0 until NFFT) {
                re[i] = iq[base + 2 * i] * win[i]
                im[i] = iq[base + 2 * i + 1] * win[i]
            }
            fft(re, im)
            for (k in 0 until NFFT) {
                acc[(k + NFFT / 2) % NFFT] += re[k] * re[k] + im[k] * im[k]
            }
        }
        val norm = blocks * wsum2
        for (k in 0 until NFFT) acc[k] /= norm
        return acc
    }

    fun db(x: Double): Double = 10.0 * log10(max(x, 1e-20))

    fun psdDb(iq: FloatArray, nSamples: Int): DoubleArray = psd(iq, nSamples).also { p ->
        for (i in p.indices) p[i] = db(p[i])
    }

    /** Mediana (como numpy.median). */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Percentil con interpolación lineal (como numpy.percentile). */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val pos = p / 100.0 * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun roundIdx(x: Double): Int = x.roundToInt()
}
