package org.dlang.liveimap.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode

private const val logTag = "LiveIMAP"
private const val gcmIvBytes = 12
private const val gcmTagBits = 128

private val Context.accountSettingsDataStore by preferencesDataStore(name = "account_settings")

class AccountStore private constructor(private val context: Context) {
    private val gate = Mutex()
    private val cipher = AndroidAccountCipher()
    private var migrationSettled = false

    private val secretPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            legacySecretFile,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun load(): AccountSettings {
        ensureMigrated()
        return gate.withLock { withContext(Dispatchers.IO) { loadLocked() } }
    }

    suspend fun save(settings: AccountSettings) {
        ensureMigrated()
        val encoded = settings.encode()
        val writePreferences = gate.withLock {
            withContext(Dispatchers.IO) { stageSave(settings, encoded) }
        }
        writePreferences()
    }

    suspend fun password(): String {
        ensureMigrated()
        return gate.withLock { withContext(Dispatchers.IO) { passwordLocked() } }
    }

    suspend fun setPassword(value: String) {
        ensureMigrated()
        val writePreferences = gate.withLock {
            withContext(Dispatchers.IO) { stagePassword(value) }
        }
        writePreferences()
    }

    fun theme(): Flow<ThemeMode> = flow {
        ensureMigrated()
        context.accountSettingsDataStore.data.collect { prefs ->
            val text = withContext(Dispatchers.IO) { themeText(prefs) }
            val mode = if (text == null) {
                ThemeMode.FollowSystem
            } else {
                decodeAccountSettings(text).theme
            }
            emit(mode)
        }
    }

    private suspend fun ensureMigrated() {
        if (migrationSettled) return
        gate.withLock { withContext(Dispatchers.IO) { ensureMigratedLocked() } }
    }

    private suspend fun ensureMigratedLocked() {
        if (migrationSettled) return
        val outcome = migrateAccount(
            legacy = legacyAdapter(),
            accounts = accountAdapter(),
            cipher = cipher,
            log = { message -> Log.i(logTag, message) },
        )
        if (outcome != MigrationOutcome.Failed) migrationSettled = true
    }

    private fun legacyAdapter(): LegacyAccount = object : LegacyAccount {
        override suspend fun encoded(): String? = legacyEncoded()
        override suspend fun password(): String = legacyPassword()
        override suspend fun deleteEncoded() = deleteLegacyEncoded()
        override suspend fun deletePassword() = deleteLegacyPassword()
    }

    private fun accountAdapter(): AccountMap = object : AccountMap {
        override suspend fun accounts(): List<ManagedAccount> {
            val account = readAccount() ?: return emptyList()
            return listOf(account)
        }

        override suspend fun write(name: String, userData: Map<String, String>, passwordSlot: String) {
            writeAccount(name, userData, passwordSlot)
        }

        override suspend fun preference(accountId: String): String? = storedPreference(accountId)

        override suspend fun putPreference(accountId: String, value: String) {
            writePreference(accountId, value)
        }
    }

    private suspend fun loadLocked(): AccountSettings {
        val account = readAccount()
        if (account != null && useNewStoreLocked(account)) {
            val accountId = account.userData[userAccountId] ?: return AccountSettings()
            val text = storedPreference(accountId) ?: return AccountSettings()
            return overlayAccountFields(decodeAccountSettings(text), account.userData)
        }
        val text = legacyEncoded() ?: return AccountSettings()
        return decodeAccountSettings(text)
    }

    private suspend fun stageSave(
        settings: AccountSettings,
        encoded: String,
    ): suspend () -> Unit {
        if (shouldUseLegacyLocked()) return { writeLegacy(encoded) }
        val existing = readAccount()
        val accountId = existing?.userData?.get(userAccountId)?.takeIf { it.isNotEmpty() }
            ?: java.util.UUID.randomUUID().toString()
        val marker = existing?.userData?.get(userMigratedFrom)
        val name = nameFor(existing, settings, accountId)
        val slot = existing?.passwordSlot ?: ""
        writeAccount(name, accountUserData(accountId, settings, marker), slot)
        return { writePreference(accountId, encoded) }
    }

    private suspend fun passwordLocked(): String {
        if (shouldUseLegacyLocked()) return legacyPassword()
        val account = readAccount() ?: return ""
        val slot = account.passwordSlot
        if (slot.isEmpty()) return ""
        val accountId = account.userData[userAccountId] ?: return ""
        return cipher.decrypt(accountId, slot)
    }

    private suspend fun stagePassword(value: String): suspend () -> Unit {
        if (shouldUseLegacyLocked()) {
            writeLegacyPassword(value)
            return {}
        }
        val existing = readAccount()
        if (existing == null) {
            val settings = loadLocked()
            val accountId = java.util.UUID.randomUUID().toString()
            val slot = cipher.encrypt(accountId, value)
            writeAccount(accountVisibleName(settings), accountUserData(accountId, settings, null), slot)
            val encoded = settings.encode()
            return { writePreference(accountId, encoded) }
        }
        val accountId = existing.userData.getValue(userAccountId)
        val slot = cipher.encrypt(accountId, value)
        writeAccount(existing.name, existing.userData, slot)
        return {}
    }

    private fun themeText(prefs: androidx.datastore.preferences.core.Preferences): String? {
        val account = readAccount()
        val legacy = prefs[stringPreferencesKey(legacyAccountKey)]
        val accountId = account?.userData?.get(userAccountId)
        val migrated = account?.userData?.get(userMigratedFrom) == migratedFromDatastoreV1
        if (migrated && accountId != null) {
            return prefs[stringPreferencesKey(accountId)] ?: legacy
        }
        if (legacy != null) return legacy
        if (accountId != null) return prefs[stringPreferencesKey(accountId)]
        return null
    }

    private suspend fun useNewStoreLocked(account: ManagedAccount?): Boolean {
        if (account == null) return false
        if (account.userData[userMigratedFrom] == migratedFromDatastoreV1) return true
        return legacyEncoded() == null && !account.userData[userAccountId].isNullOrEmpty()
    }

    private suspend fun shouldUseLegacyLocked(): Boolean {
        val account = readAccount()
        if (account?.userData?.get(userMigratedFrom) == migratedFromDatastoreV1) return false
        return legacyEncoded() != null
    }

    private suspend fun nameFor(
        existing: ManagedAccount?,
        settings: AccountSettings,
        accountId: String,
    ): String {
        val next = accountVisibleName(settings)
        if (existing == null) return next
        val previousText = storedPreference(accountId)
        val previous = if (previousText == null) {
            existing.name
        } else {
            accountVisibleName(decodeAccountSettings(previousText))
        }
        return if (existing.name == previous) next else existing.name
    }

    private fun readAccount(): ManagedAccount? {
        val manager = AccountManager.get(context)
        val account = manager.getAccountsByType(liveimapAccountType).firstOrNull() ?: return null
        val data = linkedMapOf<String, String>()
        for (key in accountUserKeys) {
            val value = manager.getUserData(account, key) ?: continue
            data[key] = value
        }
        return ManagedAccount(account.name, data, manager.getPassword(account) ?: "")
    }

    private fun writeAccount(name: String, userData: Map<String, String>, passwordSlot: String) {
        val manager = AccountManager.get(context)
        val existing = manager.getAccountsByType(liveimapAccountType)
        val account = if (existing.isEmpty()) {
            val created = Account(name, liveimapAccountType)
            val bundle = android.os.Bundle()
            for ((key, value) in userData) bundle.putString(key, value)
            // passwordSlot is ciphertext, or empty when no password has been set.
            if (!manager.addAccountExplicitly(created, passwordSlot, bundle)) error("account not added")
            created
        } else {
            renameIfNeeded(manager, existing[0], name)
        }
        val live = manager.getAccountsByType(liveimapAccountType).firstOrNull { it.name == account.name }
            ?: account
        for (key in accountUserKeys) {
            if (userData.containsKey(key)) {
                manager.setUserData(live, key, userData.getValue(key))
            } else if (key == userMigratedFrom) {
                manager.setUserData(live, key, null)
            }
        }
        manager.setPassword(live, passwordSlot)
    }

    private fun renameIfNeeded(manager: AccountManager, account: Account, name: String): Account {
        if (account.name == name) return account
        return manager.renameAccount(account, name, null, null)
            .getResult(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private suspend fun legacyEncoded(): String? {
        return context.accountSettingsDataStore.data.first()[stringPreferencesKey(legacyAccountKey)]
    }

    private suspend fun writeLegacy(text: String) {
        context.accountSettingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(legacyAccountKey)] = text
        }
    }

    private suspend fun deleteLegacyEncoded() {
        context.accountSettingsDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(legacyAccountKey))
        }
    }

    private fun legacyPassword(): String = secretPrefs.getString(legacyPasswordKey, "") ?: ""

    private fun writeLegacyPassword(value: String) {
        val saved = secretPrefs.edit().putString(legacyPasswordKey, value).commit()
        if (!saved) error("password not saved")
    }

    private fun deleteLegacyPassword() {
        val saved = secretPrefs.edit().remove(legacyPasswordKey).commit()
        if (!saved) error("password not removed")
    }

    private suspend fun storedPreference(accountId: String): String? {
        return context.accountSettingsDataStore.data.first()[stringPreferencesKey(accountId)]
    }

    private suspend fun writePreference(accountId: String, value: String) {
        context.accountSettingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(accountId)] = value
        }
    }

    companion object {
        private val instanceGate = Any()

        @Volatile
        private var instance: AccountStore? = null

        fun shared(context: Context): AccountStore {
            instance?.let { return it }
            synchronized(instanceGate) {
                instance?.let { return it }
                return AccountStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

internal class AndroidAccountCipher : AccountCipher {
    override fun encrypt(accountId: String, password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(accountId))
        val iv = cipher.iv
        if (iv.size != gcmIvBytes) error("password unavailable")
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(combined)
        encrypted.copyInto(combined, destinationOffset = iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(accountId: String, passwordSlot: String): String {
        try {
            val combined = Base64.decode(passwordSlot, Base64.NO_WRAP)
            if (combined.size <= gcmIvBytes) error("password unavailable")
            val iv = combined.copyOfRange(0, gcmIvBytes)
            val encrypted = combined.copyOfRange(gcmIvBytes, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(accountId), GCMParameterSpec(gcmTagBits, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (failed: IllegalStateException) {
            throw failed
        } catch (_: Exception) {
            error("password unavailable")
        }
    }

    fun delete(accountId: String) {
        val keyStore = androidKeyStore()
        val alias = alias(accountId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(accountId: String): SecretKey {
        val alias = alias(accountId)
        val keyStore = androidKeyStore()
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun alias(accountId: String): String = "liveimap-$accountId"

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore
    }
}

internal fun deleteAccountArtifacts(context: Context, account: Account) {
    val appContext = context.applicationContext
    val accountId = AccountManager.get(appContext).getUserData(account, userAccountId)
    if (!accountId.isNullOrEmpty()) {
        AndroidAccountCipher().delete(accountId)
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            appContext.accountSettingsDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(accountId))
            }
        }
    }
    File(appContext.filesDir, "unsent").deleteRecursively()
    File(appContext.cacheDir, "imap-traffic.log").delete()
}
