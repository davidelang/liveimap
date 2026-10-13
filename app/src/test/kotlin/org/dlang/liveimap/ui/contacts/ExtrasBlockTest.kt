package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasBlockTest {
    private val android = CopyContact(
        nickname = "ada",
        displayName = "Ada Lovelace",
        emails = listOf("ada@example.com"),
        note = "likes engines",
        phones = listOf(CopyField("Mobile", "+1 555 0100")),
        postal = listOf(CopyField("Postal", "1 Main St\nAnytown")),
        organization = "Analytical Inc",
        birthday = "1815-12-10",
        websites = listOf(CopyField("Website", "https://ada.example/a=b%")),
        im = listOf(CopyField("IM", "ada]x")),
        custom = listOf(CopyField("vnd.shoe", "12")),
    )

    @Test fun escapeRoundTrips() {
        val raw = "a b\tc\nd%e=f[g]h\r"
        assertEquals(raw, extraUnescape(extraEscape(raw)))
        assertFalse(extraEscape(raw).any { it == ' ' || it == '\n' || it == ']' })
    }

    @Test fun parseKeepsUserTextAndForeignBlocks() {
        val text = "hello " + encodeExtraBlock(listOf("org" to "X Y"), oneLine = true) + " [liveimap-extra v9] z=1 [/liveimap-extra] bye"
        val p = parseExtraBlock(text)
        assertEquals(listOf("org" to "X Y"), p.entries)
        assertEquals(listOf("[liveimap-extra v9] z=1 [/liveimap-extra]"), p.foreign)
        assertTrue(p.text.startsWith("hello"))
        assertTrue(p.text.endsWith("bye"))
    }

    @Test fun androidToAlpineToAndroidRestoresFields() {
        val pine = copyContacts(listOf(android), emptyList(), intoPine = true).entries.single()
        assertFalse(pine.comments.contains("\n"))
        assertTrue(pine.comments.startsWith("likes engines "))
        val back = copyContacts(listOf(pine), emptyList(), intoPine = false).entries.single()
        assertEquals(android.phones, back.phones)
        assertEquals(android.postal, back.postal)
        assertEquals(android.organization, back.organization)
        assertEquals(android.birthday, back.birthday)
        assertEquals(android.websites, back.websites)
        assertEquals(android.im, back.im)
        // custom rows cannot be recreated as foreign mimetypes; they stay in the note's block
        assertTrue(back.note.contains("custom.vnd.shoe=12"))
        assertTrue(back.note.startsWith("likes engines"))
    }

    @Test fun alpineToAndroidToAlpineRestoresFccAndPlaintext() {
        val entry = CopyContact(nickname = "bob", displayName = "Bob", emails = listOf("bob@example.com"),
            fcc = "sent-friends", comments = "old pal [plaintext]", plaintext = true)
        val droid = copyContacts(listOf(entry), emptyList(), intoPine = false).entries.single()
        assertTrue(droid.note.contains("fcc=sent-friends"))
        val back = copyContacts(listOf(droid), emptyList(), intoPine = true).entries.single()
        assertEquals("sent-friends", back.fcc)
        assertTrue(back.plaintext)
        assertEquals("old pal [plaintext]", back.comments)
    }

    @Test fun oldPineFccLinesAreStillRead() {
        val droid = CopyContact(displayName = "C", emails = listOf("c@example.com"), note = "pine-fcc: Old\npine-plaintext: yes\nhi")
        val pine = copyContacts(listOf(droid), emptyList(), intoPine = true).entries.single()
        assertEquals("Old", pine.fcc)
        assertTrue(pine.plaintext)
    }

    @Test fun androidToAndroidKeepsNativeFields() {
        val out = copyContacts(listOf(android), emptyList(), intoPine = false).entries.single()
        assertEquals(android.phones, out.phones)
        assertEquals(android.organization, out.organization)
        assertTrue(out.note.startsWith("likes engines"))
    }

    @Test fun unknownKeysSurviveAlpineToAlpine() {
        val entry = CopyContact(nickname = "d", emails = listOf("d@example.com"),
            comments = "x " + encodeExtraBlock(listOf("future.key" to "v 1"), oneLine = true))
        val out = copyContacts(listOf(entry), emptyList(), intoPine = true).entries.single()
        assertTrue(out.comments.contains("future.key=v%201"))
    }
}
