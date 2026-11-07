package app.rdf.finder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Servidor rtl_tcp falso: emite ruido + portadoras a ritmo de tiempo real y apunta los comandos recibidos. */
class FakeRtlTcp(private val carriers: List<Pair<Double, Double>>) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile var freq = 100_000_000L
    @Volatile var fs = 2_048_000
    val commands = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
    @Volatile private var closed = false

    init {
        thread(isDaemon = true) {
            try {
                val s = server.accept()
                val out = s.getOutputStream()
                out.write(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte()))
                out.write(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)) // tuner R820T, 29 ganancias
                out.flush()
                thread(isDaemon = true) {
                    val inp = DataInputStream(s.getInputStream())
                    try {
                        while (!closed) {
                            val code = inp.readUnsignedByte()
                            val v = inp.readInt().toLong() and 0xFFFFFFFFL
                            commands.add(code to v)
                            if (code == 1) freq = v
                            if (code == 2) fs = v.toInt()
                        }
                    } catch (e: Exception) {
                    }
                }
                val rnd = Random(7)
                val phases = DoubleArray(carriers.size)
                val chunk = 8192 // muestras
                var next = System.nanoTime()
                while (!closed) {
                    val f = freq
                    val rate = fs
                    val buf = ByteArray(chunk * 2)
                    for (i in 0 until chunk) {
                        var re = rnd.nextGaussian() * 0.004
                        var im = rnd.nextGaussian() * 0.004
                        for ((k, c) in carriers.withIndex()) {
                            val off = c.first - f
                            if (abs(off) < rate / 2.0) {
                                phases[k] += 2 * PI * off / rate
                                re += c.second * cos(phases[k])
                                im += c.second * sin(phases[k])
                            }
                        }
                        buf[2 * i] = (127.5 + 127.5 * re).roundToInt().coerceIn(0, 255).toByte()
                        buf[2 * i + 1] = (127.5 + 127.5 * im).roundToInt().coerceIn(0, 255).toByte()
                    }
                    out.write(buf)
                    next += (chunk * 1_000_000_000L) / rate
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}

class RtlTcpIntegrationTest {
    @Test
    fun scannerFindsCarriersAndCommandsAreEncoded() = runBlocking {
        FakeRtlTcp(listOf(433_920_000.0 to 0.05, 434_500_000.0 to 0.03)).use { fake ->
            val client = RtlTcpClient()
            client.connect("127.0.0.1", fake.port)
            assertEquals(5, client.tunerType)
            val sdr = Sdr(client)
            val state = MutableStateFlow(ScanState())
            withTimeout(60_000) {
                Scanner.run(sdr, ScanParams(433.0e6, 435.0e6, 4.0, 8.0, 38.0), state)
            }
            val r = state.value
            println("barridos=${r.sweeps} emisoras=" + r.results.joinToString { "%.4f".format(it.freqHz / 1e6) + "(${it.snrDb.roundToInt()}dB)" })
            assertTrue("barridos", r.sweeps >= 1)
            assertTrue("433.920 no detectada", r.results.any { abs(it.freqHz - 433_920_000.0) < 3_000 })
            assertTrue("434.500 no detectada", r.results.any { abs(it.freqHz - 434_500_000.0) < 3_000 })
            assertTrue("falsos positivos: ${r.results.size}", r.results.size <= 3)
            // comandos: sample rate (0x02) con 2.048 MS/s y ganancia manual 0x04 = 372 (37.2 dB, el valor R820T más cercano a 38.0 es 37.2? no: 38.6=386)
            assertTrue(fake.commands.any { it.first == 2 && it.second == 2_048_000L })
            assertTrue(fake.commands.any { it.first == 4 && it.second == 386L })
            assertTrue(fake.commands.any { it.first == 1 })
            client.close()
        }
    }

    @Test
    fun trackerMeasuresCarrierAndDropsWhenAbsent() = runBlocking {
        FakeRtlTcp(listOf(433_920_000.0 to 0.05)).use { fake ->
            val client = RtlTcpClient()
            client.connect("127.0.0.1", fake.port)
            val sdr = Sdr(client)
            val tracker = Tracker()
            val live = MutableStateFlow(LiveState())

            val job = launch(Dispatchers.Default) {
                try {
                    tracker.run(sdr, 433_920_000.0, 12_500.0, 30.0, live)
                } catch (e: CancellationException) {
                    throw e
                }
            }
            delay(2500)
            val onCarrier = tracker.sampleForPoint()
            println("sobre la portadora: $onCarrier")
            assertTrue(onCarrier != null && onCarrier.snrDb > 20)
            job.cancel()
            job.join()

            // Mismo canal pero en 433.980 (sin portadora): solo ruido
            val job2 = launch(Dispatchers.Default) {
                try {
                    tracker.run(sdr, 433_980_000.0, 12_500.0, 30.0, live)
                } catch (e: CancellationException) {
                    throw e
                }
            }
            delay(2500)
            val offCarrier = tracker.sampleForPoint()
            println("sin portadora: $offCarrier")
            assertTrue(offCarrier != null && offCarrier.snrDb < 6)
            job2.cancel()
            job2.join()
            client.close()
        }
    }
}
