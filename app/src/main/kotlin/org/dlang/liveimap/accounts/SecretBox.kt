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
 * SharedPreferences file, wrapped by an AndroidKeyStore key. Replaces androidx.security EncryptedSharedPreferences;
 * the old file is read once (through [legacy]), copied here, then cleared and deleted.
 */
internal class SecretBox(
    private val context: Context,
    private val legacy: () -> SharedPreferences,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(secretBoxFile, Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, keysetFile)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    @Volatile private var migrated = false

    fun getString(key: String, default: String?): String? {
        migrate()
        val stored = prefs.getString(key, null) ?: return default
        return open(key, stored)
    }

    fun contains(key: String): Boolean {
        migrate()
        return prefs.contains(key)
    }

    fun edit(): Editor {
        migrate()
        return Editor()
    }

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

    private fun seal(key: String, value: String): String =
        Base64.encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    private fun open(key: String, stored: String): String =
        String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /** One-time copy from the EncryptedSharedPreferences file; the old file goes only after the copy commits. */
    @Synchronized
    private fun migrate() {
        if (migrated) return
        if (prefs.getBoolean(migratedFlag, false)) {
            migrated = true
            return
        }
        val oldFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$legacySecretFile.xml")
        if (oldFile.exists()) {
            val old = legacy()
            val edit = prefs.edit()
            for ((key, value) in old.all) {
                if (value is String) edit.putString(key, seal(key, value))
            }
            edit.putBoolean(migratedFlag, true)
            if (!edit.commit()) error("secrets not migrated")
            old.edit().clear().commit()
            context.deleteSharedPreferences(legacySecretFile)
        } else if (!prefs.edit().putBoolean(migratedFlag, true).commit()) {
            error("secrets not migrated")
        }
        migrated = true
    }

    private companion object {
        const val secretBoxFile = "liveimap_secret_v2"
        const val keysetFile = "liveimap_secret_keyset"
        const val keysetName = "liveimap_secret_keyset"
        const val masterKeyUri = "android-keystore://liveimap_secret_master"
        const val migratedFlag = "__migrated_from_encrypted_prefs"
    }
}
