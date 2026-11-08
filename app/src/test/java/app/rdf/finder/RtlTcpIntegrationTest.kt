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

/** Fake rtl_tcp server: streams noise + carriers at real-time rate and records the commands it receives. */
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
                out.write(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)) // R820T tuner, 29 gains
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
                val chunk = 8192 // samples
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
                Scanner.run(sdr, ScanParams(433.0e6, 435.0e6, 10.0, 8.0, 38.0), state) // long enough for a full sweep even on a loaded machine
            }
            val r = state.value
            println("sweeps=${r.sweeps} emitters=" + r.results.joinToString { "%.4f".format(it.freqHz / 1e6) + "(${it.snrDb.roundToInt()}dB)" })
            assertTrue("sweeps", r.sweeps >= 1)
            assertTrue("433.920 not detected", r.results.any { abs(it.freqHz - 433_920_000.0) < 3_000 })
            assertTrue("434.500 not detected", r.results.any { abs(it.freqHz - 434_500_000.0) < 3_000 })
            assertTrue("false positives: ${r.results.size}", r.results.size <= 3)
            // commands: sample rate (0x02) at 2.048 MS/s and manual gain 0x04 = 386 (38.6 dB, the R820T value nearest to 38.0)
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
            println("on carrier: $onCarrier")
            assertTrue(onCarrier != null && onCarrier.snrDb > 20)
            job.cancel()
            job.join()

            // Same channel but at 433.980 (no carrier): noise only
            val job2 = launch(Dispatchers.Default) {
                try {
                    tracker.run(sdr, 433_980_000.0, 12_500.0, 30.0, live)
                } catch (e: CancellationException) {
                    throw e
                }
            }
            delay(2500)
            val offCarrier = tracker.sampleForPoint()
            println("off carrier: $offCarrier")
            assertTrue(offCarrier != null && offCarrier.snrDb < 6)
            job2.cancel()
            job2.join()
            client.close()
        }
    }
}
