package org.dlang.liveimap.accounts

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.dlang.liveimap.engine.ImportedKeyStillPresent
import org.dlang.liveimap.session.MailFailure

private const val cipherLogTag = "LiveIMAP"
private const val gcmIvBytes = 12
private const val gcmTagBits = 128

internal class SecretUnreadable : Exception("password unavailable")

internal class AndroidAccountCipher {
    fun encrypt(accountId: String, password: String, regenerate: Boolean = false): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(accountId, regenerate))
        val iv = cipher.iv
        if (iv.size != gcmIvBytes) error("password unavailable")
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(combined)
        encrypted.copyInto(combined, destinationOffset = iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(accountId: String, passwordSlot: String): String {
        try {
            val combined = Base64.decode(passwordSlot, Base64.NO_WRAP)
            if (combined.size <= gcmIvBytes) throw SecretUnreadable()
            val iv = combined.copyOfRange(0, gcmIvBytes)
            val encrypted = combined.copyOfRange(gcmIvBytes, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(accountId, regenerate = false), GCMParameterSpec(gcmTagBits, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (locked: MailFailure) {
            throw locked
        } catch (failed: SecretUnreadable) {
            throw failed
        } catch (error: Exception) {
            if (deviceLocked(error)) throw MailFailure("device locked")
            throw SecretUnreadable()
        }
    }

    fun delete(accountId: String) {
        val keyStore = androidKeyStore()
        val name = alias(accountId)
        if (!keyStore.containsAlias(name)) return
        keyStore.deleteEntry(name)
        if (keyStore.containsAlias(name)) throw ImportedKeyStillPresent(name)
    }

    private fun key(accountId: String, regenerate: Boolean): SecretKey {
        val name = alias(accountId)
        val keyStore = androidKeyStore()
        if (regenerate && keyStore.containsAlias(name)) keyStore.deleteEntry(name)
        if (!regenerate) {
            val existing = keyStore.getKey(name, null) as? SecretKey
            if (existing != null) return existing
        }
        return generate(name, accountId)
    }

    private fun generate(alias: String, accountId: String): SecretKey {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(spec(alias, strongBox = true))
                val created = generator.generateKey()
                Log.i(cipherLogTag, "password key $accountId: StrongBox unlocked-device")
                return created
            } catch (_: StrongBoxUnavailableException) {
                val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(spec(alias, strongBox = false))
                val created = generator.generateKey()
                Log.i(cipherLogTag, "password key $accountId: TEE unlocked-device")
                return created
            }
        }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec(alias, strongBox = false))
        val created = generator.generateKey()
        Log.i(cipherLogTag, "password key $accountId: TEE after-first-unlock")
        return created
    }

    private fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun alias(accountId: String): String = "liveimap-$accountId"

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore
    }
}

private fun deviceLocked(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val name = current.javaClass.name
        if (name.endsWith("UserNotAuthenticatedException")) return true
        val message = current.message.orEmpty()
        if (message.contains("User authentication required") || message.contains("device locked", ignoreCase = true)) {
            return true
        }
        current = current.cause
    }
    return false
}
