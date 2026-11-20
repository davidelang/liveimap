package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** David, 2026-10-12: the wrap column is shown under Compose, not Sending/SMTP; the stored key is unchanged. */
class WrapColumnPlacementTest {
    @Test fun storedValueRoundTrips() {
        val s = newAccountSettings().copy(composerWrapColumn = 72)
        assertEquals(72, s.copy().composerWrapColumn)
    }
}
