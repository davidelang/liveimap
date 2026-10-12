package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F80(e): QP / percent escapes take exactly two hex digits; a sign is not a digit. */
class HexByteTest {
    @Test fun signsAreNotHex() {
        assertNull(hexByteOrNull("-1"))
        assertNull(hexByteOrNull("+F"))
        assertNull(hexByteOrNull("G0"))
        assertNull(hexByteOrNull("A"))
        assertEquals(0xA9, hexByteOrNull("A9"))
        assertEquals(0x0f, hexByteOrNull("0f"))
    }
}
