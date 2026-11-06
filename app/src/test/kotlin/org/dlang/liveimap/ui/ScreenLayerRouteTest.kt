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
}
