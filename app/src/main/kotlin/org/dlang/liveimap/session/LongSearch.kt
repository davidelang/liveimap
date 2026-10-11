package org.dlang.liveimap.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** INPROGRESS state: [done]/[total] are -1 when the server sent NIL or nothing yet; [lines] counts updates. */
data class SearchProgress(val done: Long, val total: Long, val lines: Long) {
    /** 0..1 when both numbers are known, else null (show an indeterminate bar). */
    val fraction: Float?
        get() = if (done >= 0 && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

sealed class LongSearchResult {
    data class Hits(val uids: List<Long>) : LongSearchResult()
    data object Cancelled : LongSearchResult()
    data class Failed(val text: String) : LongSearchResult()
}

/** One connection used for exactly one search. */
interface LongSearchLink {
    suspend fun open()
    suspend fun search(kind: String, argument: String): List<Long>
    fun progress(): SearchProgress
    /** Drops the connection from another thread; the blocked search then fails. */
    fun abort()
    suspend fun close()
}

/**
 * A long search on its own connection so the main session stays usable. Cancel drops that connection; whether the
 * server stops searching is not assumed either way. A result that arrives after Cancel is discarded. No retry:
 * a failure is reported once and the user decides.
 */
class LongSearch(private val link: LongSearchLink, private val pollMs: Long = 250) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        link.abort()
    }

    suspend fun run(kind: String, argument: String, onProgress: (SearchProgress) -> Unit): LongSearchResult =
        coroutineScope {
            var seen = -1L
            val poller = launch {
                while (isActive) {
                    val p = link.progress()
                    if (p.lines != seen) {
                        seen = p.lines
                        onProgress(p)
                    }
                    delay(pollMs)
                }
            }
            try {
                if (cancelled) return@coroutineScope LongSearchResult.Cancelled
                withContext(Dispatchers.IO) { link.open() }
                if (cancelled) return@coroutineScope LongSearchResult.Cancelled
                val hits = withContext(Dispatchers.IO) { link.search(kind, argument) }
                if (cancelled) LongSearchResult.Cancelled else LongSearchResult.Hits(hits)
            } catch (failure: MailFailure) {
                if (cancelled) LongSearchResult.Cancelled else LongSearchResult.Failed(failure.text)
            } finally {
                poller.cancel()
                poller.join()
                try {
                    link.close()
                } catch (_: Exception) {
                }
            }
        }
}

/** Searches that can run for minutes: text and body scans. Header and flag searches stay on the main session. */
fun isLongSearch(kind: String, argument: String = ""): Boolean = when (kind) {
    "Text", "Body" -> true
    "Advanced" -> Regex("(^|\n)[^\t\n]*\t(Text|Body)\t").containsMatchIn(argument)
    else -> false
}

/**
 * Read timeout for the side connection. With INPROGRESS each progress line restarts the 60 s timeout, so a
 * search that keeps reporting never times out; without it a body search may be silent for minutes, so allow
 * 10 minutes (Cancel is always available).
 */
fun longSearchTimeoutSec(inProgress: Boolean): Int = if (inProgress) 60 else 600
