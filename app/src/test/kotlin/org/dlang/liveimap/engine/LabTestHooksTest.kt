package org.dlang.liveimap.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabTestHooksTest {
    @After
    fun reset() = LabTestHooks.clear()

    @Test
    fun unsetGivesNothing() {
        assertNull(LabTestHooks.passwordFor("10.0.0.10", true))
    }

    @Test
    fun onlyDebugAndOnlyTheLabHost() {
        LabTestHooks.set("10.0.0.10", "pw")
        assertEquals("pw", LabTestHooks.passwordFor("10.0.0.10", true))
        assertNull(LabTestHooks.passwordFor("10.0.0.10", false))
        assertNull(LabTestHooks.passwordFor("imap.example.com", true))
        LabTestHooks.clear()
        assertNull(LabTestHooks.passwordFor("10.0.0.10", true))
    }
}
