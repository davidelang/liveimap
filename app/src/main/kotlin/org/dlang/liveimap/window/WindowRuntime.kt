package org.dlang.liveimap.window

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dlang.liveimap.MainActivity

internal object WindowIdentity {
    val record: SlotRecord by lazy {
        val pid = android.os.Process.myPid()
        val start = readProcStart(pid) ?: 0L
        SlotRecord(UUID.randomUUID().toString(), pid, start)
    }

    fun marker(): SettingsMarker = SettingsMarker(record.token, record.pid, record.startTime)
}

private fun windowsRoot(context: Context) = File(context.applicationContext.filesDir, "windows")

private fun slots(context: Context) = SlotBoard(windowsRoot(context), ::readProcStart)

private fun markers(context: Context) = MarkerBoard(windowsRoot(context), ::readProcStart)

internal fun windowClass(slot: Int): Class<out Activity> = when (slot) {
    0 -> MainActivity::class.java
    1 -> WindowActivity1::class.java
    2 -> WindowActivity2::class.java
    3 -> WindowActivity3::class.java
    4 -> WindowActivity4::class.java
    5 -> WindowActivity5::class.java
    6 -> WindowActivity6::class.java
    7 -> WindowActivity7::class.java
    else -> throw IllegalArgumentException("window")
}

internal fun claimWindow(context: Context, slot: Int): Boolean = slots(context).claim(slot, WindowIdentity.record)

internal fun releaseWindow(context: Context, slot: Int) {
    slots(context).release(slot, WindowIdentity.record)
}

internal fun firstFreeSlot(context: Context): Int? = slots(context).firstFree(WindowIdentity.record)

internal fun firstLiveSlot(context: Context): Int? = slots(context).firstLive(WindowIdentity.record)

internal fun nextFreeSlot(context: Context, start: Int): Int? = slots(context).nextFree(start, WindowIdentity.record)

internal fun startWindow(context: Context, slot: Int) {
    context.startActivity(
        Intent(context, windowClass(slot)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** The launcher returns to a live window. It does not claim a slot. */
internal fun launcherSlot(context: Context): Int = firstLiveSlot(context) ?: 0

internal object SettingsEntry {
    var generation by mutableIntStateOf(0)
        private set

    fun refuse() {
        org.dlang.liveimap.engine.DebugLog.event("screen") { "settings refused: another window holds the settings marker" }
        generation += 1
    }
}

internal object SettingsSession {
    private var depth = 0

    suspend fun enter(context: Context): Boolean {
        if (depth > 0) {
            depth += 1
            return true
        }
        val app = context.applicationContext
        val ok = withContext(Dispatchers.IO) { markers(app).enter(WindowIdentity.marker()) }
        org.dlang.liveimap.engine.DebugLog.event("screen") { "settings marker enter pid=${WindowIdentity.record.pid} ok=$ok" }
        if (ok) depth += 1
        return ok
    }

    fun leave(context: Context) {
        if (depth == 0) return
        depth -= 1
        if (depth > 0) return
        val app = context.applicationContext
        val post = Runnable {
            if (depth == 0) markers(app).remove(WindowIdentity.marker())
        }
        val main = runCatching { Looper.getMainLooper() }.getOrNull()
        if (main != null) Handler(main).post(post) else post.run()
    }

    /** The same look as enter, without writing. The caller's own marker does not count. */
    suspend fun allowWrite(context: Context): Boolean = withContext(Dispatchers.IO) {
        val look = markers(context).look(WindowIdentity.marker())
        if (!look.allow && look.held) postRefuse()
        look.allow
    }
}

private fun postRefuse() {
    val main = runCatching { Looper.getMainLooper() }.getOrNull()
    if (main != null && Looper.myLooper() !== main) {
        Handler(main).post { SettingsEntry.refuse() }
    } else {
        SettingsEntry.refuse()
    }
}

internal suspend fun requireSettingsWritable(context: Context) {
    if (!SettingsSession.allowWrite(context)) throw SettingsHeldElsewhere()
}
