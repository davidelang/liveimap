package org.dlang.liveimap.engine

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLogTest {
    @Test
    fun refusedIdsHaveNoPath() {
        val cache = File("cache")
        for (id in listOf("a/b", "", " ", ".", "..", "a\\b")) {
            assertNull(id, TrafficLog.trafficLogDir(cache, id))
            assertNull(id, TrafficLog.trafficLogFile(cache, id))
        }
    }

    @Test
    fun aUuidHasADirectoryAndAShareFile() {
        val cache = File("cache")
        val id = UUID.randomUUID().toString()
        assertEquals(File(File(cache, "traffic"), id), TrafficLog.trafficLogDir(cache, id))
        assertEquals(File(File(cache, "traffic"), "$id.log"), TrafficLog.trafficLogFile(cache, id))
        assertTrue(TrafficLog.trafficLogDir(File("other"), id) != TrafficLog.trafficLogDir(cache, id))
    }
}
