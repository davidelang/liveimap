package org.dlang.liveimap.debug.acceptance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptanceSeedTest {
    @Test
    fun markerParsesVersionAndProfile() {
        assertEquals(SeedMarker("corpus-v1", false), parseSeedMarker("Subject: LiveIMAP seed corpus-v1 base\r\nX-LiveIMAP-Seed: corpus-v1\r\nX-LiveIMAP-Seed-Profile: base"))
        assertEquals(SeedMarker("corpus-v1", true), parseSeedMarker("x-liveimap-seed:corpus-v1\nX-LiveIMAP-Seed-Profile: base+scale"))
        assertNull(parseSeedMarker("Subject: hello"))
    }

    @Test
    fun gateReportsNotReset() {
        assertEquals("not reset: no seed marker", seedGate(null))
        assertEquals("not reset: seed corpus-v0, want corpus-v1", seedGate(SeedMarker("corpus-v0", false)))
        assertNull(seedGate(SeedMarker("corpus-v1", false)))
    }

    @Test
    fun labServerWithoutMarkerSkipsEveryTest() = kotlinx.coroutines.runBlocking {
        val tests = acceptanceTests.take(3)
        val ctx = AcceptanceContext(FakeLab(emptySet()), org.dlang.liveimap.settings.AccountSettings(username = "liveimap", imapHost = "lab.test")) { org.dlang.liveimap.session.OpenResult.Connected }
        val rows = runAcceptance(ctx, tests, readOnly = false, readMarker = { null })
        assertEquals(List(3) { "skip" }, rows.map { it.result })
        assertEquals("not reset: no seed marker", rows.first().reason)
    }

    @Test
    fun labHostAddressIsALabWriteHost() {
        org.junit.Assert.assertTrue(allowWriteHost("10.2.3.133"))
        org.junit.Assert.assertTrue(allowWriter("liveimap", "10.2.3.133"))
        org.junit.Assert.assertFalse(allowWriteHost("10.2.3.134"))
        org.junit.Assert.assertFalse(allowWriter("dlang", "10.2.3.133"))
    }
}
