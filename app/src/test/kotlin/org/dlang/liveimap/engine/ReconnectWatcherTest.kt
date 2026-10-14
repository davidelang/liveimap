package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** F144: a reconnect's re-SELECT changes reach the index watcher only for the folder it watches. */
class ReconnectWatcherTest {
    @Test
    fun onlyTheWatchedFolderGetsReconnectChanges() {
        assertEquals(true, reconnectChangeReachesWatcher("INBOX", "INBOX"))
        assertEquals(true, reconnectChangeReachesWatcher("inbox", "INBOX"))
        assertEquals(false, reconnectChangeReachesWatcher("Archive", "INBOX"))
        assertEquals(false, reconnectChangeReachesWatcher("archive", "Archive"))
        assertEquals(false, reconnectChangeReachesWatcher(null, "INBOX"))
        assertEquals(false, reconnectChangeReachesWatcher("INBOX", null))
    }
}
