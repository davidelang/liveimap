package org.dlang.liveimap.accounts

import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode

internal const val liveimapAccountType: String = "com.davidlang.liveimap"
internal const val accountSchemaValue: String = "1"
internal const val selectedAccountIdKey: String = "selectedAccountId"

/** The only empty-string check for an account id. Mailbox names are not account ids. */
internal fun parseAccountId(raw: String?): String? {
    if (raw == null || raw.isEmpty()) return null
    return raw
}
internal const val userSmtpPasswordSlot: String = "smtpPasswordSlot"
internal const val userSievePasswordSlot: String = "sievePasswordSlot"

class NoAccountSelected : Exception("no account selected")

class AccountGone(val accountId: String) : Exception("account $accountId gone")

class AccountDeleteFailed(val step: Int, cause: Throwable? = null) : Exception("account delete step $step", cause)

internal data class AccountRemoval(
    val remove: Boolean,
    val selectedId: String?,
)

internal fun accountRemoval(
    accounts: List<ManagedAccount>,
    removeId: String,
    selectedId: String?,
): AccountRemoval {
    val id = parseAccountId(removeId) ?: return AccountRemoval(remove = false, selectedId = selectedId)
    val present = accounts.any { it.userData[userAccountId] == id }
    if (!present) return AccountRemoval(remove = false, selectedId = selectedId)
    val remaining = accounts.filter { it.userData[userAccountId] != id }
    val next = if (remaining.isEmpty() || selectedId == id) null else selectedId
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
    val passwordSlot: String?,
)

internal fun accountVisibleName(settings: Settings): String {
    if (settings.email.isNotEmpty()) return settings.email
    return "${settings.username}@${settings.imapHost}"
}

internal fun accountUserData(
    accountId: String,
    settings: Settings,
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

internal fun overlayAccountFields(base: Settings, userData: Map<String, String>): Settings {
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
internal data class Retarget(
    val settings: Settings,
    val dropImapPassword: Boolean,
    val dropSmtpPassword: Boolean,
    val dropSievePassword: Boolean,
)

/**
 * F32/F87 (and the F32 addendum): a stored password or certificate pin belongs to the host it was entered for.
 * When the IMAP host changes, the IMAP password, the IMAP/Sieve pins and the client-certificate choice are dropped;
 * when the SMTP host changes, the SMTP password is dropped. The user re-enters them for the new server.
 */
internal fun retargetCredentials(previous: Settings?, next: Settings): Retarget {
    if (previous == null) return Retarget(next, dropImapPassword = false, dropSmtpPassword = false, dropSievePassword = false)
    fun moved(a: String, b: String) = a.isNotBlank() && !a.trim().equals(b.trim(), ignoreCase = true)
    val imapMoved = moved(previous.imapHost, next.imapHost)
    val smtpMoved = moved(previous.smtpHost, next.smtpHost)
    val sieveMoved = moved(previous.sieveHost, next.sieveHost)
    var settings = next
    if (imapMoved) {
        settings = settings.copy(
            certPin = "",
            sieveCertPin = "",
            jmapCertPin = "",
            clientCertAlias = "",
            acceptInvalidCert = false,
        )
    }
    if (sieveMoved) settings = settings.copy(sieveCertPin = "")
    return Retarget(
        settings,
        dropImapPassword = imapMoved,
        dropSmtpPassword = smtpMoved || (imapMoved && settings.smtpSameAsImap),
        dropSievePassword = sieveMoved,
    )
}

/** One settings save: the settings-file text and the AccountManager record written with it, always together. */
internal data class AccountSavePlan(
    val settings: Settings,
    val text: String,
    val userData: Map<String, String>,
    val passwordSlot: String,
    val dropSmtpPassword: Boolean,
    val dropSievePassword: Boolean,
)

internal fun planAccountSave(
    accountId: String,
    previous: Settings?,
    settings: Settings,
    encoded: String,
    passwordSlot: String,
): AccountSavePlan {
    val retarget = retargetCredentials(previous, settings)
    val saved = retarget.settings
    return AccountSavePlan(
        settings = saved,
        text = if (saved == settings) encoded else saved.encode(),
        userData = accountUserData(accountId, saved, null),
        passwordSlot = if (retarget.dropImapPassword) "" else passwordSlot,
        dropSmtpPassword = retarget.dropSmtpPassword,
        dropSievePassword = retarget.dropSievePassword,
    )
}
