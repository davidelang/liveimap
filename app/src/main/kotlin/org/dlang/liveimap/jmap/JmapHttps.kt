package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI
import javax.net.ssl.HttpsURLConnection

interface JmapHttpExchange {
    val status: Int

    fun peerDer(): List<ByteArray>

    fun header(name: String): String?

    fun body(): String

    fun close()
}

class JmapHttpsFetch(
    private val pin: String,
    private val open: (String) -> JmapHttpExchange,
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) : JmapFetch {
    override fun get(url: String): JmapHttpResult {
        if (!httpsUrl(url)) throw JmapFailure("jmap fetch is not https")
        var current = url
        var followed = 0
        while (true) {
            val host = jmapTrustHost(current)
            val exchange = open(current)
            try {
                val verdict = trust(host, exchange.peerDer(), pin)
                if (verdict.isNotEmpty()) throw JmapFailure(verdict)
                val status = exchange.status
                if (status !in jmapRedirectStatuses) {
                    return JmapHttpResult(status, exchange.body(), current)
                }
                if (followed == 5) throw JmapFailure("jmap redirect limit")
                val location = exchange.header("Location")
                if (location.isNullOrBlank()) throw JmapFailure("jmap redirect lacks location")
                val next = URI(current).resolve(location).toString()
                if (!httpsUrl(next)) throw JmapFailure("jmap redirect is not https")
                current = next
                followed += 1
            } finally {
                exchange.close()
            }
        }
    }
}

fun platformJmapExchange(url: String): JmapHttpExchange {
    val connection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
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
            connection.serverCertificates.map { it.encoded }

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun body(): String {
            val stream = if (connection.responseCode < 400) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            if (stream == null) return ""
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
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
