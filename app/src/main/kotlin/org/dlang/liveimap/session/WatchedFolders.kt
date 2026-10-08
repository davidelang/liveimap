package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings

data class WatchPlan(
    val idle: List<String>,
    val status: List<String>,
)

/** Splits extra folders into IDLE slots and STATUS checks. Does not open a socket. */
fun planWatchedFolders(folders: List<String>, idleBudget: Int): WatchPlan {
    val kept = ArrayList<String>()
    val seen = HashSet<String>()
    for (name in folders) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !seen.add(trimmed)) continue
        kept.add(trimmed)
    }
    val slots = idleBudget.coerceAtLeast(0)
    return WatchPlan(kept.take(slots), kept.drop(slots))
}

/** Feeds the stored extra folders into the splitter. Does not open a socket. */
fun extraWatchPlan(settings: AccountSettings): WatchPlan =
    planWatchedFolders(settings.watchedFolders, settings.extraIdleBudget)

/** One stored name on each line. Does not open a socket. */
fun watchedFolderText(names: List<String>): String = names.joinToString("\n")

/** Trims each line, drops a blank line, and keeps a repeated name. Does not open a socket. */
fun watchedFoldersFromText(text: String): List<String> {
    val names = ArrayList<String>()
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        names.add(trimmed)
    }
    return names
}

/** Optional minus and digits that fit in an Int, or null. Does not open a socket. */
fun extraIdleBudgetFromText(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.matches(Regex("-?\\d+"))) return null
    return trimmed.toIntOrNull()
}

/** Opens extra IDLE names and checks STATUS names. Does not open a socket. */
interface ExtraWatchLink {
    suspend fun openIdle(mailbox: String)
    suspend fun closeIdle(mailbox: String)
    suspend fun status(mailbox: String)
}

/** Applies an extra-folder watch plan. Does not open a socket. */
class ExtraWatchApply {
    private val open = ArrayList<String>()

    fun openNames(): List<String> = open.toList()

    suspend fun apply(plan: WatchPlan, link: ExtraWatchLink) {
        val keepIdle = plan.idle.toHashSet()
        val stillOpen = ArrayList<String>()
        for (name in open) {
            if (name in keepIdle) {
                stillOpen.add(name)
            } else {
                link.closeIdle(name)
            }
        }
        open.clear()
        open.addAll(stillOpen)
        val openNow = stillOpen.toHashSet()
        for (name in plan.idle) {
            if (openNow.add(name)) {
                link.openIdle(name)
                open.add(name)
            }
        }
        for (name in plan.status) {
            link.status(name)
        }
    }

    suspend fun stop(link: ExtraWatchLink) {
        val closing = open.toList()
        for (name in closing) {
            link.closeIdle(name)
        }
        open.clear()
    }
}
