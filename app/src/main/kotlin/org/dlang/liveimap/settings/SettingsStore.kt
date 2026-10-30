package org.dlang.liveimap.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.AccountTarget

data class AccountChoice(
    val id: String,
    val name: String,
    val drawer: Boolean,
    val editing: Boolean = false,
    val corrupt: Boolean = false,
)

data class DrawerAccount(
    val id: String,
    val name: String,
    val chosen: Boolean,
    val postponedMailbox: String,
    val favorites: List<FolderFavorite>,
    val corrupt: Boolean = false,
)

data class AccountDrawerFolders(
    val postponedMailbox: String,
    val favorites: List<FolderFavorite>,
)

fun accountDrawerFolders(encoded: String?): AccountDrawerFolders {
    if (encoded == null) return AccountDrawerFolders(postponedMailbox = "", favorites = emptyList())
    val settings = decodeSettings(encoded)
    return AccountDrawerFolders(
        postponedMailbox = settings.postponedMailbox,
        favorites = settings.favorites,
    )
}

interface SettingsStore {
    suspend fun load(): Settings
    suspend fun save(settings: Settings)
    suspend fun password(): String?
    /** F55/F66: change some fields without writing back a stale snapshot of the others. */
    suspend fun update(edit: (Settings) -> Settings): Settings {
        val next = edit(load())
        save(next)
        return next
    }
    suspend fun setPassword(value: String)
    suspend fun smtpPassword(): String? = ""
    suspend fun setSmtpPassword(value: String) {}
    suspend fun listAccounts(): List<AccountChoice> = emptyList()
    suspend fun listDrawerAccounts(): List<DrawerAccount> = emptyList()
    suspend fun chosenAccountId(): String? = null
    suspend fun selectAccount(accountId: String) {}
    suspend fun createAccount(settings: Settings, password: String, smtpPassword: String): String = ""
    suspend fun removeAccount(accountId: String): Boolean = false
    fun theme(): Flow<ThemeMode> = flowOf(ThemeMode.FollowSystem)
}

/**
 * Settings and passwords. Unbound ([boundAccountId] null): the drawer's active account. Bound (F121): exactly
 * that account for every read and write, whatever the drawer shows later; selectAccount is a no-op when bound.
 */
class DataStoreSettingsStore(
    private val context: Context,
    val boundAccountId: String? = null,
) : SettingsStore {
    private val store = AccountStore.shared(context)

    private suspend fun <T> bound(block: suspend () -> T): T =
        if (boundAccountId == null) block() else withContext(AccountTarget(boundAccountId)) { block() }

    override suspend fun load(): Settings = bound { store.load() }

    override suspend fun save(settings: Settings) = bound { store.save(settings) }

    override suspend fun update(edit: (Settings) -> Settings): Settings = bound { store.update(edit) }

    override suspend fun password(): String? = bound { store.password() }

    override suspend fun setPassword(value: String) = bound { store.setPassword(value) }

    override suspend fun smtpPassword(): String? = bound { store.smtpPassword() }

    suspend fun accountById(accountId: String): org.dlang.liveimap.accounts.AccountCredentials? = store.accountById(accountId)

    override suspend fun setSmtpPassword(value: String) = bound { store.setSmtpPassword(value) }

    override suspend fun listAccounts(): List<AccountChoice> = store.listAccounts()

    suspend fun accountFields(accountId: String): Map<String, String>? = store.accountFields(accountId)

    override suspend fun listDrawerAccounts(): List<DrawerAccount> = store.listDrawerAccounts()

    override suspend fun chosenAccountId(): String? {
        org.dlang.liveimap.accounts.parseAccountId(boundAccountId)?.let { return it }
        return store.listAccounts().firstOrNull { it.drawer && !it.corrupt }?.id?.let {
            org.dlang.liveimap.accounts.parseAccountId(it)
        }
    }

    override suspend fun selectAccount(accountId: String) {
        if (boundAccountId != null) return // F121: only the drawer (unbound store) changes the active account
        store.selectAccount(accountId)
    }

    override suspend fun createAccount(settings: Settings, password: String, smtpPassword: String): String =
        store.createAccount(settings, password, smtpPassword)

    override suspend fun removeAccount(accountId: String): Boolean = store.removeAccount(accountId)

    override fun theme(): Flow<ThemeMode> = store.theme()

    suspend fun commitKeys(draft: Settings, keys: Set<String>): Settings = bound { store.commitKeys(draft, keys) }

    suspend fun <T> onAccount(block: suspend () -> T): T = store.onAccount(block)

    /** See [AccountStore.onAccount]. [publish] runs on the caller before the next command. */
    suspend fun <T> onAccount(publish: (T) -> Unit, block: suspend () -> T): T = store.onAccount(publish, block)

    fun chosenAccountIdFlow(): Flow<String?> = store.chosenAccountIdFlow()

    /** F121: a store bound to [accountId]. */
    fun forAccount(accountId: String): DataStoreSettingsStore = DataStoreSettingsStore(context, accountId)
}

internal suspend fun <T> SettingsStore.accountCommand(block: suspend () -> T): T {
    val data = this as? DataStoreSettingsStore
    if (data == null) return block()
    return data.onAccount(block)
}

/** [publish] runs on the caller, still on the caller's [Job], before the next account command. */
internal suspend fun <T> SettingsStore.accountCommand(publish: (T) -> Unit, block: suspend () -> T): T {
    val data = this as? DataStoreSettingsStore
    if (data == null) {
        val value = block()
        publish(value)
        return value
    }
    return data.onAccount(publish, block)
}

