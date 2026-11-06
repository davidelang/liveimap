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
        val decoded = decodeSettings("clientCert=/x.p12\n")
        assertEquals(listOf("clientCert=/x.p12"), decoded.unknownSettings)
        val chain = Settings(clientCertAlias = "LiveIMAP lab client")
        assertEquals(chain, decodeSettings(chain.encode()))
        assertEquals("LiveIMAP lab client", decodeSettings(chain.encode()).clientCertAlias)
    }

    @Test
    fun compressionDefaultsOnAndRoundTrips() {
        assertEquals(Compression.On, Settings().compression)
        for (mode in Compression.entries) {
            val a = Settings(compression = mode)
            assertEquals(a, decodeSettings(a.encode()))
        }
        assertEquals(Compression.On, Settings().compression)
        assertEquals(30, Settings().connectTimeoutSec)
        assertEquals(60, Settings().readTimeoutSec)
        assertEquals(4, Settings().trafficLogMaxMb)
        assertEquals(200, Settings().jmapPartCacheMessages)
        assertEquals(65536, Settings().bodyFetchChunk)
        val chunk = Settings(bodyFetchChunk = 8192)
        assertEquals(8192, decodeSettings(chunk.encode()).bodyFetchChunk)
        assertEquals(1024, decodeSettings("bodyFetchChunk=1\n").bodyFetchChunk)
        assertEquals(1048576, decodeSettings("bodyFetchChunk=99999999\n").bodyFetchChunk)
        assertEquals(9, Settings().idleRenewMinutes)
    }

    @Test
    fun moveAllIncludesDeletedDefaultsOffAndRoundTrips() {
        assertEquals(false, Settings().moveAllIncludesDeleted)
        val on = Settings(moveAllIncludesDeleted = true)
        assertEquals(on, decodeSettings(on.encode()))
        assertEquals("NotDeleted", org.dlang.liveimap.ui.index.moveAllKind(Settings()))
        assertEquals("All", org.dlang.liveimap.ui.index.moveAllKind(on))
    }

    @Test
    fun defaultsRoundTrip() {
        val text = Settings().encode()
        val keys = text.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(
            keys.containsAll(listOf(
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
                "readerDisplay",
                "displayImages",
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
                "dynamicColor",
                "inboxStart",
                "folderStart",
                "folderStarts",
                "startAfterChange",
                "showRecentRules",
                "openAtInIndexMenu",
                "pinercStartDefault",
                "nonAsciiDisplay",
                "altAddresses",
            )),
        )
        assertTrue(text.contains("imapPort=993"))
        assertTrue(text.contains("smtpPort=465"))
        assertTrue(text.contains("density=Compact"))
        assertTrue(text.contains("defaultView=Arrival|newest"))
        assertTrue(text.contains("\nfolderViews=\n"))
        assertTrue(text.contains("swipeTrailing=Delete||"))
        assertTrue(text.contains("swipeLeading=ReplyAll||"))
        assertTrue(keys.contains("readerSwipeTrailing"))
        assertTrue(keys.contains("readerSwipeLeading"))
        assertEquals(SwipeBinding(SwipeAction.Next), decodeSettings(text).readerSwipeTrailing)
        assertEquals(SwipeBinding(SwipeAction.Previous), decodeSettings(text).readerSwipeLeading)
        assertTrue(text.contains("bounceFcc=false"))
        assertTrue(text.lines().contains("friendlyName="))
        assertTrue(text.lines().contains("theme=FollowSystem"))
        assertTrue(text.lines().contains("includeForwardAttachments=true"))
        assertTrue(text.lines().contains("replyAboveQuote=false"))
        assertTrue(text.lines().contains("askBeforeExpunge=true"))
        assertTrue(text.lines().contains("pipelineCommands=true"))
        assertTrue(text.lines().contains("logImapTraffic=false"))
        assertFalse(text.contains("readerBar="))
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
        assertTrue(keys.contains("displayImages"))
        assertTrue(text.lines().contains("altAddresses="))
        assertTrue(keys.contains("completionSources"))
        assertTrue(keys.contains("addressBookHistory"))
        assertTrue(keys.contains("addressBookNeverTrim"))
        assertEquals(Settings(), decodeSettings(text))
    }

    @Test
    fun readerSwipeDeleteRoundTrip() {
        val settings = Settings(readerSwipeTrailing = SwipeBinding(SwipeAction.Delete))
        val text = settings.encode()
        assertTrue(text.contains("readerSwipeTrailing=Delete||"))
        assertEquals(settings, decodeSettings(text))
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
        assertEquals(Settings().layoutCatalog, loaded.layoutCatalog)
        val again = loaded.encode()
        for (key in listOf("multiPane", "folderPlace", "foldPosture", "paneArrangement", "customLayout")) {
            assertFalse(again.contains("\"$key\""))
        }
        assertEquals(loaded, loaded.withSetting("multiPane", SettingValue("Off")))
    }

    @Test
    fun sieveHostRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("sieveHost="))
        assertTrue(defaults.contains("sievePort="))
        assertTrue(defaults.contains("sieveSameAsInbound="))
        val set = Settings(
            imapHost = "imap.example.com",
            sieveHost = "sieve.example.com",
            sievePort = 4191,
            sieveSameAsInbound = false,
            sieveUsername = "sieve-user",
        )
        val text = set.encode()
        assertTrue(text.contains("sieveHost=sieve.example.com"))
        assertTrue(text.contains("sievePort=4191"))
        val decoded = decodeSettings(text)
        assertEquals(set, decoded)
        val login = sieveTarget(decoded, "inbound", "sieve-secret")
        assertEquals("sieve.example.com", login.host)
        assertEquals(4191, login.port)
        assertEquals("sieve-user", login.username)
        assertEquals("sieve-secret", login.password)
        assertEquals(text, decoded.encode())
        assertEquals(4190, decodeSettings(defaults.trimEnd() + "\nsievePort=0\n").sievePort)
        assertEquals(4190, decodeSettings(defaults.trimEnd() + "\nsievePort=65536\n").sievePort)
        assertEquals("", decodeSettings(defaults).sieveHost)
        assertEquals(4190, decodeSettings(defaults).sievePort)
    }

    @Test
    fun allowPlaintextAuthRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("allowPlaintextAuth=false"))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("allowPlaintextAuth"))
        assertFalse(decodeSettings(defaults).allowPlaintextAuth)
        val set = Settings(allowPlaintextAuth = true)
        val text = set.encode()
        assertTrue(text.contains("allowPlaintextAuth=true"))
        assertTrue(decodeSettings(text).allowPlaintextAuth)
        assertEquals(set, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun certPinRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("certPin="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("certPin"))
        assertEquals("", decodeSettings(defaults).certPin)
        val pin = "ab cd:ef"
        val set = Settings(certPin = pin)
        val text = set.encode()
        assertTrue(text.contains("certPin=ab%20cd%3Aef"))
        assertEquals(pin, decodeSettings(text).certPin)
        assertEquals(text, decodeSettings(text).encode())
        assertEquals("", decodeSettings(defaults.trimEnd() + "\n").certPin)
    }

    @Test
    fun sieveCertPinRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("sieveCertPin="))
        assertEquals("", decodeSettings(defaults).sieveCertPin)
        val pin = "ab cd:ef"
        val text = Settings(sieveCertPin = pin).encode()
        assertTrue(text.contains("sieveCertPin=ab%20cd%3Aef"))
        assertEquals(pin, decodeSettings(text).sieveCertPin)
        val imap = Settings(certPin = "imap-pin").encode()
        val decoded = decodeSettings(imap)
        assertEquals("imap-pin", decoded.certPin)
        assertEquals("", decoded.sieveCertPin)
    }

    @Test
    fun smtpUsernameRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("smtpUsername="))
        assertEquals("", decodeSettings(defaults).smtpUsername)
        val set = Settings(smtpUsername = "post master")
        val text = set.encode()
        assertTrue(text.contains("smtpUsername=post%20master"))
        assertEquals("post master", decodeSettings(text).smtpUsername)
        assertEquals(set, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun tlsModeRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("tlsMode=Implicit"))
        assertEquals(TlsMode.Implicit, decodeSettings(defaults).tlsMode)
        val start = Settings(tlsMode = TlsMode.StartTls)
        val startText = start.encode()
        assertTrue(startText.contains("tlsMode=StartTls"))
        assertEquals(start, decodeSettings(startText))
        assertEquals(startText, decodeSettings(startText).encode())
        val implicit = Settings(tlsMode = TlsMode.Implicit)
        val implicitText = implicit.encode()
        assertTrue(implicitText.contains("tlsMode=Implicit"))
        assertEquals(implicit, decodeSettings(implicitText))
        assertEquals(implicitText, decodeSettings(implicitText).encode())
    }

    @Test
    fun newAccountUsesImplicitTls() {
        val fresh = newAccountSettings()
        assertEquals(TlsMode.Implicit, fresh.tlsMode)
        assertEquals(993, fresh.imapPort)
        assertEquals(465, fresh.smtpPort)
        assertEquals(fresh, decodeSettings(fresh.encode()))
    }

    @Test
    fun missingTlsModeStaysPlaintext() {
        val stored = Settings(imapPort = 1143, smtpPort = 2525).encode()
            .lineSequence().filter { !it.startsWith("tlsMode=") }.joinToString("\n")
        assertFalse(stored.contains("tlsMode="))
        val loaded = decodeSettings(stored)
        assertEquals(TlsMode.None, loaded.tlsMode)
        assertEquals(1143, loaded.imapPort)
        assertEquals(2525, loaded.smtpPort)
        val saved = loaded.encode()
        assertTrue(saved.contains("tlsMode=None"))
        assertFalse(saved.contains("tlsMode=Implicit"))
    }

    @Test
    fun tlsModeMovesOnlyDefaultPorts() {
        val implicit = Settings().withTlsMode(TlsMode.Implicit)
        assertEquals(993, implicit.imapPort)
        assertEquals(465, implicit.smtpPort)
        val start = implicit.withTlsMode(TlsMode.StartTls)
        assertEquals(143, start.imapPort)
        assertEquals(587, start.smtpPort)
        val custom = Settings(imapPort = 1143, smtpPort = 2525).withTlsMode(TlsMode.Implicit)
        assertEquals(1143, custom.imapPort)
        assertEquals(2525, custom.smtpPort)
        assertEquals(TlsMode.Implicit, custom.tlsMode)
    }

    @Test
    fun fullScreenRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("fullScreen=false"))
        val text = Settings(fullScreen = true).encode()
        assertTrue(text.contains("fullScreen=true"))
        val decoded = decodeSettings(text)
        assertTrue(decoded.fullScreen)
        assertEquals(text, decoded.encode())
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
            addressBookMailbox = "Contacts",
            markSeenOnOpen = false,
            showDeleted = false,
            readerDisplay = ReaderDisplay.Html,
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
        assertEquals(original, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun garbageIsToleratedFieldByField() {
        // F69: nothing here bricks the account any more; each bad field takes its default
        val d = Settings()
        assertEquals(d.copy(tlsMode = TlsMode.None, smtpTlsMode = TlsMode.None), decodeSettings("garbage"))
        assertEquals(listOf("notAKey=1"), decodeSettings("notAKey=1").unknownSettings)
        assertTrue(decodeSettings(d.encode() + "password=secret\n").unknownSettings.isEmpty())
        assertEquals(d.imapPort, decodeSettings(d.encode().replace("imapPort=993", "imapPort=nope")).imapPort)
        assertEquals(d.density, decodeSettings(d.encode().replace("density=Compact", "density=Huge")).density)
        assertEquals(d.dateFormat, decodeSettings(d.encode().replace("dateFormat=Short", "dateFormat=Huge")).dateFormat)
        assertEquals(
            d.readerDisplay,
            decodeSettings(d.encode().replace("\"readerDisplay\":\"Monospaced\"", "\"readerDisplay\":\"Nope\"")).readerDisplay,
        )
        val missing = d.encode().lineSequence().filterNot { it.startsWith("bounceFcc=") }.joinToString("\n")
        assertEquals(d.bounceFcc, decodeSettings(missing).bounceFcc)
    }

    @Test
    fun encodedTextHasNoPasswordKey() {
        val samples = listOf(
            Settings().encode(),
            Settings(username = "password", displayName = "secret").encode(),
        )
        for (text in samples) {
            val keys = text.lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
            assertFalse(keys.contains("password"))
        }
    }

    @Test
    fun friendlyNameAndThemeRoundTrip() {
        val original = Settings(friendlyName = "Ada/Lane", theme = ThemeMode.Dark)
        val text = original.encode()
        assertTrue(text.contains("friendlyName=Ada%2FLane"))
        assertTrue(text.contains("theme=Dark"))
        assertFalse(text.lineSequence().any { it.substringBefore('=') == "password" })
        assertEquals(original, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun olderBlobMissingNewKeysDecodes() {
        val older = Settings().encode().lineSequence()
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
        assertEquals(Settings(), decodeSettings(older))
        assertEquals("", decodeSettings(older).friendlyName)
        assertEquals(ThemeMode.FollowSystem, decodeSettings(older).theme)
        assertFalse(decodeSettings(older).showUnreadCounts)
        assertEquals(DateFormat.Short, decodeSettings(older).dateFormat)
        assertEquals("", decodeSettings(older).datePattern)
        assertEquals("", decodeSettings(older).spamMailbox)
        assertTrue(decodeSettings(older).includeForwardAttachments)
        assertFalse(decodeSettings(older).replyAboveQuote)
        assertTrue(decodeSettings(older).askBeforeExpunge)
        assertTrue(decodeSettings(older).pipelineCommands)
        assertFalse(decodeSettings(older).logImapTraffic)

        val nameLine = Settings(friendlyName = "Ada/Lane").encode().lineSequence()
            .first { it.startsWith("friendlyName=") }
        val nameOnly = decodeSettings(older + "\n" + nameLine)
        assertEquals("Ada/Lane", nameOnly.friendlyName)
        assertEquals(ThemeMode.FollowSystem, nameOnly.theme)

        val themeOnly = decodeSettings(older + "\ntheme=Light")
        assertEquals("", themeOnly.friendlyName)
        assertEquals(ThemeMode.Light, themeOnly.theme)

    }

    @Test
    fun readerDisplayKeepsStoredAndMapsOldPair() {
        assertEquals(ReaderDisplay.Monospaced, Settings().readerDisplay)
        assertFalse(Settings().preferHtml)
        assertFalse(Settings().displayImages)
        val saved = decodeSettings(
            """{"readerDisplay":"Html","displayImages":true,"bodyView":"Raw","preferHtml":false}""",
        )
        assertEquals(ReaderDisplay.Html, saved.readerDisplay)
        assertTrue(saved.preferHtml)
        assertTrue(saved.displayImages)
        val savedText = saved.encode()
        assertTrue(savedText.contains("\"readerDisplay\":\"Html\""))
        assertTrue(savedText.contains("\"displayImages\":true"))
        assertFalse(savedText.contains("\"bodyView\""))
        assertFalse(savedText.contains("\"readerTextLayout\""))
        assertFalse(savedText.contains("\"preferHtml\""))
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
        assertEquals(ReaderDisplay.Monospaced, mapped("{}"))
        val fromOld = decodeSettings("""{"bodyView":"PlainOrHtml","readerTextLayout":"Text","preferHtml":true}""")
        val next = fromOld.encode()
        assertEquals(ReaderDisplay.Html, fromOld.readerDisplay)
        assertTrue(next.contains("\"readerDisplay\":\"Html\""))
        assertFalse(next.contains("\"bodyView\""))
        assertFalse(next.contains("\"readerTextLayout\""))
        assertFalse(next.contains("\"preferHtml\""))
        assertEquals(ReaderDisplay.Html, decodeSettings(next).readerDisplay)
        val bad = decodeSettings("""{"readerDisplay":"Nope","bodyView":"PlainOrHtml"}""")
        assertEquals(ReaderDisplay.Monospaced, bad.readerDisplay)
        assertTrue(bad.encode().contains("\"readerDisplay\":\"Nope\""))
        assertFalse(bad.encode().contains("\"bodyView\""))
    }

    @Test
    fun favoriteLabel() {
        assertEquals("INBOX", favoriteLabel(false, "INBOX", '.'))
        assertEquals("INBOX.[]", favoriteLabel(true, "INBOX", '.'))
        assertEquals("(empty prefix)", favoriteLabel(false, "", '.'))
        assertEquals("(empty prefix)[]", favoriteLabel(true, "", '.'))
        val older = Settings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("favorites=") }
            .joinToString("\n")
        assertEquals(emptyList<FolderFavorite>(), decodeSettings(older).favorites)
        val saved = Settings(
            favorites = listOf(
                FolderFavorite(false, "IN BOX", '.'),
                FolderFavorite(true, "INBOX", '.'),
            ),
        )
        val text = saved.encode()
        assertTrue(text.contains("0|IN%20BOX|."))
        assertTrue(text.contains("1|INBOX|."))
        assertEquals(saved.favorites, decodeSettings(text).favorites)
        val named = FolderFavorite(false, "IN BOX", '.', "Mine/Box")
        val second = FolderFavorite(true, "INBOX", '.', "")
        val namedSaved = Settings(favorites = listOf(named, second))
        val namedText = namedSaved.encode()
        assertTrue(namedText.contains("0|IN%20BOX|.|Mine%2FBox"))
        assertEquals(listOf(named, second), decodeSettings(namedText).favorites)
        val swapped = namedSaved.copy(favorites = listOf(second, named))
        assertEquals(listOf(second, named), decodeSettings(swapped.encode()).favorites)
        val threeField = Settings().encode().lineSequence()
            .map { line ->
                if (line.startsWith("favorites=")) "favorites=0|IN%20BOX|.;1|INBOX|." else line
            }
            .joinToString("\n")
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
        val older = Settings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("askBeforeExpunge=") }
            .joinToString("\n")
        assertTrue(decodeSettings(older).askBeforeExpunge)
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
        assertTrue(text.contains("inboxStart=FirstImportant"))
        assertTrue(text.contains("folderStart=Last"))
        assertTrue(text.contains("Lists%7Calpine|FirstRecent"))
        assertTrue(text.contains("startAfterChange=KeepTopVisible"))
        assertTrue(text.contains("showRecentRules=false"))
        assertTrue(text.contains("openAtInIndexMenu=true"))
        assertTrue(text.contains("pinercStartDefault=AlpineDefault"))
        assertEquals(original, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())

        val older = Settings().encode().lineSequence()
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
        val loaded = decodeSettings(older)
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
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.CopyThenMarkDeleted, true))
        assertEquals("Move", moveCommandKind(MoveMethod.ImapMove, true))
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.ImapMove, false))
        val text = Settings().encode()
        assertTrue(text.contains("autoExpunge=false"))
        assertTrue(text.contains("deletePolicy=MarkDeleted"))
        assertTrue(text.contains("moveMethod=CopyThenMarkDeleted"))
        assertTrue(text.lines().contains("trashMailbox="))
        val decoded = decodeSettings(text)
        assertFalse(decoded.autoExpunge)
        assertEquals(DeletePolicy.MarkDeleted, decoded.deletePolicy)
        assertEquals(MoveMethod.CopyThenMarkDeleted, decoded.moveMethod)
        assertEquals("", decoded.trashMailbox)
        val chosen = Settings(
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
        assertEquals(chosen, decodeSettings(stored))
        val absent = decodeSettings(text.lineSequence().filter { it.isNotEmpty() }.joinToString("\n"))
        assertEquals(Settings(), absent)
    }

    @Test
    fun savedMailboxRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.lines().contains("savedMailbox="))
        assertEquals("", decodeSettings(text).savedMailbox)
        val saved = Settings(savedMailbox = "Saved Mail")
        val encoded = saved.encode()
        assertTrue(encoded.contains("savedMailbox=Saved%20Mail"))
        assertEquals(saved, decodeSettings(encoded))
        assertEquals(encoded, decodeSettings(encoded).encode())
    }

    @Test
    fun saveNameRuleRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("saveNameRule=DefaultFolder"))
        assertTrue(text.lines().contains("lastSaveMailbox="))
        assertEquals(SaveNameRule.DefaultFolder, decodeSettings(text).saveNameRule)
        assertEquals("", decodeSettings(text).lastSaveMailbox)
        val saved = Settings(saveNameRule = SaveNameRule.ByFrom, lastSaveMailbox = "Archive")
        val encoded = saved.encode()
        assertTrue(encoded.contains("saveNameRule=ByFrom"))
        assertTrue(encoded.contains("lastSaveMailbox=Archive"))
        assertEquals(saved, decodeSettings(encoded))
        assertEquals(SaveNameRule.ByFrom, decodeSettings(encoded).saveNameRule)
        assertEquals("Archive", decodeSettings(encoded).lastSaveMailbox)
        assertEquals(encoded, decodeSettings(encoded).encode())
        val missing = encoded.lineSequence()
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("saveNameRule=") &&
                    !line.startsWith("lastSaveMailbox=")
            }
            .joinToString("\n")
        assertEquals(SaveNameRule.DefaultFolder, decodeSettings(missing).saveNameRule)
        assertEquals("", decodeSettings(missing).lastSaveMailbox)
        val spaced = Settings(lastSaveMailbox = "My Archive")
        val spacedText = spaced.encode()
        assertTrue(spacedText.contains("lastSaveMailbox=My%20Archive"))
        assertTrue(spacedText.contains("saveNameRule=DefaultFolder"))
        assertEquals(spaced, decodeSettings(spacedText))
    }

    @Test
    fun quellFlowedRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("quellFlowed=false"))
        assertFalse(decodeSettings(text).quellFlowed)
        val saved = Settings(quellFlowed = true)
        val encoded = saved.encode()
        assertTrue(encoded.contains("quellFlowed=true"))
        assertTrue(decodeSettings(encoded).quellFlowed)
        assertEquals(saved, decodeSettings(encoded))
    }

    @Test
    fun composerWrapColumnRoundTrips() {
        val text = Settings().encode()
        assertTrue(text.contains("composerWrapColumn=74"))
        assertEquals(74, decodeSettings(text).composerWrapColumn)
        val saved = Settings(composerWrapColumn = 72)
        val encoded = saved.encode()
        assertTrue(encoded.contains("composerWrapColumn=72"))
        assertEquals(72, decodeSettings(encoded).composerWrapColumn)
        assertEquals(encoded, decodeSettings(encoded).encode())
        assertEquals(0, decodeSettings(text.trimEnd() + "\ncomposerWrapColumn=0\n").composerWrapColumn)
        assertEquals(998, decodeSettings(text.trimEnd() + "\ncomposerWrapColumn=2000\n").composerWrapColumn)
        assertEquals(0, decodeSettings(text.trimEnd() + "\ncomposerWrapColumn=-3\n").composerWrapColumn)
        for (value in listOf(-1, 999)) {
            try {
                Settings(composerWrapColumn = value)
                fail(value.toString())
            } catch (error: IllegalArgumentException) {
                assertEquals("bad composerWrapColumn", error.message)
            }
        }
    }

    @Test
    fun inboundRulesRoundTrip() {
        val rule = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "ann@example.com")),
            fileInto = "INBOX.lists",
        )
        val saved = Settings(inboundRules = listOf(rule))
        val text = saved.encode()
        assertTrue(text.contains("inboundRules="))
        assertEquals(listOf(rule), decodeSettings(text).inboundRules)
        assertEquals(text, decodeSettings(text).encode())
    }

    @Test
    fun watchedFoldersRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("watchedFolders="))
        assertTrue(defaults.contains("extraIdleBudget=2"))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("watchedFolders"))
        val decodedDefaults = decodeSettings(defaults)
        assertEquals(emptyList<String>(), decodedDefaults.watchedFolders)
        assertEquals(2, decodedDefaults.extraIdleBudget)

        val spaced = Settings(
            watchedFolders = listOf("INBOX", "Sent Mail"),
            extraIdleBudget = 0,
        )
        val spacedText = spaced.encode()
        assertTrue(spacedText.contains("watchedFolders=INBOX,Sent%20Mail"))
        assertTrue(spacedText.contains("extraIdleBudget=0"))
        assertEquals(spaced, decodeSettings(spacedText))

        val four = Settings(
            watchedFolders = listOf("A", "B", "C", "D"),
            extraIdleBudget = 2,
        )
        val fourText = four.encode()
        assertTrue(fourText.contains("watchedFolders=A,B,C,D"))
        assertTrue(fourText.contains("extraIdleBudget=2"))
        assertEquals(four, decodeSettings(fourText))

        val duplicates = Settings(
            watchedFolders = listOf("INBOX", "INBOX", "Sent"),
            extraIdleBudget = 1,
        )
        val duplicatesText = duplicates.encode()
        assertTrue(duplicatesText.contains("watchedFolders=INBOX,INBOX,Sent"))
        assertTrue(duplicatesText.contains("extraIdleBudget=1"))
        assertEquals(listOf("INBOX", "INBOX", "Sent"), decodeSettings(duplicatesText).watchedFolders)
        assertEquals(duplicates, decodeSettings(duplicatesText))

        val missingBudget = defaults.trimEnd() + "\nwatchedFolders=INBOX\n"
        val missingDecoded = decodeSettings(missingBudget)
        assertEquals(listOf("INBOX"), missingDecoded.watchedFolders)
        assertEquals(2, missingDecoded.extraIdleBudget)
        val storedZero = defaults.trimEnd() + "\nextraIdleBudget=0\n"
        assertEquals(0, decodeSettings(storedZero).extraIdleBudget)
        assertEquals(emptyList<String>(), decodeSettings(storedZero).watchedFolders)
        val storedNegative = defaults.trimEnd() + "\nextraIdleBudget=-3\n"
        assertEquals(-3, decodeSettings(storedNegative).extraIdleBudget)
    }

    @Test
    fun savedSearchesRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("savedSearches="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("savedSearches"))
        assertEquals(emptyList<SavedSearch>(), decodeSettings(defaults).savedSearches)

        val saved = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("Sent Mail", "a|b", SimpleSearchField.Subject),
        )
        val settings = Settings(savedSearches = saved)
        val text = settings.encode()
        assertTrue(text.contains("savedSearches=News|ada|From,Sent%20Mail|a%7Cb|Subject"))
        assertEquals(saved, decodeSettings(text).savedSearches)
        assertEquals(settings, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())

        val repeated = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("News", "ada", SimpleSearchField.From),
        )
        val dirty = defaults.trimEnd() +
            "\nsavedSearches=News|ada|From,News|ada|Nope,only|two,|ada|From,News||From,News|ada|From\n"
        assertEquals(repeated, decodeSettings(dirty).savedSearches)
        assertEquals(emptyList<SavedSearch>(), decodeSettings(defaults).savedSearches)
    }

    @Test
    fun savedAdvancedRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.lines().contains("savedAdvanced="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("savedAdvanced"))
        assertEquals(emptyList<SavedAdvanced>(), decodeSettings(defaults).savedAdvanced)

        val one = SavedAdvanced("News", "And\nYes\tSubject\tada", SearchScope.Current)
        val oneSettings = Settings(savedAdvanced = listOf(one))
        val oneText = oneSettings.encode()
        assertTrue(oneText.lines().contains("savedAdvanced=News|And%0AYes%09Subject%09ada|Current"))
        assertEquals(listOf(one), decodeSettings(oneText).savedAdvanced)
        assertEquals(oneSettings, decodeSettings(oneText))
        assertEquals(oneText, decodeSettings(oneText).encode())

        val saved = listOf(
            one,
            SavedAdvanced("Sent Mail", "a|b,c", SearchScope.All),
            SavedAdvanced("News", "ada", SearchScope.Subtree),
        )
        val settings = Settings(savedAdvanced = saved)
        val text = settings.encode()
        assertTrue(
            text.contains(
                "savedAdvanced=News|And%0AYes%09Subject%09ada|Current,Sent%20Mail|a%7Cb%2Cc|All,News|ada|Subtree",
            ),
        )
        assertEquals(saved, decodeSettings(text).savedAdvanced)
        assertEquals(settings, decodeSettings(text))
        assertEquals(text, decodeSettings(text).encode())

        val repeated = listOf(
            SavedAdvanced("News", "ada", SearchScope.Current),
            SavedAdvanced("News", "bob", SearchScope.All),
        )
        val dirty = defaults.trimEnd() +
            "\nsavedAdvanced=News|ada|Current,News|ada|Nope,only|two,|ada|Current,News||Current,News|bob|All\n"
        assertEquals(repeated, decodeSettings(dirty).savedAdvanced)
        val dropped = defaults.trimEnd() + "\nsavedAdvanced=News|ada|Nope\n"
        assertEquals(emptyList<SavedAdvanced>(), decodeSettings(dropped).savedAdvanced)
        assertEquals(emptyList<SavedAdvanced>(), decodeSettings(defaults).savedAdvanced)
    }

    @Test
    fun emailSubmissionRoundTrip() {
        val text = Settings().encode()
        assertTrue(text.contains("emailSubmission=false"))
        assertFalse(decodeSettings(text).emailSubmission)
        val saved = Settings(emailSubmission = true)
        val encoded = saved.encode()
        assertTrue(encoded.lines().contains("emailSubmission=true"))
        assertTrue(decodeSettings(encoded).emailSubmission)
        assertEquals(saved, decodeSettings(encoded))
        assertFalse(decodeSettings(text.trimEnd() + "\nemailSubmission=false\n").emailSubmission)
        assertTrue(decodeSettings(text.trimEnd() + "\nemailSubmission=true\n").emailSubmission)
    }

    @Test
    fun askThresholdsRoundTrip() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("askSort=-1"))
        assertTrue(defaults.contains("askThread=-1"))
        assertTrue(defaults.contains("askSearch=-1"))
        assertTrue(defaults.contains("askFallback=-1"))
        assertTrue(defaults.lines().contains("sortAskSkip="))
        assertTrue(defaults.lines().contains("threadAskSkip="))
        assertTrue(defaults.lines().contains("searchAskSkip="))
        assertTrue(defaults.lines().contains("fallbackAskSkip="))
        val decoded = decodeSettings(defaults)
        assertEquals(-1, decoded.askSort)
        assertEquals(-1, decoded.askThread)
        assertEquals(-1, decoded.askSearch)
        assertEquals(-1, decoded.askFallback)
        assertEquals(emptySet<String>(), decoded.sortAskSkip)
        assertEquals(emptySet<String>(), decoded.threadAskSkip)
        assertEquals(emptySet<String>(), decoded.searchAskSkip)
        assertEquals(emptySet<String>(), decoded.fallbackAskSkip)
        val saved = Settings(askSort = 20, threadAskSkip = setOf("Sent Mail"))
        val text = saved.encode()
        assertTrue(text.contains("askSort=20"))
        assertTrue(text.contains("askThread=-1"))
        assertTrue(text.contains("askSearch=-1"))
        assertTrue(text.contains("askFallback=-1"))
        assertTrue(text.contains("threadAskSkip=Sent%20Mail"))
        assertTrue(text.lines().contains("sortAskSkip="))
        assertTrue(text.lines().contains("searchAskSkip="))
        assertTrue(text.lines().contains("fallbackAskSkip="))
        assertEquals(saved, decodeSettings(text))
        assertEquals(-1, decodeSettings(defaults.trimEnd() + "\naskFallback=-7\n").askFallback)
        assertFalse(overAskThreshold(5001, -1))
        assertFalse(overAskThreshold(5000, 5000))
        assertTrue(overAskThreshold(5001, 5000))
        assertTrue(overAskThreshold(1, 0))
        assertFalse(overAskThreshold(Int.MAX_VALUE, Int.MAX_VALUE))
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
        val text = Settings().encode().trimEnd() +
            "\ninboundRules=From=ann%40example.com\tfile=INBOX.lists\nbogus\tzz\nSubject=x\tfile=Sent\n"
        val decoded = decodeSettings(text)
        assertEquals(listOf(first, second), decoded.inboundRules)
        val encoded = decoded.encode()
        assertTrue(encoded.contains("From=ann%40example.com\tfile=INBOX.lists"))
        assertTrue(encoded.contains("Subject=x\tfile=Sent"))
        assertFalse(encoded.contains("bogus"))
    }

    @Test
    fun everyBrokenInboundRuleIsKeptRaw() {
        val raw = "inboundRules=bogus\tzz\nalso\tbad"
        val text = Settings().encode().trimEnd() + "\n$raw\n"
        val decoded = decodeSettings(text)
        assertEquals(emptyList<InboundRule>(), decoded.inboundRules)
        assertEquals(listOf(raw), decoded.unknownSettings)
        val encoded = decoded.encode()
        assertTrue(encoded.contains(raw))
        assertEquals(encoded.indexOf(raw), encoded.lastIndexOf(raw))
        assertFalse(encoded.lines().any { it == "inboundRules=" })
    }

    @Test
    fun duplicateListEntriesKeepTheFirst() {
        val text = Settings().encode().trimEnd() +
            "\nfavorites=1|INBOX|/|In;nope;0|Sent|/|S;1|INBOX|/|Again" +
            "\nfolderStarts=INBOX|First;nope;Sent|Newest;INBOX|Last" +
            "\nfolderViews=INBOX|Arrival|newest;bad;Sent|Date|oldest;INBOX|From|newest" +
            "\nexpandedFolders=INBOX;%;Sent;INBOX\n"
        val decoded = decodeSettings(text)
        assertEquals(
            listOf(
                FolderFavorite(true, "INBOX", '/', "In"),
                FolderFavorite(false, "Sent", '/', "S"),
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
        assertEquals(setOf("INBOX", "Sent"), decoded.expandedFolders)
        val encoded = decoded.encode()
        assertTrue(encoded.lines().contains("favorites=1|INBOX|%2F|In;0|Sent|%2F|S"))
        assertTrue(encoded.lines().contains("folderStarts=INBOX|First;Sent|Newest"))
        assertTrue(encoded.lines().contains("folderViews=INBOX|Arrival|newest;Sent|Date|oldest"))
        assertTrue(encoded.lines().contains("expandedFolders=INBOX;Sent"))
        assertFalse(encoded.contains("nope"))
        assertFalse(encoded.contains("Again"))
        assertFalse(encoded.contains("INBOX|From|newest"))
    }

    @Test
    fun unreadableTlsModeIsKeptRaw() {
        val text = Settings().encode().trimEnd() + "\ntlsMode=NotAMode\n"
        val decoded = decodeSettings(text)
        assertEquals(TlsMode.None, decoded.tlsMode)
        assertTrue(decoded.unknownSettings.contains("tlsMode=NotAMode"))
        val encoded = decoded.encode()
        assertEquals(1, encoded.lines().count { it == "tlsMode=NotAMode" })
        assertFalse(encoded.contains("tlsMode=None"))
        val edited = decoded.copy(tlsMode = TlsMode.StartTls)
        val replaced = edited.encode()
        assertEquals(1, replaced.lines().count { it == "tlsMode=StartTls" })
        assertFalse(replaced.contains("tlsMode=NotAMode"))
    }

    @Test
    fun preservedSecurityKeyNamesTheUnreadableLine() {
        assertEquals("tlsMode", Settings(unknownSettings = listOf("tlsMode=NotAMode")).preservedSecurityKey())
        assertEquals("deletePolicy", Settings(unknownSettings = listOf("deletePolicy=Nope")).preservedSecurityKey())
        assertEquals("moveMethod", Settings(unknownSettings = listOf("moveMethod=Nope")).preservedSecurityKey())
        assertEquals(
            "tlsMode",
            Settings(
                unknownSettings = listOf("moveMethod=Nope", "deletePolicy=Nope", "tlsMode=NotAMode"),
            ).preservedSecurityKey(),
        )
        assertEquals(
            "deletePolicy",
            Settings(unknownSettings = listOf("moveMethod=Nope", "deletePolicy=Nope")).preservedSecurityKey(),
        )
        assertNull(Settings(unknownSettings = listOf("futureOption=1")).preservedSecurityKey())
        assertNull(Settings().preservedSecurityKey())
    }

    @Test
    fun sieveTlsModeIsSeparateFromSecurity() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("sieveTlsMode=StartTls"))
        assertEquals(TlsMode.StartTls, decodeSettings(defaults).sieveTlsMode)
        assertNull(decodeSettings(defaults).preservedSieveTlsKey())
        val implicit = Settings(sieveTlsMode = TlsMode.Implicit)
        val text = implicit.encode()
        assertTrue(text.contains("sieveTlsMode=Implicit"))
        val decoded = decodeSettings(text)
        assertEquals(TlsMode.Implicit, decoded.sieveTlsMode)
        assertTrue(decoded.encode().contains("sieveTlsMode=Implicit"))
        val bad = decodeSettings(defaults.trimEnd() + "\nsieveTlsMode=Nope\n")
        assertEquals(TlsMode.StartTls, bad.sieveTlsMode)
        assertTrue(bad.unknownSettings.contains("sieveTlsMode=Nope"))
        assertEquals("sieveTlsMode", bad.preservedSieveTlsKey())
        assertNull(bad.preservedSecurityKey())
        val cleared = bad.withoutPreserved("sieveTlsMode")
        assertFalse(cleared.encode().contains("sieveTlsMode=Nope"))
        assertNull(cleared.preservedSieveTlsKey())
    }

    @Test
    fun choosingTheDefaultClearsThePreservedSecurityLine() {
        fun cleared(raw: String, choose: (Settings) -> Settings) {
            val decoded = decodeSettings(Settings().encode().trimEnd() + "\n$raw\n")
            val chosen = choose(decoded)
            val text = chosen.encode()
            val again = decodeSettings(text)
            assertNull(again.preservedSecurityKey())
            assertFalse(text.contains(raw))
        }
        cleared("deletePolicy=Nope") {
            it.copy(deletePolicy = DeletePolicy.MarkDeleted).withoutPreserved("deletePolicy")
        }
        cleared("moveMethod=Nope") {
            it.copy(moveMethod = MoveMethod.CopyThenMarkDeleted).withoutPreserved("moveMethod")
        }
        cleared("tlsMode=NotAMode") {
            it.copy(tlsMode = TlsMode.None).withoutPreserved("tlsMode")
        }
    }

    @Test
    fun editedFavoriteReplacesPreservedLine() {
        val raw = "favorites=nope;also-bad"
        val text = Settings().encode().trimEnd() + "\n$raw\n"
        val decoded = decodeSettings(text)
        assertEquals(emptyList<FolderFavorite>(), decoded.favorites)
        assertTrue(decoded.unknownSettings.contains(raw))
        val edited = decoded.copy(favorites = listOf(FolderFavorite(false, "INBOX", '/', "In")))
        val encoded = edited.encode()
        assertTrue(encoded.contains("favorites=0|INBOX|%2F|In"))
        assertFalse(encoded.contains(raw))
        assertFalse(encoded.contains("nope"))
    }

    @Test
    fun realTreeMatchesRegistry() {
        val errors = validateSettingsTree(settingsTreeText())
        assertEquals(emptyList<String>(), errors)
        assertEquals(TlsMode.Implicit, Settings().tlsMode)
        assertEquals(993, Settings().imapPort)
        assertEquals(465, Settings().smtpPort)
        assertEquals("", Settings().layoutCatalog)
        assertEquals(PreviewChoice.Auto, Settings().previewSize)
        assertEquals(Settings(), newAccountSettings())
    }

    @Test
    fun smtpModeCopiesTlsAndPollNamesItself() {
        val stored = Settings(tlsMode = TlsMode.StartTls).encode()
            .lineSequence().filter { !it.startsWith("smtpTlsMode=") }.joinToString("\n")
        val loaded = decodeSettings(stored)
        assertEquals(TlsMode.StartTls, loaded.tlsMode)
        assertEquals(TlsMode.StartTls, loaded.smtpTlsMode)
        val saved = loaded.encode()
        assertTrue(saved.contains("smtpTlsMode=StartTls"))
        val noAuth = Settings().encode().lineSequence()
            .filter { !it.startsWith("smtpNoAuth=") }.joinToString("\n")
        assertEquals(SmtpNoAuth.Off, decodeSettings(noAuth).smtpNoAuth)
        val low = Settings().encode().replace("pollSeconds=60", "pollSeconds=10")
        try {
            decodeSettings(low)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pollSeconds"))
        }
        val missingPoll = Settings().encode().lineSequence()
            .filter { !it.startsWith("pollSeconds=") }.joinToString("\n")
        assertEquals(60, decodeSettings(missingPoll).pollSeconds)
    }

    @Test
    fun missingOrdinaryKeyUsesTreeDefault() {
        val raw = Settings().encode().lineSequence().filter { !it.startsWith("pollSeconds=") }.joinToString("\n")
        assertEquals(60, decodeSettings(raw).pollSeconds)
        assertFalse(Settings().encode().contains("\"multiPane\""))
        assertEquals("", decodeSettings(Settings().encode()).layoutCatalog)
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

    private fun assertThrowsIae(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
