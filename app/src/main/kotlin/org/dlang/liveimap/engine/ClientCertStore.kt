package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID

/** A per-account TLS client certificate: the PKCS#12 bytes and their password, handed to OpenSSL as is. */
class ClientCert(val p12: ByteArray, val password: String)

/** Seals small secrets; on the device an AES-GCM key held in the Android keystore ([KeystoreSealer]). */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in AndroidKeyStore; output is IV (12 bytes) + ciphertext. */
object KeystoreSealer : Sealer {
    private const val ALIAS = "liveimap-client-cert-passwords"

    private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * Imported PKCS#12 client certificates in app-private storage (filesDir/client-certs). Each import is
 * `<id>.p12` with its password sealed by the Android keystore in `<id>.pw.sealed` beside it (design docs:
 * secrets live in the Android keystore; never in AccountSettings, so settings exports carry no secret).
 * AccountSettings.clientCert holds the .p12 path. A plaintext `<id>.pw` from before sealing is migrated on load.
 *
 * The other per-account choice, an Android KeyChain certificate, is [KeyChainCerts].
 */
object ClientCertStore {
    /** Replaced in JVM unit tests (no AndroidKeyStore there). */
    @Volatile
    var sealer: Sealer = KeystoreSealer

    /** Checks that [p12] opens with [password] and holds a private key with a certificate; returns its subject. */
    fun check(p12: ByteArray, password: String): String {
        val store = KeyStore.getInstance("PKCS12")
        try {
            store.load(ByteArrayInputStream(p12), password.toCharArray())
        } catch (e: Exception) {
            throw IllegalArgumentException("not a PKCS#12 file, or wrong password", e)
        }
        for (alias in store.aliases()) {
            if (!store.isKeyEntry(alias)) continue
            val cert = store.getCertificate(alias) as? X509Certificate ?: continue
            return cert.subjectX500Principal.name
        }
        throw IllegalArgumentException("the PKCS#12 file has no private key with a certificate")
    }

    /** Validates and stores an import under [dir]; returns the path for AccountSettings.clientCert. */
    fun import(dir: File, p12: ByteArray, password: String): String {
        check(p12, password)
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.p12")
        writePrivate(sealedFile(file), sealer.seal(password.toByteArray(Charsets.UTF_8)))
        writePrivate(file, p12)
        return file.absolutePath
    }

    /** The stored certificate at [path], or null when [path] is blank or the files are gone. */
    fun load(path: String): ClientCert? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val sealed = sealedFile(file)
        val legacy = passwordFile(file)
        if (!sealed.isFile && legacy.isFile) {
            writePrivate(sealed, sealer.seal(legacy.readBytes()))
            legacy.delete()
        }
        if (!sealed.isFile) return null
        val password = try {
            sealer.open(sealed.readBytes()).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return null  // keystore key gone (e.g. restored to another device): import again
        }
        return ClientCert(file.readBytes(), password)
    }

    fun remove(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        passwordFile(file).delete()
        sealedFile(file).delete()
        file.delete()
    }

    private fun sealedFile(p12: File) = File(p12.parentFile, p12.name.removeSuffix(".p12") + ".pw.sealed")

    private fun passwordFile(p12: File) = File(p12.parentFile, p12.name.removeSuffix(".p12") + ".pw")

    private fun writePrivate(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}
