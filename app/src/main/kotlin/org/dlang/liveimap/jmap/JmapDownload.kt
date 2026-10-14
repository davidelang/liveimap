package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** One blob download: raw bytes, size-capped, never redirected. */
interface JmapBlobExchange {
    val status: Int
    fun peerDer(): List<ByteArray>
    fun bytes(): ByteArray
    fun close()
}

/** RFC 8620 §6.2 downloadUrl template, every variable percent-encoded. */
fun jmapDownloadUrl(template: String, accountId: String, blobId: String, type: String, name: String): String {
    if (template.isBlank()) throw JmapFailure("jmap download url is empty")
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (blobId.isBlank()) throw JmapFailure("jmap blob id is empty")
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    return template.replace("{accountId}", enc(accountId)).replace("{blobId}", enc(blobId))
        .replace("{type}", enc(type)).replace("{name}", enc(name))
}

/**
 * Fetch a blob. The URL must be https on the API host (F53: the session was same-origin checked at discovery, and a
 * template cannot move us elsewhere), trust uses the JMAP pin (F63), redirects are refused, and the body is capped.
 */
fun jmapDownload(
    session: JmapSession,
    blobId: String,
    type: String,
    authorization: String,
    pin: String,
    get: (String, String) -> JmapBlobExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): ByteArray {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val url = jmapDownloadUrl(session.downloadUrl, accountId, blobId, type, "message")
    val host = httpsHost(url) ?: throw JmapFailure("jmap download url is not https")
    if (!host.equals(httpsHost(session.apiUrl), ignoreCase = true)) throw JmapFailure("jmap download url is on another host")
    val exchange = jmapNet { get(url, authorization) }
    try {
        val verdict = jmapVerdict(url) { jmapNet { trust(host, exchange.peerDer(), pin) } }
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = jmapNet { exchange.status }
        if (status in 300..399) throw JmapFailure("jmap download redirect")
        if (status != 200) throw JmapFailure("jmap download status $status")
        return jmapNet { exchange.bytes() }
    } finally {
        exchange.close()
    }
}

private fun httpsHost(url: String): String? = try {
    val uri = URI(url)
    if (jmapUrlAllowed(url)) uri.host?.removePrefix("[")?.removeSuffix("]") else null
} catch (_: Exception) {
    null
}

/** Reads at most [JmapMaxBody] bytes. */
fun readJmapBytes(stream: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > JmapMaxBody) throw JmapFailure("jmap download is too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun platformJmapGet(url: String, authorization: String, pin: String): JmapBlobExchange {
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("Authorization", authorization)
    }
    return object : JmapBlobExchange {
        override val status: Int get() = connection.responseCode
        override fun peerDer(): List<ByteArray> = connection.connect().let { (connection as? HttpsURLConnection)?.serverCertificates?.map { it.encoded } ?: emptyList() } // certificates exist only once connected (no-op if already)
        override fun bytes(): ByteArray = connection.inputStream.use { readJmapBytes(it) }
        override fun close() = connection.disconnect()
    }
}

fun pinnedJmapGet(pin: String): (String, String) -> JmapBlobExchange = { url, auth -> platformJmapGet(url, auth, pin) }
