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

/** Reads at most [JmapMaxBody] bytes; a longer body is a JmapFailure, not an OutOfMemoryError. */
fun readJmapBody(stream: java.io.InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > JmapMaxBody) throw JmapFailure("jmap response is too large")
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
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) : JmapFetch {
    override fun get(url: String): JmapHttpResult {
        if (!jmapUrlAllowed(url)) throw JmapFailure("jmap fetch is not https")
        var current = url
        var followed = 0
        while (true) {
            val host = jmapTrustHost(current)
            val acceptInvalidCert = jmapDiscoveryAcceptInvalid.get() == true
            val exchange = jmapNet { withJmapDiscoveryTls(pin, acceptInvalidCert) { open(current) } }
            try {
                val ders = jmapNet { exchange.peerDer() }
                val verdict = applyTlsPolicy(
                    jmapVerdict(current) { jmapNet { trust(host, ders, pin) } },
                    TlsPolicy(acceptInvalidCert = acceptInvalidCert),
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
                if (!jmapUrlAllowed(next)) throw JmapFailure("jmap redirect is not https")
                current = next
                followed += 1
            } finally {
                exchange.close()
            }
        }
    }
}

private val jmapDiscoveryPin = ThreadLocal<String>()
private val jmapDiscoveryAcceptInvalid = ThreadLocal<Boolean>()

/** The account pin and acceptInvalidCert for the discovery handshake. Restored when [block] returns. */
internal fun <T> withJmapDiscoveryTls(pin: String, acceptInvalidCert: Boolean, block: () -> T): T {
    val previousPin = jmapDiscoveryPin.get()
    val previousAccept = jmapDiscoveryAcceptInvalid.get()
    val previousPeer = PeerTrust.acceptInvalid.get()
    jmapDiscoveryPin.set(pin)
    jmapDiscoveryAcceptInvalid.set(acceptInvalidCert)
    PeerTrust.acceptInvalid.set(acceptInvalidCert)
    try {
        return block()
    } finally {
        if (previousPin == null) jmapDiscoveryPin.remove() else jmapDiscoveryPin.set(previousPin)
        if (previousAccept == null) jmapDiscoveryAcceptInvalid.remove() else jmapDiscoveryAcceptInvalid.set(previousAccept)
        if (previousPeer == null) PeerTrust.acceptInvalid.remove() else PeerTrust.acceptInvalid.set(previousPeer)
    }
}

fun platformJmapExchange(url: String): JmapHttpExchange {
    val pin = jmapDiscoveryPin.get().orEmpty()
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        // The account pin, when discovery installed one. No Authorization on this call: a certificate the system
        // CAs reject is left for the post-exchange check, which can offer the pin.
        if (this is HttpsURLConnection) pinJmapTls(this, pin, deferPinnable = true)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }
    return object : JmapHttpExchange {
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
            if (stream == null) return ""
            return stream.use { readJmapBody(it) }
        }

        override fun close() {
            connection.disconnect()
        }
    }
}

private val jmapRedirectStatuses = setOf(301, 302, 303, 307, 308)

private fun httpsUrl(url: String): Boolean {
    val scheme = try {
        URI(url).scheme
    } catch (_: Exception) {
        null
    }
    return scheme != null && scheme.equals("https", ignoreCase = true)
}

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
 * acceptInvalidCert on the discovery scope accepts a name, expiry, or trust failure the way IMAP's TlsPolicy does.
 */
fun pinJmapTls(connection: HttpsURLConnection, pin: String, deferPinnable: Boolean = false) {
    val host = jmapTrustHost(connection.url.toString())
    val acceptInvalidCert = jmapDiscoveryAcceptInvalid.get() == true
    val manager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("client trust is not used")

        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            val ders = chain?.map { it.encoded } ?: emptyList()
            val verdict = applyTlsPolicy(PeerTrust.check(host, ders, pin), TlsPolicy(acceptInvalidCert = acceptInvalidCert))
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

fun pinnedJmapPost(pin: String): (String, String, String) -> JmapHttpExchange =
    { url, body, authorization -> platformJmapPost(url, body, authorization, pin) }

fun pinnedJmapUpload(pin: String): (String, ByteArray, String, String) -> JmapHttpExchange =
    { url, bytes, type, authorization -> platformJmapUpload(url, bytes, type, authorization, pin) }
