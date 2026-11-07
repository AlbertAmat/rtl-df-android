package app.rdf.finder

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Client for the rtl_tcp protocol (the one the "SDR driver" app exposes on 127.0.0.1:1234).
 *
 * The server streams IQ bytes (interleaved uint8 I,Q) non-stop. A reader thread always drains
 * the socket so stale data never piles up; [capture] requests a block of samples received
 * after the call (optionally discarding a settle time first after retuning).
 */
class RtlTcpClient {
    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set
    var tunerType = 0
        private set
    var gainCount = 0
        private set

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val outLock = Any()

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var capBuf: ByteArray? = null
    private var capFilled = 0
    private var capSkip = 0L
    private var capActive = false

    fun connect(host: String, port: Int, timeoutMs: Int = 3000) {
        close()
        lastError = null
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val header = ByteArray(12)
            input.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") {
                throw IOException("Unexpected response: this does not look like an rtl_tcp server")
            }
            tunerType = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            gainCount = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            s.soTimeout = 5000 // if nothing arrives within 5 s, the connection is dead
            socket = s
            out = s.getOutputStream()
            connected = true
            thread(isDaemon = true, name = "rtl_tcp-reader") { readLoop(input) }
        } catch (e: Exception) {
            runCatching { s.close() }
            connected = false
            throw e
        }
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        out = null
        lock.withLock { cond.signalAll() }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) throw IOException("SDR driver closed the connection")
                lock.withLock {
                    if (capActive) {
                        val target = capBuf!!
                        var off = 0
                        var len = n
                        if (capSkip > 0) {
                            val s = min(capSkip, len.toLong()).toInt()
                            capSkip -= s
                            off += s
                            len -= s
                        }
                        if (len > 0) {
                            val take = min(len, target.size - capFilled)
                            System.arraycopy(buf, off, target, capFilled, take)
                            capFilled += take
                            if (capFilled >= target.size) {
                                capActive = false
                                cond.signalAll()
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (connected) lastError = e.message ?: e.javaClass.simpleName
        } finally {
            connected = false
            lock.withLock { cond.signalAll() }
        }
    }

    /**
     * Returns [wantBytes] IQ bytes received after the call, after discarding [skipBytes].
     * Throws IOException if the connection drops or data does not arrive in time.
     */
    fun capture(wantBytes: Int, skipBytes: Long = 0, timeoutMs: Long = 4000): ByteArray {
        val buf = ByteArray(wantBytes)
        lock.withLock {
            if (!connected) throw IOException(lastError ?: "SDR disconnected")
            capBuf = buf
            capFilled = 0
            capSkip = skipBytes
            capActive = true
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (capActive && connected && remaining > 0) {
                remaining = cond.awaitNanos(remaining)
            }
            capActive = false
            capBuf = null
            if (capFilled < wantBytes) {
                throw IOException(lastError ?: "Timed out waiting for samples from the SDR")
            }
        }
        return buf
    }

    // ---- commands (5 bytes: code + big-endian uint32) ----
    private fun cmd(code: Int, value: Int) {
        val b = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(code.toByte()).putInt(value).array()
        synchronized(outLock) {
            val o = out ?: throw IOException("SDR disconnected")
            o.write(b)
            o.flush()
        }
    }

    fun setFrequency(hz: Long) = cmd(0x01, hz.toInt())
    fun setSampleRate(hz: Int) = cmd(0x02, hz)
    fun setGainMode(manual: Boolean) = cmd(0x03, if (manual) 1 else 0)
    fun setGainTenthsDb(tenths: Int) = cmd(0x04, tenths)
    fun setPpm(ppm: Int) = cmd(0x05, ppm)
    fun setAgc(on: Boolean) = cmd(0x08, if (on) 1 else 0)
}
