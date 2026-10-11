package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID

/** A per-account TLS client certificate: the PKCS#12 bytes and their password, handed to OpenSSL as is. */
class ClientCert(val p12: ByteArray, val password: String)

/**
 * Imported PKCS#12 client certificates in app-private storage (filesDir/client-certs). Each import is
 * `<id>.p12` with its password in `<id>.pw` beside it (mode 0600, never in AccountSettings, so settings
 * exports carry no secret). AccountSettings.clientCert holds the .p12 path.
 *
 * TODO(later): Android KeyChain-backed keys (non-exportable) need a JNI signing callback (incl. RSA-PSS).
 */
object ClientCertStore {
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
        val pw = File(dir, "$id.pw")
        val file = File(dir, "$id.p12")
        writePrivate(pw, password.toByteArray(Charsets.UTF_8))
        writePrivate(file, p12)
        return file.absolutePath
    }

    /** The stored certificate at [path], or null when [path] is blank or the files are gone. */
    fun load(path: String): ClientCert? {
        if (path.isBlank()) return null
        val file = File(path)
        val pw = passwordFile(file)
        if (!file.isFile || !pw.isFile) return null
        return ClientCert(file.readBytes(), pw.readText(Charsets.UTF_8))
    }

    fun remove(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        passwordFile(file).delete()
        file.delete()
    }

    private fun passwordFile(p12: File) = File(p12.parentFile, p12.name.removeSuffix(".p12") + ".pw")

    private fun writePrivate(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}
