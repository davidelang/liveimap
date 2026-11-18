package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.parseIndexBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F69: version skew or one bad value no longer bricks an account. */
class TolerantSettingsTest {
    private val base = Settings(imapHost = "mail.lang.hm", username = "dlang", email = "dlang@lang.hm")

    @Test
    fun unknownKeysAreKeptAndRoundTrip() {
        val text = documentWith(base.encode(), "futureOption", "42")
        val decoded = decodeSettings(text)
        assertEquals("mail.lang.hm", decoded.imapHost)
        assertEquals(listOf("futureOption\n42"), decoded.unknownSettings)
        assertTrue(decoded.encode().contains("\"futureOption\":42"))
    }

    @Test
    fun oneBadValueFallsBackToItsDefaultOnly() {
        val text = documentWith(documentWith(base.encode(), "density", "\"Galactic\""), "imapPort", "\"x\"")
        val decoded = decodeSettings(text)
        assertEquals(Settings().density, decoded.density)
        assertEquals(Settings().imapPort, decoded.imapPort)
        assertEquals("dlang@lang.hm", decoded.email)
    }

    @Test
    fun missingAndDuplicateKeysAreFine() {
        val text = documentWith(documentWithout(base.encode(), "showDeleted"), "username", "\"other\"")
        val decoded = decodeSettings(text)
        assertEquals("other", decoded.username)
        assertEquals(Settings().showDeleted, decoded.showDeleted)
    }

    @Test
    fun inboundRulesStillSpanLinesAndStopAtUnknownKeys() {
        val text = documentWith(
            documentWith(base.encode(), "inboundRules", """["From=a%40b\tseen","Subject=x\tflagged"]"""),
            "newerThing",
            "1",
        )
        val decoded = decodeSettings(text)
        assertEquals(2, decoded.inboundRules.size)
        assertEquals(listOf("newerThing\n1"), decoded.unknownSettings)
    }

    @Test
    fun storedToolbarsMergeWithNewActions() {
        val layout = parseIndexBar("T:Search|O:Refresh|H:")
        val all = layout.toolbar + layout.overflow + layout.hidden
        assertEquals(IndexBarAction.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf(IndexBarAction.Search), layout.toolbar.take(1))
        val withUnknown = parseIndexBar("T:Search,FutureAction,Search|O:|H:")
        assertEquals(IndexBarAction.Search, withUnknown.toolbar.first())
        assertEquals(1, withUnknown.toolbar.count { it == IndexBarAction.Search })
    }
}
