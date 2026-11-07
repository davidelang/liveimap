package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayerRouteTest {
    @Test
    fun fullScreensAreRouteKeysAndMailRoutesAreNot() {
        val screens = listOf(
            "compose",
            "unsent",
            "settings/setup",
            "settings/setup?accountId=abc",
            "settings",
            "settings/accounts",
            "settings/account/_",
            "settings/account/abc",
            "settings/account/abc/editor/addressBooks",
            "folderLists/expanded",
            "folderLists/views",
            "folderLists/starts",
            "addressbook",
            "contacts",
            "filters",
            "filter",
            "help",
            "about",
            "about/licenses",
            "toolbar/index",
            "toolbar/selection",
            "toolbar/folders",
            "toolbar/reader",
            "toolbar/compose",
        )
        for (route in screens) {
            assertEquals(route, screenLayerKey(route))
            assertTrue(isScreenRoute(route))
        }
        assertTrue(isScreenRoute("settings/account/{accountId}"))
        assertTrue(isScreenRoute("settings/account/{accountId}/editor/{editorId}"))
        assertTrue(isScreenRoute("settings/setup?accountId={accountId}"))
        assertTrue(screenClosesOnSettingsRefusal("settings/setup"))
        assertTrue(screenClosesOnSettingsRefusal("settings/setup?accountId=abc"))
        assertTrue(screenClosesOnSettingsRefusal("folderLists/expanded"))
        assertTrue(screenClosesOnSettingsRefusal("folderLists/views"))
        assertTrue(screenClosesOnSettingsRefusal("folderLists/starts"))
        assertFalse(screenClosesOnSettingsRefusal("help"))
        assertFalse(screenClosesOnSettingsRefusal("filters"))
        for (route in listOf(
            "folders",
            "index/{mailbox}",
            "index/INBOX",
            "search/{mailbox}",
            "search/INBOX",
            FoundRoutePattern,
            "found/INBOX/Current",
            ReaderRoutePattern,
            "reader/INBOX/1/1?epoch=0",
        )) {
            assertNull(screenLayerKey(route))
            assertFalse(isScreenRoute(route))
        }
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
