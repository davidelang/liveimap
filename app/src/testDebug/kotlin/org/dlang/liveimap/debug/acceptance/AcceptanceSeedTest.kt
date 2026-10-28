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

}
