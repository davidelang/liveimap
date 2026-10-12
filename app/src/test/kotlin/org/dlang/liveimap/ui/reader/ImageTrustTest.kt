package org.dlang.liveimap.ui.reader

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTrustTest {
    @Test
    fun keys() {
        assertEquals("ann@example.org", trustSenderKey("Ann <Ann@Example.org>"))
        assertEquals("", trustSenderKey("undisclosed"))
        assertEquals("dev.lists.example.org", listIdKey("Dev list <Dev.Lists.Example.org>"))
        assertEquals("plain.example", listIdKey(" plain.example "))
        assertEquals("", listIdKey("<bad id>"))
        assertEquals("", listIdKey("x".repeat(300)))
    }

    @Test
    fun trustBySenderOrList() {
        var t = ImageTrust()
        assertFalse(imagesTrusted(t, "ann@example.org", ""))
        t = withSender(t, "Ann <ann@example.org>", true)
        assertTrue(imagesTrusted(t, "ANN@example.org", ""))
        assertFalse(imagesTrusted(t, "bob@example.org", "<other.example>"))
        t = withList(t, "List <dev.example>", true)
        assertTrue(imagesTrusted(t, "bob@example.org", "Dev <DEV.example>"))
        t = withSender(t, "ann@example.org", false)
        assertFalse(imagesTrusted(t, "ann@example.org", ""))
    }

    @Test
    fun roundTripAndHostileFile() {
        val t = ImageTrust(setOf("a@b.c"), setOf("l.example"))
        assertEquals(t, decodeImageTrust(encodeImageTrust(t)))
        val junk = "sender not-an-address\nlist <with space>\nbogus x\n\u0000\nsender ok@x.y\n"
        assertEquals(ImageTrust(setOf("ok@x.y"), emptySet()), decodeImageTrust(junk))
        val many = (1..(MaxTrustEntries + 50)).joinToString("\n") { "sender u$it@x.y" }
        assertEquals(MaxTrustEntries, decodeImageTrust(many).senders.size)
    }

    @Test
    fun storeIsPerAccountAndRejectsPathIds() {
        val dir = Files.createTempDirectory("trust").toFile()
        val t = ImageTrust(setOf("a@b.c"))
        assertTrue(saveImageTrust(dir, "acct1", t))
        assertEquals(t, loadImageTrust(dir, "acct1"))
        assertEquals(ImageTrust(), loadImageTrust(dir, "acct2"))
        assertFalse(saveImageTrust(dir, "../x", t))
        assertNull(imageTrustFile(dir, ".."))
        dir.deleteRecursively()
    }

    @Test
    fun listIdFromHeader() {
        val h = "From: a@b.c\r\nList-Id: Dev =?UTF-8?Q?L=C3=BCst?=\r\n <dev.example>\r\nSubject: x\r\n"
        assertEquals("dev.example", listIdKey(headerFields(h).listId))
    }
}
