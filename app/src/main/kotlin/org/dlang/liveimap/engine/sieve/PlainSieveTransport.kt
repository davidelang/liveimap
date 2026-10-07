package org.dlang.liveimap.engine.sieve

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000
private const val MAX_LINE_CHARS = 8192

// Four UTF-8 bytes per character, plus one trailing CR that is not part of the line.
private const val MAX_LINE_BYTES = MAX_LINE_CHARS * 4 + 1

private const val CR = '\r'.code
private const val LF = '\n'.code
private val CR_LF = byteArrayOf(CR.toByte(), LF.toByte())

class PlainSieveTransport internal constructor(
    private val socket: Socket,
) : SieveLineTransport, Closeable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    override suspend fun readLine(): String {
        return withContext(Dispatchers.IO) {
            val bytes = readRawLine()
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.codePointCount(0, text.length) > MAX_LINE_CHARS) throw SieveFailure("line")
            text
        }
    }

    override suspend fun writeLine(line: String) {
        withContext(Dispatchers.IO) {
            output.write(line.toByteArray(StandardCharsets.UTF_8))
            output.write(CR_LF)
            output.flush()
        }
    }

    override fun close() {
        runBlocking(Dispatchers.IO) {
            if (!closed.compareAndSet(false, true)) return@runBlocking
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }

    private fun readRawLine(): ByteArray {
        val raw = ByteArrayOutputStream()
        while (true) {
            val next = readOne()
            if (next == LF) break
            if (raw.size() >= MAX_LINE_BYTES) throw SieveFailure("line")
            raw.write(next)
        }
        val bytes = raw.toByteArray()
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR.toByte()) {
            return bytes.copyOf(bytes.size - 1)
        }
        return bytes
    }

    private fun readOne(): Int {
        val next = try {
            input.read()
        } catch (e: IOException) {
            throw SieveFailure("read")
        }
        if (next < 0) throw SieveFailure("read")
        return next
    }
}

suspend fun openPlainSieve(host: String, port: Int): PlainSieveTransport {
    return withContext(Dispatchers.IO) {
        val socket = Socket()
        var handedOff = false
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val transport = PlainSieveTransport(socket)
            handedOff = true
            transport
        } catch (e: IOException) {
            throw SieveFailure("connect")
        } finally {
            if (!handedOff) {
                try {
                    socket.close()
                } catch (e: IOException) {
                }
            }
        }
    }
}

suspend fun greetPlain(host: String, port: Int): SieveCapabilities {
    val transport = openPlainSieve(host, port)
    try {
        val caps = readGreeting(transport)
        logout(transport)
        return caps
    } finally {
        transport.close()
    }
}
