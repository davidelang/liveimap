package org.dlang.liveimap.settings

import java.io.File
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.engine.sieve.RuleCriterion
import org.dlang.liveimap.engine.sieve.RuleField
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SavedSearch
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.SimpleSearchField
import org.dlang.liveimap.ui.reader.ReaderDisplay
import org.dlang.liveimap.ui.toolbar.BarSection
import org.dlang.liveimap.ui.toolbar.ComposeBarAction
import org.dlang.liveimap.ui.toolbar.FolderBarAction
import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarLayout
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountSettingsTest {
    @Test
    fun clientCertChoicesRoundTrip() {
        val decoded = decodeSettings("""{"clientCert":"/x.p12"}""")
        assertEquals(listOf("clientCert\n\"/x.p12\""), decoded.unknownSettings)
        val chain = Settings(clientCertAlias = "LiveIMAP lab client")
        assertSameSettings(chain, decodeSettings(chain.encode()))
        assertEquals("LiveIMAP lab client", decodeSettings(chain.encode()).clientCertAlias)
    }

    @Test
    fun compressionDefaultsOnAndRoundTrips() {
        for (mode in listOf(false, true)) {
            val a = Settings(compression = mode)
            assertSameSettings(a, roundTrip(a))
        }
        val chunk = Settings(bodyFetchChunk = 8192)
        assertEquals(8192, decodeSettings(chunk.encode()).bodyFetchChunk)
        val encoded = Settings().encode()
        assertEquals(1, decodeSettings(documentWith(encoded, "bodyFetchChunk", "1")).bodyFetchChunk)
        assertEquals(99999999, decodeSettings(documentWith(encoded, "bodyFetchChunk", "99999999")).bodyFetchChunk)
        assertNotNull(rejectedNumber(settingsTreeText(), "bodyFetchChunk", 1))
        assertNotNull(rejectedNumber(settingsTreeText(), "bodyFetchChunk", 99999999))
        assertNull(rejectedNumber(settingsTreeText(), "bodyFetchChunk", 8192))
    }

    @Test
    fun moveAllIncludesDeletedDefaultsOffAndRoundTrips() {
        val off = Settings(moveAllIncludesDeleted = false)
        val on = Settings(moveAllIncludesDeleted = true)
        assertEquals("NotDeleted", org.dlang.liveimap.ui.index.moveAllKind(off))
        assertEquals("All", org.dlang.liveimap.ui.index.moveAllKind(on))
        assertSameSettings(on, roundTrip(on))
        assertTrue(on.encodeUi().contains("\"moveAllIncludesDeleted\":true"))
        assertFalse(on.encode().contains("moveAllIncludesDeleted"))
    }

    @Test
    fun defaultsRoundTrip() {
        assertSectionKeys()
        assertFalse(Settings().encode().contains("\"readerBar\""))
        val shaped = Settings(
            density = Density.Large,
            defaultView = FolderView(SortKey.Arrival, newestFirst = true),
            folderViews = emptyMap(),
            swipeTrailing = SwipeBinding(SwipeAction.Delete),
            swipeLeading = SwipeBinding(SwipeAction.ReplyAll),
            readerSwipeTrailing = SwipeBinding(SwipeAction.Delete),
            readerSwipeLeading = SwipeBinding(SwipeAction.ReplyAll),
            bounceFcc = false,
            friendlyName = "Ada",
            includeForwardAttachments = false,
            forwardAsAttachment = true,
            replyAboveQuote = true,
            askBeforeExpunge = false,
            pipelineCommands = true,
            logImapTraffic = true,
            showUserInDebugReport = true,
            inboxStart = StartRule.FirstImportant,
            folderStart = StartRule.Last,
            folderStarts = emptyMap(),
            startAfterChange = StartAfterChange.KeepTopVisible,
            showRecentRules = false,
            openAtInIndexMenu = true,
            pinercStartDefault = PinercStartDefault.AlpineDefault,
            altAddresses = listOf("ada@example.com"),
        )
        val account = shaped.encode()
        val shapedUi = shaped.encodeUi()
        assertTrue(account.contains("\"defaultView\":{\"key\":\"Arrival\",\"newestFirst\":true}"))
        assertTrue(account.contains("\"folderViews\":{}"))
        assertTrue(shapedUi.contains("\"swipeTrailing\":{\"action\":\"Delete\",\"moveMailbox\":\"\",\"flag\":\"\"}"))
        assertTrue(shapedUi.contains("\"swipeLeading\":{\"action\":\"ReplyAll\",\"moveMailbox\":\"\",\"flag\":\"\"}"))
        assertTrue(shapedUi.contains("\"density\":\"Large\""))
        assertTrue(shapedUi.contains("\"bounceFcc\":false"))
        assertTrue(account.contains("\"friendlyName\":\"Ada\""))
        assertTrue(account.contains("\"includeForwardAttachments\":false"))
        assertTrue(account.contains("\"replyAboveQuote\":true"))
        assertTrue(shapedUi.contains("\"askBeforeExpunge\":false"))
        assertTrue(account.contains("\"pipelineCommands\":true"))
        assertTrue(account.contains("\"logImapTraffic\":true"))
        assertFalse(account.contains("\"readerBar\""))
        assertTrue(account.contains("\"forwardAsAttachment\":true"))
        assertTrue(account.contains("\"showUserInDebugReport\":true"))
        assertTrue(account.contains("\"inboxStart\":\"FirstImportant\""))
        assertTrue(account.contains("\"folderStart\":\"Last\""))
        assertTrue(account.contains("\"folderStarts\":{}"))
        assertTrue(account.contains("\"startAfterChange\":\"KeepTopVisible\""))
        assertTrue(account.contains("\"showRecentRules\":false"))
        assertTrue(account.contains("\"openAtInIndexMenu\":true"))
        assertTrue(account.contains("\"pinercStartDefault\":\"AlpineDefault\""))
        assertTrue(account.contains("\"altAddresses\":[\"ada@example.com\"]"))
        assertEquals(Density.Large, decodeSettings(shapedUi).density)
        assertSameSettings(shaped, roundTrip(shaped))
        assertSameSettings(Settings(), roundTrip(Settings()))
    }

    @Test
    fun readerSwipeDeleteRoundTrip() {
        val settings = Settings(readerSwipeTrailing = SwipeBinding(SwipeAction.Delete))
        val text = settings.encodeUi()
        assertTrue(text.contains("\"readerSwipeTrailing\":{\"action\":\"Delete\",\"moveMailbox\":\"\",\"flag\":\"\"}"))
        assertFalse(settings.encode().contains("readerSwipeTrailing"))
        assertSameSettings(settings, roundTrip(settings))
    }

    @Test
    fun retiredLayoutKeysLoadAndAreNotWritten() {
        val fresh = Settings().encode()
        for (key in listOf("multiPane", "folderPlace", "foldPosture", "paneArrangement", "customLayout")) {
            assertFalse(fresh.contains("\"$key\""))
        }
        val stored = fresh.dropLast(1) +
            ""","multiPane":"Off","folderPlace":"Beside","foldPosture":"On","paneArrangement":"Stacked","customLayout":"H0.5(list,message)"}"""
        val loaded = decodeSettings(stored)
        val again = loaded.encode()
        for (key in listOf("multiPane", "folderPlace", "foldPosture", "paneArrangement", "customLayout")) {
            assertFalse(again.contains("\"$key\""))
        }
        assertEquals(loaded, loaded.withSetting("multiPane", SettingValue("Off")))
    }

    @Test
    fun sieveHostRoundTrip() {
        val fresh = Settings()
        val defaults = fresh.encode()
        assertTrue(defaults.contains("\"sieveHost\":"))
        assertTrue(defaults.contains("\"sievePort\":"))
        assertTrue(defaults.contains("\"sieveSameAsInbound\":"))
        val set = Settings(
            imapHost = "imap.example.com",
            sieveHost = "sieve.example.com",
            sievePort = 4191,
            sieveSameAsInbound = false,
            sieveUsername = "sieve-user",
        )
        val text = set.encode()
        assertTrue(text.contains("\"sieveHost\":\"sieve.example.com\""))
        assertTrue(text.contains("\"sievePort\":4191"))
        val decoded = decodeSettings(text)
        assertSameSettings(set, decoded)
        val login = sieveTarget(decoded, "inbound", "sieve-secret")
        assertEquals("sieve.example.com", login.host)
        assertEquals(4191, login.port)
        assertEquals("sieve-user", login.username)
        assertEquals("sieve-secret", login.password)
        assertEquals(text, decoded.encode())
        assertEquals(fresh.sievePort, decodeSettings(documentWith(defaults, "sievePort", "0")).sievePort)
        assertEquals(fresh.sievePort, decodeSettings(documentWith(defaults, "sievePort", "65536")).sievePort)
    }

    @Test
    fun allowPlaintextAuthRoundTrip() {
        val defaults = Settings().encode()
        val keys = (parseJson(defaults) as JsonValue.Obj).fields.keys
        assertTrue(keys.contains("allowPlaintextAuth"))
        val set = Settings(allowPlaintextAuth = true)
        val text = set.encode()
        assertTrue(text.contains("\"allowPlaintextAuth\":true"))
        assertTrue(decodeSettings(text).allowPlaintextAuth)
        assertSameSettings(set, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun certPinRoundTrip() {
        val defaults = Settings().encode()
        val keys = (parseJson(defaults) as JsonValue.Obj).fields.keys
        assertTrue(keys.contains("certPin"))
        val pin = "ab cd:ef"
        val set = Settings(certPin = pin)
        val text = set.encode()
        assertTrue(text.contains("\"certPin\":\"ab cd:ef\""))
        assertEquals(pin, decodeSettings(text).certPin)
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun sieveCertPinRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("\"sieveCertPin\":"))
        val pin = "ab cd:ef"
        val text = Settings(sieveCertPin = pin).encode()
        assertTrue(text.contains("\"sieveCertPin\":\"ab cd:ef\""))
        assertEquals(pin, decodeSettings(text).sieveCertPin)
        val imap = Settings(certPin = "imap-pin").encode()
        val decoded = decodeSettings(imap)
        assertEquals("imap-pin", decoded.certPin)
        assertEquals(Settings().sieveCertPin, decoded.sieveCertPin)
    }

    @Test
    fun smtpUsernameRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("\"smtpUsername\":"))
        val set = Settings(smtpUsername = "post master")
        val text = set.encode()
        assertTrue(text.contains("\"smtpUsername\":\"post master\""))
        assertEquals("post master", decodeSettings(text).smtpUsername)
        assertSameSettings(set, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun tlsModeRoundTrip() {
        assertTrue(jsonKeys(Settings().encode()).contains("tlsMode"))
        val start = Settings(tlsMode = TlsMode.StartTls)
        val startText = start.encode()
        assertTrue(startText.contains("\"tlsMode\":\"StartTls\""))
        assertSameSettings(start, decodeSettings(startText))
        assertEquals(startText, decodeSettings(startText).encode())
        val implicit = Settings(tlsMode = TlsMode.Implicit)
        val implicitText = implicit.encode()
        assertTrue(implicitText.contains("\"tlsMode\":\"Implicit\""))
        assertSameSettings(implicit, decodeSettings(implicitText))
        assertEquals(implicitText, decodeSettings(implicitText).encode())
    }

    @Test
    fun newAccountUsesImplicitTls() {
        val fresh = newAccountSettings()
        assertEquals(Settings(), fresh)
        assertSameSettings(fresh, roundTrip(fresh))
    }

    @Test
    fun missingTlsModeUsesTheDefault() {
        val stored = Settings(imapPort = 1143, smtpPort = 2525).encode()
        assertTrue(jsonKeys(stored).contains("tlsMode"))
        val loaded = decodeSettings(stored)
        assertEquals(1143, loaded.imapPort)
        assertEquals(2525, loaded.smtpPort)
        val saved = loaded.encode()
        assertTrue(jsonKeys(saved).contains("tlsMode"))
        assertEquals(1143, decodeSettings(saved).imapPort)
        assertEquals(2525, decodeSettings(saved).smtpPort)
    }

    @Test
    fun ipAddressHostKeepsImplicitTls() {
        val start = Settings(
            imapHost = "192.0.2.10",
            imapPort = 993,
            smtpPort = 465,
            tlsMode = TlsMode.Implicit,
        ).withTlsMode(TlsMode.StartTls)
        assertEquals("192.0.2.10", start.imapHost)
        assertEquals(143, start.imapPort)
        val implicit = start.withTlsMode(TlsMode.Implicit)
        assertEquals("192.0.2.10", implicit.imapHost)
        assertEquals(TlsMode.Implicit, implicit.tlsMode)
        assertEquals(993, implicit.imapPort)
        assertEquals(465, implicit.smtpPort)
        assertSameSettings(implicit, decodeSettings(implicit.encode()))
    }

    @Test
    fun tlsModeMovesOnlyDefaultPorts() {
        val implicit = Settings(imapPort = 143, smtpPort = 587, tlsMode = TlsMode.StartTls).withTlsMode(TlsMode.Implicit)
        assertEquals(993, implicit.imapPort)
        assertEquals(465, implicit.smtpPort)
        val start = implicit.withTlsMode(TlsMode.StartTls)
        assertEquals(143, start.imapPort)
        assertEquals(587, start.smtpPort)
        val custom = Settings(imapPort = 1143, smtpPort = 2525, tlsMode = TlsMode.StartTls).withTlsMode(TlsMode.Implicit)
        assertEquals(1143, custom.imapPort)
        assertEquals(2525, custom.smtpPort)
        assertEquals(TlsMode.Implicit, custom.tlsMode)
    }

    @Test
    fun fullScreenRoundTrip() {
        assertTrue(jsonKeys(Settings().encodeUi()).contains("fullScreen"))
        val text = Settings(fullScreen = true).encodeUi()
        assertTrue(text.contains("\"fullScreen\":true"))
        val decoded = decodeSettings(text)
        assertTrue(decoded.fullScreen)
        assertEquals(text, decoded.encodeUi())
    }

    @Test
    fun nonDefaultRoundTrip() {
        val original = Settings(
            imapHost = "imap.example.com",
            imapPort = 993,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            username = "user name",
            displayName = "Ada/Lovelace",
            email = "ada@example.com",
            sentMailbox = "Sent Items",
            postponedMailbox = "Drafts",
            addressBooks = listOf(AddressBookEntry(kind = "pine", mailbox = "Contacts")),
            markSeenOnOpen = false,
            showDeleted = false,
            readerDisplay = ReaderDisplay.Html,
            defaultView = FolderView(SortKey.Subject, newestFirst = false),
            folderViews = mapOf(
                "INBOX" to FolderView(SortKey.Date, newestFirst = true),
                "Archive|x" to FolderView(SortKey.From, newestFirst = false),
            ),
            expandedFolders = listOf("INBOX", "A;B"),
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
        val ui = original.encodeUi()
        assertTrue(text.contains("\"username\":\"user name\""))
        assertTrue(text.contains("\"displayName\":\"Ada/Lovelace\""))
        assertTrue(text.contains("\"email\":\"ada@example.com\""))
        assertTrue(text.contains("\"Archive|x\":{\"key\":\"From\",\"newestFirst\":false}"))
        assertTrue(text.contains("\"A;B\""))
        assertTrue(text.contains("\"includeForwardAttachments\":false"))
        assertTrue(text.contains("\"replyAboveQuote\":true"))
        assertTrue(text.contains("\"pipelineCommands\":false"))
        assertTrue(text.contains("\"logImapTraffic\":true"))
        assertFalse(text.contains("\"askBeforeExpunge\""))
        assertFalse(text.contains("\"readerDisplay\""))
        assertTrue(ui.contains("\"action\":\"SetFlag\""))
        assertTrue(ui.contains("\"flag\":\"\\\\Flagged\""))
        assertTrue(ui.contains("\"askBeforeExpunge\":false"))
        assertTrue(ui.contains("\"readerDisplay\":\"Html\""))
        assertSameSettings(original, decodeSettings(text).withUiOf(decodeSettings(ui)))
        assertEquals(text, decodeSettings(text).encode())
        assertEquals(ui, decodeSettings(ui).encodeUi())
    }

    @Test
    fun garbageIsToleratedFieldByField() {
        // F69: nothing here bricks the account any more; each bad field takes its default
        val d = Settings()
        try {
            decodeSettings("garbage")
            fail("expected SettingsDocumentException")
        } catch (_: SettingsDocumentException) {
        }
        assertEquals(listOf("notAKey\n1"), decodeSettings("""{"notAKey":1}""").unknownSettings)
        assertTrue(decodeSettings(documentWith(d.encode(), "password", "\"secret\"")).unknownSettings.isEmpty())
        assertEquals(d.imapPort, decodeSettings(d.encode().replace("\"imapPort\":993", "\"imapPort\":\"nope\"")).imapPort)
        assertEquals(d.density, decodeSettings(d.encode().replace("\"density\":\"Compact\"", "\"density\":\"Huge\"")).density)
        assertEquals(d.dateFormat, decodeSettings(d.encode().replace("\"dateFormat\":\"Short\"", "\"dateFormat\":\"Huge\"")).dateFormat)
        assertEquals(
            d.readerDisplay,
            decodeSettings(d.encode().replace("\"readerDisplay\":\"Monospaced\"", "\"readerDisplay\":\"Nope\"")).readerDisplay,
        )
        val missing = documentWithout(d.encode(), "bounceFcc")
        assertEquals(d.bounceFcc, decodeSettings(missing).bounceFcc)
    }

    @Test
    fun encodedTextHasNoPasswordKey() {
        val samples = listOf(
            Settings().encode(),
            Settings(username = "password", displayName = "secret").encode(),
        )
        for (text in samples) {
            val keys = (parseJson(text) as JsonValue.Obj).fields.keys
            assertFalse(keys.contains("password"))
        }
    }

    @Test
    fun friendlyNameAndThemeRoundTrip() {
        val original = Settings(friendlyName = "Ada/Lane", theme = ThemeMode.Dark)
        val account = original.encode()
        val ui = original.encodeUi()
        assertTrue(account.contains("\"friendlyName\":\"Ada/Lane\""))
        assertFalse(account.contains("\"theme\""))
        assertTrue(ui.contains("\"theme\":\"Dark\""))
        assertFalse((parseJson(account) as JsonValue.Obj).fields.keys.contains("password"))
        assertEquals("Ada/Lane", decodeSettings(account).friendlyName)
        assertEquals(ThemeMode.Dark, decodeSettings(ui).theme)
        assertEquals(account, decodeSettings(account).encode())
        assertEquals(ui, decodeSettings(ui).encodeUi())
    }

    @Test
    fun olderBlobMissingNewKeysDecodes() {
        val account = jsonKeys(Settings().encode())
        val ui = jsonKeys(Settings().encodeUi())
        for (key in listOf(
            "friendlyName",
            "showUnreadCounts",
            "spamMailbox",
            "includeForwardAttachments",
            "replyAboveQuote",
            "pipelineCommands",
            "logImapTraffic",
        )) {
            assertTrue(account.contains(key))
        }
        for (key in listOf("theme", "dateFormat", "datePattern", "askBeforeExpunge")) {
            assertTrue(ui.contains(key))
        }
        val nameOnly = decodeSettings(documentWith(Settings().encode(), "friendlyName", "\"Ada/Lane\""))
        assertEquals("Ada/Lane", nameOnly.friendlyName)
        val themeOnly = decodeSettings(documentWith(Settings().encodeUi(), "theme", "\"Light\""))
        assertEquals(ThemeMode.Light, themeOnly.theme)
    }

    @Test
    fun readerDisplayKeepsStoredAndMapsOldPair() {
        val saved = decodeSettings(
            """{"readerDisplay":"Html","displayImages":true,"bodyView":"Raw","preferHtml":false}""",
        )
        assertEquals(ReaderDisplay.Html, saved.readerDisplay)
        assertTrue(saved.preferHtml)
        assertTrue(saved.displayImages)
        val savedText = saved.encodeUi()
        assertTrue(savedText.contains("\"readerDisplay\":\"Html\""))
        assertTrue(savedText.contains("\"displayImages\":true"))
        assertFalse(savedText.contains("\"bodyView\""))
        assertFalse(savedText.contains("\"readerTextLayout\""))
        assertFalse(savedText.contains("\"preferHtml\""))
        assertFalse(saved.encode().contains("\"readerDisplay\""))
        fun mapped(json: String) = decodeSettings(json).readerDisplay
        assertEquals(ReaderDisplay.Html, mapped("""{"bodyView":"PlainOrHtml","readerTextLayout":"Flowed","preferHtml":false}"""))
        assertEquals(ReaderDisplay.Html, mapped("""{"preferHtml":true,"readerTextLayout":"Flowed"}"""))
        assertEquals(ReaderDisplay.HtmlAsText, mapped("""{"bodyView":"PlainOrText","readerTextLayout":"Text"}"""))
        assertEquals(ReaderDisplay.HtmlAsText, mapped("""{"bodyView":"PlainOrText"}"""))
        assertEquals(ReaderDisplay.Flowed, mapped("""{"bodyView":"PlainOrText","readerTextLayout":"Flowed"}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"bodyView":"PlainOrText","readerTextLayout":"Monospaced"}"""))
        assertEquals(ReaderDisplay.Flowed, mapped("""{"bodyView":"PlainOrError","readerTextLayout":"Flowed"}"""))
        assertEquals(ReaderDisplay.Flowed, mapped("""{"bodyView":"Headers","readerTextLayout":"Flowed"}"""))
        assertEquals(ReaderDisplay.Flowed, mapped("""{"bodyView":"Raw","readerTextLayout":"Flowed"}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"bodyView":"PlainOrError","readerTextLayout":"Text"}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"bodyView":"Headers"}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"bodyView":"Raw","readerTextLayout":"Monospaced"}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"readerTextLayout":"Text","preferHtml":false}"""))
        assertEquals(ReaderDisplay.Monospaced, mapped("""{"preferHtml":false}"""))
        val fromOld = decodeSettings("""{"bodyView":"PlainOrHtml","readerTextLayout":"Text","preferHtml":true}""")
        val next = fromOld.encodeUi()
        assertEquals(ReaderDisplay.Html, fromOld.readerDisplay)
        assertTrue(next.contains("\"readerDisplay\":\"Html\""))
        assertFalse(next.contains("\"bodyView\""))
        assertFalse(next.contains("\"readerTextLayout\""))
        assertFalse(next.contains("\"preferHtml\""))
        assertEquals(ReaderDisplay.Html, decodeSettings(next).readerDisplay)
        val bad = decodeSettings("""{"readerDisplay":"Nope","bodyView":"PlainOrHtml"}""")
        assertEquals(Settings().readerDisplay, bad.readerDisplay)
        val badText = bad.encodeUi()
        assertTrue(badText.contains("\"readerDisplay\":\"Nope\""))
        assertFalse(badText.contains("\"bodyView\""))
    }

    @Test
    fun favoriteLabel() {
        assertEquals("INBOX", favoriteLabel(false, "INBOX", '.'))
        assertEquals("INBOX.[]", favoriteLabel(true, "INBOX", '.'))
        assertEquals("(empty prefix)", favoriteLabel(false, "", '.'))
        assertEquals("(empty prefix)[]", favoriteLabel(true, "", '.'))
        assertTrue(jsonKeys(Settings().encodeUi()).contains("favorites"))
        val saved = Settings(
            favorites = listOf(
                FolderFavorite(false, "IN BOX", '.'),
                FolderFavorite(true, "INBOX", '.'),
            ),
        )
        val text = saved.encodeUi()
        assertTrue(text.contains("\"mailbox\":\"IN BOX\""))
        assertTrue(text.contains("\"mailbox\":\"INBOX\""))
        assertEquals(saved.favorites, decodeSettings(text).favorites)
        val named = FolderFavorite(false, "IN BOX", '.', "Mine/Box")
        val second = FolderFavorite(true, "INBOX", '.', "")
        val namedSaved = Settings(favorites = listOf(named, second))
        val namedText = namedSaved.encodeUi()
        assertTrue(namedText.contains("\"name\":\"Mine/Box\""))
        assertEquals(listOf(named, second), decodeSettings(namedText).favorites)
        val swapped = namedSaved.copy(favorites = listOf(second, named))
        assertEquals(listOf(second, named), decodeSettings(swapped.encodeUi()).favorites)
        val threeField = documentWith(
            Settings().encodeUi(),
            "favorites",
            """[{"mailbox":"IN BOX","delimiter":".","container":false},{"mailbox":"INBOX","delimiter":".","container":true}]""",
        )
        assertEquals(
            listOf(
                FolderFavorite(false, "IN BOX", '.', ""),
                FolderFavorite(true, "INBOX", '.', ""),
            ),
            decodeSettings(threeField).favorites,
        )
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
        assertEquals("Thread", sortKeyLabel(SortKey.ThreadReferences))
        assertEquals("Ordered subject", sortKeyLabel(SortKey.ThreadOrderedSubject))
        assertTrue(jsonKeys(Settings().encodeUi()).contains("askBeforeExpunge"))
    }

    @Test
    fun startPositionRoundTripAndMissingDefaults() {
        val original = Settings(
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
        assertTrue(text.contains("\"inboxStart\":\"FirstImportant\""))
        assertTrue(text.contains("\"folderStart\":\"Last\""))
        assertTrue(text.contains("\"Lists|alpine\":\"FirstRecent\""))
        assertTrue(text.contains("\"startAfterChange\":\"KeepTopVisible\""))
        assertTrue(text.contains("\"showRecentRules\":false"))
        assertTrue(text.contains("\"openAtInIndexMenu\":true"))
        assertTrue(text.contains("\"pinercStartDefault\":\"AlpineDefault\""))
        assertSameSettings(original, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
        val account = jsonKeys(Settings().encode())
        for (key in listOf(
            "inboxStart",
            "folderStart",
            "folderStarts",
            "startAfterChange",
            "showRecentRules",
            "openAtInIndexMenu",
            "pinercStartDefault",
        )) {
            assertTrue(account.contains(key))
        }
    }

    @Test
    fun startRuleForOverrideInboxAndOther() {
        val settings = Settings(
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
    }

    @Test
    fun moveCommandKindFollowsMethodAndMove() {
        assertEquals("CopyThenDelete", moveCommandKind(disableImapMove = true, moveAdvertised = true))
        assertEquals("Move", moveCommandKind(disableImapMove = false, moveAdvertised = true))
        assertEquals("CopyThenDelete", moveCommandKind(disableImapMove = false, moveAdvertised = false))
        val chosen = Settings(
            autoExpunge = true,
            deletePolicy = DeletePolicy.DeletePermanently,
            disableImapMove = true,
            trashMailbox = "Trash Can",
        )
        val stored = chosen.encode()
        val storedUi = chosen.encodeUi()
        assertFalse(stored.contains("\"autoExpunge\""))
        assertFalse(stored.contains("\"deletePolicy\""))
        assertFalse(stored.contains("\"disableImapMove\""))
        assertTrue(storedUi.contains("\"autoExpunge\":true"))
        assertTrue(storedUi.contains("\"deletePolicy\":\"DeletePermanently\""))
        assertTrue(storedUi.contains("\"disableImapMove\":true"))
        assertTrue(stored.contains("\"trashMailbox\":\"Trash Can\""))
        val back = decodeSettings(stored).withUiOf(decodeSettings(storedUi))
        assertTrue(back.autoExpunge)
        assertEquals(DeletePolicy.DeletePermanently, back.deletePolicy)
        assertEquals(true, back.disableImapMove)
        assertEquals("Trash Can", back.trashMailbox)
        assertSameSettings(chosen, back)
    }

    @Test
    fun savedMailboxRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("\"savedMailbox\":"))
        val saved = Settings(savedMailbox = "Saved Mail")
        val encoded = saved.encode()
        assertTrue(encoded.contains("\"savedMailbox\":\"Saved Mail\""))
        assertSameSettings(saved, decodeSettings(encoded))
        assertEquals(encoded, decodeSettings(encoded).encode())
    }

    @Test
    fun saveNameRuleRoundTrip() {
        val text = Settings().encode()
        val ui = Settings().encodeUi()
        assertTrue(text.contains("\"saveNameRule\":"))
        assertTrue(ui.contains("\"lastSaveMailbox\":"))
        val saved = Settings(saveNameRule = SaveNameRule.ByFrom, lastSaveMailbox = "Archive")
        val encoded = saved.encode()
        val encodedUi = saved.encodeUi()
        assertTrue(encoded.contains("\"saveNameRule\":\"ByFrom\""))
        assertTrue(encodedUi.contains("\"lastSaveMailbox\":\"Archive\""))
        assertEquals(SaveNameRule.ByFrom, decodeSettings(encoded).saveNameRule)
        assertEquals("Archive", decodeSettings(encodedUi).lastSaveMailbox)
        assertEquals(encoded, decodeSettings(encoded).encode())
        assertEquals(encodedUi, decodeSettings(encodedUi).encodeUi())
        val spaced = Settings(lastSaveMailbox = "My Archive")
        val spacedText = spaced.encodeUi()
        assertTrue(spacedText.contains("\"lastSaveMailbox\":\"My Archive\""))
        assertEquals("My Archive", decodeSettings(spacedText).lastSaveMailbox)
        assertEquals(Settings().saveNameRule, decodeSettings(spaced.encode()).saveNameRule)
    }

    @Test
    fun quellFlowedRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("\"quellFlowed\":"))
        val saved = Settings(quellFlowed = true)
        val encoded = saved.encode()
        assertTrue(encoded.contains("\"quellFlowed\":true"))
        assertTrue(decodeSettings(encoded).quellFlowed)
        assertSameSettings(saved, decodeSettings(encoded))
    }

    @Test
    fun composerWrapColumnRoundTrips() {
        val text = Settings().encode()
        assertTrue(text.contains("\"composerWrapColumn\":"))
        val saved = Settings(composerWrapColumn = 72)
        val encoded = saved.encode()
        assertTrue(encoded.contains("\"composerWrapColumn\":72"))
        assertEquals(72, decodeSettings(encoded).composerWrapColumn)
        assertEquals(encoded, decodeSettings(encoded).encode())
        assertEquals(0, decodeSettings(documentWith(text, "composerWrapColumn", "0")).composerWrapColumn)
        assertEquals(2000, decodeSettings(documentWith(text, "composerWrapColumn", "2000")).composerWrapColumn)
        assertEquals(-3, decodeSettings(documentWith(text, "composerWrapColumn", "-3")).composerWrapColumn)
        assertNotNull(rejectedNumber(settingsTreeText(), "composerWrapColumn", -1))
        assertNotNull(rejectedNumber(settingsTreeText(), "composerWrapColumn", 999))
        assertNull(rejectedNumber(settingsTreeText(), "composerWrapColumn", 72))
    }

    @Test
    fun inboundRulesRoundTrip() {
        val rule = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "ann@example.com")),
            fileInto = "INBOX.lists",
        )
        val saved = Settings(inboundRules = listOf(rule))
        val text = saved.encode()
        assertTrue(text.contains("\"inboundRules\":"))
        assertTrue(text.contains("From=ann%40example.com\\tfile=INBOX.lists"))
        assertEquals(listOf(rule), decodeSettings(text).inboundRules)
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun watchedFoldersRoundTrip() {
        val fresh = Settings()
        val defaults = fresh.encode()
        val keys = (parseJson(defaults) as JsonValue.Obj).fields.keys
        assertTrue(keys.contains("watchedFolders"))
        assertTrue(keys.contains("extraIdleBudget"))

        val spaced = Settings(
            watchedFolders = listOf("INBOX", "Sent Mail"),
            extraIdleBudget = 0,
        )
        val spacedText = spaced.encode()
        assertTrue(spacedText.contains("\"watchedFolders\":[\"INBOX\",\"Sent Mail\"]"))
        assertTrue(spacedText.contains("\"extraIdleBudget\":0"))
        assertEquals(spaced.watchedFolders, decodeSettings(spacedText).watchedFolders)
        assertEquals(0, decodeSettings(spacedText).extraIdleBudget)

        val four = Settings(
            watchedFolders = listOf("A", "B", "C", "D"),
            extraIdleBudget = 2,
        )
        val fourText = four.encode()
        assertTrue(fourText.contains("\"watchedFolders\":[\"A\",\"B\",\"C\",\"D\"]"))
        assertTrue(fourText.contains("\"extraIdleBudget\":2"))
        assertEquals(four.watchedFolders, decodeSettings(fourText).watchedFolders)

        val duplicates = Settings(
            watchedFolders = listOf("INBOX", "INBOX", "Sent"),
            extraIdleBudget = 1,
        )
        val duplicatesText = duplicates.encode()
        assertTrue(duplicatesText.contains("\"watchedFolders\":[\"INBOX\",\"INBOX\",\"Sent\"]"))
        assertTrue(duplicatesText.contains("\"extraIdleBudget\":1"))
        assertEquals(listOf("INBOX", "INBOX", "Sent"), decodeSettings(duplicatesText).watchedFolders)
        assertEquals(1, decodeSettings(duplicatesText).extraIdleBudget)

        val withFolders = documentWith(defaults, "watchedFolders", """["INBOX"]""")
        assertEquals(listOf("INBOX"), decodeSettings(withFolders).watchedFolders)
        assertTrue(jsonKeys(withFolders).contains("extraIdleBudget"))
        assertEquals(0, decodeSettings(documentWith(defaults, "extraIdleBudget", "0")).extraIdleBudget)
        assertEquals(-3, decodeSettings(documentWith(defaults, "extraIdleBudget", "-3")).extraIdleBudget)
        assertNotNull(rejectedNumber(settingsTreeText(), "extraIdleBudget", -3))
        assertNull(rejectedNumber(settingsTreeText(), "extraIdleBudget", 2))
    }

    @Test
    fun savedSearchesRoundTrip() {
        val defaults = Settings().encode()
        val keys = (parseJson(defaults) as JsonValue.Obj).fields.keys
        assertTrue(keys.contains("savedSearches"))

        val saved = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("Sent Mail", "a|b", SimpleSearchField.Subject),
        )
        val settings = Settings(savedSearches = saved)
        val text = settings.encode()
        assertTrue(text.contains("\"name\":\"News\""))
        assertTrue(text.contains("\"query\":\"ada\""))
        assertTrue(text.contains("\"field\":\"From\""))
        assertTrue(text.contains("\"name\":\"Sent Mail\""))
        assertTrue(text.contains("\"query\":\"a|b\""))
        assertTrue(text.contains("\"field\":\"Subject\""))
        assertEquals(saved, decodeSettings(text).savedSearches)
        assertEquals(text, decodeSettings(text).encode())

        val repeated = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("News", "ada", SimpleSearchField.From),
        )
        assertEquals(repeated, decodeSettings(Settings(savedSearches = repeated).encode()).savedSearches)
        val dirty = documentWith(
            defaults,
            "savedSearches",
            """[{"name":"News","query":"ada","field":"Nope"}]""",
        )
        val bad = decodeSettings(dirty)
        assertEquals(Settings().savedSearches, bad.savedSearches)
        assertTrue(bad.unknownSettings.any { it.startsWith("savedSearches\n") })
    }

    @Test
    fun savedAdvancedRoundTrip() {
        val defaults = Settings().encode()
        val keys = (parseJson(defaults) as JsonValue.Obj).fields.keys
        assertTrue(keys.contains("savedAdvanced"))

        val one = SavedAdvanced("News", "And\nYes\tSubject\tada", SearchScope.Current)
        val oneSettings = Settings(savedAdvanced = listOf(one))
        val oneText = oneSettings.encode()
        assertTrue(oneText.contains("\"name\":\"News\""))
        assertTrue(oneText.contains("\"scope\":\"Current\""))
        assertEquals(listOf(one), decodeSettings(oneText).savedAdvanced)
        assertEquals(oneText, decodeSettings(oneText).encode())

        val saved = listOf(
            one,
            SavedAdvanced("Sent Mail", "a|b,c", SearchScope.All),
            SavedAdvanced("News", "ada", SearchScope.Subtree),
        )
        val settings = Settings(savedAdvanced = saved)
        val text = settings.encode()
        assertTrue(text.contains("\"name\":\"Sent Mail\""))
        assertTrue(text.contains("\"text\":\"a|b,c\""))
        assertTrue(text.contains("\"scope\":\"All\""))
        assertTrue(text.contains("\"scope\":\"Subtree\""))
        assertEquals(saved, decodeSettings(text).savedAdvanced)
        assertEquals(text, decodeSettings(text).encode())

        val repeated = listOf(
            SavedAdvanced("News", "ada", SearchScope.Current),
            SavedAdvanced("News", "bob", SearchScope.All),
        )
        assertEquals(repeated, decodeSettings(Settings(savedAdvanced = repeated).encode()).savedAdvanced)
        val dropped = documentWith(defaults, "savedAdvanced", """[{"name":"News","text":"ada","scope":"Nope"}]""")
        val bad = decodeSettings(dropped)
        assertEquals(Settings().savedAdvanced, bad.savedAdvanced)
        assertTrue(bad.unknownSettings.any { it.startsWith("savedAdvanced\n") })
    }

    @Test
    fun emailSubmissionRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("\"emailSubmission\":"))
        val saved = Settings(emailSubmission = true)
        val encoded = saved.encode()
        assertTrue(encoded.contains("\"emailSubmission\":true"))
        assertTrue(decodeSettings(encoded).emailSubmission)
        assertEquals(encoded, decodeSettings(encoded).encode())
        assertFalse(decodeSettings(documentWith(text, "emailSubmission", "false")).emailSubmission)
        assertTrue(decodeSettings(documentWith(text, "emailSubmission", "true")).emailSubmission)
    }

    @Test
    fun askThresholdsRoundTrip() {
        val fresh = Settings()
        val defaults = fresh.encode()
        val present = jsonKeys(defaults)
        for (key in listOf(
            "askSort",
            "askThread",
            "askSearch",
            "askFallback",
            "sortAskSkip",
            "threadAskSkip",
            "searchAskSkip",
            "fallbackAskSkip",
        )) {
            assertTrue(present.contains(key))
        }
        val saved = Settings(askSort = 20L, threadAskSkip = listOf("Sent Mail"))
        val text = saved.encode()
        assertTrue(text.contains("\"askSort\":20"))
        assertTrue(text.contains("\"threadAskSkip\":[\"Sent Mail\"]"))
        val back = decodeSettings(text)
        assertEquals(20L, back.askSort)
        assertEquals(fresh.askThread, back.askThread)
        assertEquals(fresh.askSearch, back.askSearch)
        assertEquals(fresh.askFallback, back.askFallback)
        assertEquals(listOf("Sent Mail"), back.threadAskSkip)
        assertEquals(fresh.sortAskSkip, back.sortAskSkip)
        assertEquals(fresh.searchAskSkip, back.searchAskSkip)
        assertEquals(fresh.fallbackAskSkip, back.fallbackAskSkip)
        assertEquals(-7L, decodeSettings(documentWith(defaults, "askFallback", "-7")).askFallback)
        assertFalse(overAskThreshold(5001, -1))
        assertFalse(overAskThreshold(5000, 5000))
        assertTrue(overAskThreshold(5001, 5000))
        assertTrue(overAskThreshold(1, 0))
        assertFalse(overAskThreshold(Int.MAX_VALUE, Int.MAX_VALUE.toLong()))
    }

    @Test
    fun oneBadInboundRuleIsSkipped() {
        val first = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "ann@example.com")),
            fileInto = "INBOX.lists",
        )
        val second = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.Subject, "x")),
            fileInto = "Sent",
        )
        val text = documentWith(
            Settings().encode(),
            "inboundRules",
            """["From=ann%40example.com\tfile=INBOX.lists","bogus\tzz","Subject=x\tfile=Sent"]""",
        )
        val decoded = decodeSettings(text)
        assertEquals(listOf(first, second), decoded.inboundRules)
        val encoded = decoded.encode()
        assertTrue(encoded.contains("From=ann%40example.com\\tfile=INBOX.lists"))
        assertTrue(encoded.contains("Subject=x\\tfile=Sent"))
        assertFalse(encoded.contains("bogus"))
    }

    @Test
    fun brokenInboundRulesDecodeEmpty() {
        val text = documentWith(
            Settings().encode(),
            "inboundRules",
            """["bogus\tzz","also\tbad"]""",
        )
        val decoded = decodeSettings(text)
        assertEquals(emptyList<InboundRule>(), decoded.inboundRules)
        assertTrue(decoded.unknownSettings.none { it.startsWith("inboundRules\n") })
        val encoded = decoded.encode()
        assertFalse(encoded.contains("bogus"))
        assertFalse(encoded.contains("also\\tbad"))
        assertTrue(encoded.contains("\"inboundRules\":[]"))
    }

    @Test
    fun duplicateJsonEntriesAreAllKept() {
        val text = documentWith(
            documentWith(
                documentWith(
                    documentWith(
                        Settings().encode(),
                        "favorites",
                        """[{"mailbox":"INBOX","name":"In","container":true,"delimiter":"/"},{"mailbox":"Sent","name":"S","container":false,"delimiter":"/"},{"mailbox":"INBOX","name":"Again","container":true,"delimiter":"/"}]""",
                    ),
                    "folderStarts",
                    """{"INBOX":"First","Sent":"Newest"}""",
                ),
                "folderViews",
                """{"INBOX":{"key":"Arrival","newestFirst":true},"Sent":{"key":"Date","newestFirst":false}}""",
            ),
            "expandedFolders",
            """["INBOX","Sent","INBOX"]""",
        )
        val decoded = decodeSettings(text)
        assertEquals(
            listOf(
                FolderFavorite(true, "INBOX", '/', "In"),
                FolderFavorite(false, "Sent", '/', "S"),
                FolderFavorite(true, "INBOX", '/', "Again"),
            ),
            decoded.favorites,
        )
        assertEquals(
            mapOf("INBOX" to StartRule.First, "Sent" to StartRule.Newest),
            decoded.folderStarts,
        )
        assertEquals(
            mapOf(
                "INBOX" to FolderView(SortKey.Arrival, true),
                "Sent" to FolderView(SortKey.Date, false),
            ),
            decoded.folderViews,
        )
        assertEquals(listOf("INBOX", "Sent", "INBOX"), decoded.expandedFolders)
        val encoded = decoded.encode()
        assertTrue(encoded.contains("\"Again\""))
        assertTrue(encoded.contains("\"INBOX\":\"First\""))
        assertTrue(encoded.contains("\"Sent\":{\"key\":\"Date\",\"newestFirst\":false}"))
        assertTrue(encoded.contains("\"expandedFolders\":[\"INBOX\",\"Sent\",\"INBOX\"]"))
        val badStarts = decodeSettings(documentWith(Settings().encode(), "folderStarts", """{"INBOX":"Nope"}"""))
        assertEquals(emptyMap<String, StartRule>(), badStarts.folderStarts)
        assertTrue(badStarts.unknownSettings.any { it.startsWith("folderStarts\n") })
        val badViews = decodeSettings(documentWith(Settings().encode(), "folderViews", """{"INBOX":"bad"}"""))
        assertEquals(emptyMap<String, FolderView>(), badViews.folderViews)
        assertTrue(badViews.unknownSettings.any { it.startsWith("folderViews\n") })
    }

    @Test
    fun unreadableTlsModeIsKeptRaw() {
        val text = documentWith(Settings().encode(), "tlsMode", "\"NotAMode\"")
        val decoded = decodeSettings(text)
        assertEquals(Settings().tlsMode, decoded.tlsMode)
        assertTrue(decoded.unknownSettings.contains("tlsMode\n\"NotAMode\""))
        val encoded = decoded.encode()
        assertTrue(encoded.contains("\"tlsMode\":\"NotAMode\""))
        assertFalse(encoded.contains("\"tlsMode\":\"${Settings().tlsMode.name}\""))
        val edited = decoded.copy(tlsMode = TlsMode.StartTls)
        val replaced = edited.encode()
        assertTrue(replaced.contains("\"tlsMode\":\"StartTls\""))
        assertFalse(replaced.contains("NotAMode"))
    }

    @Test
    fun preservedSecurityKeyNamesTheUnreadableLine() {
        assertEquals("tlsMode", Settings(unknownSettings = listOf("tlsMode\n\"NotAMode\"")).preservedSecurityKey())
        assertEquals("deletePolicy", Settings(unknownSettings = listOf("deletePolicy\n\"Nope\"")).preservedSecurityKey())
        assertNull(Settings(unknownSettings = listOf("moveMethod\n\"Nope\"")).preservedSecurityKey())
        assertEquals(
            "tlsMode",
            Settings(
                unknownSettings = listOf("moveMethod\n\"Nope\"", "deletePolicy\n\"Nope\"", "tlsMode\n\"NotAMode\""),
            ).preservedSecurityKey(),
        )
        assertEquals(
            "deletePolicy",
            Settings(unknownSettings = listOf("moveMethod\n\"Nope\"", "deletePolicy\n\"Nope\"")).preservedSecurityKey(),
        )
        assertNull(Settings(unknownSettings = listOf("futureOption\n1")).preservedSecurityKey())
        assertNull(Settings().preservedSecurityKey())
    }

    @Test
    fun sieveTlsModeIsSeparateFromSecurity() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("\"sieveTlsMode\":"))
        assertNull(decodeSettings(defaults).preservedSieveTlsKey())
        val implicit = Settings(sieveTlsMode = TlsMode.Implicit)
        val text = implicit.encode()
        assertTrue(text.contains("\"sieveTlsMode\":\"Implicit\""))
        val decoded = decodeSettings(text)
        assertEquals(TlsMode.Implicit, decoded.sieveTlsMode)
        assertTrue(decoded.encode().contains("\"sieveTlsMode\":\"Implicit\""))
        val bad = decodeSettings(documentWith(defaults, "sieveTlsMode", "\"Nope\""))
        assertEquals(Settings().sieveTlsMode, bad.sieveTlsMode)
        assertTrue(bad.unknownSettings.contains("sieveTlsMode\n\"Nope\""))
        assertEquals("sieveTlsMode", bad.preservedSieveTlsKey())
        assertNull(bad.preservedSecurityKey())
        val cleared = bad.withoutPreserved("sieveTlsMode")
        assertFalse(cleared.encode().contains("Nope"))
        assertNull(cleared.preservedSieveTlsKey())
    }

    @Test
    fun choosingTheDefaultClearsThePreservedSecurityLine() {
        fun cleared(key: String, rawJson: String, choose: (Settings) -> Settings) {
            val decoded = decodeSettings(documentWith(Settings().encode(), key, rawJson))
            val chosen = choose(decoded)
            val text = chosen.encode()
            val again = decodeSettings(text)
            assertNull(again.preservedSecurityKey())
            assertFalse(text.contains("Nope"))
            assertFalse(text.contains("NotAMode"))
        }
        cleared("deletePolicy", "\"Nope\"") {
            it.copy(deletePolicy = DeletePolicy.MarkDeleted).withoutPreserved("deletePolicy")
        }
        cleared("tlsMode", "\"NotAMode\"") {
            it.copy(tlsMode = TlsMode.Implicit).withoutPreserved("tlsMode")
        }
    }

    @Test
    fun editedFavoriteReplacesPreservedLine() {
        val text = documentWith(Settings().encodeUi(), "favorites", """["nope"]""")
        val decoded = decodeSettings(text)
        assertEquals(Settings().favorites, decoded.favorites)
        assertTrue(decoded.unknownSettings.any { it.startsWith("favorites\n") })
        val edited = decoded.copy(favorites = listOf(FolderFavorite(false, "INBOX", '/', "In")))
        val encoded = edited.encodeUi()
        assertTrue(encoded.contains("\"mailbox\":\"INBOX\""))
        assertFalse(encoded.contains("nope"))
    }

    @Test
    fun realTreeMatchesRegistry() {
        val errors = validateSettingsTree(settingsTreeText())
        assertEquals(emptyList<String>(), errors)
        assertEquals(Settings(), newAccountSettings())
    }

    @Test
    fun smtpTlsStaysIndependentAndPollDecodes() {
        val stored = Settings(tlsMode = TlsMode.StartTls).encode()
        assertTrue(jsonKeys(stored).contains("smtpTlsMode"))
        val loaded = decodeSettings(stored)
        assertEquals(TlsMode.StartTls, loaded.tlsMode)
        assertTrue(jsonKeys(loaded.encode()).contains("smtpTlsMode"))
        assertTrue(jsonKeys(Settings().encode()).contains("smtpNoAuth"))
        val low = documentWith(Settings().encode(), "pollSeconds", "10")
        assertEquals(10, decodeSettings(low).pollSeconds)
        assertNotNull(rejectedNumber(settingsTreeText(), "pollSeconds", 10))
        assertNull(rejectedNumber(settingsTreeText(), "pollSeconds", 60))
        assertTrue(jsonKeys(Settings().encode()).contains("pollSeconds"))
    }

    @Test
    fun missingOrdinaryKeyUsesTreeDefault() {
        assertTrue(jsonKeys(Settings().encode()).contains("pollSeconds"))
        assertFalse(Settings().encode().contains("\"multiPane\""))
        assertFalse(Settings().encodeUi().contains("\"multiPane\""))
        assertTrue(jsonKeys(Settings().encodeUi()).contains("layoutCatalog"))
    }

    @Test
    fun hardLimitMismatchNamesBothValues() {
        val json = """[{"setting":"moveChunk","default":1000,"hardLimit":{"min":1,"max":100000}}]"""
        val message = validateSettingsTree(json, complete = false).single()
        assertTrue(message.contains("moveChunk"))
        assertTrue(message.contains("hardLimit"))
    }

    @Test
    fun missingAndExtraHardLimitNameBothValues() {
        val missing = """[{"setting":"imapPort","type":"Int","scope":"account","kind":"number","default":993,"title":"settings_imap_port","min":1,"max":65535}]"""
        assertTrue(validateSettingsTree(missing, complete = false).none { "hardLimit" in it })
        val extra = """[{"setting":"pollSeconds","default":60,"hardLimit":{"min":1,"max":10}}]"""
        val ex = validateSettingsTree(extra, complete = false).single()
        assertTrue(ex.contains("pollSeconds"))
        assertTrue(ex.contains("hardLimit"))
    }

    @Test
    fun portOutsideHardLimitIsRejectedWithoutLeafMin() {
        val tree = settingsTreeText()
        val rejected = rejectedNumber(tree, "imapPort", 70000)
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("imapPort"))
        assertTrue(rejected.contains("70000"))
        assertNull(rejectedNumber(tree, "imapPort", 993))
        val connections = rejectedNumber(tree, "maxConnections", 1)
        assertNotNull(connections)
        assertTrue(connections!!.contains("maxConnections"))
        assertNull(rejectedNumber(tree, "maxConnections", 8))
    }

    private fun settingsTreeText(): String {
        val file = listOf(File("src/main/assets/settings-tree.json"), File("app/src/main/assets/settings-tree.json"))
            .first { it.isFile }
        return file.readText()
    }

    private fun assertSameSettings(expected: Settings, actual: Settings) {
        assertEquals(expected.copy(storedKeys = actual.storedKeys), actual)
    }

    private fun roundTrip(settings: Settings): Settings =
        decodeSettings(settings.encode()).withUiOf(decodeSettings(settings.encodeUi()))

    private fun jsonKeys(text: String): Set<String> =
        (parseJson(text) as JsonValue.Obj).fields.keys

    private fun assertSectionKeys() {
        val account = jsonKeys(Settings().encode())
        val ui = jsonKeys(Settings().encodeUi())
        val missingAccount = ArrayList<String>()
        val missingUi = ArrayList<String>()
        val uiOnAccount = ArrayList<String>()
        val accountOnUi = ArrayList<String>()
        for (spec in settingsRegistry.values) {
            if (spec.key in documentSecretKeys) continue
            if (spec.leaf.typeName in setOf("password", "checkServer", "pinerc", "settingsFile")) continue
            if (spec.scope == SettingScope.Ui) {
                if (spec.key !in ui) missingUi.add(spec.key)
                if (spec.key in account) uiOnAccount.add(spec.key)
            } else {
                if (spec.key !in account) missingAccount.add(spec.key)
                if (spec.key in ui) accountOnUi.add(spec.key)
            }
        }
        assertEquals(emptyList<String>(), missingAccount.sorted())
        assertEquals(emptyList<String>(), missingUi.sorted())
        assertEquals(emptyList<String>(), uiOnAccount.sorted())
        assertEquals(emptyList<String>(), accountOnUi.sorted())
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
