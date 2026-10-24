package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.MailFailure

/**
 * A sorted message order known only window by window (ESORT PARTIAL). [size] is the server's COUNT; positions
 * not loaded yet read as [Unknown] (0, never a real UID). [ensure] loads the windows covering a range on demand.
 * Bulk operations do not need the full list: select all already targets the entire mailbox.
 */
class WindowedOrder(
    private val total: Int,
    private val windowSize: Int,
    private val loader: suspend (first: Int, last: Int) -> List<Long>,
) : AbstractList<Long>() {
    private val windows = HashMap<Int, LongArray>()

    override val size: Int get() = total

    override fun get(index: Int): Long {
        if (index < 0 || index >= total) throw IndexOutOfBoundsException("$index of $total")
        val w = windows[index / windowSize] ?: return Unknown
        val off = index % windowSize
        if (off >= w.size) throw MailFailure("anchor $index")
        return w[off]
    }

    /** 1-based position in a loaded window. An unknown uid is a failure, not position 0. */
    fun sequence(uid: Long): Int {
        val index = indexOfLoaded(uid) ?: throw MailFailure("anchor $uid")
        return index + 1
    }

    fun loadedWindows(): Int = windows.size

    /** Stores a window already fetched (the first one comes with the COUNT). */
    fun seed(window: Int, uids: List<Long>) {
        windows[window] = uids.toLongArray()
    }

    /** Loads every window overlapping positions [start] until [start]+[count] (0-based). */
    suspend fun ensure(start: Int, count: Int) {
        if (count <= 0 || total == 0) return
        val from = start.coerceIn(0, total - 1) / windowSize
        val to = (start + count - 1).coerceIn(0, total - 1) / windowSize
        for (w in from..to) {
            if (w in windows) continue
            val first = w * windowSize + 1
            val last = minOf(total, first + windowSize - 1)
            val requested = last - first + 1
            val got = loader(first, last)
            if (got.size < requested) throw MailFailure("anchor $first")
            windows[w] = got.toLongArray()
        }
    }

    /**
     * Known UIDs, in order, skipping unloaded positions. F81: walks the loaded windows only, so a huge server COUNT
     * (e.g. 2147483647) costs nothing here.
     */
    fun known(): List<Long> {
        val out = ArrayList<Long>()
        for (w in windows.keys.sorted()) {
            val list = windows.getValue(w)
            val start = w * windowSize
            val limit = minOf(list.size, (total - start).coerceAtLeast(0))
            for (i in 0 until limit) {
                val id = list[i]
                if (id != Unknown) out.add(id)
            }
        }
        return out
    }

    /** First loaded position matching [uid], or null when that UID is not in a loaded window. */
    fun indexOfLoaded(uid: Long): Int? {
        if (uid == Unknown) return null
        return indexOfLoaded { it == uid }
    }

    /** First loaded position matching [match]. Scans loaded windows only and does not call [ensure]. */
    fun indexOfLoaded(match: (Long) -> Boolean): Int? {
        for (w in windows.keys.sorted()) {
            val list = windows.getValue(w)
            val start = w * windowSize
            val limit = minOf(list.size, (total - start).coerceAtLeast(0))
            for (i in 0 until limit) {
                val id = list[i]
                if (id != Unknown && match(id)) return start + i
            }
        }
        return null
    }

    /**
     * Loaded position of the highest real UID. Walks loaded windows only, ignores [Unknown], and does not call
     * [ensure]. Null when no real UID is loaded.
     */
    fun maxUidIndex(): Int? {
        var bestIndex: Int? = null
        var bestUid = Long.MIN_VALUE
        for (w in windows.keys.sorted()) {
            val list = windows.getValue(w)
            val start = w * windowSize
            val limit = minOf(list.size, (total - start).coerceAtLeast(0))
            for (i in 0 until limit) {
                val id = list[i]
                if (id == Unknown) continue
                if (bestIndex == null || id > bestUid) {
                    bestUid = id
                    bestIndex = start + i
                }
            }
        }
        return bestIndex
    }

    /** After removing [gone], positions shift: shrink only by loaded UIDs and reload windows lazily. */
    fun without(gone: Set<Long>): WindowedOrder {
        var removed = 0
        if (gone.isNotEmpty()) {
            for (uid in known()) {
                if (uid in gone) removed += 1
            }
        }
        return WindowedOrder((total - removed).coerceAtLeast(0), windowSize, loader)
    }

    /** New messages arrived: the server order changed, so start over with a larger total. */
    fun grown(by: Int): WindowedOrder = WindowedOrder(total + by, windowSize, loader)

    companion object {
        const val Unknown = 0L
    }
}

/** Removes [gone] from an order, sparse or not. */
fun orderWithout(order: List<Long>, gone: Set<Long>): List<Long> =
    if (order is WindowedOrder) order.without(gone) else compactUids(order) { it !in gone }

/** Adds newly arrived UIDs at the front or back of an order; a sparse order reloads its windows instead. */
fun orderWith(order: List<Long>, placed: List<Long>, front: Boolean): List<Long> = when {
    order is WindowedOrder -> order.grown(placed.size)
    front -> joinUids(placed, order)
    else -> joinUids(order, placed)
}

/** [uids] (optionally filtered by [keep]) as a LongArray-backed list: 8 bytes per UID, no boxed copies. */
inline fun compactUids(uids: List<Long>, keep: (Long) -> Boolean = { true }): List<Long> {
    val out = LongArray(uids.size)
    var n = 0
    for (i in uids.indices) {
        val u = uids[i]
        if (keep(u)) out[n++] = u
    }
    return (if (n == out.size) out else out.copyOf(n)).asList()
}

fun joinUids(a: List<Long>, b: List<Long>): List<Long> {
    val out = LongArray(a.size + b.size)
    for (i in a.indices) out[i] = a[i]
    for (i in b.indices) out[a.size + i] = b[i]
    return out.asList()
}

/** What the reader may step through: only real UIDs. */
fun publishableOrder(order: List<Long>): List<Long> = if (order is WindowedOrder) order.known() else order

/** Display positions [lo]..[hi] (1-based) as ascending-UID positions for ESEARCH PARTIAL. */
fun searchWindowRange(lo: Int, hi: Int, total: Int, newestFirst: Boolean): Pair<Int, Int> =
    if (newestFirst) (total - hi + 1) to (total - lo + 1) else lo to hi

/**
 * UIDs ascending or descending, stored as a LongArray behind a List view: 8 bytes per UID instead of a boxed
 * Long (about 24 bytes) per entry, which matters at 200k messages.
 */
fun sortedUids(uids: List<Long>, descending: Boolean): List<Long> {
    val a = uids.toLongArray()
    a.sort()
    if (descending) a.reverse()
    return a.asList()
}
