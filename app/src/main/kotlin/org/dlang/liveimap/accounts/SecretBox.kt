package org.dlang.liveimap.accounts

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Small secrets (SMTP passwords, the legacy account password), F20: each value sealed with Tink AES-256-GCM
 * (associated data = the entry name) in a plain SharedPreferences file. The Tink keyset lives in its own
 * SharedPreferences file, wrapped by an AndroidKeyStore key. Replaces androidx.security EncryptedSharedPreferences,
 * whose old file is not read (no legacy path; such accounts re-enter their passwords).
 * F96: an entry that cannot be opened (Keystore key lost after restore, corrupt value) reads as unset, so the
 * user is asked for the password again instead of every account failing to open.
 */
/** F96: secrets may be stored only when the keyset is wrapped by the Android Keystore. */
internal fun secretStoreUsable(usingKeystore: Boolean): Boolean = usingKeystore

internal class SecretBox(
    private val context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(secretBoxFile, Context.MODE_PRIVATE)

    private val manager: AndroidKeysetManager by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, keysetFile)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()
    }

    private val aead: Aead by lazy { manager.keysetHandle.getPrimitive(Aead::class.java) }

    /**
     * F96: Tink falls back to an unwrapped keyset when the Android Keystore key is unusable. Secrets are then
     * refused rather than stored under a key sitting in plain SharedPreferences.
     */
    private fun requireKeystore() {
        if (!secretStoreUsable(manager.isUsingKeystore)) {
            throw java.security.GeneralSecurityException("Android Keystore is unavailable; the password was not stored")
        }
    }

    fun getString(key: String, default: String?): String? {
        val stored = prefs.getString(key, null) ?: return default
        return try {
            open(key, stored)
        } catch (_: java.security.GeneralSecurityException) {
            default
        } catch (_: IllegalArgumentException) {
            default
        } catch (_: java.io.IOException) {
            default // F96: keyset unreadable; treat as unset
        } catch (_: java.security.ProviderException) {
            default
        }
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun edit(): Editor = Editor()

    inner class Editor {
        private val edit = prefs.edit()

        fun putString(key: String, value: String): Editor {
            edit.putString(key, seal(key, value))
            return this
        }

        fun remove(key: String): Editor {
            edit.remove(key)
            return this
        }

        fun commit(): Boolean = edit.commit()
    }

    private fun seal(key: String, value: String): String {
        requireKeystore()
        return Base64.encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun open(key: String, stored: String): String =
        String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    private companion object {
        const val secretBoxFile = "liveimap_secret_v2"
        const val keysetFile = "liveimap_secret_keyset"
        const val keysetName = "liveimap_secret_keyset"
        const val masterKeyUri = "android-keystore://liveimap_secret_master"
    }
}
