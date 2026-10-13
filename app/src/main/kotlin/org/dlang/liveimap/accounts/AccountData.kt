package org.dlang.liveimap.accounts

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode

internal const val liveimapAccountType: String = "org.dlang.liveimap"
internal const val accountSchemaValue: String = "1"
internal const val smtpPasswordKey: String = "smtpPassword"
internal const val selectedAccountIdKey: String = "selectedAccountId"

internal fun smtpPasswordKeyFor(accountId: String): String = "$smtpPasswordKey.$accountId"

internal fun chooseAccount(accounts: List<ManagedAccount>, selectedId: String?): ManagedAccount? {
    if (accounts.isEmpty()) return null
    if (!selectedId.isNullOrBlank()) {
        accounts.firstOrNull { it.userData[userAccountId] == selectedId }?.let { return it }
    }
    if (accounts.size == 1) return accounts.first()
    return accounts.first()
}

internal data class AccountRemoval(
    val remove: Boolean,
    val selectedId: String?,
)

internal fun accountRemoval(
    accounts: List<ManagedAccount>,
    removeId: String,
    selectedId: String?,
): AccountRemoval {
    val present = removeId.isNotBlank() && accounts.any { it.userData[userAccountId] == removeId }
    if (!present || accounts.size < 2) return AccountRemoval(remove = false, selectedId = selectedId)
    val next = if (selectedId == removeId) {
        val remaining = accounts.filter { it.userData[userAccountId] != removeId }
        chooseAccount(remaining, null)?.userData?.get(userAccountId)
    } else {
        selectedId
    }
    return AccountRemoval(remove = true, selectedId = next)
}

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

internal interface AccountCipher {
    fun encrypt(accountId: String, password: String): String
    fun decrypt(accountId: String, passwordSlot: String): String
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


/** F32/F87: what saving [next] over [previous] must forget because a server moved. */
internal data class Retarget(val settings: AccountSettings, val dropImapPassword: Boolean, val dropSmtpPassword: Boolean)

/**
 * F32/F87 (and the F32 addendum): a stored password or certificate pin belongs to the host it was entered for.
 * When the IMAP host changes, the IMAP password, the IMAP/Sieve pins and the client-certificate choice are dropped;
 * when the SMTP host changes, the SMTP password is dropped. The user re-enters them for the new server.
 */
internal fun retargetCredentials(previous: AccountSettings?, next: AccountSettings): Retarget {
    if (previous == null) return Retarget(next, dropImapPassword = false, dropSmtpPassword = false)
    fun moved(a: String, b: String) = a.isNotBlank() && !a.trim().equals(b.trim(), ignoreCase = true)
    val imapMoved = moved(previous.imapHost, next.imapHost)
    val smtpMoved = moved(previous.smtpHost, next.smtpHost)
    val settings = if (imapMoved) next.copy(certPin = "", sieveCertPin = "", clientCertAlias = "") else next
    return Retarget(settings, dropImapPassword = imapMoved, dropSmtpPassword = smtpMoved || (imapMoved && next.smtpSameAsImap))
}
