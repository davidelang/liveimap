package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayerRouteTest {
    @Test
    fun mailRoutesNeverBecomeScreenLayers() {
        // A mail route taken for a screen would push a second copy of the folder or message as a Screen layer.
        for (route in listOf("folders", "index/INBOX", "search/INBOX", "found/INBOX/Current", "reader/INBOX/1/1?epoch=0")) {
            assertNull(route, screenLayerKey(route))
            assertFalse(route, isScreenRoute(route))
        }
        // Argument routes keep their arguments in the key, so two accounts' settings are two layers.
        assertEquals("settings/account/abc", screenLayerKey("settings/account/abc"))
        assertTrue(screenLayerKey("settings/account/abc") != screenLayerKey("settings/account/xyz"))
    }

    @Test
    fun reopeningAScreenWhoseRouteIsNotShownNavigatesAgain() {
        val base = org.dlang.liveimap.window.WindowStack.drawer("a").pushMailboxIndex("INBOX")
        val fresh = screenOpenPlan(base, "settings")
        assertFalse(fresh.existing)
        assertEquals(0, fresh.pops)
        assertTrue(screenNeedsNavigate(fresh, null, "settings"))
        val withSettings = fresh.stack
        val again = screenOpenPlan(withSettings, "settings")
        assertTrue(again.existing)
        assertEquals(withSettings, again.stack)
        assertEquals(0, again.pops)
        // Layer present and its route on top: nothing to do. Layer present, route gone: navigate.
        assertFalse(screenNeedsNavigate(again, "settings", "settings"))
        assertTrue(screenNeedsNavigate(again, null, "settings"))
        assertTrue(screenNeedsNavigate(again, "help", "settings"))
        val piled = withSettings.pushScreen("help").pushScreen("about")
        val back = screenOpenPlan(piled, "settings")
        assertEquals(2, back.pops)
        assertEquals(withSettings, back.stack)
    }

    @Test
    fun routePatternsRebuildTheScreenKeys() {
        val args = mapOf("accountId" to "abc", "editorId" to "addressBooks")
        assertEquals("settings", screenKeyOfRoute("settings") { args[it] })
        assertEquals("settings/account/abc", screenKeyOfRoute("settings/account/{accountId}") { args[it] })
        assertEquals(
            "settings/account/abc/editor/addressBooks",
            screenKeyOfRoute("settings/account/{accountId}/editor/{editorId}") { args[it] },
        )
        assertEquals("settings/setup?accountId=abc", screenKeyOfRoute("settings/setup?accountId={accountId}") { args[it] })
        assertNull(screenKeyOfRoute("settings/account/{accountId}") { null })
        assertNull(screenKeyOfRoute("folders") { args[it] })
    }
}
