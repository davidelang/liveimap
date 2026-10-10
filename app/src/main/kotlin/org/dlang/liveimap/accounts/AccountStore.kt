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
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.ui.compose.deleteUnsentAccount
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.newAccountSettings
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.settings.accountDrawerFolders
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

    suspend fun smtpPassword(): String {
        ensureMigrated()
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val chosen = chooseAccount(accounts, selectedId())
                val id = chosen?.userData?.get(userAccountId)
                if (!id.isNullOrEmpty() && secretPrefs.contains(smtpPasswordKeyFor(id))) {
                    return@withContext secretPrefs.getString(smtpPasswordKeyFor(id), "") ?: ""
                }
                if (accounts.size == 1) {
                    return@withContext secretPrefs.getString(smtpPasswordKey, "") ?: ""
                }
                ""
            }
        }
    }

    suspend fun setSmtpPassword(value: String) {
        ensureMigrated()
        gate.withLock {
            withContext(Dispatchers.IO) {
                val id = smtpAccountId()
                val key = smtpPasswordKeyFor(id)
                val edit = secretPrefs.edit()
                val saved = if (value.isEmpty()) {
                    edit.remove(key).commit()
                } else {
                    edit.putString(key, value).commit()
                }
                if (!saved) error("smtp password not saved")
            }
        }
    }

    suspend fun listAccounts(): List<AccountChoice> {
        ensureMigrated()
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val chosen = chooseAccount(accounts, selectedId())
                accounts.mapNotNull { account ->
                    val id = account.userData[userAccountId]
                    if (id.isNullOrEmpty()) return@mapNotNull null
                    AccountChoice(
                        id = id,
                        name = listedName(account),
                        chosen = account === chosen,
                    )
                }
            }
        }
    }

    suspend fun listDrawerAccounts(): List<DrawerAccount> {
        ensureMigrated()
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val chosen = chooseAccount(accounts, selectedId())
                accounts.mapNotNull { account ->
                    val id = account.userData[userAccountId]
                    if (id.isNullOrEmpty()) return@mapNotNull null
                    val folders = accountDrawerFolders(storedPreference(id))
                    DrawerAccount(
                        id = id,
                        name = listedName(account),
                        chosen = account === chosen,
                        postponedMailbox = folders.postponedMailbox,
                        favorites = folders.favorites,
                    )
                }
            }
        }
    }

    suspend fun selectAccount(accountId: String) {
        if (accountId.isBlank()) return
        ensureMigrated()
        gate.withLock {
            withContext(Dispatchers.IO) {
                val known = readAccounts().any { it.userData[userAccountId] == accountId }
                if (!known) return@withContext
                writeSelectedId(accountId)
            }
        }
    }

    suspend fun addAccount() {
        ensureMigrated()
        gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                if (accounts.size == 1) {
                    copyLegacySmtpPassword(accounts[0])
                }
                val settings = newAccountSettings()
                val accountId = java.util.UUID.randomUUID().toString()
                writeAccount(
                    accountVisibleName(settings),
                    accountUserData(accountId, settings, null),
                    "",
                    forceInsert = true,
                )
                writePreference(accountId, settings.encode())
                writeSelectedId(accountId)
            }
        }
    }

    suspend fun removeAccount(accountId: String): Boolean {
        ensureMigrated()
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val selected = selectedId()
                val decision = accountRemoval(accounts, accountId, selected)
                if (!decision.remove) return@withContext false
                val manager = AccountManager.get(context)
                val account = manager.getAccountsByType(liveimapAccountType).firstOrNull { row ->
                    manager.getUserData(row, userAccountId) == accountId
                } ?: return@withContext false
                if (!manager.removeAccountExplicitly(account)) return@withContext false
                deleteUnsentAccount(context.filesDir, accountId)
                AndroidAccountCipher().delete(accountId)
                context.accountSettingsDataStore.edit { prefs ->
                    prefs.remove(stringPreferencesKey(accountId))
                }
                if (decision.selectedId != selected) {
                    val next = decision.selectedId
                    if (next.isNullOrEmpty()) {
                        context.accountSettingsDataStore.edit { prefs ->
                            prefs.remove(stringPreferencesKey(selectedAccountIdKey))
                        }
                    } else {
                        writeSelectedId(next)
                    }
                }
                val saved = secretPrefs.edit().remove(smtpPasswordKeyFor(accountId)).commit()
                if (!saved) error("smtp password not removed")
                true
            }
        }
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
        override suspend fun accounts(): List<ManagedAccount> = readAccounts()

        override suspend fun write(name: String, userData: Map<String, String>, passwordSlot: String) {
            writeAccount(name, userData, passwordSlot)
        }

        override suspend fun preference(accountId: String): String? = storedPreference(accountId)

        override suspend fun putPreference(accountId: String, value: String) {
            writePreference(accountId, value)
        }
    }

    private suspend fun loadLocked(): AccountSettings {
        val accounts = readAccounts()
        val account = chooseAccount(accounts, selectedId())
        if (account != null && (useNewStoreLocked(account) || accounts.size > 1)) {
            val accountId = account.userData[userAccountId] ?: return newAccountSettings()
            val text = storedPreference(accountId) ?: return newAccountSettings()
            return overlayAccountFields(decodeAccountSettings(text), account.userData)
        }
        val text = legacyEncoded() ?: return newAccountSettings()
        return decodeAccountSettings(text)
    }

    private suspend fun stageSave(
        settings: AccountSettings,
        encoded: String,
    ): suspend () -> Unit {
        if (shouldUseLegacyLocked()) return { writeLegacy(encoded) }
        val existing = chooseAccount(readAccounts(), selectedId())
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
        val account = chooseAccount(readAccounts(), selectedId()) ?: return ""
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
        val existing = chooseAccount(readAccounts(), selectedId())
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
        val account = chooseAccount(readAccounts(), prefs[stringPreferencesKey(selectedAccountIdKey)])
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
        val accounts = readAccounts()
        if (accounts.size > 1) return false
        val account = chooseAccount(accounts, selectedId())
        if (useNewStoreLocked(account)) return false
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

    private fun readAccounts(): List<ManagedAccount> {
        val manager = AccountManager.get(context)
        return manager.getAccountsByType(liveimapAccountType).map { account ->
            val data = linkedMapOf<String, String>()
            for (key in accountUserKeys) {
                val value = manager.getUserData(account, key) ?: continue
                data[key] = value
            }
            ManagedAccount(account.name, data, manager.getPassword(account) ?: "")
        }
    }

    private fun writeAccount(
        name: String,
        userData: Map<String, String>,
        passwordSlot: String,
        forceInsert: Boolean = false,
    ) {
        val manager = AccountManager.get(context)
        val accountId = userData[userAccountId]
        val existing = if (forceInsert) null else findUpdatable(manager, accountId)
        val account = if (existing == null) {
            val createdName = uniqueAccountName(manager, name, accountId.orEmpty())
            val created = Account(createdName, liveimapAccountType)
            val bundle = android.os.Bundle()
            for ((key, value) in userData) bundle.putString(key, value)
            // passwordSlot is ciphertext, or empty when no password has been set.
            if (!manager.addAccountExplicitly(created, passwordSlot, bundle)) error("account not added")
            created
        } else {
            renameIfNeeded(manager, existing, name)
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

    private fun findUpdatable(manager: AccountManager, accountId: String?): Account? {
        val existing = manager.getAccountsByType(liveimapAccountType)
        if (!accountId.isNullOrEmpty()) {
            existing.firstOrNull { manager.getUserData(it, userAccountId) == accountId }?.let { return it }
        }
        if (existing.size == 1 && manager.getUserData(existing[0], userAccountId).isNullOrEmpty()) {
            return existing[0]
        }
        return null
    }

    private fun uniqueAccountName(manager: AccountManager, name: String, accountId: String): String {
        val taken = manager.getAccountsByType(liveimapAccountType).map { it.name }.toSet()
        if (name.isNotEmpty() && name !in taken) return name
        if (accountId.isNotEmpty() && accountId !in taken) return accountId
        val base = accountId.ifEmpty { "account" }
        var suffix = 2
        var candidate = "$base-$suffix"
        while (candidate in taken) {
            suffix += 1
            candidate = "$base-$suffix"
        }
        return candidate
    }

    private fun renameIfNeeded(manager: AccountManager, account: Account, name: String): Account {
        if (account.name == name || name.isEmpty()) return account
        if (manager.getAccountsByType(liveimapAccountType).any { it.name == name }) return account
        return manager.renameAccount(account, name, null, null)
            .getResult(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun listedName(account: ManagedAccount): String {
        val email = account.userData[userEmail].orEmpty()
        if (email.isNotEmpty()) return email
        return "${account.userData[userUsername].orEmpty()}@${account.userData[userImapHost].orEmpty()}"
    }

    private suspend fun selectedId(): String? {
        return context.accountSettingsDataStore.data.first()[stringPreferencesKey(selectedAccountIdKey)]
    }

    private suspend fun writeSelectedId(accountId: String) {
        context.accountSettingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(selectedAccountIdKey)] = accountId
        }
    }

    private suspend fun smtpAccountId(): String {
        val accounts = readAccounts()
        val chosenId = chooseAccount(accounts, selectedId())
            ?.userData
            ?.get(userAccountId)
            ?.takeIf { it.isNotEmpty() }
        if (chosenId != null) return chosenId
        if (accounts.isNotEmpty()) error("smtp password not saved")
        val settings = loadLocked()
        val accountId = java.util.UUID.randomUUID().toString()
        writeAccount(accountVisibleName(settings), accountUserData(accountId, settings, null), "")
        return accountId
    }

    private fun copyLegacySmtpPassword(account: ManagedAccount) {
        val accountId = account.userData[userAccountId]
        if (accountId.isNullOrEmpty()) return
        val perId = smtpPasswordKeyFor(accountId)
        val legacy = secretPrefs.getString(smtpPasswordKey, "") ?: ""
        if (secretPrefs.contains(perId) || legacy.isEmpty()) return
        val saved = secretPrefs.edit().putString(perId, legacy).commit()
        if (!saved) error("smtp password not saved")
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
        deleteUnsentAccount(appContext.filesDir, accountId)
    }
    File(appContext.cacheDir, "imap-traffic.log").delete()
}
