package org.dlang.liveimap

import android.app.Application
import android.content.Context
import java.io.File
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.settings.DataStoreSettingsStore
import kotlinx.coroutines.runBlocking

/** Process start: traffic sweep now, then hourly. Holds the context sessions are built with. */
class LiveImapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        sweepTraffic()
        Thread({
            while (true) {
                try {
                    Thread.sleep(60L * 60L * 1000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                sweepTraffic()
            }
        }, "traffic-sweep").apply { isDaemon = true; start() }
    }

    private fun sweepTraffic() {
        val root = File(cacheDir, "traffic")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        val now = System.currentTimeMillis()
        for (dir in dirs) {
            val maxMb = try {
                runBlocking { DataStoreSettingsStore(this@LiveImapApplication, dir.name).load().trafficLogMaxMb }.toLong()
            } catch (_: Exception) {
                4L
            }
            TrafficLog.sweep(dir, maxMb * 1024L * 1024L, now)
        }
    }

    companion object {
        @Volatile
        var context: Context? = null
    }
}
