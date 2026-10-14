package org.dlang.liveimap.session

import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

/**
 * A folder as every operation names it: the mailbox and the UIDVALIDITY its UIDs belong to. uidValidity 0 means
 * "whatever the server has now" and is only for reads that report the epoch back (listings, counts); anything
 * that acts on UIDs must name the epoch it listed them in.
 */
data class FolderAt(val mailbox: String, val uidValidity: Long = 0L)

/** Lowest-layer helper behind [MailSession.inFolder]: select (or re-read) [folder] on [s] and check its epoch. */
suspend fun selectFolderNow(s: MailSession, folder: FolderAt, fresh: Boolean): SelectResult {
    if (fresh) s.unselect()
    val selected = s.select(folder.mailbox)
    if (folder.uidValidity != 0L && selected.uidValidity != folder.uidValidity) throw EpochChanged(folder.mailbox)

    return selected
}

/**
 * Called again after a reconnect, before the command's UIDs are used. A mismatch throws; nothing is stored,
 * expunged, or copied in the new epoch.
 */
fun epochAfterReconnect(named: Long, now: Long, mailbox: String) {
    if (named != 0L && now != named) throw EpochChanged(mailbox)
}

/** The folder's UIDVALIDITY is not the one its UIDs were listed in: nothing was sent; reload the list. */
class EpochChanged(val mailbox: String) :
    MailFailure("folder $mailbox changed on the server (UIDVALIDITY); reload the list")

/** UIDs need the epoch they were listed in; refuse a UID action without one. */
private fun FolderAt.forUids(): FolderAt {
    if (uidValidity == 0L) throw MailFailure("message reference is incomplete; reload the list")
    return this
}

/** Result of a copy or move, read in the same call as the command (COPYUID is per connection, not per folder). */
data class CopyOutcome(val copiedUids: List<Long>, val targetEpoch: Long)

/** Copy (or move, with [delete]) [uids] of [folder] to [target], all in one lowest-layer call. */
suspend fun MailSession.copyFrom(folder: FolderAt, uids: List<Long>, target: String, delete: Boolean): CopyOutcome =
    inFolder(folder.forUids()) { _, s ->
        if (delete) s.copyThenDelete(uids, target) else s.copyUids(uids, target)
        CopyOutcome(s.takeCopiedUids(), s.copiedEpoch())
    }

/** The folder's SELECT result now (fresh: counts as the server has them). */
suspend fun MailSession.folderInfo(folder: FolderAt): SelectResult = inFolder(folder, fresh = true) { sel, _ -> sel }

/**
 * A session view bound to one folder: every folder-dependent call becomes one [MailSession.inFolder] call for
 * [folder], so code written against [MailSession] (sort pipelines, searches, fetches) can never act in whatever
 * folder happens to be selected. Calls that are not folder-dependent pass through.
 */
class FolderView(internal val base: MailSession, val folder: FolderAt) : MailSession by base {
    private suspend fun <T> run(op: suspend (MailSession) -> T): T = base.inFolder(folder) { _, s -> op(s) }
    private suspend fun <T> uids(op: suspend (MailSession) -> T): T = base.inFolder(folder.forUids()) { _, s -> op(s) }

    override suspend fun select(mailbox: String): SelectResult {
        if (!sameMailbox(mailbox, folder.mailbox)) throw MailFailure("this view is for ${folder.mailbox}")
        return base.inFolder(folder, fresh = true) { sel, _ -> sel }
    }
    override suspend fun unselect() {}
    override suspend fun selectedInfo(): SelectResult = base.inFolder(folder, fresh = true) { sel, _ -> sel }
    override suspend fun selectedExists(): Int = base.inFolder(folder, fresh = true) { _, s -> s.selectedExists() }
    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        if (!sameMailbox(request.mailbox, folder.mailbox)) throw MailFailure("this view is for ${folder.mailbox}")
        return run { it.fetchIndex(request) }
    }
    override suspend fun fetchStructure(uid: Long) = uids { it.fetchStructure(uid) }
    override suspend fun fetchStructureAt(uid: Long, section: String) = uids { it.fetchStructureAt(uid, section) }
    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int) =
        uids { it.peekPart(uid, section, offset, length) }
    override suspend fun fetchRfc822(uid: Long) = uids { it.fetchRfc822(uid) }
    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) =
        uids { it.storeFlags(uids, add, remove) }
    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) = uids { it.storeFlagsAll(add, remove) }
    override suspend fun uidExpungeDeleted() = uids { it.uidExpungeDeleted() }
    override suspend fun uidExpunge(uids: List<Long>) = uids { it.uidExpunge(uids) }
    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = uids { it.copyThenDelete(uids, targetMailbox) }
    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) = uids { it.copyUids(uids, targetMailbox) }
    override suspend fun copyAllThenDelete(targetMailbox: String) = uids { it.copyAllThenDelete(targetMailbox) }
    override suspend fun searchText(query: String) = run { it.searchText(query) }
    override suspend fun searchCriterion(kind: String, argument: String) = run { it.searchCriterion(kind, argument) }
    override suspend fun searchCount(kind: String, argument: String) = run { it.searchCount(kind, argument) }
    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge) = run { it.searchStart(rule, byUid, edge) }
    override suspend fun locateUid(uid: Long) = run { it.locateUid(uid) }
    override suspend fun sort(key: SortKey, newestFirst: Boolean) = run { it.sort(key, newestFirst) }
    override suspend fun sortWindow(key: SortKey, newestFirst: Boolean, first: Int, last: Int) =
        run { it.sortWindow(key, newestFirst, first, last) }
    override suspend fun searchWindow(kind: String, argument: String, first: Int, last: Int) =
        run { it.searchWindow(kind, argument, first, last) }
    override suspend fun thread(key: SortKey) = run { it.thread(key) }
    override suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String) =
        run { it.sortMatching(key, newestFirst, criteria) }
    override suspend fun sortWindowMatching(key: SortKey, newestFirst: Boolean, first: Int, last: Int, criteria: String) =
        run { it.sortWindowMatching(key, newestFirst, first, last, criteria) }
    override suspend fun threadMatching(key: SortKey, criteria: String) = run { it.threadMatching(key, criteria) }
    override suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
    ) = run { it.clientOrder(key, newestFirst, onProgress, cancelled) }
    override suspend fun clientThread(key: SortKey) = run { it.clientThread(key) }
    override suspend fun searchScope(scopeName: String, home: String, kind: String, argument: String) =
        run { it.searchScope(scopeName, home, kind, argument) }
    override suspend fun searchScopeCount(scopeName: String, home: String, kind: String, argument: String) =
        run { it.searchScopeCount(scopeName, home, kind, argument) }
    override suspend fun expungeOnLeave(mailbox: String) = base.expungeOnLeave(mailbox)
    // Connection-level COPYUID state is only meaningful inside the call that copied; use [copyFrom].
    override suspend fun takeCopiedUids(): List<Long> = throw MailFailure("use copyFrom")
    override suspend fun copiedEpoch(): Long = throw MailFailure("use copyFrom")
}

/** This session bound to [folder]. */
fun MailSession.at(folder: FolderAt): MailSession = if (this is FolderView && this.folder == folder) this else FolderView(unbound(), folder)

/** The session under a folder view (views nest by replacing the folder, never by stacking). */
fun MailSession.unbound(): MailSession = if (this is FolderView) base else this
