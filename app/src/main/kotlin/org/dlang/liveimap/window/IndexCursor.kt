package org.dlang.liveimap.window

/** Where the index was scrolled. Next and previous do not replace this with the top row. */
data class ScrollAnchor(val uid: Long, val offsetPx: Int)

/** Checked rows, the cursor, and the scroll anchor. Three fields, kept apart. */
data class IndexRecord(
    val checked: Set<Long>,
    val cursorUid: Long?,
    val anchor: ScrollAnchor?,
)

enum class RowReveal { BringOnScreen }

data class CursorStep(val index: IndexRecord, val reveal: RowReveal, val revealUid: Long)

enum class MessageAfterDelete { ShowNext, Close }

data class DeleteAtCursor(
    val message: MessageAfterDelete,
    val nextUid: Long?,
    val index: IndexRecord,
)

/** Sets the cursor. Checks and the anchor stay. */
fun IndexRecord.withOpenedMessage(uid: Long): IndexRecord = copy(cursorUid = uid)

/** New mail does not move the cursor, the checks, or the anchor. */
fun IndexRecord.afterNewMail(): IndexRecord = this

/** Moves the cursor to [neighborUid] and asks for that row on screen. The anchor stays. */
fun IndexRecord.next(neighborUid: Long): CursorStep = stepTo(neighborUid)

/** Moves the cursor to [neighborUid] and asks for that row on screen. The anchor stays. */
fun IndexRecord.previous(neighborUid: Long): CursorStep = stepTo(neighborUid)

/**
 * Both values of [deleteMovesToNext] move the cursor to [nextUid].
 * True shows that uid in the message layer. False closes the message layer.
 */
fun IndexRecord.afterDelete(deleteMovesToNext: Boolean, nextUid: Long?): DeleteAtCursor {
    val message = if (deleteMovesToNext) MessageAfterDelete.ShowNext else MessageAfterDelete.Close
    return DeleteAtCursor(message, nextUid, copy(cursorUid = nextUid))
}

private fun IndexRecord.stepTo(neighborUid: Long): CursorStep =
    CursorStep(copy(cursorUid = neighborUid), RowReveal.BringOnScreen, neighborUid)
