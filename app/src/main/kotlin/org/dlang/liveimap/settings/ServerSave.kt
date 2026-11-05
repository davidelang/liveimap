package org.dlang.liveimap.settings

import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.engine.ServerCheck

/** What Save shows before storing a server draft. The draft itself is never modified here (no revert path). */
data class ServerSaveReview(
    val tlsWarning: String?,
    val unreachable: Boolean,
    val lines: List<String>,
) {
    /** Nothing to tell the user: Save stores at once. */
    val quiet: Boolean get() = tlsWarning == null && !unreachable && lines.isEmpty()
}

/** One review at Save: the TLS-off warning (once) plus the server check. A null check failed for IMAP; a JMAP draft skips that probe. */
fun serverSaveReview(draft: Settings, check: ServerCheck?): ServerSaveReview = ServerSaveReview(
    tlsWarning = noTlsWarning(draft),
    unreachable = if (draft.jmapFolders) check?.reachable == false else check == null || !check.reachable,
    lines = check?.let { it.conflicts + it.notes }.orEmpty(),
)

/** Account Save and the separate Save store only when the review is missing or quiet. A confirmed save stores. */
internal enum class SettingsSaveChoice { Store, Hold }

internal fun settingsSaveChoice(confirmed: Boolean, review: ServerSaveReview?): SettingsSaveChoice =
    if (confirmed || review == null || review.quiet) SettingsSaveChoice.Store else SettingsSaveChoice.Hold

/** One release line: the control, the account id, and stored, held, or failed. No password and no settings blob. */
internal fun settingsSaveReport(control: String, accountId: String?, outcome: String): String =
    "settings save $control account=${accountId.orEmpty()} $outcome"

internal fun settingsSaveHeld(review: ServerSaveReview): String =
    "held unreachable=${review.unreachable} tls=${review.tlsWarning != null}"

internal fun settingsSaveFailed(message: String): String = "failed $message"

/**
 * A listed account with no settings blob is still that account.
 * The blob text is decoded when present; a null blob is a new account overlaid with the AccountManager record.
 */
internal fun baseForListedSave(blob: String?, userData: Map<String, String>): Settings {
    val decoded = if (blob == null) newAccountSettings() else decodeSettings(blob)
    return overlayAccountFields(decoded, userData)
}

/** The server review and the exit dialog stay up until the confirmed write is stored. */
internal fun confirmedSaveDialogs(reviewOpen: Boolean, discardOpen: Boolean, stored: Boolean): Pair<Boolean, Boolean> =
    if (stored) false to false else reviewOpen to discardOpen

/**
 * Another account keeps the capture the check started with.
 * The same account stores [finished]: the screen, server draft, and password drafts open when the check ended.
 */
internal fun boundSaveCapture(started: SaveCapture, editingNow: String?, finished: SaveCapture): SaveCapture {
    if (started.accountId != editingNow || finished.accountId != started.accountId) return started
    return finished
}

/** Log and failure text when the attempt wrote no key and no password. Not a string resource. */
internal const val settingsSaveEmptyWrite: String = "empty write"

internal data class SettingsSaveVerdict(val stored: Boolean, val failure: String?) {
    val outcome: String
        get() = if (stored) "stored" else settingsSaveFailed(failure.orEmpty())
}

internal fun accountIdentityChanged(before: Settings, screen: Settings): Boolean =
    before.imapHost != screen.imapHost || before.imapPort != screen.imapPort ||
        before.smtpHost != screen.smtpHost || before.smtpPort != screen.smtpPort ||
        before.username != screen.username || before.email != screen.email

internal fun applyWrittenKeys(before: Settings, screen: Settings, keys: Set<String>): Settings {
    var blob = before
    for (key in keys) blob = blob.withSetting(key, SettingValue(screen.getSetting(key)))
    return blob
}

/**
 * Stored only when this attempt wrote the screen's edits and a following load would return them.
 * Host, port, username, and email come from the AccountManager record ([overlayAccountFields]).
 * A rejection or a commit that writes nothing is not stored. [checkRecord] is false before the write.
 */
internal fun settingsSaveVerdict(
    rejected: String?,
    before: Settings,
    screen: Settings,
    keysWritten: Set<String>,
    wrotePassword: Boolean,
    wroteSmtp: Boolean,
    wroteSieve: Boolean,
    needPassword: Boolean,
    needSmtp: Boolean,
    needSieve: Boolean,
    record: Map<String, String>?,
    checkRecord: Boolean,
): SettingsSaveVerdict {
    if (!rejected.isNullOrEmpty()) return SettingsSaveVerdict(false, rejected)
    val changed = screen.changedKeysAgainst(before)
    val wroteNothing = keysWritten.isEmpty() && !wrotePassword && !wroteSmtp && !wroteSieve
    if (!keysWritten.containsAll(changed) || (needPassword && !wrotePassword) || (needSmtp && !wroteSmtp) ||
        (needSieve && !wroteSieve) || wroteNothing
    ) {
        return SettingsSaveVerdict(false, settingsSaveEmptyWrite)
    }
    if (!checkRecord) return SettingsSaveVerdict(true, null)
    if (record == null) return SettingsSaveVerdict(false, settingsSaveEmptyWrite)
    val loaded = try {
        overlayAccountFields(applyWrittenKeys(before, screen, keysWritten), record)
    } catch (_: Exception) {
        return SettingsSaveVerdict(false, settingsSaveEmptyWrite)
    }
    if (accountIdentityChanged(screen, loaded)) return SettingsSaveVerdict(false, settingsSaveEmptyWrite)
    if (changed.any { loaded.getSetting(it) != screen.getSetting(it) }) {
        return SettingsSaveVerdict(false, settingsSaveEmptyWrite)
    }
    return SettingsSaveVerdict(true, null)
}

/** True when [a] and [b] have the same server fields (a draft equal to the saved settings is not a draft). */
fun sameServer(a: Settings, b: Settings): Boolean = a.withServerOf(b) == a

/**
 * Set while a screen holds unsaved edits; the top-bar Up arrow then goes through Back so the screen can ask.
 * Drawer navigation goes through [guard]: with edits pending, the holding screen's [ask] decides (Save, Keep editing
 * or Discard) and runs the navigation only after Save or Discard.
 */
object UnsavedEdits {
    @Volatile var pending: Boolean = false
    @Volatile var ask: ((go: () -> Unit) -> Unit)? = null

    fun guard(go: () -> Unit) {
        val holder = ask
        if (pending && holder != null) holder(go) else go()
    }
}
