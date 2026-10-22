package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeButtonTest {
    @Test
    fun hiddenRoutesStayHiddenWithOneAccount() {
        val accounts = setOf("acct")
        for (route in listOf(
            "settings",
            "settings/account",
            "help",
            "about",
            "about/licenses",
            "compose",
        )) {
            assertEquals(route, ComposeButton.Hidden, composeButton(route, accounts))
        }
    }

    @Test
    fun oneAccountOnFoldersOrIndexOpens() {
        assertEquals(ComposeButton.Open, composeButton("folders", setOf("acct")))
        assertEquals(ComposeButton.Open, composeButton("index/{mailbox}", setOf("acct")))
    }

    @Test
    fun noneOrSeveralOrBlankAsks() {
        assertEquals(ComposeButton.Pick, composeButton("folders", emptySet()))
        assertEquals(ComposeButton.Pick, composeButton("folders", setOf("acct-a", "acct-b")))
        assertEquals(ComposeButton.Pick, composeButton("index/{mailbox}", setOf("")))
    }

    @Test
    fun modalDrawerMatchesTheMailSheet() {
        assertEquals(
            false,
            mailUsesModalDrawer(
                settingsSplit = true,
                hasCustomTree = false,
                drawerOutsideMail = false,
                split = true,
            ),
        )
        assertEquals(
            false,
            mailUsesModalDrawer(
                settingsSplit = false,
                hasCustomTree = true,
                drawerOutsideMail = true,
                split = false,
            ),
        )
        assertEquals(
            false,
            mailUsesModalDrawer(
                settingsSplit = false,
                hasCustomTree = false,
                drawerOutsideMail = false,
                split = true,
            ),
        )
        assertEquals(
            true,
            mailUsesModalDrawer(
                settingsSplit = false,
                hasCustomTree = true,
                drawerOutsideMail = false,
                split = true,
            ),
        )
        assertEquals(
            true,
            mailUsesModalDrawer(
                settingsSplit = false,
                hasCustomTree = false,
                drawerOutsideMail = false,
                split = false,
            ),
        )
    }

    @Test
    fun settingsRouteIsNotASplit() {
        val nav = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt",
        ).map(::File).first { it.exists() }.readText()
        assertFalse(nav.contains("settingsSplitRoute"))
        assertTrue(nav.contains("settingsSplit = false"))
    }

    @Test
    fun fabLayerFollowsTheModalDrawer() {
        assertEquals(ComposeFabLayer.Hidden, composeFabLayer(ComposeButton.Hidden, modalDrawer = true))
        assertEquals(ComposeFabLayer.Hidden, composeFabLayer(ComposeButton.Hidden, modalDrawer = false))
        assertEquals(ComposeFabLayer.UnderDrawer, composeFabLayer(ComposeButton.Open, modalDrawer = true))
        assertEquals(ComposeFabLayer.UnderDrawer, composeFabLayer(ComposeButton.Pick, modalDrawer = true))
        assertEquals(ComposeFabLayer.Overlay, composeFabLayer(ComposeButton.Open, modalDrawer = false))
    }
}
