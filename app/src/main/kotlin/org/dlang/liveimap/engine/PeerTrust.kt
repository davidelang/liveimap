package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
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
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, chain[0])) {
                return "name mismatch"
            }
            ""
        } catch (_: Exception) {
            "certificate rejected"
        }
    }
}
