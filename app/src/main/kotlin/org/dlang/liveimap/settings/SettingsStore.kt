package org.dlang.liveimap.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.dlang.liveimap.accounts.AccountStore

data class AccountChoice(
    val id: String,
    val name: String,
    val chosen: Boolean,
)

data class DrawerAccount(
    val id: String,
    val name: String,
    val chosen: Boolean,
    val postponedMailbox: String,
    val favorites: List<FolderFavorite>,
)

data class AccountDrawerFolders(
    val postponedMailbox: String,
    val favorites: List<FolderFavorite>,
)

fun accountDrawerFolders(encoded: String?): AccountDrawerFolders {
    if (encoded == null) return AccountDrawerFolders(postponedMailbox = "", favorites = emptyList())
    val settings = decodeAccountSettings(encoded)
    return AccountDrawerFolders(
        postponedMailbox = settings.postponedMailbox,
        favorites = settings.favorites,
    )
}

interface SettingsStore {
    suspend fun load(): AccountSettings
    suspend fun save(settings: AccountSettings)
    suspend fun password(): String
    suspend fun setPassword(value: String)
    suspend fun smtpPassword(): String = ""
    suspend fun setSmtpPassword(value: String) {}
    suspend fun listAccounts(): List<AccountChoice> = emptyList()
    suspend fun listDrawerAccounts(): List<DrawerAccount> = emptyList()
    suspend fun chosenAccountId(): String? = null
    suspend fun selectAccount(accountId: String) {}
    suspend fun addAccount() {}
    suspend fun removeAccount(accountId: String): Boolean = false
    fun theme(): Flow<ThemeMode> = flowOf(ThemeMode.FollowSystem)
}

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val store = AccountStore.shared(context)

    override suspend fun load(): AccountSettings = store.load()

    override suspend fun save(settings: AccountSettings) = store.save(settings)

    override suspend fun password(): String = store.password()

    override suspend fun setPassword(value: String) = store.setPassword(value)

    override suspend fun smtpPassword(): String = store.smtpPassword()

    override suspend fun setSmtpPassword(value: String) = store.setSmtpPassword(value)

    override suspend fun listAccounts(): List<AccountChoice> = store.listAccounts()

    override suspend fun listDrawerAccounts(): List<DrawerAccount> = store.listDrawerAccounts()

    override suspend fun chosenAccountId(): String? {
        val id = store.listAccounts().firstOrNull { it.chosen }?.id
        if (id.isNullOrEmpty()) return null
        return id
    }

    override suspend fun selectAccount(accountId: String) = store.selectAccount(accountId)

    override suspend fun addAccount() = store.addAccount()

    override suspend fun removeAccount(accountId: String): Boolean = store.removeAccount(accountId)

    override fun theme(): Flow<ThemeMode> = store.theme()
}
