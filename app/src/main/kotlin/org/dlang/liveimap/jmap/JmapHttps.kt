package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.applyTlsPolicy
import java.net.URI
import javax.net.ssl.HttpsURLConnection

interface JmapHttpExchange {
    val status: Int

    fun peerDer(): List<ByteArray>

    fun header(name: String): String?

    fun body(): String

    fun close()
}

/** F58: the largest JMAP response body read into memory. */
const val JmapMaxBody = 16 * 1024 * 1024

/** The account cap for the read in progress. Unset means [JmapMaxBody]. Platform exchanges call the readers with the default. */
internal object JmapResponseCap {
    private val current = ThreadLocal<Int>()

    fun limitOr(fallback: Int): Int = current.get() ?: fallback

    fun <T> use(limit: Int, block: () -> T): T {
        val previous = current.get()
        current.set(limit)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/** Reads at most [limit] bytes; a longer body is a JmapFailure, not an OutOfMemoryError. */
fun readJmapBody(stream: java.io.InputStream, limit: Int = JmapResponseCap.limitOr(JmapMaxBody)): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw JmapFailure("jmap response is too large: maxJmapResponseBytes")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** F68: a network failure (IOException/SSLException) becomes a JmapFailure that every caller handles. */
inline fun <T> jmapNet(block: () -> T): T = try {
    block()
} catch (error: java.io.IOException) {
    throw JmapFailure("jmap network error: ${error.message ?: error.javaClass.simpleName}")
}

/** The host of an https URL or URL template (templates may hold {accountId} etc., which URI() rejects). */
fun jmapUrlHost(url: String): String? =
    Regex("^https?://(\\[[^\\]]+\\]|[^/:?#{}]+)", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)?.lowercase()

class JmapHttpsFetch(
    private val pin: String,
    private val open: (String) -> JmapHttpExchange,
    private val policy: TlsPolicy = TlsPolicy(),
    private val accountKey: String = "",
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) : JmapFetch {
    override fun get(url: String): JmapHttpResult {
        if (!jmapUrlAllowed(accountKey, url)) throw JmapFailure("jmap fetch is not https")
        var current = url
        var followed = 0
        while (true) {
            val host = jmapTrustHost(current)
            val exchange = jmapNet { open(current) }
            try {
                val ders = jmapNet { exchange.peerDer() }
                val verdict = applyTlsPolicy(
                    jmapVerdict(accountKey, current) { jmapNet { trust(host, ders, pin) } },
                    policy,
                )
                if (verdict.isNotEmpty()) {
                    // Discovery is unauthenticated, so a pinnable failure can be offered (nothing was sent).
                    if (jmapPinnable(verdict)) throw JmapPinOffer(verdict, host, ders)
                    throw JmapFailure(verdict)
                }
                val status = jmapNet { exchange.status }
                if (status !in jmapRedirectStatuses) {
                    return JmapHttpResult(status, jmapNet { exchange.body() }, current)
                }
                if (followed == 5) throw JmapFailure("jmap redirect limit")
                val location = exchange.header("Location")
                if (location.isNullOrBlank()) throw JmapFailure("jmap redirect lacks location")
                val next = URI(current).resolve(location).toString()
                if (!jmapUrlAllowed(accountKey, next)) throw JmapFailure("jmap redirect is not https")
                current = next
                followed += 1
            } finally {
                exchange.close()
            }
        }
    }
}

private data class JmapFactoryKey(val host: String, val port: Int, val pin: String, val policy: TlsPolicy)

private val jmapExchangeFactories = java.util.concurrent.ConcurrentHashMap<JmapFactoryKey, javax.net.ssl.SSLSocketFactory>()

/** One factory per host, port, pin, and policy. A different pin is a different key. The manager checks the pin on every handshake. */
private fun jmapExchangeFactory(host: String, port: Int, pin: String, policy: TlsPolicy): javax.net.ssl.SSLSocketFactory {
    val key = JmapFactoryKey(host, port, pin, policy)
    return jmapExchangeFactories.computeIfAbsent(key) {
        val manager = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
                throw java.security.cert.CertificateException("client trust is not used")

            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                val ders = chain?.map { it.encoded } ?: emptyList()
                val verdict = applyTlsPolicy(PeerTrust.check(host, ders, pin), policy)
                if (verdict.isEmpty()) return
                // Discovery is unauthenticated: a pinnable failure is offered after the exchange.
                if (jmapPinnable(verdict)) return
                throw java.security.cert.CertificateException(verdict)
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, arrayOf(manager), null)
        context.socketFactory
    }
}

/** Discovery exchange. [deferPinnable] is on: a certificate the system CAs reject can still be offered for pinning. */
fun platformJmapExchange(pin: String, policy: TlsPolicy = TlsPolicy()): (String) -> JmapHttpExchange = { url ->
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        // No Authorization on this call: a certificate the system CAs reject is left for the post-exchange check,
        // which can offer the pin.
        if (this is HttpsURLConnection) {
            val https = this
            val host = jmapTrustHost(url)
            val port = if (https.url.port == -1) https.url.defaultPort else https.url.port
            sslSocketFactory = jmapExchangeFactory(host, port, pin, policy)
            hostnameVerifier = javax.net.ssl.HostnameVerifier { name, _ -> name.equals(https.url.host, ignoreCase = true) }
        }
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }
    object : JmapHttpExchange {
        private var finished = false

        override val status: Int
            get() = connection.responseCode

        override fun peerDer(): List<ByteArray> =
            connection.connect().let { (connection as? HttpsURLConnection)?.serverCertificates?.map { it.encoded } ?: emptyList() } // certificates exist only once connected (no-op if already)

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun body(): String {
            val stream = if (connection.responseCode < 400) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            if (stream == null) {
                finished = true
                return ""
            }
            val text = stream.use { readJmapBody(it) }
            finished = true
            return text
        }

        override fun close() {
            if (finished) return
            try {
                connection.inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                connection.errorStream?.close()
            } catch (_: Exception) {
            }
            connection.disconnect()
        }
    }
}

private val jmapRedirectStatuses = setOf(301, 302, 303, 307, 308)

private fun jmapTrustHost(url: String): String {
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    } ?: throw JmapFailure("jmap fetch is not https")
    if (host.startsWith("[") && host.endsWith("]") && host.length >= 2) {
        return host.substring(1, host.length - 1)
    }
    return host
}

/**
 * Runs the account's trust verdict (pin, lab hooks, platform CAs, host name) inside the TLS handshake, so a
 * request carrying credentials is never written to a peer that fails it (F02). The verdict after the exchange
 * stays as a second check. Unauthenticated discovery passes [deferPinnable], so a certificate the system CAs
 * reject still reaches that check and can be offered for pinning. A call that will send credentials does not.
 * [policy] is applied once. acceptInvalidCert accepts a name, expiry, or trust failure. A changed certificate stays a failure.
 */
fun pinJmapTls(connection: HttpsURLConnection, pin: String, policy: TlsPolicy = TlsPolicy(), deferPinnable: Boolean = false) {
    val host = jmapTrustHost(connection.url.toString())
    val manager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("client trust is not used")

        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            val ders = chain?.map { it.encoded } ?: emptyList()
            val verdict = applyTlsPolicy(PeerTrust.check(host, ders, pin), policy)
            if (verdict.isEmpty()) return
            if (deferPinnable && jmapPinnable(verdict)) return
            throw java.security.cert.CertificateException(verdict)
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    val context = javax.net.ssl.SSLContext.getInstance("TLS")
    context.init(null, arrayOf(manager), null)
    connection.sslSocketFactory = context.socketFactory
    // The verdict already ran in the handshake, including a stored pin that wins over the name.
    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { name, _ -> name.equals(connection.url.host, ignoreCase = true) }
}

fun pinnedJmapPost(pin: String, policy: TlsPolicy = TlsPolicy()): (String, String, String) -> JmapHttpExchange =
    { url, body, authorization -> platformJmapPost(url, body, authorization, pin, policy) }

fun pinnedJmapUpload(pin: String, policy: TlsPolicy = TlsPolicy()): (String, ByteArray, String, String) -> JmapHttpExchange =
    { url, bytes, type, authorization -> platformJmapUpload(url, bytes, type, authorization, pin, policy) }
