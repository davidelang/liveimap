package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavHostForwardTest {
    @Test
    fun navForwardChoiceMapsStoredText() {
        assertNull(navForwardChoice(""))
        assertEquals(true, navForwardChoice("true"))
        assertEquals(false, navForwardChoice("false"))
    }
}
