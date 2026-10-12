package org.dlang.liveimap.session

/**
 * One message, fully named: account, folder, UIDVALIDITY epoch and UID. A bare UID means nothing without the
 * other three (F06, F43/F98, F93, F105). uidValidity 0 = unknown (refused for writes by [onRefs]).
 */
data class MessageRef(
    val accountId: String,
    val mailbox: String,
    val uidValidity: Long,
    val uid: Long,
) {
    /** Stable key for UI lists, selections and caches. */
    val key: String get() = "$accountId\u0000$mailbox\u0000$uidValidity\u0000$uid"
}

/**
 * The single gate for actions on messages. Refuses refs of another account before any I/O; per folder, selects
 * it, checks UIDVALIDITY and fails safely on a mismatch (nothing is sent); runs [block] with that folder's UIDs;
 * finally returns the session to [home] (when given). Groups run in first-appearance order.
 */
suspend fun <T> MailSession.onRefs(
    accountId: String,
    refs: List<MessageRef>,
    home: String? = null,
    block: suspend (mailbox: String, uids: List<Long>) -> T,
): List<T> {
    if (refs.isEmpty()) return emptyList()
    if (refs.any { it.accountId != accountId }) throw MailFailure("message belongs to another account")
    if (refs.any { it.uidValidity == 0L }) throw MailFailure("message reference is incomplete; reload the list")
    val groups = LinkedHashMap<Pair<String, Long>, MutableList<Long>>()
    for (r in refs) groups.getOrPut(r.mailbox to r.uidValidity) { ArrayList() }.add(r.uid)
    val out = ArrayList<T>(groups.size)
    try {
        for ((folder, uids) in groups) {
            val selected = select(folder.first)
            if (selected.uidValidity != folder.second) {
                throw MailFailure("folder ${folder.first} changed on the server (UIDVALIDITY); reload the list")
            }
            out += block(folder.first, uids)
        }
    } finally {
        if (home != null) {
            try {
                select(home)
            } catch (_: MailFailure) {
            }
        }
    }
    return out
}
