package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
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
}
