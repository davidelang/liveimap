package org.dlang.liveimap.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.dlang.liveimap.accounts.AccountStore

interface SettingsStore {
    suspend fun load(): AccountSettings
    suspend fun save(settings: AccountSettings)
    suspend fun password(): String
    suspend fun setPassword(value: String)
    suspend fun smtpPassword(): String = ""
    suspend fun setSmtpPassword(value: String) {}
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

    override fun theme(): Flow<ThemeMode> = store.theme()
}
