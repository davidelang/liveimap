package org.dlang.liveimap.engine.sieve

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.settings.TlsMode

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
    private var input: InputStream = socket.getInputStream()
    private var output: OutputStream = socket.getOutputStream()
    private var layered: SSLSocket? = null
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

    override suspend fun readBytes(count: Int): ByteArray {
        if (count < 0) throw SieveFailure("literal")
        return withContext(Dispatchers.IO) {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val n = try {
                    input.read(bytes, offset, count - offset)
                } catch (e: IOException) {
                    throw SieveFailure("read")
                }
                if (n <= 0) throw SieveFailure("read")
                offset += n
            }
            bytes
        }
    }

    override suspend fun writeBytes(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    /** Wraps the connected socket. Does not open TCP and does not write AUTHENTICATE. */
    suspend fun upgradeToTls(host: String, pin: String) {
        withContext(Dispatchers.IO) {
            val trust = SieveServerTrust(host, pin)
            val tls = layeredSocket(host, trust)
            val protocols = sieveTlsProtocols(tls.supportedProtocols)
            if (protocols.isEmpty()) {
                closeQuiet(tls)
                throw SieveFailure("TLS 1.2 required")
            }
            tls.enabledProtocols = protocols.toTypedArray()
            try {
                tls.startHandshake()
            } catch (e: Exception) {
                val remotePort = socket.port
                closeQuiet(socket)
                closeQuiet(tls)
                throw sieveHandshakeFailure(trust, host, remotePort)
            }
            input = tls.inputStream
            output = tls.outputStream
            layered = tls
        }
    }

    override fun close() {
        runBlocking(Dispatchers.IO) {
            if (!closed.compareAndSet(false, true)) return@runBlocking
            val tls = layered
            if (tls != null) {
                try {
                    tls.close()
                } catch (e: IOException) {
                }
            }
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }

    private fun layeredSocket(host: String, trust: SieveServerTrust): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), null)
        val created = context.socketFactory.createSocket(socket, host, socket.port, false)
        val tls = created as SSLSocket
        tls.useClientMode = true
        enableSieveHostnameCheck(tls)
        tls.soTimeout = socket.soTimeout
        return tls
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

internal class SieveServerTrust(
    private val host: String,
    private val pin: String,
) : X509TrustManager {
    internal var ders: List<ByteArray> = emptyList()
        private set
    internal var failure: String = ""
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        throw CertificateException("client certificate")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        if (chain == null || chain.isEmpty()) {
            failure = "empty certificate chain"
            throw CertificateException(failure)
        }
        ders = chain.map { it.encoded }
        failure = PeerTrust.check(host, ders, pin)
        if (failure.isNotEmpty()) throw CertificateException(failure)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun enableSieveHostnameCheck(tls: SSLSocket) {
    val params = tls.sslParameters
    params.endpointIdentificationAlgorithm = "HTTPS"
    tls.sslParameters = params
}

internal fun sieveCertificateOffer(
    failure: String,
    ders: List<ByteArray>,
    host: String,
    port: Int,
): SievePinOffer? {
    if (failure != "certificate untrusted" && failure != "certificate changed") return null
    val offer = PeerTrust.offer(ders)
    if (offer == null || offer.size != 5) return null
    return SievePinOffer(host, port, failure, offer[0], offer[1], offer[2], offer[3], offer[4])
}

private fun sieveHandshakeFailure(trust: SieveServerTrust, host: String, port: Int): Exception {
    val failure = trust.failure
    if (failure.isEmpty()) return SieveFailure("certificate rejected")
    val offer = sieveCertificateOffer(failure, trust.ders, host, port)
    if (offer != null) return offer
    return SieveFailure(failure)
}

private fun closeQuiet(socket: Socket) {
    try {
        socket.close()
    } catch (e: IOException) {
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

/** TLS 1.2 and TLS 1.3 only, in input order. */
internal fun sieveTlsProtocols(supported: Array<String>): List<String> {
    val kept = ArrayList<String>(supported.size)
    for (name in supported) {
        if (name == "TLSv1.2" || name == "TLSv1.3") kept.add(name)
    }
    return kept
}

/** Implicit TLS from the first byte. Does not call upgradeToTls and does not write AUTHENTICATE. */
suspend fun openImplicitSieve(host: String, port: Int, pin: String): PlainSieveTransport {
    return withContext(Dispatchers.IO) {
        val trust = SieveServerTrust(host, pin)
        val tls = try {
            connectImplicitSieve(host, port, trust)
        } catch (e: IOException) {
            throw SieveFailure("connect")
        }
        var handedOff = false
        try {
            val protocols = sieveTlsProtocols(tls.supportedProtocols)
            if (protocols.isEmpty()) throw SieveFailure("TLS 1.2 required")
            tls.enabledProtocols = protocols.toTypedArray()
            try {
                tls.startHandshake()
            } catch (e: Exception) {
                closeQuiet(tls)
                throw sieveHandshakeFailure(trust, host, port)
            }
            val transport = PlainSieveTransport(tls)
            handedOff = true
            transport
        } catch (e: SieveFailure) {
            throw e
        } catch (e: SievePinOffer) {
            throw e
        } catch (e: IOException) {
            throw SieveFailure("connect")
        } finally {
            if (!handedOff) closeQuiet(tls)
        }
    }
}

/** Opens, authenticates, and uploads liveimap. Does not SETACTIVE. A blank host or secret writes nothing. */
suspend fun openAndDeliverLiveimap(
    host: String,
    port: Int,
    mode: TlsMode,
    pin: String,
    confirm: suspend (CertPrompt) -> Boolean,
    save: suspend (String) -> Unit,
    username: String,
    password: String,
    script: String,
    allowPlaintextAuth: Boolean,
): String {
    if (host.isBlank() || username.isBlank() || password.isBlank()) throw SieveFailure("sasl")
    val plaintextOk = mode != TlsMode.None || allowPlaintextAuth
    suspend fun deliver(activePin: String): String {
        val transport = when (mode) {
            TlsMode.None, TlsMode.StartTls -> openPlainSieve(host, port)
            TlsMode.Implicit -> openImplicitSieve(host, port, activePin)
        }
        try {
            val caps = when (mode) {
                TlsMode.None -> readGreeting(transport)
                TlsMode.StartTls -> {
                    requireAdvertisedStartTls(readGreeting(transport))
                    completeStartTls(transport, host, activePin)
                }
                TlsMode.Implicit -> readGreeting(transport)
            }
            return authenticateAndUpload(
                transport,
                caps,
                script,
                username,
                password,
                plaintextOk,
            )
        } finally {
            transport.close()
        }
    }
    if (mode == TlsMode.None) return deliver(pin)
    return withSieveCertificate(pin, confirm, save) { activePin -> deliver(activePin) }
}

/** Greeting for the account TLS mode. Does not authenticate and does not upload a script. */
suspend fun greetSieve(
    host: String,
    port: Int,
    mode: TlsMode,
    pin: String,
    confirm: suspend (CertPrompt) -> Boolean,
    save: suspend (String) -> Unit,
): SieveCapabilities {
    if (mode == TlsMode.None) return greetPlain(host, port)
    return withSieveCertificate(pin, confirm, save) { activePin ->
        when (mode) {
            TlsMode.None -> greetPlain(host, port)
            TlsMode.StartTls -> {
                val transport = openPlainSieve(host, port)
                try {
                    requireAdvertisedStartTls(readGreeting(transport))
                    val caps = completeStartTls(transport, host, activePin)
                    logout(transport)
                    caps
                } finally {
                    transport.close()
                }
            }
            TlsMode.Implicit -> {
                val transport = openImplicitSieve(host, port, activePin)
                try {
                    val caps = readGreeting(transport)
                    logout(transport)
                    caps
                } finally {
                    transport.close()
                }
            }
        }
    }
}

private fun connectImplicitSieve(host: String, port: Int, trust: SieveServerTrust): SSLSocket {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trust), null)
    val tls = context.socketFactory.createSocket() as SSLSocket
    tls.useClientMode = true
    enableSieveHostnameCheck(tls)
    tls.tcpNoDelay = true
    try {
        tls.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        tls.soTimeout = READ_TIMEOUT_MS
    } catch (e: IOException) {
        closeQuiet(tls)
        throw e
    }
    return tls
}
