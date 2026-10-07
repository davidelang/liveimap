package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.toolbar.BarSection
import org.dlang.liveimap.ui.toolbar.ComposeBarAction
import org.dlang.liveimap.ui.toolbar.FolderBarAction
import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarAction
import org.dlang.liveimap.ui.toolbar.SelectionBarAction
import org.dlang.liveimap.ui.toolbar.defaultComposeBar
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultSelectionBar
import org.dlang.liveimap.ui.toolbar.moveComposeAction
import org.dlang.liveimap.ui.toolbar.moveFolderAction
import org.dlang.liveimap.ui.toolbar.moveIndexAction
import org.dlang.liveimap.ui.toolbar.moveReaderAction
import org.dlang.liveimap.ui.toolbar.moveSelectionAction
import org.dlang.liveimap.ui.toolbar.readerToolbarFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                "forwardAsAttachment",
                "replyAboveQuote",
                "askBeforeExpunge",
                "pipelineCommands",
                "logImapTraffic",
                "showUserInDebugReport",
                "readerBar",
                "dynamicColor",
                "inboxStart",
                "folderStart",
                "folderStarts",
                "startAfterChange",
                "showRecentRules",
                "openAtInIndexMenu",
                "pinercStartDefault",
                "plainTextMonospace",
                "altAddresses",
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
        assertTrue(text.lines().contains("replyAboveQuote=false"))
        assertTrue(text.lines().contains("askBeforeExpunge=true"))
        assertTrue(text.lines().contains("pipelineCommands=true"))
        assertTrue(text.lines().contains("logImapTraffic=false"))
        assertTrue(text.lines().contains("readerBar=Reply;ReplyAll;Forward;Delete;Move"))
        assertTrue(text.lines().contains("forwardAsAttachment=false"))
        assertTrue(text.lines().contains("showUserInDebugReport=false"))
        assertTrue(text.lines().contains("dynamicColor=true"))
        assertTrue(text.lines().contains("inboxStart=Newest"))
        assertTrue(text.lines().contains("folderStart=Newest"))
        assertTrue(text.lines().contains("folderStarts="))
        assertTrue(text.lines().contains("startAfterChange=RerunRule"))
        assertTrue(text.lines().contains("showRecentRules=true"))
        assertTrue(text.lines().contains("openAtInIndexMenu=false"))
        assertTrue(text.lines().contains("pinercStartDefault=LeaveUnchanged"))
        assertTrue(text.lines().contains("plainTextMonospace=false"))
        assertTrue(text.lines().contains("altAddresses="))
        assertFalse(keys.contains("completionSources"))
        assertFalse(keys.contains("addressBookHistory"))
        assertFalse(keys.contains("addressBookNeverTrim"))
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
            replyAboveQuote = true,
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
        assertTrue(text.contains("replyAboveQuote=true"))
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
                    !it.startsWith("replyAboveQuote=") &&
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
        assertFalse(decodeAccountSettings(older).replyAboveQuote)
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

    @Test
    fun startPositionRoundTripAndMissingDefaults() {
        val original = AccountSettings(
            inboxStart = StartRule.FirstImportant,
            folderStart = StartRule.Last,
            folderStarts = mapOf(
                "INBOX" to StartRule.FirstUnseen,
                "Lists|alpine" to StartRule.FirstRecent,
            ),
            startAfterChange = StartAfterChange.KeepTopVisible,
            showRecentRules = false,
            openAtInIndexMenu = true,
            pinercStartDefault = PinercStartDefault.AlpineDefault,
        )
        val text = original.encode()
        assertTrue(text.contains("inboxStart=FirstImportant"))
        assertTrue(text.contains("folderStart=Last"))
        assertTrue(text.contains("Lists%7Calpine|FirstRecent"))
        assertTrue(text.contains("startAfterChange=KeepTopVisible"))
        assertTrue(text.contains("showRecentRules=false"))
        assertTrue(text.contains("openAtInIndexMenu=true"))
        assertTrue(text.contains("pinercStartDefault=AlpineDefault"))
        assertEquals(original, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())

        val older = AccountSettings().encode().lineSequence()
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("inboxStart=") &&
                    !line.startsWith("folderStart=") &&
                    !line.startsWith("folderStarts=") &&
                    !line.startsWith("startAfterChange=") &&
                    !line.startsWith("showRecentRules=") &&
                    !line.startsWith("openAtInIndexMenu=") &&
                    !line.startsWith("pinercStartDefault=")
            }
            .joinToString("\n")
        val loaded = decodeAccountSettings(older)
        assertEquals(StartRule.Newest, loaded.inboxStart)
        assertEquals(StartRule.Newest, loaded.folderStart)
        assertEquals(emptyMap<String, StartRule>(), loaded.folderStarts)
        assertEquals(StartAfterChange.RerunRule, loaded.startAfterChange)
        assertTrue(loaded.showRecentRules)
        assertFalse(loaded.openAtInIndexMenu)
        assertEquals(PinercStartDefault.LeaveUnchanged, loaded.pinercStartDefault)
    }

    @Test
    fun startRuleForOverrideInboxAndOther() {
        val settings = AccountSettings(
            inboxStart = StartRule.FirstUnseen,
            folderStart = StartRule.Last,
            folderStarts = mapOf("INBOX" to StartRule.First, "Lists" to StartRule.Newest),
        )
        assertEquals(StartRule.First, startRuleFor("INBOX", settings))
        assertEquals(StartRule.FirstUnseen, startRuleFor("inbox", settings))
        assertEquals(StartRule.FirstUnseen, startRuleFor("InBox", settings))
        assertEquals(StartRule.Newest, startRuleFor("Lists", settings))
        assertEquals(StartRule.Last, startRuleFor("Sent", settings))
        assertEquals(
            listOf(StartRule.First, StartRule.Last, StartRule.Newest),
            startRuleChoices(showRecent = false, selected = StartRule.Newest).filter { rule ->
                rule == StartRule.First || rule == StartRule.Last || rule == StartRule.Newest ||
                    rule == StartRule.FirstRecent || rule == StartRule.FirstImportantOrRecent
            },
        )
        assertFalse(startRuleChoices(false, StartRule.Newest).contains(StartRule.FirstRecent))
        assertTrue(startRuleChoices(false, StartRule.FirstRecent).contains(StartRule.FirstRecent))
        assertFalse(
            startRuleChoices(false, StartRule.FirstRecent).contains(StartRule.FirstImportantOrRecent),
        )
        assertEquals("Open this folder at…", openAtMenuText(AccountSettings(openAtInIndexMenu = true)))
        assertEquals(null, openAtMenuText(AccountSettings()))
    }

    @Test
    fun moveCommandKindFollowsMethodAndMove() {
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.CopyThenMarkDeleted, true))
        assertEquals("Move", moveCommandKind(MoveMethod.ImapMove, true))
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.ImapMove, false))
        val text = AccountSettings().encode()
        assertFalse(text.contains("autoExpunge="))
        assertFalse(text.contains("deletePolicy="))
        assertFalse(text.contains("moveMethod="))
        assertFalse(text.contains("trashMailbox="))
        val decoded = decodeAccountSettings(text)
        assertFalse(decoded.autoExpunge)
        assertEquals(DeletePolicy.MarkDeleted, decoded.deletePolicy)
        assertEquals(MoveMethod.CopyThenMarkDeleted, decoded.moveMethod)
        assertEquals("", decoded.trashMailbox)
        val chosen = AccountSettings(
            autoExpunge = true,
            deletePolicy = DeletePolicy.DeletePermanently,
            moveMethod = MoveMethod.ImapMove,
            trashMailbox = "Trash Can",
        )
        val stored = chosen.encode()
        assertTrue(stored.contains("autoExpunge=true"))
        assertTrue(stored.contains("deletePolicy=DeletePermanently"))
        assertTrue(stored.contains("moveMethod=ImapMove"))
        assertTrue(stored.contains("trashMailbox=Trash%20Can"))
        assertEquals(chosen, decodeAccountSettings(stored))
        val absent = decodeAccountSettings(text.lineSequence().filter { it.isNotEmpty() }.joinToString("\n"))
        assertEquals(AccountSettings(), absent)
    }

    @Test
    fun indexBarRoundTrip() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("indexBar"))
        assertEquals(defaultIndexBar(), decodeAccountSettings(text).indexBar)
        val explicit = text.trimEnd() + "\nindexBar=T:Refresh,Search,Filter|O:|H:\n"
        assertEquals(defaultIndexBar(), decodeAccountSettings(explicit).indexBar)
        val moved = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Overflow)
        val saved = AccountSettings(indexBar = moved)
        val encoded = saved.encode()
        assertTrue(encoded.contains("indexBar=T:Refresh,Filter|O:Search|H:"))
        assertEquals(moved, decodeAccountSettings(encoded).indexBar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val badValues = listOf(
            "T:Refresh|O:Search,Search|H:Filter",
            "T:Sort|O:|H:",
            "T:Refresh|O:|H:Filter",
            "nope",
        )
        for (value in badValues) {
            val bad = text.trimEnd() + "\nindexBar=$value\n"
            try {
                decodeAccountSettings(bad)
                fail(value)
            } catch (error: IllegalArgumentException) {
                assertEquals(value, "bad indexBar", error.message)
            }
        }
    }

    @Test
    fun selectionBarRoundTrip() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("selectionBar"))
        assertEquals(defaultSelectionBar(), decodeAccountSettings(text).selectionBar)
        val explicit = text.trimEnd() + "\nselectionBar=T:Seen,Flag,Move,Delete|O:|H:\n"
        assertEquals(defaultSelectionBar(), decodeAccountSettings(explicit).selectionBar)
        val moved = moveSelectionAction(defaultSelectionBar(), SelectionBarAction.Move, BarSection.Overflow)
        val saved = AccountSettings(selectionBar = moved)
        val encoded = saved.encode()
        assertFalse(encoded.contains("indexBar"))
        assertTrue(encoded.contains("selectionBar=T:Seen,Flag,Delete|O:Move|H:"))
        assertEquals(moved, decodeAccountSettings(encoded).selectionBar)
        assertEquals(defaultIndexBar(), decodeAccountSettings(encoded).indexBar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val badValues = listOf(
            "T:Seen|O:Move,Move|H:Delete",
            "T:Sort|O:|H:",
            "T:Seen|O:|H:Delete",
            "nope",
        )
        for (value in badValues) {
            val bad = text.trimEnd() + "\nselectionBar=$value\n"
            try {
                decodeAccountSettings(bad)
                fail(value)
            } catch (error: IllegalArgumentException) {
                assertEquals(value, "bad selectionBar", error.message)
            }
        }
    }

    @Test
    fun folderBarRoundTrip() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("folderBar"))
        assertEquals(defaultFolderBar(), decodeAccountSettings(text).folderBar)
        val explicit = text.trimEnd() + "\nfolderBar=T:Refresh|O:CollapseAll,SaveDefault,ResetDefault|H:\n"
        assertEquals(defaultFolderBar(), decodeAccountSettings(explicit).folderBar)
        val moved = moveFolderAction(defaultFolderBar(), FolderBarAction.Refresh, BarSection.Overflow)
        val saved = AccountSettings(folderBar = moved)
        val encoded = saved.encode()
        assertFalse(encoded.contains("indexBar"))
        assertFalse(encoded.contains("selectionBar"))
        assertTrue(encoded.contains("folderBar=T:|O:CollapseAll,SaveDefault,ResetDefault,Refresh|H:"))
        assertEquals(moved, decodeAccountSettings(encoded).folderBar)
        assertEquals(defaultIndexBar(), decodeAccountSettings(encoded).indexBar)
        assertEquals(defaultSelectionBar(), decodeAccountSettings(encoded).selectionBar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val bad = text.trimEnd() + "\nfolderBar=T:Refresh|O:CollapseAll,CollapseAll|H:\n"
        try {
            decodeAccountSettings(bad)
            fail("T:Refresh|O:CollapseAll,CollapseAll|H:")
        } catch (error: IllegalArgumentException) {
            assertEquals("bad folderBar", error.message)
        }
    }

    @Test
    fun readerToolbarRoundTrip() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("readerToolbar"))
        assertNull(decodeAccountSettings(text).readerToolbar)
        assertTrue(text.lines().contains("readerBar=Reply;ReplyAll;Forward;Delete;Move"))
        val layout = readerToolbarFrom(defaultReaderBar)
        val saved = AccountSettings(readerToolbar = layout)
        val encoded = saved.encode()
        assertTrue(
            encoded.contains(
                "readerToolbar=T:Refresh,Reply,ReplyAll,Forward,Delete|O:Move,Spam,Bounce|H:",
            ),
        )
        assertTrue(encoded.lines().contains("readerBar=Reply;ReplyAll;Forward;Delete;Move"))
        assertEquals(layout, decodeAccountSettings(encoded).readerToolbar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val moved = moveReaderAction(layout, ReaderToolbarAction.Bounce, BarSection.Toolbar)
        val movedText = AccountSettings(readerToolbar = moved).encode()
        assertTrue(
            movedText.contains(
                "readerToolbar=T:Refresh,Reply,ReplyAll,Forward,Delete,Bounce|O:Move,Spam|H:",
            ),
        )
        assertEquals(moved, decodeAccountSettings(movedText).readerToolbar)
        val bad = text.trimEnd() + "\nreaderToolbar=T:Refresh|O:Reply,Reply|H:\n"
        try {
            decodeAccountSettings(bad)
            fail("T:Refresh|O:Reply,Reply|H:")
        } catch (error: IllegalArgumentException) {
            assertEquals("bad readerToolbar", error.message)
        }
    }

    @Test
    fun composeBarRoundTrip() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("composeBar"))
        assertEquals(defaultComposeBar(), decodeAccountSettings(text).composeBar)
        val explicit = text.trimEnd() + "\ncomposeBar=T:|O:Postpone|H:\n"
        assertEquals(defaultComposeBar(), decodeAccountSettings(explicit).composeBar)
        val onBar = moveComposeAction(defaultComposeBar(), ComposeBarAction.Postpone, BarSection.Toolbar)
        val saved = AccountSettings(composeBar = onBar)
        val encoded = saved.encode()
        assertTrue(encoded.contains("composeBar=T:Postpone|O:|H:"))
        assertEquals(onBar, decodeAccountSettings(encoded).composeBar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val badValues = listOf(
            "T:Postpone|O:Postpone|H:",
            "T:|O:|H:",
            "T:Send|O:Postpone|H:",
            "nope",
        )
        for (value in badValues) {
            val bad = text.trimEnd() + "\ncomposeBar=$value\n"
            try {
                decodeAccountSettings(bad)
                fail(value)
            } catch (error: IllegalArgumentException) {
                assertEquals("bad composeBar", error.message)
            }
        }
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
