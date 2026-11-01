package org.dlang.liveimap.settings

/** The account whose folders are listed. The drawer id is never selected from here. */
internal sealed class SettingsFolderAccount {
    data class Saved(val id: String) : SettingsFolderAccount()
    data class Draft(val settings: Settings) : SettingsFolderAccount()
}

/**
 * [editingId] when an account is being edited, otherwise the on-screen [draft].
 * [drawerId] is the open drawer account and is not the list.
 */
internal fun settingsFolderAccount(
    editingId: String?,
    draft: Settings,
    @Suppress("UNUSED_PARAMETER") drawerId: String,
): SettingsFolderAccount {
    val edited = editingId?.takeIf { it.isNotEmpty() }
    if (edited != null) return SettingsFolderAccount.Saved(edited)
    return SettingsFolderAccount.Draft(draft)
}

/** Session label for a throwaway login. A draft is not the drawer account. */
internal fun settingsFolderProbeId(account: SettingsFolderAccount): String = when (account) {
    is SettingsFolderAccount.Saved -> account.id
    is SettingsFolderAccount.Draft -> "probe"
}

internal fun settingsPageChanged(
    creating: Boolean,
    pageDirty: Boolean,
    hasDraft: Boolean,
    pendingLine: Boolean,
): Boolean = creating || pageDirty || hasDraft || pendingLine

/** On-screen Save is absent when this is false. The accounts list is never a save page. */
internal fun settingsSaveShown(
    accountsPage: Boolean,
    creating: Boolean,
    pageDirty: Boolean,
    hasDraft: Boolean,
    pendingLine: Boolean,
): Boolean = !accountsPage && settingsPageChanged(creating, pageDirty, hasDraft, pendingLine)

/** Shared by the on-screen Save and account-save. False while the server check runs. */
internal fun settingsSaveEnabled(
    accountsPage: Boolean,
    creating: Boolean,
    pageDirty: Boolean,
    hasDraft: Boolean,
    pendingLine: Boolean,
    serverChecking: Boolean,
): Boolean = settingsSaveShown(accountsPage, creating, pageDirty, hasDraft, pendingLine) && !serverChecking
