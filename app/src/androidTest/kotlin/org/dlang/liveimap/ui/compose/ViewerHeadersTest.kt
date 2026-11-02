package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerHeadersTest {
    @Test
    fun listOrderAndUnfolding() {
        val h = "From: a@x\r\nX-Mailer: M\r\nList-Id: Foo\r\n <foo.x>\r\nSubject: =?UTF-8?Q?h=C3=A9?=\r\n\r\n"
        assertEquals(
            listOf("List-Id" to "Foo <foo.x>", "X-Mailer" to "M", "Subject" to "hé"),
            org.dlang.liveimap.ui.reader.viewerHeaderLines(h, "List-Id, X-Mailer, Subject, X-None", "ISO-8859-1"),
        )
    }
}
