package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTrustTest {
    @Test
    fun listIdFromHeader() {
        val h = "From: a@b.c\r\nList-Id: Dev =?UTF-8?Q?L=C3=BCst?=\r\n <dev.example>\r\nSubject: x\r\n"
        assertEquals("dev.example", listIdKey(headerFields(h, "UTF-8").listId))
    }
}
