package org.dlang.liveimap.settings

import java.io.File
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.engine.sieve.RuleCriterion
import org.dlang.liveimap.engine.sieve.RuleField
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SavedSearch
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.SimpleSearchField
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
        val p12 = AccountSettings(clientCert = "/data/x/client-certs/a b.p12")
        assertEquals(p12, decodeAccountSettings(p12.encode()))
        val chain = AccountSettings(clientCertAlias = "LiveIMAP lab client")
        assertEquals(chain, decodeAccountSettings(chain.encode()))
        assertEquals("", decodeAccountSettings(chain.encode()).clientCert)
    }

    @Test
    fun compressionDefaultsOnAndRoundTrips() {
        assertEquals(Compression.On, AccountSettings().compression)
        for (mode in Compression.entries) {
            val a = AccountSettings(compression = mode)
            assertEquals(a, decodeAccountSettings(a.encode()))
        }
        assertEquals(true, compressionWanted(Compression.On, false))
        assertEquals(false, compressionWanted(Compression.Off, true))
        assertEquals(true, compressionWanted(Compression.Auto, true))
        assertEquals(false, compressionWanted(Compression.Auto, false))
    }

    @Test
    fun moveAllIncludesDeletedDefaultsOffAndRoundTrips() {
        assertEquals(false, AccountSettings().moveAllIncludesDeleted)
        val on = AccountSettings(moveAllIncludesDeleted = true)
        assertEquals(on, decodeAccountSettings(on.encode()))
        assertEquals("NotDeleted", org.dlang.liveimap.ui.index.moveAllKind(AccountSettings()))
        assertEquals("All", org.dlang.liveimap.ui.index.moveAllKind(on))
    }

    @Test
    fun defaultsRoundTrip() {
        val text = AccountSettings().encode()
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
        assertEquals(SwipeBinding(SwipeAction.Next), decodeAccountSettings(text).readerSwipeTrailing)
        assertEquals(SwipeBinding(SwipeAction.Previous), decodeAccountSettings(text).readerSwipeLeading)
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
        assertTrue(keys.contains("readerTextLayout"))
        assertTrue(text.lines().contains("altAddresses="))
        assertTrue(keys.contains("completionSources"))
        assertTrue(keys.contains("addressBookHistory"))
        assertTrue(keys.contains("addressBookNeverTrim"))
        assertEquals(AccountSettings(), decodeAccountSettings(text))
    }

    @Test
    fun readerSwipeDeleteRoundTrip() {
        val settings = AccountSettings(readerSwipeTrailing = SwipeBinding(SwipeAction.Delete))
        val text = settings.encode()
        assertTrue(text.contains("readerSwipeTrailing=Delete||"))
        assertEquals(settings, decodeAccountSettings(text))
    }

    @Test
    fun multiPaneOffRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("multiPane=Auto"))
        assertEquals(MultiPane.Auto, decodeAccountSettings(text).multiPane)
        val offText = text.lineSequence().filter { !it.startsWith("multiPane=") }.joinToString("\n") + "\nmultiPane=Off\n"
        assertEquals(MultiPane.Off, decodeAccountSettings(offText).multiPane)
        val wide = AccountSettings(multiPane = MultiPane.Wide).encode()
        assertTrue(wide.contains("multiPane=Wide"))
        assertEquals(MultiPane.Wide, decodeAccountSettings(wide).multiPane)
    }

    @Test
    fun layoutChoiceOffRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("folderPane=Auto"))
        assertTrue(text.contains("foldPosture=Off"))
        val offText = text.lineSequence().filter { !it.startsWith("folderPane=") && !it.startsWith("foldPosture=") }.joinToString("\n") + "\nfolderPane=Off\nfoldPosture=Off\n"
        val off = decodeAccountSettings(offText)
        assertEquals(LayoutChoice.Off, off.folderPane)
        assertEquals(LayoutChoice.Off, off.foldPosture)
        val on = AccountSettings(folderPane = LayoutChoice.On, foldPosture = LayoutChoice.On).encode()
        assertTrue(on.contains("folderPane=On"))
        assertTrue(on.contains("foldPosture=On"))
        val decoded = decodeAccountSettings(on)
        assertEquals(LayoutChoice.On, decoded.folderPane)
        assertEquals(LayoutChoice.On, decoded.foldPosture)
    }

    @Test
    fun sieveEndpointRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("sieveHost="))
        assertTrue(defaults.contains("sievePort="))
        val blank = AccountSettings(imapHost = "imap.example.com", sieveHost = "  ")
        assertEquals("imap.example.com" to 4190, sieveEndpoint(blank))
        val set = AccountSettings(
            imapHost = "imap.example.com",
            sieveHost = "sieve.example.com",
            sievePort = 4191,
        )
        val text = set.encode()
        assertTrue(text.contains("sieveHost=sieve.example.com"))
        assertTrue(text.contains("sievePort=4191"))
        val decoded = decodeAccountSettings(text)
        assertEquals(set, decoded)
        assertEquals("sieve.example.com" to 4191, sieveEndpoint(decoded))
        assertEquals(text, decoded.encode())
        assertEquals(4190, decodeAccountSettings(defaults.trimEnd() + "\nsievePort=0\n").sievePort)
        assertEquals(4190, decodeAccountSettings(defaults.trimEnd() + "\nsievePort=65536\n").sievePort)
        assertEquals("", decodeAccountSettings(defaults).sieveHost)
        assertEquals(4190, decodeAccountSettings(defaults).sievePort)
    }

    @Test
    fun allowPlaintextAuthRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("allowPlaintextAuth=false"))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("allowPlaintextAuth"))
        assertFalse(decodeAccountSettings(defaults).allowPlaintextAuth)
        val set = AccountSettings(allowPlaintextAuth = true)
        val text = set.encode()
        assertTrue(text.contains("allowPlaintextAuth=true"))
        assertTrue(decodeAccountSettings(text).allowPlaintextAuth)
        assertEquals(set, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())
    }

    @Test
    fun certPinRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("certPin="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("certPin"))
        assertEquals("", decodeAccountSettings(defaults).certPin)
        val pin = "ab cd:ef"
        val set = AccountSettings(certPin = pin)
        val text = set.encode()
        assertTrue(text.contains("certPin=ab%20cd%3Aef"))
        assertEquals(pin, decodeAccountSettings(text).certPin)
        assertEquals(text, decodeAccountSettings(text).encode())
        assertEquals("", decodeAccountSettings(defaults.trimEnd() + "\n").certPin)
    }

    @Test
    fun sieveCertPinRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("sieveCertPin="))
        assertEquals("", decodeAccountSettings(defaults).sieveCertPin)
        val pin = "ab cd:ef"
        val text = AccountSettings(sieveCertPin = pin).encode()
        assertTrue(text.contains("sieveCertPin=ab%20cd%3Aef"))
        assertEquals(pin, decodeAccountSettings(text).sieveCertPin)
        val imap = AccountSettings(certPin = "imap-pin").encode()
        val decoded = decodeAccountSettings(imap)
        assertEquals("imap-pin", decoded.certPin)
        assertEquals("", decoded.sieveCertPin)
    }

    @Test
    fun smtpUsernameRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("smtpUsername="))
        assertEquals("", decodeAccountSettings(defaults).smtpUsername)
        val set = AccountSettings(smtpUsername = "post master")
        val text = set.encode()
        assertTrue(text.contains("smtpUsername=post%20master"))
        assertEquals("post master", decodeAccountSettings(text).smtpUsername)
        assertEquals(set, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())
    }

    @Test
    fun tlsModeRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("tlsMode=Implicit"))
        assertEquals(TlsMode.Implicit, decodeAccountSettings(defaults).tlsMode)
        val start = AccountSettings(tlsMode = TlsMode.StartTls)
        val startText = start.encode()
        assertTrue(startText.contains("tlsMode=StartTls"))
        assertEquals(start, decodeAccountSettings(startText))
        assertEquals(startText, decodeAccountSettings(startText).encode())
        val implicit = AccountSettings(tlsMode = TlsMode.Implicit)
        val implicitText = implicit.encode()
        assertTrue(implicitText.contains("tlsMode=Implicit"))
        assertEquals(implicit, decodeAccountSettings(implicitText))
        assertEquals(implicitText, decodeAccountSettings(implicitText).encode())
    }

    @Test
    fun newAccountUsesImplicitTls() {
        val fresh = newAccountSettings()
        assertEquals(TlsMode.Implicit, fresh.tlsMode)
        assertEquals(993, fresh.imapPort)
        assertEquals(465, fresh.smtpPort)
        assertEquals(fresh, decodeAccountSettings(fresh.encode()))
    }

    @Test
    fun missingTlsModeStaysPlaintext() {
        val stored = AccountSettings(imapPort = 1143, smtpPort = 2525).encode()
            .lineSequence().filter { !it.startsWith("tlsMode=") }.joinToString("\n")
        assertFalse(stored.contains("tlsMode="))
        val loaded = decodeAccountSettings(stored)
        assertEquals(TlsMode.None, loaded.tlsMode)
        assertEquals(1143, loaded.imapPort)
        assertEquals(2525, loaded.smtpPort)
        val saved = loaded.encode()
        assertTrue(saved.contains("tlsMode=None"))
        assertFalse(saved.contains("tlsMode=Implicit"))
    }

    @Test
    fun tlsModeMovesOnlyDefaultPorts() {
        val implicit = AccountSettings().withTlsMode(TlsMode.Implicit)
        assertEquals(993, implicit.imapPort)
        assertEquals(465, implicit.smtpPort)
        val start = implicit.withTlsMode(TlsMode.StartTls)
        assertEquals(143, start.imapPort)
        assertEquals(587, start.smtpPort)
        val custom = AccountSettings(imapPort = 1143, smtpPort = 2525).withTlsMode(TlsMode.Implicit)
        assertEquals(1143, custom.imapPort)
        assertEquals(2525, custom.smtpPort)
        assertEquals(TlsMode.Implicit, custom.tlsMode)
    }

    @Test
    fun fullScreenRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("fullScreen=false"))
        val text = AccountSettings(fullScreen = true).encode()
        assertTrue(text.contains("fullScreen=true"))
        val decoded = decodeAccountSettings(text)
        assertTrue(decoded.fullScreen)
        assertEquals(text, decoded.encode())
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
    fun garbageIsToleratedFieldByField() {
        // F69: nothing here bricks the account any more; each bad field takes its default
        val d = AccountSettings()
        assertEquals(d, decodeAccountSettings("garbage"))
        assertEquals(listOf("notAKey=1"), decodeAccountSettings("notAKey=1").unknownSettings)
        assertTrue(decodeAccountSettings(d.encode() + "password=secret\n").unknownSettings.isEmpty())
        assertEquals(d.imapPort, decodeAccountSettings(d.encode().replace("imapPort=143", "imapPort=nope")).imapPort)
        assertEquals(d.density, decodeAccountSettings(d.encode().replace("density=Compact", "density=Huge")).density)
        assertEquals(d.dateFormat, decodeAccountSettings(d.encode().replace("dateFormat=Short", "dateFormat=Huge")).dateFormat)
        assertEquals(d.bodyView, decodeAccountSettings(d.encode().replace("bodyView=PlainOrError", "bodyView=Nope")).bodyView)
        val missing = d.encode().lineSequence().filterNot { it.startsWith("bounceFcc=") }.joinToString("\n")
        assertEquals(d.bounceFcc, decodeAccountSettings(missing).bounceFcc)
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
        assertNull(decodeAccountSettings(missingBar).readerToolbar)
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
        val older = AccountSettings().encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("askBeforeExpunge=") }
            .joinToString("\n")
        assertTrue(decodeAccountSettings(older).askBeforeExpunge)
    }

    @Test
    fun unusedLabelsAreGone() {
        val text = listOf(
            "src/main/kotlin/org/dlang/liveimap/settings/AccountSettings.kt",
            "app/src/main/kotlin/org/dlang/liveimap/settings/AccountSettings.kt",
        ).map(::File).first { it.exists() }.readText()
        assertFalse(text.contains("fun densityLabel"))
        assertFalse(text.contains("fun startAfterChangeLabel"))
        assertFalse(text.contains("fun pinercStartDefaultLabel"))
        assertFalse(text.contains("fun openAtMenuText"))
        assertFalse(text.contains("fun swipeActionLabel"))
        assertFalse(text.contains("fun themeLabel"))
        assertTrue(text.contains("fun sortKeyLabel"))
        assertTrue(text.contains("fun startRuleLabel"))
        assertTrue(text.contains("fun dateFormatLabel"))
        assertTrue(text.contains("fun bodyViewLabel"))
        assertTrue(text.contains("fun favoriteLabel"))
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
    }

    @Test
    fun moveCommandKindFollowsMethodAndMove() {
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.CopyThenMarkDeleted, true))
        assertEquals("Move", moveCommandKind(MoveMethod.ImapMove, true))
        assertEquals("CopyThenDelete", moveCommandKind(MoveMethod.ImapMove, false))
        val text = AccountSettings().encode()
        assertTrue(text.contains("autoExpunge=false"))
        assertTrue(text.contains("deletePolicy=MarkDeleted"))
        assertTrue(text.contains("moveMethod=CopyThenMarkDeleted"))
        assertTrue(text.lines().contains("trashMailbox="))
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
    fun savedMailboxRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.lines().contains("savedMailbox="))
        assertEquals("", decodeAccountSettings(text).savedMailbox)
        val saved = AccountSettings(savedMailbox = "Saved Mail")
        val encoded = saved.encode()
        assertTrue(encoded.contains("savedMailbox=Saved%20Mail"))
        assertEquals(saved, decodeAccountSettings(encoded))
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
    }

    @Test
    fun saveNameRuleRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("saveNameRule=DefaultFolder"))
        assertTrue(text.lines().contains("lastSaveMailbox="))
        assertEquals(SaveNameRule.DefaultFolder, decodeAccountSettings(text).saveNameRule)
        assertEquals("", decodeAccountSettings(text).lastSaveMailbox)
        val saved = AccountSettings(saveNameRule = SaveNameRule.ByFrom, lastSaveMailbox = "Archive")
        val encoded = saved.encode()
        assertTrue(encoded.contains("saveNameRule=ByFrom"))
        assertTrue(encoded.contains("lastSaveMailbox=Archive"))
        assertEquals(saved, decodeAccountSettings(encoded))
        assertEquals(SaveNameRule.ByFrom, decodeAccountSettings(encoded).saveNameRule)
        assertEquals("Archive", decodeAccountSettings(encoded).lastSaveMailbox)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val missing = encoded.lineSequence()
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("saveNameRule=") &&
                    !line.startsWith("lastSaveMailbox=")
            }
            .joinToString("\n")
        assertEquals(SaveNameRule.DefaultFolder, decodeAccountSettings(missing).saveNameRule)
        assertEquals("", decodeAccountSettings(missing).lastSaveMailbox)
        val spaced = AccountSettings(lastSaveMailbox = "My Archive")
        val spacedText = spaced.encode()
        assertTrue(spacedText.contains("lastSaveMailbox=My%20Archive"))
        assertTrue(spacedText.contains("saveNameRule=DefaultFolder"))
        assertEquals(spaced, decodeAccountSettings(spacedText))
    }

    @Test
    fun indexBarRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("indexBar="))
        assertEquals(defaultIndexBar(), decodeAccountSettings(text).indexBar)
        val explicit = text.trimEnd() + "\nindexBar=T:Refresh,Search,Filter|O:|H:\n"
        val storedIndex = decodeAccountSettings(explicit).indexBar
        assertEquals(
            listOf(IndexBarAction.Refresh, IndexBarAction.Search, IndexBarAction.Filter),
            storedIndex.toolbar,
        )
        assertEquals(emptyList<IndexBarAction>(), storedIndex.overflow)
        assertEquals(emptyList<IndexBarAction>(), storedIndex.hidden)
        val moved = moveIndexAction(defaultIndexBar(), IndexBarAction.Refresh, BarSection.Toolbar)
        val saved = AccountSettings(indexBar = moved)
        val encoded = saved.encode()
        assertTrue(encoded.contains("indexBar=T:Search,Refresh|O:Filter|H:"))
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
            decodeAccountSettings(bad) // F69: a bad stored value falls back instead of throwing
        }
    }

    @Test
    fun selectionBarRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("selectionBar="))
        assertEquals(defaultSelectionBar(), decodeAccountSettings(text).selectionBar)
        val explicit = text.trimEnd() + "\nselectionBar=T:Seen,Flag,Move,Delete|O:|H:\n"
        val storedSelection = decodeAccountSettings(explicit).selectionBar
        assertEquals(
            listOf(
                SelectionBarAction.Seen,
                SelectionBarAction.Flag,
                SelectionBarAction.Move,
                SelectionBarAction.Delete,
            ),
            storedSelection.toolbar,
        )
        assertEquals(emptyList<SelectionBarAction>(), storedSelection.overflow)
        assertEquals(emptyList<SelectionBarAction>(), storedSelection.hidden)
        val moved = moveSelectionAction(defaultSelectionBar(), SelectionBarAction.Delete, BarSection.Overflow)
        val saved = AccountSettings(selectionBar = moved)
        val encoded = saved.encode()
        assertTrue(encoded.contains("indexBar="))
        assertTrue(encoded.contains("selectionBar=T:|O:Seen,Flag,Move,Delete|H:"))
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
            decodeAccountSettings(bad) // F69: a bad stored value falls back instead of throwing
        }
    }

    @Test
    fun folderBarRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("folderBar="))
        assertEquals(defaultFolderBar(), decodeAccountSettings(text).folderBar)
        val explicit = text.trimEnd() + "\nfolderBar=T:Refresh|O:CollapseAll,SaveDefault,ResetDefault|H:\n"
        assertEquals(defaultFolderBar(), decodeAccountSettings(explicit).folderBar)
        val moved = moveFolderAction(defaultFolderBar(), FolderBarAction.Refresh, BarSection.Overflow)
        val saved = AccountSettings(folderBar = moved)
        val encoded = saved.encode()
        assertTrue(encoded.contains("indexBar="))
        assertTrue(encoded.contains("selectionBar="))
        assertTrue(encoded.contains("folderBar=T:|O:CollapseAll,SaveDefault,ResetDefault,Refresh|H:"))
        assertEquals(moved, decodeAccountSettings(encoded).folderBar)
        assertEquals(defaultIndexBar(), decodeAccountSettings(encoded).indexBar)
        assertEquals(defaultSelectionBar(), decodeAccountSettings(encoded).selectionBar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val bad = text.trimEnd() + "\nfolderBar=T:Refresh|O:CollapseAll,CollapseAll|H:\n"
        decodeAccountSettings(bad) // F69: duplicates are tolerated (first place kept)
    }

    @Test
    fun readerToolbarRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.lines().contains("readerToolbar="))
        assertFalse(text.contains("readerBar="))
        assertNull(decodeAccountSettings(text).readerToolbar)
        val layout = ReaderToolbarLayout(
            toolbar = listOf(
                ReaderToolbarAction.Refresh,
                ReaderToolbarAction.Reply,
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
            ),
            overflow = listOf(
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
                ReaderToolbarAction.Headers,
            ),
            hidden = emptyList(),
        )
        val saved = AccountSettings(readerToolbar = layout)
        val encoded = saved.encode()
        assertTrue(
            encoded.contains(
                "readerToolbar=T:Refresh,Reply,ReplyAll,Forward,Delete|O:Move,Spam,Bounce,Headers|H:",
            ),
        )
        assertFalse(encoded.contains("readerBar="))
        assertEquals(layout, decodeAccountSettings(encoded).readerToolbar)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        val moved = moveReaderAction(layout, ReaderToolbarAction.Bounce, BarSection.Toolbar)
        val movedText = AccountSettings(readerToolbar = moved).encode()
        assertTrue(
            movedText.contains(
                "readerToolbar=T:Refresh,Reply,ReplyAll,Forward,Delete,Bounce|O:Move,Spam,Headers|H:",
            ),
        )
        assertEquals(moved, decodeAccountSettings(movedText).readerToolbar)
        val bad = text.trimEnd() + "\nreaderToolbar=T:Refresh|O:Reply,Reply|H:\n"
        decodeAccountSettings(bad) // F69: duplicates are tolerated (first place kept)
    }

    @Test
    fun readerBarMigratesOnceWhenToolbarIsAbsent() {
        val replyOnly = ReaderToolbarLayout(
            toolbar = listOf(ReaderToolbarAction.Refresh, ReaderToolbarAction.Reply),
            overflow = listOf(
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
                ReaderToolbarAction.Headers,
            ),
            hidden = emptyList(),
        )
        val stored = AccountSettings().encode().lineSequence().filter { !it.startsWith("readerToolbar=") }.joinToString("\n") + "\nreaderBar=Reply\n"
        val decoded = decodeAccountSettings(stored)
        assertEquals(replyOnly, decoded.readerToolbar)
        val encoded = decoded.encode()
        assertFalse(encoded.contains("readerBar="))
        assertEquals(replyOnly, decodeAccountSettings(encoded).readerToolbar)
        val kept = ReaderToolbarLayout(
            toolbar = listOf(ReaderToolbarAction.Reply, ReaderToolbarAction.Delete),
            overflow = listOf(
                ReaderToolbarAction.Refresh,
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
                ReaderToolbarAction.Headers,
            ),
            hidden = emptyList(),
        )
        val both = "readerToolbar=T:Reply,Delete|O:Refresh,ReplyAll,Forward,Move,Spam,Bounce,Headers|H:\nreaderBar=Reply\n"
        assertEquals(kept, decodeAccountSettings(both).readerToolbar)
        assertFalse(decodeAccountSettings(both).encode().contains("readerBar="))
    }

    @Test
    fun defaultStoredReaderBarStaysNull() {
        val stored = AccountSettings().encode().lineSequence().filter { !it.startsWith("readerToolbar=") }.joinToString("\n") +
            "\nreaderBar=Reply;ReplyAll;Forward;Delete;Move\n"
        val decoded = decodeAccountSettings(stored)
        assertNull(decoded.readerToolbar)
        val encoded = decoded.encode()
        assertFalse(encoded.contains("readerBar="))
        assertTrue(encoded.lines().contains("readerToolbar="))
        assertFalse(encodePinerc(decoded).contains("# toolbar"))
        val kept = ReaderToolbarLayout(
            toolbar = listOf(ReaderToolbarAction.Reply, ReaderToolbarAction.Delete),
            overflow = listOf(
                ReaderToolbarAction.Refresh,
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
                ReaderToolbarAction.Headers,
            ),
            hidden = emptyList(),
        )
        val both = "readerToolbar=T:Reply,Delete|O:Refresh,ReplyAll,Forward,Move,Spam,Bounce,Headers|H:\n" +
            "readerBar=Reply;ReplyAll;Forward;Delete;Move\n"
        assertEquals(kept, decodeAccountSettings(both).readerToolbar)
        assertFalse(decodeAccountSettings(both).encode().contains("readerBar="))
    }

    @Test
    fun composeBarRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("composeBar="))
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
            decodeAccountSettings(bad) // F69: a bad stored value falls back instead of throwing
        }
    }

    @Test
    fun quellFlowedRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("quellFlowed=false"))
        assertFalse(decodeAccountSettings(text).quellFlowed)
        val saved = AccountSettings(quellFlowed = true)
        val encoded = saved.encode()
        assertTrue(encoded.contains("quellFlowed=true"))
        assertTrue(decodeAccountSettings(encoded).quellFlowed)
        assertEquals(saved, decodeAccountSettings(encoded))
    }

    @Test
    fun composerWrapColumnRoundTrips() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("composerWrapColumn=74"))
        assertEquals(74, decodeAccountSettings(text).composerWrapColumn)
        val saved = AccountSettings(composerWrapColumn = 72)
        val encoded = saved.encode()
        assertTrue(encoded.contains("composerWrapColumn=72"))
        assertEquals(72, decodeAccountSettings(encoded).composerWrapColumn)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        assertEquals(0, decodeAccountSettings(text.trimEnd() + "\ncomposerWrapColumn=0\n").composerWrapColumn)
        assertEquals(998, decodeAccountSettings(text.trimEnd() + "\ncomposerWrapColumn=2000\n").composerWrapColumn)
        assertEquals(0, decodeAccountSettings(text.trimEnd() + "\ncomposerWrapColumn=-3\n").composerWrapColumn)
        for (value in listOf(-1, 999)) {
            try {
                AccountSettings(composerWrapColumn = value)
                fail(value.toString())
            } catch (error: IllegalArgumentException) {
                assertEquals("bad composerWrapColumn", error.message)
            }
        }
    }

    @Test
    fun toolbarRowsRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("toolbarRows=2"))
        assertEquals(2, decodeAccountSettings(text).toolbarRows)
        val stored = text.trimEnd() + "\ntoolbarRows=3\n"
        assertEquals(3, decodeAccountSettings(stored).toolbarRows)
        val saved = AccountSettings(toolbarRows = 3)
        val encoded = saved.encode()
        assertTrue(encoded.contains("toolbarRows=3"))
        assertEquals(3, decodeAccountSettings(encoded).toolbarRows)
        assertEquals(encoded, decodeAccountSettings(encoded).encode())
        for (value in listOf("0", "5")) {
            val bad = text.trimEnd() + "\ntoolbarRows=$value\n"
            assertEquals(2, decodeAccountSettings(bad).toolbarRows) // F69: out of range -> default
        }
    }

    @Test
    fun inboundRulesRoundTrip() {
        val rule = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "ann@example.com")),
            fileInto = "INBOX.lists",
        )
        val saved = AccountSettings(inboundRules = listOf(rule))
        val text = saved.encode()
        assertTrue(text.contains("inboundRules="))
        assertEquals(listOf(rule), decodeAccountSettings(text).inboundRules)
        assertEquals(text, decodeAccountSettings(text).encode())
    }

    @Test
    fun watchedFoldersRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("watchedFolders="))
        assertTrue(defaults.contains("extraIdleBudget=2"))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("watchedFolders"))
        val decodedDefaults = decodeAccountSettings(defaults)
        assertEquals(emptyList<String>(), decodedDefaults.watchedFolders)
        assertEquals(2, decodedDefaults.extraIdleBudget)

        val spaced = AccountSettings(
            watchedFolders = listOf("INBOX", "Sent Mail"),
            extraIdleBudget = 0,
        )
        val spacedText = spaced.encode()
        assertTrue(spacedText.contains("watchedFolders=INBOX,Sent%20Mail"))
        assertTrue(spacedText.contains("extraIdleBudget=0"))
        assertEquals(spaced, decodeAccountSettings(spacedText))

        val four = AccountSettings(
            watchedFolders = listOf("A", "B", "C", "D"),
            extraIdleBudget = 2,
        )
        val fourText = four.encode()
        assertTrue(fourText.contains("watchedFolders=A,B,C,D"))
        assertTrue(fourText.contains("extraIdleBudget=2"))
        assertEquals(four, decodeAccountSettings(fourText))

        val duplicates = AccountSettings(
            watchedFolders = listOf("INBOX", "INBOX", "Sent"),
            extraIdleBudget = 1,
        )
        val duplicatesText = duplicates.encode()
        assertTrue(duplicatesText.contains("watchedFolders=INBOX,INBOX,Sent"))
        assertTrue(duplicatesText.contains("extraIdleBudget=1"))
        assertEquals(listOf("INBOX", "INBOX", "Sent"), decodeAccountSettings(duplicatesText).watchedFolders)
        assertEquals(duplicates, decodeAccountSettings(duplicatesText))

        val missingBudget = defaults.trimEnd() + "\nwatchedFolders=INBOX\n"
        val missingDecoded = decodeAccountSettings(missingBudget)
        assertEquals(listOf("INBOX"), missingDecoded.watchedFolders)
        assertEquals(2, missingDecoded.extraIdleBudget)
        val storedZero = defaults.trimEnd() + "\nextraIdleBudget=0\n"
        assertEquals(0, decodeAccountSettings(storedZero).extraIdleBudget)
        assertEquals(emptyList<String>(), decodeAccountSettings(storedZero).watchedFolders)
        val storedNegative = defaults.trimEnd() + "\nextraIdleBudget=-3\n"
        assertEquals(-3, decodeAccountSettings(storedNegative).extraIdleBudget)
    }

    @Test
    fun savedSearchesRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("savedSearches="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("savedSearches"))
        assertEquals(emptyList<SavedSearch>(), decodeAccountSettings(defaults).savedSearches)

        val saved = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("Sent Mail", "a|b", SimpleSearchField.Subject),
        )
        val settings = AccountSettings(savedSearches = saved)
        val text = settings.encode()
        assertTrue(text.contains("savedSearches=News|ada|From,Sent%20Mail|a%7Cb|Subject"))
        assertEquals(saved, decodeAccountSettings(text).savedSearches)
        assertEquals(settings, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())

        val repeated = listOf(
            SavedSearch("News", "ada", SimpleSearchField.From),
            SavedSearch("News", "ada", SimpleSearchField.From),
        )
        val dirty = defaults.trimEnd() +
            "\nsavedSearches=News|ada|From,News|ada|Nope,only|two,|ada|From,News||From,News|ada|From\n"
        assertEquals(repeated, decodeAccountSettings(dirty).savedSearches)
        assertEquals(emptyList<SavedSearch>(), decodeAccountSettings(defaults).savedSearches)
    }

    @Test
    fun savedAdvancedRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.lines().contains("savedAdvanced="))
        val keys = defaults.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }
        assertTrue(keys.contains("savedAdvanced"))
        assertEquals(emptyList<SavedAdvanced>(), decodeAccountSettings(defaults).savedAdvanced)

        val one = SavedAdvanced("News", "And\nYes\tSubject\tada", SearchScope.Current)
        val oneSettings = AccountSettings(savedAdvanced = listOf(one))
        val oneText = oneSettings.encode()
        assertTrue(oneText.lines().contains("savedAdvanced=News|And%0AYes%09Subject%09ada|Current"))
        assertEquals(listOf(one), decodeAccountSettings(oneText).savedAdvanced)
        assertEquals(oneSettings, decodeAccountSettings(oneText))
        assertEquals(oneText, decodeAccountSettings(oneText).encode())

        val saved = listOf(
            one,
            SavedAdvanced("Sent Mail", "a|b,c", SearchScope.All),
            SavedAdvanced("News", "ada", SearchScope.Subtree),
        )
        val settings = AccountSettings(savedAdvanced = saved)
        val text = settings.encode()
        assertTrue(
            text.contains(
                "savedAdvanced=News|And%0AYes%09Subject%09ada|Current,Sent%20Mail|a%7Cb%2Cc|All,News|ada|Subtree",
            ),
        )
        assertEquals(saved, decodeAccountSettings(text).savedAdvanced)
        assertEquals(settings, decodeAccountSettings(text))
        assertEquals(text, decodeAccountSettings(text).encode())

        val repeated = listOf(
            SavedAdvanced("News", "ada", SearchScope.Current),
            SavedAdvanced("News", "bob", SearchScope.All),
        )
        val dirty = defaults.trimEnd() +
            "\nsavedAdvanced=News|ada|Current,News|ada|Nope,only|two,|ada|Current,News||Current,News|bob|All\n"
        assertEquals(repeated, decodeAccountSettings(dirty).savedAdvanced)
        val dropped = defaults.trimEnd() + "\nsavedAdvanced=News|ada|Nope\n"
        assertEquals(emptyList<SavedAdvanced>(), decodeAccountSettings(dropped).savedAdvanced)
        assertEquals(emptyList<SavedAdvanced>(), decodeAccountSettings(defaults).savedAdvanced)
    }

    @Test
    fun emailSubmissionRoundTrip() {
        val text = AccountSettings().encode()
        assertTrue(text.contains("emailSubmission=false"))
        assertFalse(decodeAccountSettings(text).emailSubmission)
        val saved = AccountSettings(emailSubmission = true)
        val encoded = saved.encode()
        assertTrue(encoded.lines().contains("emailSubmission=true"))
        assertTrue(decodeAccountSettings(encoded).emailSubmission)
        assertEquals(saved, decodeAccountSettings(encoded))
        assertFalse(decodeAccountSettings(text.trimEnd() + "\nemailSubmission=false\n").emailSubmission)
        assertTrue(decodeAccountSettings(text.trimEnd() + "\nemailSubmission=true\n").emailSubmission)
    }

    @Test
    fun askThresholdsRoundTrip() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("askSort=-1"))
        assertTrue(defaults.contains("askThread=-1"))
        assertTrue(defaults.contains("askSearch=-1"))
        assertTrue(defaults.contains("askFallback=-1"))
        assertTrue(defaults.lines().contains("sortAskSkip="))
        assertTrue(defaults.lines().contains("threadAskSkip="))
        assertTrue(defaults.lines().contains("searchAskSkip="))
        assertTrue(defaults.lines().contains("fallbackAskSkip="))
        val decoded = decodeAccountSettings(defaults)
        assertEquals(-1, decoded.askSort)
        assertEquals(-1, decoded.askThread)
        assertEquals(-1, decoded.askSearch)
        assertEquals(-1, decoded.askFallback)
        assertEquals(emptySet<String>(), decoded.sortAskSkip)
        assertEquals(emptySet<String>(), decoded.threadAskSkip)
        assertEquals(emptySet<String>(), decoded.searchAskSkip)
        assertEquals(emptySet<String>(), decoded.fallbackAskSkip)
        val saved = AccountSettings(askSort = 20, threadAskSkip = setOf("Sent Mail"))
        val text = saved.encode()
        assertTrue(text.contains("askSort=20"))
        assertTrue(text.contains("askThread=-1"))
        assertTrue(text.contains("askSearch=-1"))
        assertTrue(text.contains("askFallback=-1"))
        assertTrue(text.contains("threadAskSkip=Sent%20Mail"))
        assertTrue(text.lines().contains("sortAskSkip="))
        assertTrue(text.lines().contains("searchAskSkip="))
        assertTrue(text.lines().contains("fallbackAskSkip="))
        assertEquals(saved, decodeAccountSettings(text))
        assertEquals(-1, decodeAccountSettings(defaults.trimEnd() + "\naskFallback=-7\n").askFallback)
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
        val text = AccountSettings().encode().trimEnd() +
            "\ninboundRules=From=ann%40example.com\tfile=INBOX.lists\nbogus\tzz\nSubject=x\tfile=Sent\n"
        val decoded = decodeAccountSettings(text)
        assertEquals(listOf(first, second), decoded.inboundRules)
        val encoded = decoded.encode()
        assertTrue(encoded.contains("From=ann%40example.com\tfile=INBOX.lists"))
        assertTrue(encoded.contains("Subject=x\tfile=Sent"))
        assertFalse(encoded.contains("bogus"))
    }

    @Test
    fun everyBrokenInboundRuleIsKeptRaw() {
        val raw = "inboundRules=bogus\tzz\nalso\tbad"
        val text = AccountSettings().encode().trimEnd() + "\n$raw\n"
        val decoded = decodeAccountSettings(text)
        assertEquals(emptyList<InboundRule>(), decoded.inboundRules)
        assertEquals(listOf(raw), decoded.unknownSettings)
        val encoded = decoded.encode()
        assertTrue(encoded.contains(raw))
        assertEquals(encoded.indexOf(raw), encoded.lastIndexOf(raw))
        assertFalse(encoded.lines().any { it == "inboundRules=" })
    }

    @Test
    fun duplicateListEntriesKeepTheFirst() {
        val text = AccountSettings().encode().trimEnd() +
            "\nfavorites=1|INBOX|/|In;nope;0|Sent|/|S;1|INBOX|/|Again" +
            "\nfolderStarts=INBOX|First;nope;Sent|Newest;INBOX|Last" +
            "\nfolderViews=INBOX|Arrival|newest;bad;Sent|Date|oldest;INBOX|From|newest" +
            "\nexpandedFolders=INBOX;%;Sent;INBOX\n"
        val decoded = decodeAccountSettings(text)
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
        val text = AccountSettings().encode().trimEnd() + "\ntlsMode=NotAMode\n"
        val decoded = decodeAccountSettings(text)
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
        assertEquals("tlsMode", AccountSettings(unknownSettings = listOf("tlsMode=NotAMode")).preservedSecurityKey())
        assertEquals("deletePolicy", AccountSettings(unknownSettings = listOf("deletePolicy=Nope")).preservedSecurityKey())
        assertEquals("moveMethod", AccountSettings(unknownSettings = listOf("moveMethod=Nope")).preservedSecurityKey())
        assertEquals(
            "tlsMode",
            AccountSettings(
                unknownSettings = listOf("moveMethod=Nope", "deletePolicy=Nope", "tlsMode=NotAMode"),
            ).preservedSecurityKey(),
        )
        assertEquals(
            "deletePolicy",
            AccountSettings(unknownSettings = listOf("moveMethod=Nope", "deletePolicy=Nope")).preservedSecurityKey(),
        )
        assertNull(AccountSettings(unknownSettings = listOf("futureOption=1")).preservedSecurityKey())
        assertNull(AccountSettings().preservedSecurityKey())
    }

    @Test
    fun sieveTlsModeIsSeparateFromSecurity() {
        val defaults = AccountSettings().encode()
        assertTrue(defaults.contains("sieveTlsMode=StartTls"))
        assertEquals(TlsMode.StartTls, decodeAccountSettings(defaults).sieveTlsMode)
        assertNull(decodeAccountSettings(defaults).preservedSieveTlsKey())
        val implicit = AccountSettings(sieveTlsMode = TlsMode.Implicit)
        val text = implicit.encode()
        assertTrue(text.contains("sieveTlsMode=Implicit"))
        val decoded = decodeAccountSettings(text)
        assertEquals(TlsMode.Implicit, decoded.sieveTlsMode)
        assertTrue(decoded.encode().contains("sieveTlsMode=Implicit"))
        val bad = decodeAccountSettings(defaults.trimEnd() + "\nsieveTlsMode=Nope\n")
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
        fun cleared(raw: String, choose: (AccountSettings) -> AccountSettings) {
            val decoded = decodeAccountSettings(AccountSettings().encode().trimEnd() + "\n$raw\n")
            val chosen = choose(decoded)
            val text = chosen.encode()
            val again = decodeAccountSettings(text)
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
        val text = AccountSettings().encode().trimEnd() + "\n$raw\n"
        val decoded = decodeAccountSettings(text)
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
        assertEquals(TlsMode.Implicit, AccountSettings().tlsMode)
        assertEquals(993, AccountSettings().imapPort)
        assertEquals(465, AccountSettings().smtpPort)
        assertEquals(MultiPane.Auto, AccountSettings().multiPane)
        assertEquals(LayoutChoice.Auto, AccountSettings().folderPane)
        assertEquals(ArrangementChoice.Auto, AccountSettings().paneArrangement)
        assertEquals(PreviewChoice.Auto, AccountSettings().previewSize)
        assertEquals(FolderPositionChoice.Auto, AccountSettings().folderPosition)
        assertEquals(AccountSettings(), newAccountSettings())
    }

    @Test
    fun missingOrdinaryKeyUsesTreeDefault() {
        val raw = AccountSettings().encode().lineSequence().filter { !it.startsWith("pollSeconds=") }.joinToString("\n")
        assertEquals(60, decodeAccountSettings(raw).pollSeconds)
        val panes = AccountSettings().encode().lineSequence().filter { !it.startsWith("multiPane=") }.joinToString("\n")
        assertEquals(MultiPane.Auto, decodeAccountSettings(panes).multiPane)
    }

    @Test
    fun hardLimitMismatchNamesBothValues() {
        val json = """[{"setting":"moveChunk","default":1000,"hardLimit":{"min":1,"max":100000}}]"""
        val message = validateSettingsTree(json, complete = false).single { "moveChunk" in it && "hardLimit" in it }
        assertTrue(message.contains("`moveChunk`"))
        assertTrue(message.contains("100000"))
        assertTrue(message.contains("2147483647"))
        assertTrue(message.contains("edit the JSON or the registry"))
    }

    @Test
    fun missingAndExtraHardLimitNameBothValues() {
        val missing = """[{"setting":"imapPort","default":993}]"""
        val miss = validateSettingsTree(missing, complete = false).single { "imapPort" in it && "hardLimit" in it }
        assertTrue(miss.contains("none"))
        assertTrue(miss.contains("65535"))
        assertTrue(miss.contains("edit the JSON or the registry"))
        val extra = """[{"setting":"pollSeconds","default":60,"hardLimit":{"min":1,"max":10}}]"""
        val ex = validateSettingsTree(extra, complete = false).single { "pollSeconds" in it && "hardLimit" in it }
        assertTrue(ex.contains("none"))
        assertTrue(ex.contains("10"))
        assertTrue(ex.contains("`pollSeconds`"))
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
