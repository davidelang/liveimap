package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeDecodeContractTest {
    @Test
    fun severalHtmlChunksCallThemedHtmlOnce() {
        assertEquals(1, themedHtmlChunkLoads(listOf("<p>one", " two", "</p>")))
    }
}
