package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.parseIndexBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F69: version skew or one bad value no longer bricks an account. */
class TolerantSettingsTest {
    private val base = AccountSettings(imapHost = "mail.lang.hm", username = "dlang", email = "dlang@lang.hm")

    @Test
    fun unknownKeysAreKeptAndRoundTrip() {
        val text = base.encode() + "futureOption=42\n"
        val decoded = decodeAccountSettings(text)
        assertEquals("mail.lang.hm", decoded.imapHost)
        assertEquals(listOf("futureOption=42"), decoded.unknownSettings)
        assertTrue(decoded.encode().contains("futureOption=42"))
    }

    @Test
    fun oneBadValueFallsBackToItsDefaultOnly() {
        val text = base.encode().replace(Regex("(?m)^density=.*$"), "density=Galactic").replace(Regex("(?m)^imapPort=.*$"), "imapPort=x")
        val decoded = decodeAccountSettings(text)
        assertEquals(AccountSettings().density, decoded.density)
        assertEquals(AccountSettings().imapPort, decoded.imapPort)
        assertEquals("dlang@lang.hm", decoded.email)
    }

    @Test
    fun missingAndDuplicateKeysAreFine() {
        val text = base.encode().lines().filterNot { it.startsWith("showDeleted=") }.joinToString("\n") + "\nusername=other\n"
        val decoded = decodeAccountSettings(text)
        assertEquals("other", decoded.username)
        assertEquals(AccountSettings().showDeleted, decoded.showDeleted)
    }

    @Test
    fun inboundRulesStillSpanLinesAndStopAtUnknownKeys() {
        val rules = "From=a%40b\tseen\nSubject=x\tflagged"
        val text = base.encode() + "inboundRules=$rules\nnewerThing=1\n"
        val decoded = decodeAccountSettings(text)
        assertEquals(2, decoded.inboundRules.size)
        assertEquals(listOf("newerThing=1"), decoded.unknownSettings)
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
