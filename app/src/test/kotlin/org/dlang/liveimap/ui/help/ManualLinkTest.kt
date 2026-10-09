package org.dlang.liveimap.ui.help

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualLinkTest {
    @Test
    fun manualPage() {
        assertEquals(
            "https://davidelang.github.io/liveimap/manual/folders.html#menu",
            manualPage("folders.html#menu"),
        )
    }
}
