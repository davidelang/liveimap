package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.dlang.liveimap.BuildConfig

/**
 * Lab-runner hooks (debug source set only; release has a stub that never overrides or trusts anything).
 * Only the instrumented lab suite (androidTest) sets these; app code never does.
 * Gate: the override applies only in a debug build, and only when the account's IMAP host is
 * exactly the lab host the suite named. A release build or any other host reads the stored password.
 */
internal object LabTestHooks {
    @Volatile
    private var host: String? = null

    @Volatile
    private var password: String? = null

    @Volatile
    private var labCa: X509Certificate? = null

    fun set(labHost: String, labPassword: String) {
        host = labHost
        password = labPassword
    }

    /** The lab CA (DER). Trusted only under the same gate as the password: debug build and the lab host. */
    fun setLabCa(der: ByteArray) {
        labCa = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** Lab only: cap native TLS at 1.2 (for servers whose SASL only has tls-unique). Ignored in release builds. */
    fun setMaxTls12(on: Boolean) {
        if (!BuildConfig.DEBUG) return
        System.loadLibrary("liveimap")
        nativeSetMaxTls12(on)
    }

    private external fun nativeSetMaxTls12(on: Boolean)

    fun clear() {
        if (BuildConfig.DEBUG) runCatching { nativeSetMaxTls12(false) }
        host = null
        password = null
        labCa = null
    }

    fun trustsLabLeaf(imapHost: String, leaf: X509Certificate): Boolean =
        trustsLabLeaf(imapHost, leaf, BuildConfig.DEBUG)

    internal fun trustsLabLeaf(imapHost: String, leaf: X509Certificate, debug: Boolean): Boolean {
        val ca = labCa ?: return false
        val lab = host ?: return false
        if (!debug || lab.isEmpty() || imapHost != lab) return false
        return try {
            leaf.verify(ca.publicKey)
            leaf.issuerX500Principal == ca.subjectX500Principal
        } catch (_: Exception) {
            false
        }
    }

    fun passwordFor(imapHost: String): String? =
        passwordFor(imapHost, BuildConfig.DEBUG)

    internal fun passwordFor(imapHost: String, debug: Boolean): String? {
        val lab = host ?: return null
        if (!debug || lab.isEmpty() || imapHost != lab) return null
        return password
    }
}
