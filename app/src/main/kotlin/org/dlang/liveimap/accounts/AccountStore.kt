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

    // Tink-sealed secrets (F20). No EncryptedSharedPreferences read: old secrets are not carried over (David).
    private val secretPrefs: SecretBox by lazy { SecretBox(context) }

    suspend fun load(): AccountSettings {
        return gate.withLock { withContext(Dispatchers.IO) { loadLocked() } }
    }

    suspend fun save(settings: AccountSettings) {
        val encoded = settings.encode()
        val writePreferences = gate.withLock {
            withContext(Dispatchers.IO) { stageSave(settings, encoded) }
        }
        writePreferences()
    }

    suspend fun password(): String {
        return gate.withLock { withContext(Dispatchers.IO) { passwordLocked() } }
    }

    suspend fun setPassword(value: String) {
        val writePreferences = gate.withLock {
            withContext(Dispatchers.IO) { stagePassword(value) }
        }
        writePreferences()
    }

    suspend fun smtpPassword(): String {
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val chosen = chooseAccount(accounts, selectedId())
                val id = chosen?.userData?.get(userAccountId)
                if (!id.isNullOrEmpty() && secretPrefs.contains(smtpPasswordKeyFor(id))) {
                    return@withContext secretPrefs.getString(smtpPasswordKeyFor(id), "") ?: ""
                }
                ""
            }
        }
    }

    /**
     * Settings and passwords of one account by id, without selecting it: used when a sending rule routes a
     * message through another account's servers. Null when there is no such account (multi-account store only).
     */
    suspend fun accountById(accountId: String): AccountCredentials? {
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val account = readAccounts().firstOrNull { it.userData[userAccountId] == accountId }
                    ?: return@withContext null
                val text = storedPreference(accountId) ?: return@withContext null
                val settings = overlayAccountFields(decodeAccountSettings(text), account.userData)
                val password = if (account.passwordSlot.isEmpty()) "" else cipher.decrypt(accountId, account.passwordSlot)
                val smtp = secretPrefs.getString(smtpPasswordKeyFor(accountId), "") ?: ""
                AccountCredentials(accountId, settings, password, smtp)
            }
        }
    }

    suspend fun setSmtpPassword(value: String) {
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
        gate.withLock {
            withContext(Dispatchers.IO) {
                val known = readAccounts().any { it.userData[userAccountId] == accountId }
                if (!known) return@withContext
                writeSelectedId(accountId)
            }
        }
    }

    suspend fun addAccount() {
        gate.withLock {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
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
                val stored = storedPreference(accountId)
                if (!manager.removeAccountExplicitly(account)) return@withContext false
                removeAccountFiles(context, accountId, stored)
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

    private suspend fun loadLocked(): AccountSettings {
        val accounts = readAccounts()
        val account = chooseAccount(accounts, selectedId())
        val accountId = account?.userData?.get(userAccountId) ?: return newAccountSettings()
        val text = storedPreference(accountId) ?: return newAccountSettings()
        return overlayAccountFields(decodeAccountSettings(text), account.userData)
    }

    private suspend fun stageSave(
        settings: AccountSettings,
        encoded: String,
    ): suspend () -> Unit {
        val existing = chooseAccount(readAccounts(), selectedId())
        val accountId = existing?.userData?.get(userAccountId)?.takeIf { it.isNotEmpty() }
            ?: java.util.UUID.randomUUID().toString()
        val name = nameFor(existing, settings, accountId)
        val slot = existing?.passwordSlot ?: ""
        writeAccount(name, accountUserData(accountId, settings, null), slot)
        return { writePreference(accountId, encoded) }
    }

    private suspend fun passwordLocked(): String {
        val account = chooseAccount(readAccounts(), selectedId()) ?: return ""
        val slot = account.passwordSlot
        if (slot.isEmpty()) return ""
        val accountId = account.userData[userAccountId] ?: return ""
        return cipher.decrypt(accountId, slot)
    }

    private suspend fun stagePassword(value: String): suspend () -> Unit {
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
        val accountId = account?.userData?.get(userAccountId) ?: return null
        return prefs[stringPreferencesKey(accountId)]
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

    private suspend fun storedPreference(accountId: String): String? {
        return context.accountSettingsDataStore.data.first()[stringPreferencesKey(accountId)]
    }

    private suspend fun writePreference(accountId: String, value: String) {
        context.accountSettingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(accountId)] = value
        }
    }

    /** Drops one account's SMTP password (system-initiated removal, F29). */
    internal fun forgetSmtpPassword(accountId: String) {
        secretPrefs.edit().remove(smtpPasswordKeyFor(accountId)).commit()
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
        // F29: the same clean-up as the in-app removal: SMTP password, client cert, imported key, image trust.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val stored = appContext.accountSettingsDataStore.data.first()[stringPreferencesKey(accountId)]
            removeAccountFiles(appContext, accountId, stored)
            AccountStore.shared(appContext).forgetSmtpPassword(accountId)
        }
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            appContext.accountSettingsDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(accountId))
            }
        }
        deleteUnsentAccount(appContext.filesDir, accountId)
    }
    File(appContext.cacheDir, "imap-traffic.log").delete()
}

/** One account's settings and secrets, for sending through it (see AccountStore.accountById). */
class AccountCredentials(
    val accountId: String,
    val settings: AccountSettings,
    val password: String,
    val smtpPassword: String,
) {
    override fun toString(): String = "AccountCredentials($accountId)"
}

/**
 * F29: files and keys one account leaves outside AccountManager and DataStore. Both removal paths (in-app and
 * Settings > Accounts) call this. [stored] is the account's encoded settings, read before they are deleted.
 */
internal fun removeAccountFiles(context: Context, accountId: String, stored: String?) {
    val settings = stored?.let { runCatching { decodeAccountSettings(it) }.getOrNull() }
    if (settings != null) {
        if (settings.clientCert.isNotBlank()) runCatching { org.dlang.liveimap.engine.ClientCertStore.remove(settings.clientCert) }
        if (settings.clientCertAlias.isNotBlank()) org.dlang.liveimap.engine.KeyChainCerts.removeImported(settings.clientCertAlias)
    }
    org.dlang.liveimap.ui.reader.imageTrustFile(context.filesDir, accountId)?.delete()
}
