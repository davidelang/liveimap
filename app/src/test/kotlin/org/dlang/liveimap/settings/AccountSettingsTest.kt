package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountSettingsTest {
    @Test
    fun defaultsRoundTrip() {
        val text = AccountSettings().encode()
        val keys = text.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertEquals(
            listOf(
                "imapHost",
                "imapPort",
                "smtpHost",
                "smtpPort",
                "username",
                "displayName",
                "email",
                "sentMailbox",
                "postponedMailbox",
                "addressBookMailbox",
                "markSeenOnOpen",
                "showDeleted",
                "preferHtml",
                "density",
                "defaultView",
                "folderViews",
                "expandedFolders",
                "swipeTrailing",
                "swipeLeading",
                "bounceFcc",
                "friendlyName",
                "theme",
                "showUnreadCounts",
            ),
            keys,
        )
        assertTrue(text.contains("imapPort=143"))
        assertTrue(text.contains("smtpPort=25"))
        assertTrue(text.contains("density=Compact"))
        assertTrue(text.contains("defaultView=Arrival|newest"))
        assertTrue(text.contains("\nfolderViews=\n"))
        assertTrue(text.contains("swipeTrailing=Delete||"))
        assertTrue(text.contains("swipeLeading=ReplyAll||"))
        assertTrue(text.contains("bounceFcc=false"))
        assertTrue(text.lines().contains("friendlyName="))
        assertTrue(text.lines().contains("theme=FollowSystem"))
        assertEquals(AccountSettings(), decodeAccountSettings(text))
    }

    @Test
    fun nonDefaultRoundTrip() {
        val original = AccountSettings(
            imapHost = "imap.example.com",
            imapPort = 993,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            username = "user name",
            displayName = "Ada/Lovelace",
            email = "ada@example.com",
            sentMailbox = "Sent Items",
            postponedMailbox = "Drafts",
            addressBookMailbox = "Contacts",
            markSeenOnOpen = false,
            showDeleted = false,
            preferHtml = true,
            density = Density.Large,
            defaultView = FolderView(SortKey.Subject, newestFirst = false),
            folderViews = mapOf(
                "INBOX" to FolderView(SortKey.Date, newestFirst = true),
                "Archive|x" to FolderView(SortKey.From, newestFirst = false),
            ),
            expandedFolders = setOf("INBOX", "A;B"),
            swipeTrailing = SwipeBinding(SwipeAction.Move, moveMailbox = "Trash Can", flag = ""),
            swipeLeading = SwipeBinding(SwipeAction.SetFlag, moveMailbox = "", flag = "\\Flagged"),
            bounceFcc = true,
            showUnreadCounts = true,
        )
        val text = original.encode()
        assertTrue(text.contains("username=user%20name"))
        assertTrue(text.contains("displayName=Ada%2FLovelace"))
        assertTrue(text.contains("email=ada%40example.com"))
        assertTrue(text.contains("Archive%7Cx|From|oldest"))
        assertTrue(text.contains("A%3BB"))
        assertTrue(text.contains("swipeLeading=SetFlag||%5CFlagged"))
        assertEquals(original, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())
    }

    @Test
    fun garbageThrows() {
        assertThrowsIae { decodeAccountSettings("garbage") }
        assertThrowsIae { decodeAccountSettings("notAKey=1") }
        assertThrowsIae { decodeAccountSettings(AccountSettings().encode() + "password=secret\n") }
        val brokenPort = AccountSettings().encode().replace("imapPort=143", "imapPort=nope")
        assertThrowsIae { decodeAccountSettings(brokenPort) }
        val brokenEnum = AccountSettings().encode().replace("density=Compact", "density=Huge")
        assertThrowsIae { decodeAccountSettings(brokenEnum) }
        val missing = AccountSettings().encode().lineSequence()
            .filterNot { it.startsWith("bounceFcc=") }
            .joinToString("\n")
        assertThrowsIae { decodeAccountSettings(missing) }
    }

    @Test
    fun encodedTextHasNoPasswordKey() {
        val samples = listOf(
            AccountSettings().encode(),
            AccountSettings(username = "password", displayName = "secret").encode(),
        )
        for (text in samples) {
            val keys = text.lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
            assertFalse(keys.contains("password"))
        }
        assertFalse(AccountSettings().encode().contains("password"))
    }

    @Test
    fun friendlyNameAndThemeRoundTrip() {
        val original = AccountSettings(friendlyName = "Ada/Lane", theme = ThemeMode.Dark)
        val text = original.encode()
        assertTrue(text.contains("friendlyName=Ada%2FLane"))
        assertTrue(text.contains("theme=Dark"))
        assertFalse(text.contains("password"))
        assertEquals(original, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())
    }

    @Test
    fun olderBlobMissingNewKeysDecodes() {
        val older = AccountSettings().encode().lineSequence()
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("friendlyName=") &&
                    !it.startsWith("theme=") &&
                    !it.startsWith("showUnreadCounts=")
            }
            .joinToString("\n")
        assertEquals(AccountSettings(), decodeAccountSettings(older))
        assertEquals("", decodeAccountSettings(older).friendlyName)
        assertEquals(ThemeMode.FollowSystem, decodeAccountSettings(older).theme)
        assertFalse(decodeAccountSettings(older).showUnreadCounts)

        val nameLine = AccountSettings(friendlyName = "Ada/Lane").encode().lineSequence()
            .first { it.startsWith("friendlyName=") }
        val nameOnly = decodeAccountSettings(older + "\n" + nameLine)
        assertEquals("Ada/Lane", nameOnly.friendlyName)
        assertEquals(ThemeMode.FollowSystem, nameOnly.theme)

        val themeOnly = decodeAccountSettings(older + "\ntheme=Light")
        assertEquals("", themeOnly.friendlyName)
        assertEquals(ThemeMode.Light, themeOnly.theme)
    }

    @Test
    fun looksLikeEmailCases() {
        assertTrue(looksLikeEmail("dlang@lang.hm"))
        assertFalse(looksLikeEmail("dlang"))
        assertFalse(looksLikeEmail("a@b"))
        assertTrue(looksLikeEmail("  dlang@lang.hm  "))
        assertFalse(looksLikeEmail("dlang @lang.hm"))
        assertFalse(looksLikeEmail("a@b."))
        assertFalse(looksLikeEmail("a@.b"))
        assertFalse(looksLikeEmail("a@b@c.com"))
        assertFalse(looksLikeEmail("@lang.hm"))
    }

    @Test
    fun emailDefaultedFromUsernameCases() {
        assertEquals("kept@x.y", emailDefaultedFromUsername("dlang@lang.hm", "kept@x.y"))
        assertEquals("dlang@lang.hm", emailDefaultedFromUsername("dlang@lang.hm", ""))
        assertEquals("", emailDefaultedFromUsername("dlang", ""))
        assertEquals("", emailDefaultedFromUsername("a@b", ""))
        assertEquals("  dlang@lang.hm  ", emailDefaultedFromUsername("  dlang@lang.hm  ", ""))
    }

    private fun assertThrowsIae(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
