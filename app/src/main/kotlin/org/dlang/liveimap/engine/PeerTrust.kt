package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PeerTrust {
    @JvmStatic
    fun check(host: String, ders: List<ByteArray>): String {
        if (ders.isEmpty()) return "empty certificate chain"
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val chain = Array(ders.size) { index ->
                factory.generateCertificate(ByteArrayInputStream(ders[index])) as X509Certificate
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            managers.init(null as KeyStore?)
            val trust = managers.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return "certificate rejected"
            val auth = chain[0].publicKey.algorithm.ifBlank { "RSA" }
            trust.checkServerTrusted(chain, auth)
            val accepted = HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(host, LeafSession(chain[0], host))
            if (!accepted) return "name mismatch"
            ""
        } catch (_: Exception) {
            "certificate rejected"
        }
    }
}

private class LeafSession(
    private val leaf: X509Certificate,
    private val host: String,
) : SSLSession {
    override fun getPeerCertificates(): Array<Certificate> = arrayOf(leaf)

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
