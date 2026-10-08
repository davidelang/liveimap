package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.AccountChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerAccountTest {
    @Test
    fun oneChoiceIsEmpty() {
        val rows = drawerInboxes(listOf(AccountChoice(id = "a", name = "Ada", chosen = true)))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun twoChoicesKeepIdOrder() {
        val rows = drawerInboxes(
            listOf(
                AccountChoice(id = "a", name = "Ada", chosen = true),
                AccountChoice(id = "b", name = "Bea", chosen = false),
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }
}
