package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** F30: QRESYNC answers are not processed, so QRESYNC is never enabled; CONDSTORE is used instead. */
class ResyncEnableTest {
    @Test fun qresyncServersGetCondstore() {
        assertEquals("CONDSTORE", enableForResync("Qresync"))
        assertEquals("CONDSTORE", enableForResync("Condstore"))
        assertEquals("", enableForResync("None"))
    }
}
