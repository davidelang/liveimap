package org.dlang.liveimap.engine

import org.dlang.liveimap.session.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGateTest {
    @Test
    fun missingIdleIsRejected() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Rejected)
        assertTrue(result !is OpenResult.Connected)
        assertEquals(line, (result as OpenResult.Rejected).capabilities)
    }
}
