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
                "spamMailbox",
                "markSeenOnOpen",
                "showDeleted",
                "preferHtml",
                "bodyView",
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
                "dateFormat",
                "datePattern",
                "favorites",
                "includeForwardAttachments",
                "askBeforeExpunge",
                "pipelineCommands",
                "logImapTraffic",
                "readerBar",
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
        assertTrue(text.lines().contains("includeForwardAttachments=true"))
        assertTrue(text.lines().contains("askBeforeExpunge=true"))
        assertTrue(text.lines().contains("pipelineCommands=true"))
        assertTrue(text.lines().contains("logImapTraffic=false"))
        assertTrue(text.lines().contains("readerBar=Reply;ReplyAll;Forward;Delete;Move"))
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
            bodyView = BodyView.PlainOrHtml,
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
            includeForwardAttachments = false,
            askBeforeExpunge = false,
            pipelineCommands = false,
            logImapTraffic = true,
        )
        val text = original.encode()
        assertTrue(text.contains("username=user%20name"))
        assertTrue(text.contains("displayName=Ada%2FLovelace"))
        assertTrue(text.contains("email=ada%40example.com"))
        assertTrue(text.contains("Archive%7Cx|From|oldest"))
        assertTrue(text.contains("A%3BB"))
        assertTrue(text.contains("swipeLeading=SetFlag||%5CFlagged"))
        assertTrue(text.contains("includeForwardAttachments=false"))
        assertTrue(text.contains("askBeforeExpunge=false"))
        assertTrue(text.contains("pipelineCommands=false"))
        assertTrue(text.contains("logImapTraffic=true"))
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
        val brokenFormat = AccountSettings().encode().replace("dateFormat=Short", "dateFormat=Huge")
        assertThrowsIae { decodeAccountSettings(brokenFormat) }
        val brokenView = AccountSettings().encode().replace("bodyView=PlainOrError", "bodyView=Nope")
        assertThrowsIae { decodeAccountSettings(brokenView) }
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
                    !it.startsWith("showUnreadCounts=") &&
                    !it.startsWith("dateFormat=") &&
                    !it.startsWith("datePattern=") &&
                    !it.startsWith("spamMailbox=") &&
                    !it.startsWith("includeForwardAttachments=") &&
                    !it.startsWith("askBeforeExpunge=") &&
                    !it.startsWith("pipelineCommands=") &&
                    !it.startsWith("logImapTraffic=")
            }
            .joinToString("\n")
        assertEquals(AccountSettings(), decodeAccountSettings(older))
        assertEquals("", decodeAccountSettings(older).friendlyName)
        assertEquals(ThemeMode.FollowSystem, decodeAccountSettings(older).theme)
        assertFalse(decodeAccountSettings(older).showUnreadCounts)
        assertEquals(DateFormat.Short, decodeAccountSettings(older).dateFormat)
        assertEquals("", decodeAccountSettings(older).datePattern)
        assertEquals("", decodeAccountSettings(older).spamMailbox)
        assertTrue(decodeAccountSettings(older).includeForwardAttachments)
        assertTrue(decodeAccountSettings(older).askBeforeExpunge)
        assertTrue(decodeAccountSettings(older).pipelineCommands)
        assertFalse(decodeAccountSettings(older).logImapTraffic)

        val nameLine = AccountSettings(friendlyName = "Ada/Lane").encode().lineSequence()
            .first { it.startsWith("friendlyName=") }
        val nameOnly = decodeAccountSettings(older + "\n" + nameLine)
        assertEquals("Ada/Lane", nameOnly.friendlyName)
        assertEquals(ThemeMode.FollowSystem, nameOnly.theme)

        val themeOnly = decodeAccountSettings(older + "\ntheme=Light")
        assertEquals("", themeOnly.friendlyName)
        assertEquals(ThemeMode.Light, themeOnly.theme)

        val withoutBodyView = AccountSettings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("bodyView=") }
            .joinToString("\n")
        assertEquals(BodyView.PlainOrError, decodeAccountSettings(withoutBodyView).bodyView)
        assertFalse(decodeAccountSettings(withoutBodyView).preferHtml)
        val neither = withoutBodyView.lineSequence()
            .filter { !it.startsWith("preferHtml=") }
            .joinToString("\n")
        assertEquals(BodyView.PlainOrError, decodeAccountSettings(neither).bodyView)
        assertFalse(decodeAccountSettings(neither).preferHtml)
        val oldHtml = withoutBodyView.replace("preferHtml=false", "preferHtml=true")
        assertEquals(BodyView.PlainOrHtml, decodeAccountSettings(oldHtml).bodyView)
        assertTrue(decodeAccountSettings(oldHtml).preferHtml)
        val headers = AccountSettings(bodyView = BodyView.Headers).encode()
        assertTrue(headers.contains("bodyView=Headers"))
        assertTrue(headers.contains("preferHtml=false"))
        assertEquals(BodyView.Headers, decodeAccountSettings(headers).bodyView)
        assertFalse(decodeAccountSettings(headers).preferHtml)
    }

    @Test
    fun favoriteLabel() {
        assertEquals("INBOX", favoriteLabel(false, "INBOX", '.'))
        assertEquals("INBOX.[]", favoriteLabel(true, "INBOX", '.'))
        assertEquals("(empty prefix)", favoriteLabel(false, "", '.'))
        assertEquals("(empty prefix)[]", favoriteLabel(true, "", '.'))
        val older = AccountSettings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("favorites=") }
            .joinToString("\n")
        assertEquals(emptyList<FolderFavorite>(), decodeAccountSettings(older).favorites)
        val saved = AccountSettings(
            favorites = listOf(
                FolderFavorite(false, "IN BOX", '.'),
                FolderFavorite(true, "INBOX", '.'),
            ),
        )
        val text = saved.encode()
        assertTrue(text.contains("0|IN%20BOX|."))
        assertTrue(text.contains("1|INBOX|."))
        assertEquals(saved.favorites, decodeAccountSettings(text).favorites)
        val named = FolderFavorite(false, "IN BOX", '.', "Mine/Box")
        val second = FolderFavorite(true, "INBOX", '.', "")
        val namedSaved = AccountSettings(favorites = listOf(named, second))
        val namedText = namedSaved.encode()
        assertTrue(namedText.contains("0|IN%20BOX|.|Mine%2FBox"))
        assertEquals(listOf(named, second), decodeAccountSettings(namedText).favorites)
        val swapped = namedSaved.copy(favorites = listOf(second, named))
        assertEquals(listOf(second, named), decodeAccountSettings(swapped.encode()).favorites)
        val threeField = AccountSettings().encode().lineSequence()
            .map { line ->
                if (line.startsWith("favorites=")) "favorites=0|IN%20BOX|.;1|INBOX|." else line
            }
            .joinToString("\n")
        assertEquals(
            listOf(
                FolderFavorite(false, "IN BOX", '.', ""),
                FolderFavorite(true, "INBOX", '.', ""),
            ),
            decodeAccountSettings(threeField).favorites,
        )
        val missingBar = AccountSettings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("readerBar=") }
            .joinToString("\n")
        assertEquals(defaultReaderBar, decodeAccountSettings(missingBar).readerBar)
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

    @Test
    fun labels() {
        assertEquals("Comfortable", densityLabel(Density.Medium))
        assertEquals("System default", themeLabel(ThemeMode.FollowSystem))
        assertEquals("Reply all", swipeActionLabel(SwipeAction.ReplyAll))
        assertEquals("Thread", sortKeyLabel(SortKey.ThreadReferences))
        assertEquals("Ordered subject", sortKeyLabel(SortKey.ThreadOrderedSubject))
        val older = AccountSettings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("askBeforeExpunge=") }
            .joinToString("\n")
        assertTrue(decodeAccountSettings(older).askBeforeExpunge)
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
