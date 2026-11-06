package org.dlang.liveimap.ui.index

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateMapOf
import org.dlang.liveimap.window.IndexRecord
import org.dlang.liveimap.window.MessageAfterDelete
import org.dlang.liveimap.window.afterDelete
import org.dlang.liveimap.window.withOpenedMessage

/** One cursor note for an index layer. The index applies it when it is composed. */
internal data class CursorNote(val uid: Long?, val reveal: Boolean, val token: Int)

internal object IndexMarks {
    private var token = 0
    val notes = mutableStateMapOf<String, CursorNote>()

    fun publish(key: String, uid: Long?, reveal: Boolean) {
        if (key.isEmpty()) return
        token += 1
        notes[key] = CursorNote(uid, reveal, token)
    }
}

internal enum class MarkReveal { Shown, Scroll, Expand, Load }

/** What bringing [uid] on screen has to do. A visible row does not move the scroll. */
internal fun markReveal(
    uid: Long,
    visible: Set<Long>,
    loaded: Set<Long>,
    members: Set<Long>,
    collapsedRoot: Long?,
): MarkReveal = when {
    uid in visible -> MarkReveal.Shown
    collapsedRoot != null -> MarkReveal.Expand
    uid in loaded || uid in members -> MarkReveal.Scroll
    else -> MarkReveal.Load
}

/** Root of the collapsed thread that hides [uid], or null when [uid] is already a shown row. */
internal fun collapsedRoot(hidden: Map<Long, List<Long>>, uid: Long, expanded: Set<Long>): Long? {
    for ((root, kids) in hidden) {
        if (root !in expanded && uid in kids) return root
    }
    return null
}

internal data class MarkedDelete(
    val moved: Boolean,
    val showNext: Boolean,
    val closeMessage: Boolean,
    val nextUid: Long?,
)

/**
 * Deleting the marked row moves the cursor either way.
 * [deleteMovesToNext] shows that row; otherwise the message layer closes.
 * Deleting some other row leaves the mark.
 */
internal fun markedDelete(
    cursor: Long?,
    deleted: Set<Long>,
    entire: Boolean,
    deleteMovesToNext: Boolean,
    nextUid: Long?,
): MarkedDelete {
    if (cursor == null || (!entire && cursor !in deleted)) {
        return MarkedDelete(moved = false, showNext = false, closeMessage = false, nextUid = cursor)
    }
    val step = IndexRecord(emptySet(), cursor, null).afterDelete(deleteMovesToNext, nextUid)
    val showNext = step.message == MessageAfterDelete.ShowNext && step.nextUid != null
    return MarkedDelete(
        moved = true,
        showNext = showNext,
        closeMessage = !showNext,
        nextUid = step.index.cursorUid,
    )
}

/** Opening a message writes the cursor only. */
internal fun openedCursor(cursor: Long?, uid: Long): Long? =
    IndexRecord(emptySet(), cursor, null).withOpenedMessage(uid).cursorUid

/** A filter that does not show the row draws no mark. The message layer is not part of this. */
internal fun rowMarked(cursor: Long?, uid: Long, shown: Boolean): Boolean =
    shown && cursor != null && cursor == uid

internal fun anchorIndex(entries: List<IndexEntry>, uid: Long): Int =
    entries.indexOfFirst { it is IndexEntry.Message && it.row.uid == uid }

/** Scroll only until [index] is visible. A row already on screen does not move. */
internal suspend fun bringRowOnScreen(listState: LazyListState, index: Int) {
    if (index < 0) return
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.any { it.index == index }) return
    val first = visible.firstOrNull()?.index
    val last = visible.lastOrNull()?.index
    if (first == null || last == null) {
        listState.scrollToItem(index)
        return
    }
    if (index < first) {
        listState.scrollToItem(index)
        return
    }
    val count = (last - first + 1).coerceAtLeast(1)
    listState.scrollToItem((index - count + 1).coerceAtLeast(0))
}

internal suspend fun revealCursor(
    uid: Long,
    hidden: Map<Long, List<Long>>,
    expanded: Set<Long>,
    loaded: Set<Long>,
    members: Set<Long>,
    expand: suspend (Long) -> Unit,
    load: suspend (Long) -> Unit,
    rowIndex: () -> Int,
    listState: LazyListState,
) {
    val root = collapsedRoot(hidden, uid, expanded)
    when (markReveal(uid, emptySet(), loaded, members, root)) {
        MarkReveal.Expand -> if (root != null) expand(root)
        MarkReveal.Load -> load(uid)
        MarkReveal.Shown, MarkReveal.Scroll -> Unit
    }
    bringRowOnScreen(listState, rowIndex())
}
