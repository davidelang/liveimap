package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PeerTrust {
    @JvmStatic
    fun check(host: String, ders: List<ByteArray>, pin: String): String {
        if (ders.isEmpty()) return "empty certificate chain"
        val chain = try {
            val factory = CertificateFactory.getInstance("X.509")
            Array(ders.size) { index ->
                factory.generateCertificate(ByteArrayInputStream(ders[index])) as X509Certificate
            }
        } catch (_: Exception) {
            return "certificate rejected"
        }
        val leaf = chain[0]
        if (!nameMatches(host, leaf)) return "name mismatch"
        try {
            leaf.checkValidity()
        } catch (_: CertificateExpiredException) {
            return "certificate expired"
        } catch (_: CertificateNotYetValidException) {
            return "certificate expired"
        }
        val normalized = normalizePin(pin)
        val fingerprint = fingerprintOf(leaf)
        if (normalized.isNotEmpty() && normalized == fingerprint) return ""
        if (normalized.isNotEmpty()) return "certificate changed"
        val managers = try {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        } catch (_: Exception) {
            return "certificate rejected"
        }
        try {
            managers.init(null as KeyStore?)
        } catch (_: Exception) {
            return "certificate rejected"
        }
        val trust = managers.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return "certificate rejected"
        val auth = leaf.publicKey.algorithm.ifBlank { "RSA" }
        return try {
            trust.checkServerTrusted(chain, auth)
            ""
        } catch (_: Exception) {
            "certificate untrusted"
        }
    }

    @JvmStatic
    fun offer(ders: List<ByteArray>): Array<String>? {
        if (ders.isEmpty()) return null
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val leaf = factory.generateCertificate(ByteArrayInputStream(ders[0])) as X509Certificate
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            arrayOf(
                leaf.subjectX500Principal.name,
                leaf.issuerX500Principal.name,
                format.format(leaf.notBefore),
                format.format(leaf.notAfter),
                fingerprintOf(leaf),
            )
        } catch (_: Exception) {
            null
        }
    }
}

private const val FINGERPRINT_HEX = "0123456789abcdef"

private fun nameMatches(host: String, leaf: X509Certificate): Boolean {
    val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
    val platform = try {
        verifier.verify(host, LeafSession(leaf, host))
    } catch (_: Exception) {
        false
    }
    if (platform) return true
    // The JDK default verifier rejects every name. Android replaces that verifier.
    if (!verifier.javaClass.name.endsWith("DefaultHostnameVerifier")) return false
    return leafNameMatches(host, leaf)
}

private fun leafNameMatches(host: String, leaf: X509Certificate): Boolean {
    val wanted = host.trim().lowercase()
    val sans = try {
        leaf.subjectAlternativeNames
    } catch (_: Exception) {
        null
    }
    var sawDns = false
    if (sans != null) {
        for (san in sans) {
            if (san.size < 2) continue
            val type = (san[0] as? Number)?.toInt() ?: continue
            if (type != 2) continue
            sawDns = true
            val name = san[1]?.toString()?.trim()?.lowercase() ?: continue
            if (name == wanted) return true
        }
    }
    if (sawDns) return false
    val cn = leaf.subjectX500Principal.name.split(",").firstNotNullOfOrNull { part ->
        val trimmed = part.trim()
        if (trimmed.startsWith("CN=", ignoreCase = true)) trimmed.substring(3).trim() else null
    }
    return cn != null && cn.lowercase() == wanted
}

private fun normalizePin(pin: String): String =
    pin.trim().replace(" ", "").replace(":", "").lowercase()

private fun fingerprintOf(leaf: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(FINGERPRINT_HEX[value ushr 4])
        out.append(FINGERPRINT_HEX[value and 0x0F])
    }
    return out.toString()
}

private class LeafSession(
    private val leaf: X509Certificate,
    private val host: String,
) : SSLSession {
    override fun getPeerCertificates(): Array<Certificate> = arrayOf(leaf)

    @Suppress("DEPRECATION")
    override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
        throw SSLPeerUnverifiedException("peer certificate chain")

    override fun getPeerPrincipal(): Principal = leaf.subjectX500Principal

    override fun getPeerHost(): String = host

    override fun getId(): ByteArray = ByteArray(0)

    override fun getSessionContext(): SSLSessionContext? = null

    override fun getCreationTime(): Long = 0

    override fun getLastAccessedTime(): Long = 0

    override fun invalidate() = Unit

    override fun isValid(): Boolean = true

    override fun putValue(name: String?, value: Any?) = Unit

    override fun getValue(name: String?): Any? = null

    override fun removeValue(name: String?) = Unit

    override fun getValueNames(): Array<String> = emptyArray()

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getLocalPrincipal(): Principal? = null

    override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"

    override fun getProtocol(): String = "TLSv1.3"

    override fun getPeerPort(): Int = -1

    override fun getPacketBufferSize(): Int = 0

    override fun getApplicationBufferSize(): Int = 0
}
