package org.dlang.liveimap.engine

import android.content.Context
import android.security.KeyChain
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAKey
import javax.crypto.Cipher
import org.dlang.liveimap.session.MailFailure

/** A client certificate whose key stays in the system keystore: the chain (leaf first, DER) and the key handle. */
class KeyChainCert(val chain: Array<ByteArray>, val key: PrivateKey)

/**
 * Android KeyChain client certificates (AccountSettings.clientCertAlias). The alias comes from
 * KeyChain.choosePrivateKeyAlias in account settings; at connect time the chain and a non-exportable key handle
 * are handed to native code, and OpenSSL signs through [KeyChainSigner] (JNI callback).
 *
 * An alias starting with [LAB_PREFIX] names an entry in this app's own AndroidKeyStore instead (lab runs only:
 * KeyChain's chooser needs a person; the AndroidKeyStore key is just as non-exportable, so the signing path is
 * the same).
 */
object KeyChainCerts {
    const val LAB_PREFIX = "androidkeystore:"

    @Volatile
    var appContext: Context? = null

    /** Chain and key for [alias], or null when [alias] is blank. Throws MailFailure when it is gone or not granted. */
    fun load(alias: String): KeyChainCert? {
        if (alias.isBlank()) return null
        if (alias.startsWith(LAB_PREFIX)) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val name = alias.removePrefix(LAB_PREFIX)
            val key = ks.getKey(name, null) as? PrivateKey ?: throw MailFailure("client certificate $alias missing")
            val chain = ks.getCertificateChain(name) ?: throw MailFailure("client certificate $alias has no chain")
            return KeyChainCert(chain.map { it.encoded }.toTypedArray(), key)
        }
        val ctx = appContext ?: throw MailFailure("client certificate: app not ready")
        val key = KeyChain.getPrivateKey(ctx, alias)
            ?: throw MailFailure("Android certificate \"$alias\" is gone or access was revoked; choose it again")
        val chain = KeyChain.getCertificateChain(ctx, alias)
            ?: throw MailFailure("Android certificate \"$alias\" has no certificate chain")
        return KeyChainCert(chain.map { it.encoded }.toTypedArray(), key)
    }

    /**
     * Lab only: puts the key and chain of a PKCS#12 into this app's AndroidKeyStore under [name], allowing the
     * operations TLS needs (PKCS#1 and raw RSA for PSS, ECDSA over a digest). Returns the alias to store.
     */
    fun importLab(name: String, p12: ByteArray, password: String): String {
        val src = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(p12), password.toCharArray()) }
        val entry = src.aliases().toList().first { src.isKeyEntry(it) }
        val key = src.getKey(entry, password.toCharArray()) as PrivateKey
        val chain = src.getCertificateChain(entry)
        val protection = if (key.algorithm == "EC") {
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .build()
        } else {
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build()
        }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.setEntry(name, KeyStore.PrivateKeyEntry(key, chain), protection)
        return LAB_PREFIX + name
    }

    fun subject(cert: KeyChainCert): String =
        (java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert.chain[0])) as X509Certificate).subjectX500Principal.name
}

/**
 * Called from native code (OpenSSL RSA_METHOD / EC_KEY_METHOD) during the TLS handshake to sign with a key that
 * cannot leave the keystore. [op]: 0 = RSA PKCS#1 v1.5 over a DigestInfo (TLS 1.2 rsa_pkcs1_*), 1 = raw RSA
 * private operation on an already padded block (OpenSSL applies RSA-PSS padding itself: TLS 1.3 and
 * rsa_pss_rsae_*), 2 = ECDSA over a digest (DER signature). Returns null on failure (the handshake then fails).
 */
object KeyChainSigner {
    const val RSA_PKCS1 = 0
    const val RSA_RAW = 1
    const val ECDSA = 2

    @JvmStatic
    fun sign(key: Any, op: Int, data: ByteArray): ByteArray? = try {
        val k = key as PrivateKey
        when (op) {
            RSA_PKCS1 -> java.security.Signature.getInstance("NONEwithRSA").run { initSign(k); update(data); sign() }
            RSA_RAW -> {
                val out = Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, k); doFinal(data) }
                val size = (k as? RSAKey)?.modulus?.bitLength()?.plus(7)?.div(8) ?: data.size
                if (out.size >= size) out else ByteArray(size - out.size) + out
            }
            ECDSA -> java.security.Signature.getInstance("NONEwithECDSA").run { initSign(k); update(data); sign() }
            else -> null
        }
    } catch (e: Exception) {
        android.util.Log.w("LiveIMAP", "client certificate signing failed (op $op): $e")
        null
    }
}
