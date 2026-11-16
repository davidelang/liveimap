package org.dlang.liveimap.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F141: an expunge ahead of the client sort walk restarts it instead of "sort failed". */
class Pass5GuardTest {
    @Test fun sortChunkPastShrunkenExistsIsDrift() {
        assertTrue(sortChunkDrift(100, 90, false))
        assertTrue(sortChunkDrift(100, 100, true))
        assertFalse(sortChunkDrift(100, 100, false))
    }
}
