package org.dlang.liveimap.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.accountSettingsDataStore by preferencesDataStore(name = "account_settings")

private val accountKey = stringPreferencesKey("account")

interface SettingsStore {
    suspend fun load(): AccountSettings
    suspend fun save(settings: AccountSettings)
    suspend fun password(): String
    suspend fun setPassword(value: String)
    fun theme(): Flow<ThemeMode> = flowOf(ThemeMode.FollowSystem)
}

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val appContext = context.applicationContext

    private val secretPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "liveimap_secret",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun load(): AccountSettings {
        val text = appContext.accountSettingsDataStore.data.first()[accountKey]
        if (text == null) return AccountSettings()
        return decodeAccountSettings(text)
    }

    override suspend fun save(settings: AccountSettings) {
        val encoded = settings.encode()
        appContext.accountSettingsDataStore.edit { prefs ->
            prefs[accountKey] = encoded
        }
    }

    override suspend fun password(): String = withContext(Dispatchers.IO) {
        secretPrefs.getString("password", "") ?: ""
    }

    override suspend fun setPassword(value: String) {
        withContext(Dispatchers.IO) {
            val saved = secretPrefs.edit().putString("password", value).commit()
            if (!saved) error("password not saved")
        }
    }

    override fun theme(): Flow<ThemeMode> =
        appContext.accountSettingsDataStore.data.map { prefs ->
            val text = prefs[accountKey]
            if (text == null) {
                ThemeMode.FollowSystem
            } else {
                decodeAccountSettings(text).theme
            }
        }
}
