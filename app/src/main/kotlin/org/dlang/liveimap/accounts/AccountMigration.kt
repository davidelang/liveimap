package org.dlang.liveimap.accounts

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings

internal const val liveimapAccountType: String = "org.dlang.liveimap"
internal const val accountSchemaValue: String = "1"
internal const val migratedFromDatastoreV1: String = "datastore-v1"
internal const val legacyAccountKey: String = "account"
internal const val legacySecretFile: String = "liveimap_secret"
internal const val legacyPasswordKey: String = "password"

internal const val userAccountId: String = "accountId"
internal const val userSchema: String = "schema"
internal const val userMigratedFrom: String = "migratedFrom"
internal const val userImapHost: String = "imapHost"
internal const val userImapPort: String = "imapPort"
internal const val userSmtpHost: String = "smtpHost"
internal const val userSmtpPort: String = "smtpPort"
internal const val userUsername: String = "username"
internal const val userEmail: String = "email"

internal val accountUserKeys: List<String> = listOf(
    userAccountId,
    userSchema,
    userMigratedFrom,
    userImapHost,
    userImapPort,
    userSmtpHost,
    userSmtpPort,
    userUsername,
    userEmail,
)

internal data class ManagedAccount(
    val name: String,
    val userData: Map<String, String>,
    val passwordSlot: String,
)

internal interface LegacyAccount {
    suspend fun encoded(): String?
    suspend fun password(): String
    suspend fun deleteEncoded()
    suspend fun deletePassword()
}

internal interface AccountMap {
    suspend fun accounts(): List<ManagedAccount>
    suspend fun write(name: String, userData: Map<String, String>, passwordSlot: String)
    suspend fun preference(accountId: String): String?
    suspend fun putPreference(accountId: String, value: String)
}

internal interface AccountCipher {
    fun encrypt(accountId: String, password: String): String
    fun decrypt(accountId: String, passwordSlot: String): String
}

internal enum class MigrationOutcome {
    Already,
    NothingToDo,
    Migrated,
    Failed,
}

internal fun accountVisibleName(settings: AccountSettings): String {
    if (settings.email.isNotEmpty()) return settings.email
    return "${settings.username}@${settings.imapHost}"
}

internal fun accountUserData(
    accountId: String,
    settings: AccountSettings,
    migratedFrom: String?,
): Map<String, String> {
    val data = linkedMapOf(
        userAccountId to accountId,
        userSchema to accountSchemaValue,
        userImapHost to settings.imapHost,
        userImapPort to settings.imapPort.toString(),
        userSmtpHost to settings.smtpHost,
        userSmtpPort to settings.smtpPort.toString(),
        userUsername to settings.username,
        userEmail to settings.email,
    )
    if (migratedFrom != null) data[userMigratedFrom] = migratedFrom
    return data
}

internal fun overlayAccountFields(base: AccountSettings, userData: Map<String, String>): AccountSettings {
    return base.copy(
        imapHost = userData.getValue(userImapHost),
        imapPort = userData.getValue(userImapPort).toInt(),
        smtpHost = userData.getValue(userSmtpHost),
        smtpPort = userData.getValue(userSmtpPort).toInt(),
        username = userData.getValue(userUsername),
        email = userData.getValue(userEmail),
    )
}

internal suspend fun migrateAccount(
    legacy: LegacyAccount,
    accounts: AccountMap,
    cipher: AccountCipher,
    newAccountId: () -> String = { java.util.UUID.randomUUID().toString() },
    log: (String) -> Unit = {},
): MigrationOutcome {
    try {
        if (accounts.accounts().any { it.userData[userMigratedFrom] == migratedFromDatastoreV1 }) {
            return MigrationOutcome.Already
        }
        val text = legacy.encoded() ?: return MigrationOutcome.NothingToDo
        val settings = decodeAccountSettings(text)
        val password = legacy.password()
        val existing = accounts.accounts().firstOrNull()
        val accountId = existing?.userData?.get(userAccountId)?.takeIf { it.isNotEmpty() }
            ?: newAccountId()
        val name = existing?.name ?: accountVisibleName(settings)
        val userData = accountUserData(accountId, settings, migratedFrom = null)
        val slot = cipher.encrypt(accountId, password)
        accounts.write(name, userData, slot)
        accounts.putPreference(accountId, settings.encode())
        val read = accounts.accounts().firstOrNull { it.userData[userAccountId] == accountId }
            ?: return migrationFailed(log)
        val stored = accounts.preference(accountId) ?: return migrationFailed(log)
        val loaded = overlayAccountFields(decodeAccountSettings(stored), read.userData)
        val decodedPassword = cipher.decrypt(accountId, read.passwordSlot)
        if (loaded != settings || decodedPassword != password) return migrationFailed(log)
        // Old stores stay until this match. The marker is set only after the delete.
        legacy.deleteEncoded()
        legacy.deletePassword()
        accounts.write(name, accountUserData(accountId, settings, migratedFromDatastoreV1), slot)
        log("account migrated")
        return MigrationOutcome.Migrated
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        log("account migration failed")
        return MigrationOutcome.Failed
    }
}

private fun migrationFailed(log: (String) -> Unit): MigrationOutcome {
    log("account migration failed")
    return MigrationOutcome.Failed
}
