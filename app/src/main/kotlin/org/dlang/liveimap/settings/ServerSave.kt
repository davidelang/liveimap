package org.dlang.liveimap.settings

import org.dlang.liveimap.engine.ServerCheck

/** What Save shows before storing a server draft. The draft itself is never modified here (no revert path). */
data class ServerSaveReview(
    val tlsWarning: Int?,
    val unreachable: Boolean,
    val lines: List<String>,
) {
    /** Nothing to tell the user: Save stores at once. */
    val quiet: Boolean get() = tlsWarning == null && !unreachable && lines.isEmpty()
}

/** One review at Save: the TLS-off warning (once) plus the server check result; [check] null = check failed. */
fun serverSaveReview(draft: AccountSettings, check: ServerCheck?): ServerSaveReview = ServerSaveReview(
    tlsWarning = noTlsWarning(draft),
    unreachable = check == null || !check.reachable,
    lines = check?.let { it.conflicts + it.notes }.orEmpty(),
)

/** True when [a] and [b] have the same server fields (a draft equal to the saved settings is not a draft). */
fun sameServer(a: AccountSettings, b: AccountSettings): Boolean = a.withServerOf(b) == a

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
