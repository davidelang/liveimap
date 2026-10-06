package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMapTest {
    @Test
    fun nicknameBothWays() {
        val android = listOf(
            CopyContact(
                nicknames = listOf("Ada", "Second"),
                displayName = "Ada Lovelace",
                emails = listOf("ada@example.com"),
            ),
        )
        val intoPine = copyContacts(android, emptyList(), intoPine = true)
        assertEquals(android, android.toList())
        assertEquals("Ada", intoPine.entries.single().nickname)
        assertTrue(intoPine.preview.contains("Nickname: Ada"))

        val fromName = listOf(CopyContact(displayName = "Ada Lovelace", emails = listOf("ada@example.com")))
        val suffixed = copyContacts(
            fromName,
            listOf(CopyContact(nickname = "AdaLovelace", emails = listOf("other@example.com"))),
            intoPine = true,
        )
        assertEquals("AdaLovelace2", suffixed.entries.last().nickname)

        val intoAndroid = copyContacts(
            listOf(CopyContact(nickname = "ada", displayName = "Ada", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = false,
        )
        assertEquals("ada", intoAndroid.entries.single().nickname)
        assertEquals(listOf("ada"), intoAndroid.entries.single().nicknames)
        val dropped = copyContacts(
            listOf(CopyContact(nickname = "ada", displayName = "Ada", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = false,
            options = CopyOptions(destinationKeepsNickname = false),
        )
        assertEquals("", dropped.entries.single().nickname)
        assertTrue(dropped.preview.contains("Nickname is not kept by this set."))
    }

    @Test
    fun fullNameBothWays() {
        val intoAndroid = copyContacts(
            listOf(CopyContact(nickname = "ada", displayName = "Lovelace, Ada", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = false,
        ).entries.single()
        assertEquals("Lovelace", intoAndroid.familyName)
        assertEquals("Ada", intoAndroid.givenName)
        assertEquals("Lovelace, Ada", intoAndroid.displayName)

        val displayOnly = copyContacts(
            listOf(CopyContact(nickname = "ada", displayName = "Ada Lovelace", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = false,
        )
        assertEquals("", displayOnly.entries.single().givenName)
        assertEquals("", displayOnly.entries.single().familyName)
        assertTrue(displayOnly.preview.contains("Full name: Ada Lovelace"))

        val intoPine = copyContacts(
            listOf(CopyContact(displayName = "Ada Lovelace", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = true,
        ).entries.single()
        assertEquals("Ada Lovelace", intoPine.displayName)
    }

    @Test
    fun emailBothWays() {
        val primary = copyContacts(
            listOf(
                CopyContact(
                    nicknames = listOf("ada"),
                    displayName = "Ada",
                    emails = listOf("ada@example.com", "work@example.com"),
                ),
            ),
            emptyList(),
            intoPine = true,
        )
        assertEquals(listOf("ada@example.com"), primary.entries.single().emails)
        assertTrue(primary.preview.contains("Email: ada@example.com"))

        val each = copyContacts(
            listOf(
                CopyContact(
                    nicknames = listOf("ada"),
                    displayName = "Ada",
                    emails = listOf("ada@example.com", "work@example.com"),
                ),
            ),
            emptyList(),
            intoPine = true,
            options = CopyOptions(oneEntryPerEmail = true),
        )
        assertEquals(2, each.entries.size)
        assertEquals("ada", each.entries[0].nickname)
        assertEquals("work@example.com", each.entries[1].emails.single())
        assertEquals("ada2", each.entries[1].nickname)
        assertTrue(each.preview.contains("One entry per email."))

        val intoAndroid = copyContacts(
            listOf(CopyContact(nickname = "ada", displayName = "Ada", emails = listOf("ada@example.com"))),
            emptyList(),
            intoPine = false,
        ).entries.single()
        assertEquals(listOf("ada@example.com"), intoAndroid.emails)
        assertEquals("Other", intoAndroid.emailType)
    }

    @Test
    fun distributionListBothWays() {
        val source = listOf(
            CopyContact(
                nickname = "team",
                displayName = "Team",
                address = "(ada@example.com, nested)",
                comments = "captain first",
            ),
            CopyContact(
                nickname = "nested",
                displayName = "Nested",
                address = "(bo@example.com)",
                comments = "inner",
            ),
        )
        val before = source.toList()
        val intoAndroid = copyContacts(source, emptyList(), intoPine = false)
        assertEquals(before, source)
        assertEquals("captain first", source[0].comments)
        assertTrue(intoAndroid.preview.contains("Order and the list comment are lost."))
        val group = intoAndroid.entries.first { it.group }
        assertEquals("team", group.displayName)
        assertEquals(listOf("ada@example.com", "bo@example.com"), group.members)
        assertTrue(intoAndroid.entries.any { it.emails == listOf("ada@example.com") })
        assertTrue(intoAndroid.entries.any { it.emails == listOf("bo@example.com") })

        val noGroups = copyContacts(
            source,
            emptyList(),
            intoPine = false,
            options = CopyOptions(destinationHasGroups = false),
        )
        assertTrue(noGroups.preview.contains("This set has no groups."))
        assertFalse(noGroups.entries.any { it.group })
        assertEquals(listOf("ada@example.com", "bo@example.com"), noGroups.entries.single().emails)

        val back = copyContacts(
            listOf(
                CopyContact(
                    group = true,
                    displayName = "Team",
                    members = listOf("ada@example.com", "bo@example.com"),
                ),
            ),
            emptyList(),
            intoPine = true,
        )
        assertTrue(back.preview.contains("Group becomes a list of primary emails."))
        assertEquals("(ada@example.com, bo@example.com)", back.entries.single().address)
    }

    @Test
    fun fccBothWays() {
        val intoAndroid = copyContacts(
            listOf(
                CopyContact(
                    nickname = "ada",
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    fcc = "Sent",
                ),
            ),
            emptyList(),
            intoPine = false,
        )
        assertTrue(intoAndroid.entries.single().note.contains("pine-fcc: Sent"))
        assertTrue(intoAndroid.preview.contains("Fcc kept as pine-fcc: Sent"))

        val dropped = copyContacts(
            listOf(
                CopyContact(
                    nickname = "ada",
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    fcc = "Sent",
                ),
            ),
            emptyList(),
            intoPine = false,
            options = CopyOptions(destinationKeepsNotes = false),
        )
        assertFalse(dropped.entries.single().note.contains("pine-fcc"))
        assertTrue(dropped.preview.contains("Fcc is dropped. This set has no notes."))

        val intoPine = copyContacts(
            listOf(
                CopyContact(
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    note = "pine-fcc: Sent\nhello",
                ),
            ),
            emptyList(),
            intoPine = true,
        ).entries.single()
        assertEquals("Sent", intoPine.fcc)
        assertEquals("hello", intoPine.comments)
    }

    @Test
    fun commentsBothWays() {
        val intoAndroid = copyContacts(
            listOf(
                CopyContact(
                    nickname = "ada",
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    comments = "hello there",
                ),
            ),
            emptyList(),
            intoPine = false,
        )
        assertTrue(intoAndroid.entries.single().note.contains("hello there"))
        assertTrue(intoAndroid.preview.contains("Comments kept in the note."))

        val note = "line\nbreak " + "x".repeat(1000)
        val intoPine = copyContacts(
            listOf(CopyContact(displayName = "Ada", emails = listOf("ada@example.com"), note = note)),
            emptyList(),
            intoPine = true,
        ).entries.single()
        assertFalse(intoPine.comments.contains('\n'))
        assertEquals(1000, intoPine.comments.length)
        assertTrue(intoPine.comments.startsWith("line break"))
    }

    @Test
    fun plaintextBothWays() {
        val intoAndroid = copyContacts(
            listOf(
                CopyContact(
                    nickname = "ada",
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    comments = "keep [plaintext]",
                ),
            ),
            emptyList(),
            intoPine = false,
        )
        assertTrue(intoAndroid.entries.single().note.contains("pine-plaintext: yes"))
        assertTrue(intoAndroid.preview.contains("Plaintext kept as pine-plaintext: yes"))
        assertFalse(intoAndroid.entries.single().note.contains("[plaintext]"))

        val intoPine = copyContacts(
            listOf(
                CopyContact(
                    displayName = "Ada",
                    emails = listOf("ada@example.com"),
                    note = "pine-plaintext: yes\nkeep",
                ),
            ),
            emptyList(),
            intoPine = true,
        )
        assertTrue(intoPine.entries.single().comments.contains("[plaintext]"))
        assertTrue(intoPine.entries.single().plaintext)
        assertTrue(intoPine.preview.contains("Plaintext restored."))
    }

    @Test
    fun sameEmailMergeOffIsSkippedAndSourceStays() {
        val source = mutableListOf(
            CopyContact(nickname = "ada", displayName = "Ada Lovelace", emails = listOf("Ada@Example.com")),
        )
        val before = source.toList()
        val destination = listOf(
            CopyContact(nickname = "old", displayName = "Old", emails = listOf("ada@example.com")),
        )
        val outcome = copyContacts(source, destination, intoPine = true)
        assertEquals(before, source)
        assertEquals("ada", source[0].nickname)
        assertTrue(outcome.preview.contains("Skipped, already there."))
        assertEquals("old", outcome.entries.single().nickname)
        assertFalse(outcome.entries.single().changed)
    }

    @Test
    fun droppedFieldsAreNamedAndCanBeAppended() {
        val contact = CopyContact(
            displayName = "Ada",
            emails = listOf("ada@example.com"),
            phones = listOf(CopyField("Mobile", "555")),
            postal = listOf(CopyField("Home", "1 Road")),
            organization = "Analytical",
            birthday = "1815-12-10",
            photo = "yes",
            websites = listOf(CopyField("Home", "https://example.com")),
            im = listOf(CopyField("Home", "ada")),
            custom = listOf(CopyField("Shoe", "12")),
            starred = true,
            ringtone = "bell",
            linked = true,
        )
        val dropped = copyContacts(listOf(contact), emptyList(), intoPine = true)
        val preview = dropped.preview
        assertTrue(preview.contains("Phone is dropped."))
        assertTrue(preview.contains("Postal address is dropped."))
        assertTrue(preview.contains("Organization is dropped."))
        assertTrue(preview.contains("Birthday is dropped."))
        assertTrue(preview.contains("Photo is dropped."))
        assertTrue(preview.contains("Website is dropped."))
        assertTrue(preview.contains("IM is dropped."))
        assertTrue(preview.contains("Custom label is dropped."))
        assertTrue(preview.contains("Starred is not copied."))
        assertTrue(preview.contains("Ringtone is not copied."))
        assertTrue(preview.contains("Linked contacts are not copied."))
        assertFalse(dropped.entries.single().comments.contains("555"))
        assertFalse(dropped.entries.single().comments.contains("bell"))

        val appended = copyContacts(
            listOf(contact),
            emptyList(),
            intoPine = true,
            options = CopyOptions(appendDroppedToComments = true),
        )
        val comments = appended.entries.single().comments
        assertTrue(comments.contains("Mobile: 555"))
        assertTrue(comments.contains("Home: 1 Road"))
        assertTrue(comments.contains("Organization: Analytical"))
        assertTrue(comments.contains("Birthday: 1815-12-10"))
        assertTrue(comments.contains("Photo: present"))
        assertTrue(comments.contains("Home: https://example.com"))
        assertTrue(comments.contains("Home: ada"))
        assertTrue(comments.contains("Shoe: 12"))
        assertFalse(comments.contains("bell"))
        assertTrue(appended.preview.contains("Phone appended to comments."))
    }
}
