package org.dlang.liveimap

import android.accounts.AccountManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountEvents
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.restoreAccounts
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.settings.DataStoreSettingsStore

/** Process start: traffic sweep now, then hourly. Holds the context sessions are built with. */
class LiveImapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            AccountManager.get(this).addOnAccountsUpdatedListener(
                { accounts -> AccountEvents.onUpdated(this, accounts) },
                null,
                true,
            )
        }
        Thread({
            runBlocking {
                restoreAccounts(this@LiveImapApplication)
                AccountStore.shared(this@LiveImapApplication).sweepPendingDeletes()
            }
        }, "account-restore").apply { isDaemon = true; start() }
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
