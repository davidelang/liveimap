package org.dlang.liveimap.accounts

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.ImportedKeyStillPresent
import org.dlang.liveimap.engine.KeyChainCerts
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.jmap.JmapFailure
import org.dlang.liveimap.jmap.JmapPlainHttp
import org.dlang.liveimap.jmap.jmapServerTarget
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.ui.compose.deleteUnsentAccount
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.getSetting
import org.dlang.liveimap.settings.withSetting
import org.dlang.liveimap.settings.withUiOf
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.newAccountSettings
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.session.dropMailSession
import org.dlang.liveimap.ui.contacts.forgetCompletionCaches
import org.dlang.liveimap.settings.accountDrawerFolders
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode

private const val logTag = "LiveIMAP"

internal val Context.accountSettingsDataStore by preferencesDataStore(name = "account_settings")

suspend fun accountSettingsStoredKeys(context: Context): Set<String> =
    context.accountSettingsDataStore.data.first().asMap().keys.map { it.name }.toSet()

class AccountStore private constructor(private val context: Context) {
    private val accountCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val accountActor = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = AndroidAccountCipher()

    init {
        accountActor.launch {
            for (command in accountCommands) {
                try {
                    command()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                }
            }
        }
    }

    private class OnAccount(val owner: AccountStore) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OnAccount>
    }

    internal suspend fun <T> onAccount(block: suspend () -> T): T = onAccountGated(block, null)

    /**
     * [publish] runs on the caller, before the next command starts.
     * It must not call [onAccount]: the actor is waiting for this call to return.
     * The block stays on the actor. Hopping to Main from there deadlocks, because the caller is
     * already on Main waiting for the command.
     */
    internal suspend fun <T> onAccount(publish: (T) -> Unit, block: suspend () -> T): T =
        onAccountGated(block, publish)

    private suspend fun <T> onAccountGated(block: suspend () -> T, publish: ((T) -> Unit)?): T {
        if (coroutineContext[OnAccount]?.owner === this) {
            val value = block()
            publish?.invoke(value)
            return value
        }
        val carried = coroutineContext.minusKey(Job).minusKey(ContinuationInterceptor)
        val done = CompletableDeferred<T>()
        val release = CompletableDeferred<Unit>()
        accountCommands.send {
            try {
                val value = withContext(carried + OnAccount(this@AccountStore)) { block() }
                done.complete(value)
            } catch (error: Throwable) {
                done.completeExceptionally(error)
                release.complete(Unit)
                return@send
            }
            // The caller publishes Compose state, then releases. The next command sees that write.
            release.await()
        }
        try {
            val value = done.await()
            publish?.invoke(value)
            return value
        } finally {
            release.complete(Unit)
        }
    }

    private fun abandon() {
        accountActor.cancel()
        accountCommands.close()
    }

    suspend fun load(): Settings {
        return onAccount { withContext(Dispatchers.IO) { loadLocked() } }
    }

    suspend fun save(settings: Settings) {
        val encoded = settings.encode()
        try {
            onAccount {
                val writePreferences = withContext(Dispatchers.IO) { stageSave(settings, encoded) }
                writePreferences()
                val id = parseAccountId(coroutineContext[AccountTarget]?.accountId)
                    ?: parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
                if (id != null) {
                    org.dlang.liveimap.settings.SettingsHub.replaceAccount(
                        id,
                        settings.withUiOf(org.dlang.liveimap.settings.SettingsHub.uiNow()),
                    )
                }
            }
        } catch (gone: AccountGone) {
            Log.w(logTag, "save dropped, account gone ${gone.accountId}", gone)
        } catch (missing: NoAccountSelected) {
            Log.w(logTag, "save dropped, no account selected", missing)
        }
    }

    /** F55/F66: read-modify-write of the (target) account's settings in one step under the store lock. */
    suspend fun update(edit: (Settings) -> Settings): Settings {
        var next: Settings = newAccountSettings()
        try {
            onAccount {
                val writePreferences = withContext(Dispatchers.IO) {
                    val updated = edit(loadLocked())
                    next = updated
                    stageSave(updated, updated.encode())
                }
                writePreferences()
                val id = parseAccountId(coroutineContext[AccountTarget]?.accountId)
                    ?: parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
                if (id != null) org.dlang.liveimap.settings.SettingsHub.replaceAccount(id, next)
            }
        } catch (gone: AccountGone) {
            Log.w(logTag, "update dropped, account gone ${gone.accountId}", gone)
        } catch (missing: NoAccountSelected) {
            Log.w(logTag, "update dropped, no account selected", missing)
        }
        return next
    }

    /**
     * Overlays [keys] from [draft] onto the blob stored now. UI keys go to the global record.
     * A throw leaves the stored blobs as they were and does not publish.
     */
    suspend fun commitKeys(draft: org.dlang.liveimap.settings.Settings, keys: Set<String>): org.dlang.liveimap.settings.Settings {
        if (keys.isEmpty()) return draft
        val registry = org.dlang.liveimap.settings.settingsRegistry
        val uiKeys = keys.filter { registry[it]?.scope == org.dlang.liveimap.settings.SettingScope.Ui }.toSet()
        val accountKeys = keys.filter { key ->
            val scope = registry[key]?.scope
            scope != null && scope != org.dlang.liveimap.settings.SettingScope.Ui
        }.toSet()
        var published = draft
        var writtenId: String? = null
        var previousText: String? = null
        if (accountKeys.isNotEmpty()) {
            onAccount {
                val writePreferences = withContext(Dispatchers.IO) {
                    var current = loadLocked()
                    for (key in accountKeys) {
                        current = current.withSetting(key, org.dlang.liveimap.settings.SettingValue(draft.getSetting(key)))
                    }
                    published = current
                    val id = parseAccountId(coroutineContext[AccountTarget]?.accountId)
                        ?: parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
                        ?: throw NoAccountSelected()
                    writtenId = id
                    previousText = storedPreference(id)
                    stageSave(current, current.encode())
                }
                writePreferences()
                if (uiKeys.isEmpty()) {
                    org.dlang.liveimap.settings.SettingsHub.replaceAccount(writtenId ?: return@onAccount, published)
                }
            }
        }
        if (uiKeys.isNotEmpty()) {
            try {
                org.dlang.liveimap.settings.SettingsHub.saveUiKeys(context, draft, uiKeys)
            } catch (error: Throwable) {
                val id = writtenId
                val previous = previousText
                if (id != null && previous != null) {
                    onAccount { withContext(Dispatchers.IO) { writePreference(id, previous) } }
                }
                throw error
            }
            val id = writtenId
            if (id != null) org.dlang.liveimap.settings.SettingsHub.replaceAccount(id, published)
        }
        return published
    }

    suspend fun password(): String? {
        return onAccount { withContext(Dispatchers.IO) { passwordLocked() } }
    }

    suspend fun setPassword(value: String) {
        try {
            onAccount {
                val accountId = withContext(Dispatchers.IO) {
                    val writePreferences = stagePassword(value)
                    writePreferences()
                    smtpAccountId()
                }
                withContext(Dispatchers.IO) { refreshBackup(accountId, value) }
            }
        } catch (gone: AccountGone) {
            Log.w(logTag, "password dropped, account gone ${gone.accountId}", gone)
        } catch (missing: NoAccountSelected) {
            Log.w(logTag, "password dropped, no account selected", missing)
        }
    }

    suspend fun smtpPassword(): String? = secretPassword(userSmtpPasswordSlot)

    suspend fun sievePassword(accountId: String): String? {
        return onAccount { withContext(Dispatchers.IO) { readSecretSlot(accountId, userSievePasswordSlot) } }
    }

    suspend fun setSievePassword(accountId: String, value: String?) {
        onAccount {
            withContext(Dispatchers.IO) { writeSecretSlot(accountId, userSievePasswordSlot, value) }
            withContext(Dispatchers.IO) { refreshBackup(accountId, null) }
        }
    }

    private suspend fun secretPassword(key: String): String? {
        return onAccount {
            withContext(Dispatchers.IO) {
                val chosen = pick(readAccounts())
                val id = chosen?.userData?.get(userAccountId)
                if (id.isNullOrEmpty()) "" else readSecretSlot(id, key)
            }
        }
    }

    /**
     * Settings and passwords of one account by id, without selecting it: used when a sending rule routes a
     * message through another account's servers. Null when there is no such account. A missing or unreadable
     * IMAP secret is a null password, which a send reports as password missing.
     */
    suspend fun accountById(accountId: String): AccountCredentials? {
        val id = parseAccountId(accountId) ?: return null
        return onAccount {
            withContext(Dispatchers.IO) {
                val account = readAccounts().firstOrNull { parseAccountId(it.userData[userAccountId]) == id }
                    ?: return@withContext null
                val text = storedPreference(id) ?: return@withContext null
                val settings = overlayAccountFields(decodeSettings(text), account.userData)
                val slot = account.passwordSlot
                val password = if (slot.isNullOrEmpty()) {
                    null
                } else {
                    try {
                        cipher.decrypt(id, slot)
                    } catch (_: SecretUnreadable) {
                        null
                    }
                }
                val smtp = readSecretSlot(id, userSmtpPasswordSlot)
                AccountCredentials(settings, password, smtp)
            }
        }
    }

    suspend fun setSmtpPassword(value: String) {
        try {
            onAccount {
                val accountId = withContext(Dispatchers.IO) {
                    val id = smtpAccountId()
                    writeSecretSlot(id, userSmtpPasswordSlot, value)
                    id
                }
                withContext(Dispatchers.IO) { refreshBackup(accountId, null) }
            }
        } catch (gone: AccountGone) {
            Log.w(logTag, "smtp password dropped, account gone ${gone.accountId}", gone)
        } catch (missing: NoAccountSelected) {
            Log.w(logTag, "smtp password dropped, no account selected", missing)
        }
    }

    suspend fun listAccounts(): List<AccountChoice> {
        return onAccount {
            withContext(Dispatchers.IO) {
                val open = parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
                readAccounts().map { account ->
                    val id = parseAccountId(account.userData[userAccountId])
                    if (id == null) {
                        AccountChoice(id = account.name, name = account.name, drawer = false, editing = false, corrupt = true)
                    } else {
                        AccountChoice(
                            id = id,
                            name = listedName(account),
                            drawer = id == open,
                            editing = false,
                        )
                    }
                }
            }
        }
    }

    suspend fun listDrawerAccounts(): List<DrawerAccount> {
        return onAccount {
            withContext(Dispatchers.IO) {
                val open = parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
                readAccounts().map { account ->
                    val id = parseAccountId(account.userData[userAccountId])
                    val stored = if (id == null) null else storedPreference(id)
                    if (id == null || stored == null) {
                        DrawerAccount(
                            id = id ?: account.name,
                            name = account.name,
                            chosen = false,
                            postponedMailbox = "",
                            favorites = emptyList(),
                            corrupt = true,
                        )
                    } else {
                        val decoded = runCatching { decodeSettings(stored) }.getOrNull()
                        val folders = if (decoded == null) accountDrawerFolders(null) else accountDrawerFolders(stored)
                        val friendly = decoded?.friendlyName?.takeIf { it.isNotEmpty() }
                        DrawerAccount(
                            id = id,
                            name = friendly ?: listedName(account),
                            chosen = id == open,
                            postponedMailbox = folders.postponedMailbox,
                            favorites = folders.favorites,
                            corrupt = decoded == null,
                        )
                    }
                }
            }
        }
    }

    suspend fun selectAccount(accountId: String) {
        val id = parseAccountId(accountId) ?: return
        onAccount {
            withContext(Dispatchers.IO) {
                val known = readAccounts().any { it.userData[userAccountId] == id }
                if (!known) return@withContext
                org.dlang.liveimap.settings.SettingsHub.selectOpen(id)
            }
        }
    }

    suspend fun createAccount(settings: Settings, password: String, smtpPassword: String): String {
        return onAccount {
            withContext(Dispatchers.IO) {
                val accounts = readAccounts()
                val accountId = java.util.UUID.randomUUID().toString()
                val slot = cipher.encrypt(accountId, password)
                writeAccount(
                    accountVisibleName(settings),
                    accountUserData(accountId, settings, null),
                    slot,
                    forceInsert = true,
                )
                writeSecretSlot(accountId, userSmtpPasswordSlot, smtpPassword)
                writePreference(accountId, settings.encode())
                org.dlang.liveimap.settings.SettingsHub.noteAccountWritten(context, accountId, settings, accounts.isEmpty())
                refreshBackup(accountId, password)
                accountId
            }
        }
    }

    /** False only when [accountId] is not an account and not a pending delete. [AccountDeleteFailed] is rethrown. */
    suspend fun removeAccount(accountId: String): Boolean {
        val known = readAccounts().any { it.userData[userAccountId] == accountId }
        val stored = storedPreference(accountId) != null
        val pending = accountId in readPendingDeletes(context)
        if (!known && !stored && !pending) return false
        deleteAccount(accountId, systemPath = false)
        return true
    }

    suspend fun deleteAccount(accountId: String, systemPath: Boolean = false) {
        if (parseAccountId(accountId) == null) return
        addPendingDelete(context, accountId)
        val manager = AccountManager.get(context)
        val entry = manager.getAccountsByType(liveimapAccountType).firstOrNull { row ->
            manager.getUserData(row, userAccountId) == accountId
        }
        val accounts = readAccounts()
        val selected: String? = null
        val name = entry?.name ?: accountId
        try {
            if (systemPath) {
                onAccount {
                    withContext(Dispatchers.IO) {
                        deleteStepsThroughSix(accountId, accounts, selected, name)
                        deleteStepEight(accountId)
                    }
                }
                return
            }
            onAccount {
                withContext(Dispatchers.IO) {
                    deleteStepsThroughSix(accountId, accounts, selected, name)
                }
                val ui = org.dlang.liveimap.settings.SettingsHub.uiNow()
                if (ui.settingsLastEditedAccount == accountId) {
                    org.dlang.liveimap.settings.SettingsHub.saveUiKeys(
                        context,
                        ui.copy(settingsLastEditedAccount = ""),
                        setOf("settingsLastEditedAccount"),
                    )
                }
            }
            if (entry != null && !manager.removeAccountExplicitly(entry)) {
                throw AccountDeleteFailed(7, IllegalStateException("removeAccountExplicitly false"))
            }
            onAccount { withContext(Dispatchers.IO) { deleteStepEight(accountId) } }
            org.dlang.liveimap.settings.SettingsHub.afterAccountRemoved(context, accountId)
        } catch (failed: AccountDeleteFailed) {
            notifyAccountDeleteFailed(context, name, failed.step, failed)
            throw failed
        }
    }

    suspend fun sweepPendingDeletes() {
        for (accountId in readPendingDeletes(context)) {
            try {
                val manager = AccountManager.get(context)
                val entry = manager.getAccountsByType(liveimapAccountType).firstOrNull { row ->
                    manager.getUserData(row, userAccountId) == accountId
                }
                val accounts = readAccounts()
                val selected: String? = null
                onAccount {
                    withContext(Dispatchers.IO) {
                        deleteStepsThroughSix(accountId, accounts, selected, entry?.name ?: accountId)
                    }
                }
                if (entry != null && !manager.removeAccountExplicitly(entry)) {
                    throw AccountDeleteFailed(7, IllegalStateException("removeAccountExplicitly false"))
                }
                onAccount { withContext(Dispatchers.IO) { deleteStepEight(accountId) } }
            } catch (error: Exception) {
                Log.w(logTag, "pending delete $accountId", error)
                if (error is AccountDeleteFailed) notifyAccountDeleteFailed(context, accountId, error.step, error)
            }
        }
        if (orphanSweepFile(context).exists()) {
            onAccount { withContext(Dispatchers.IO) { orphanSweep() } }
        }
    }

    /** Recreates the AccountManager entry for a restored settings record. Does not invent a second record. */
    internal suspend fun restoreAccountEntry(
        accountId: String,
        settings: Settings,
        imapPassword: String?,
        smtpPassword: String?,
        sievePassword: String?,
    ) {
        onAccount {
            withContext(Dispatchers.IO) {
                if (readAccounts().any { it.userData[userAccountId] == accountId }) return@withContext
                if (accountId in readPendingDeletes(context)) return@withContext
                val accounts = readAccounts()
                val slot = if (imapPassword == null) "" else cipher.encrypt(accountId, imapPassword)
                writeAccount(
                    accountVisibleName(settings),
                    accountUserData(accountId, settings, null),
                    slot,
                    forceInsert = true,
                )
                if (smtpPassword != null) writeSecretSlot(accountId, userSmtpPasswordSlot, smtpPassword)
                if (sievePassword != null) writeSecretSlot(accountId, userSievePasswordSlot, sievePassword)
                if (storedPreference(accountId) == null) writePreference(accountId, settings.encode())
                if (accounts.isEmpty()) {
                    org.dlang.liveimap.settings.SettingsHub.noteAccountWritten(context, accountId, settings, true)
                }
            }
        }
    }

    fun chosenAccountIdFlow(): Flow<String?> = org.dlang.liveimap.settings.SettingsHub.openId

    fun theme(): Flow<ThemeMode> = org.dlang.liveimap.settings.SettingsHub.openId.map { id ->
        val parsed = parseAccountId(id)
        if (parsed == null) ThemeMode.FollowSystem
        else org.dlang.liveimap.settings.SettingsHub.accountNow(parsed)?.theme ?: ThemeMode.FollowSystem
    }

    private fun dropHttpAllowUnlessPlain(accountId: String, imapHost: String) {
        val plain = try {
            jmapServerTarget(imapHost).plainHttp
        } catch (_: JmapFailure) {
            false
        }
        if (!plain) JmapPlainHttp.drop(accountId)
    }

    private suspend fun loadLocked(): Settings {
        val accounts = readAccounts()
        val account = pick(accounts) ?: throw NoAccountSelected()
        val accountId = parseAccountId(account.userData[userAccountId]) ?: throw NoAccountSelected()
        org.dlang.liveimap.settings.SettingsHub.accountNow(accountId)?.let { return it }
        val text = storedPreference(accountId) ?: throw NoAccountSelected()
        return overlayAccountFields(decodeSettings(text), account.userData).withUiOf(
            org.dlang.liveimap.settings.SettingsHub.uiNow(),
        )
    }

    private suspend fun stageSave(
        settings: Settings,
        encoded: String,
    ): suspend () -> Unit {
        val existing = requireWritable(readAccounts())
        val accountId = existing.userData[userAccountId]?.takeIf { it.isNotEmpty() } ?: throw NoAccountSelected()
        // F32/F87: a moved server never inherits the old server's password, pin or client certificate.
        // A stored row that does not decode throws here, before any write.
        val storedText = if (existing == null) null else storedPreference(accountId)
        val previous = if (existing == null || storedText == null) {
            null
        } else {
            overlayAccountFields(decodeSettings(storedText), existing.userData)
        }
        val plan = planAccountSave(accountId, previous, settings, encoded, existing?.passwordSlot ?: "")
        if (existing != null) dropHttpAllowUnlessPlain(accountId, plan.settings.imapHost)
        val name = nameFor(existing, plan.settings, accountId)
        // Every settings-file save rewrites the AccountManager record first: the engine connects from it.
        writeAccount(name, plan.userData, plan.passwordSlot)
        if (plan.dropSmtpPassword) writeSecretSlot(accountId, userSmtpPasswordSlot, null)
        if (plan.dropSievePassword) writeSecretSlot(accountId, userSievePasswordSlot, null)
        val previousChoice = previous?.passwordStorage?.name
        return {
            writePreference(accountId, plan.text)
            val nextChoice = plan.settings.passwordStorage.name
            if (previousChoice == org.dlang.liveimap.settings.PasswordStorageChoice.googleRestore.name &&
                nextChoice != previousChoice
            ) {
                PasswordStorage.movedOffGoogleRestore(context, accountId, previousChoice, nextChoice)
            }
            refreshBackup(accountId, null)
        }
    }

    private suspend fun refreshBackup(accountId: String, imap: String?) {
        val text = storedPreference(accountId) ?: return
        val settings = runCatching { decodeSettings(text) }.getOrNull() ?: return
        if (settings.passwordStorage != org.dlang.liveimap.settings.PasswordStorageChoice.googleRestore) return
        val imapPlain = imap ?: imapPasswordFor(accountId) ?: return
        val smtp = readSecretSlot(accountId, userSmtpPasswordSlot).orEmpty()
        val sieve = readSecretSlot(accountId, userSievePasswordSlot).orEmpty()
        PasswordStorage.refresh(
            context,
            accountId,
            settings.passwordStorage.name,
            encodeBackupRecord(settings, imapPlain, smtp, sieve),
        )
    }

    /** IMAP secret of [accountId]. Null when that account is absent. */
    private fun imapPasswordFor(accountId: String): String? {
        val account = readAccounts().firstOrNull { it.userData[userAccountId] == accountId } ?: return null
        val slot = account.passwordSlot
        if (slot.isNullOrEmpty()) return ""
        return try {
            cipher.decrypt(accountId, slot)
        } catch (_: SecretUnreadable) {
            ""
        }
    }

    private suspend fun passwordLocked(): String? {
        val account = pick(readAccounts()) ?: return ""
        val accountId = account.userData[userAccountId] ?: return ""
        val slot = account.passwordSlot
        if (slot.isNullOrEmpty()) return null
        return try {
            cipher.decrypt(accountId, slot)
        } catch (_: SecretUnreadable) {
            null
        }
    }

    private suspend fun stagePassword(value: String): suspend () -> Unit {
        val existing = requireWritable(readAccounts())
        val accountId = existing.userData[userAccountId]?.takeIf { it.isNotEmpty() } ?: throw NoAccountSelected()
        val slot = cipher.encrypt(accountId, value, regenerate = true)
        writeAccount(existing.name, existing.userData, slot)
        return {}
    }

    private fun themeText(prefs: androidx.datastore.preferences.core.Preferences): String? {
        val id = parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value) ?: return null
        return prefs[stringPreferencesKey(id)]
    }

    private suspend fun nameFor(
        existing: ManagedAccount?,
        settings: Settings,
        accountId: String,
    ): String {
        val next = accountVisibleName(settings)
        if (existing == null) return next
        val previousText = storedPreference(accountId)
        val previous = if (previousText == null) {
            existing.name
        } else {
            accountVisibleName(decodeSettings(previousText))
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
            ManagedAccount(account.name, data, manager.getPassword(account))
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

    /**
     * F121: the account an operation acts on. Inside [AccountTarget] (a screen or Settings bound to one account)
     * it is exactly that account, or none; never the drawer's active account and never a fallback to another.
     */
    private suspend fun pick(accounts: List<ManagedAccount>): ManagedAccount? {
        val target = parseAccountId(coroutineContext[AccountTarget]?.accountId)
            ?: parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
            ?: return null
        return accounts.firstOrNull { it.userData[userAccountId] == target }
    }

    private suspend fun requireWritable(accounts: List<ManagedAccount>): ManagedAccount {
        val target = parseAccountId(coroutineContext[AccountTarget]?.accountId)
            ?: parseAccountId(org.dlang.liveimap.settings.SettingsHub.openId.value)
            ?: throw NoAccountSelected()
        return accounts.firstOrNull { it.userData[userAccountId] == target } ?: throw AccountGone(target)
    }

    internal suspend fun storedBlobs(): Map<String, String> {
        val prefs = context.accountSettingsDataStore.data.first()
        val out = linkedMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            if (key.name == selectedAccountIdKey) continue
            val text = value as? String ?: continue
            out[key.name] = text
        }
        return out
    }

    internal suspend fun putBlob(accountId: String, text: String) {
        writePreference(accountId, text)
    }

    private suspend fun smtpAccountId(): String {
        val chosen = requireWritable(readAccounts())
        return parseAccountId(chosen.userData[userAccountId]) ?: throw NoAccountSelected()
    }

    private fun findAccount(accountId: String): Account? {
        val manager = AccountManager.get(context)
        return manager.getAccountsByType(liveimapAccountType).firstOrNull {
            manager.getUserData(it, userAccountId) == accountId
        }
    }

    private fun writeSecretSlot(accountId: String, key: String, plain: String?) {
        val manager = AccountManager.get(context)
        val account = findAccount(accountId) ?: return
        if (plain == null) {
            manager.setUserData(account, key, null)
            return
        }
        manager.setUserData(account, key, cipher.encrypt(accountId, plain))
    }

    private fun readSecretSlot(accountId: String, key: String): String? {
        val manager = AccountManager.get(context)
        val account = findAccount(accountId) ?: return null
        val slot = manager.getUserData(account, key) ?: return null
        if (slot.isEmpty()) return null
        return try {
            cipher.decrypt(accountId, slot)
        } catch (locked: org.dlang.liveimap.session.MailFailure) {
            throw locked
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun storedPreference(accountId: String): String? {
        return context.accountSettingsDataStore.data.first()[stringPreferencesKey(accountId)]
    }

    private suspend fun writePreference(accountId: String, value: String) {
        context.accountSettingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(accountId)] = value
        }
    }

    private suspend fun deleteStepsThroughSix(
        accountId: String,
        accounts: List<ManagedAccount>,
        selected: String?,
        accountName: String,
    ) {
        try {
            dropMailSession(accountId)
            JmapPlainHttp.drop(accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(1, error)
        }
        val stored = try {
            storedPreference(accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(2, error)
        }
        if (stored == null) return
        var settings: Settings? = null
        val decoded = runCatching { decodeSettings(stored) }
        if (decoded.isSuccess) {
            settings = decoded.getOrThrow()
        } else {
            val error = decoded.exceptionOrNull() ?: IllegalArgumentException("account record")
            writeOrphanSweep(context)
            Log.w(logTag, "account record unreadable $accountId", error)
            notifyAccountRecordUnreadable(context, accountName, error)
        }
        try {
            deleteNamedAccountFiles(context.filesDir, context.cacheDir, accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(3, error)
        }
        try {
            val alias = settings?.clientCertAlias.orEmpty()
            if (alias.startsWith(KeyChainCerts.LAB_PREFIX)) KeyChainCerts.removeImported(alias)
            cipher.delete(accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(4, error)
        }
        try {
            BlockStorePasswords.delete(context, accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(5, error)
        }
        try {
            val decision = if (accounts.any { it.userData[userAccountId] == accountId }) {
                accountRemoval(accounts, accountId, selected)
            } else {
                AccountRemoval(remove = true, selectedId = if (selected == accountId) null else selected)
            }
            context.accountSettingsDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(accountId))
                prefs.remove(stringPreferencesKey(selectedAccountIdKey))
            }
        } catch (error: Exception) {
            throw AccountDeleteFailed(6, error)
        }
    }

    private fun deleteStepEight(accountId: String) {
        try {
            forgetCompletionCaches()
            TrafficLog.close(accountId)
            clearPendingDelete(context, accountId)
        } catch (error: Exception) {
            throw AccountDeleteFailed(8, error)
        }
    }

    private suspend fun orphanSweep() {
        val prefs = context.accountSettingsDataStore.data.first()
        val referenced = HashSet<String>()
        for ((key, value) in prefs.asMap()) {
            if (key.name == selectedAccountIdKey) continue
            val text = value as? String ?: continue
            try {
                val alias = decodeSettings(text).clientCertAlias
                if (alias.startsWith(KeyChainCerts.LAB_PREFIX)) referenced.add(alias.removePrefix(KeyChainCerts.LAB_PREFIX))
            } catch (error: Exception) {
                Log.w(logTag, "orphan sweep stopped at ${key.name}", error)
                return
            }
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val hourAgo = System.currentTimeMillis() - 60L * 60L * 1000L
        var keep = false
        val names = keyStore.aliases().toList()
        for (alias in names) {
            if (!uuid.matches(alias) || alias in referenced) continue
            val created = keyStore.getCreationDate(alias)?.time ?: 0L
            if (created > hourAgo) {
                Log.i(logTag, "orphan key younger than one hour $alias")
                keep = true
                continue
            }
            try {
                KeyChainCerts.removeImported(KeyChainCerts.LAB_PREFIX + alias)
            } catch (error: ImportedKeyStillPresent) {
                Log.w(logTag, "orphan key still present $alias", error)
                keep = true
            } catch (error: Exception) {
                Log.w(logTag, "orphan key $alias", error)
                keep = true
            }
        }
        if (!keep) orphanSweepFile(context).delete()
    }

    companion object {
        private val instance = AtomicReference<AccountStore?>(null)

        fun shared(context: Context): AccountStore {
            instance.get()?.let { return it }
            val created = AccountStore(context.applicationContext)
            if (instance.compareAndSet(null, created)) return created
            created.abandon()
            return instance.get()!!
        }
    }
}

internal fun pendingDeletesFile(context: Context): File = File(context.noBackupFilesDir, "pending-deletes")

internal fun orphanSweepFile(context: Context): File = File(context.noBackupFilesDir, "orphan-sweep")

internal fun readPendingDeletes(context: Context): List<String> {
    val file = pendingDeletesFile(context)
    if (!file.isFile) return emptyList()
    return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

internal fun addPendingDelete(context: Context, accountId: String) {
    val current = readPendingDeletes(context)
    if (accountId in current) return
    val file = pendingDeletesFile(context)
    file.parentFile?.mkdirs()
    file.writeText((current + accountId).joinToString("\n") + "\n")
}

internal fun clearPendingDelete(context: Context, accountId: String) {
    val left = readPendingDeletes(context).filter { it != accountId }
    val file = pendingDeletesFile(context)
    if (left.isEmpty()) file.delete() else file.writeText(left.joinToString("\n") + "\n")
}

internal fun writeOrphanSweep(context: Context) {
    val file = orphanSweepFile(context)
    file.parentFile?.mkdirs()
    if (!file.exists()) file.writeText("1\n")
}

internal fun notifyAccountDeleteFailed(context: Context, accountName: String, step: Int, error: Exception) {
    val text = context.getString(com.davidlang.liveimap.R.string.account_delete_failed, accountName, step)
    Log.w(logTag, text, error)
    postAccountNotice(context, accountName, text)
}

internal fun notifyAccountRecordUnreadable(context: Context, accountName: String, error: Throwable) {
    val text = context.getString(com.davidlang.liveimap.R.string.account_record_unreadable, accountName)
    Log.w(logTag, text, error)
    postAccountNotice(context, accountName, text)
}

private fun postAccountNotice(context: Context, accountName: String, text: String) {
    try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel("liveimap-account") == null) {
            manager.createNotificationChannel(
                NotificationChannel("liveimap-account", "Account", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = Notification.Builder(context, "liveimap-account")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(accountName)
            .setContentText(text)
            .build()
        manager.notify(accountName.hashCode(), notification)
    } catch (_: SecurityException) {
    }
}

/** One account's settings and secrets, for sending through it (see AccountStore.accountById). */
class AccountCredentials(
    val settings: Settings,
    val password: String?,
    val smtpPassword: String?,
) {
    override fun toString(): String = "AccountCredentials"
}


/** F121: binds AccountStore calls made in this coroutine context to one account id (see AccountStore.pick). */
class AccountTarget(val accountId: String) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AccountTarget>
}
