package org.dlang.liveimap.settings

import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.accounts.userEmail
import org.dlang.liveimap.accounts.userImapHost
import org.dlang.liveimap.accounts.userImapPort
import org.dlang.liveimap.accounts.userSmtpHost
import org.dlang.liveimap.accounts.userSmtpPort
import org.dlang.liveimap.accounts.userUsername
import org.dlang.liveimap.settings.editors.openingAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEditorsTest {
    @Test
    fun openingAccountFollowsThePageRule() {
        assertNull(openingAccount(emptyList(), "drawer", ""))
        val only = AccountChoice("a", "Ada", drawer = true)
        assertEquals(only, openingAccount(listOf(only), "lastEdited", "missing"))
        val first = AccountChoice("a", "Ada", drawer = false)
        val second = AccountChoice("b", "Bea", drawer = true)
        val both = listOf(first, second)
        assertEquals(second, openingAccount(both, "drawer", "a"))
        assertEquals(first, openingAccount(both, "lastEdited", "a"))
        assertEquals(second, openingAccount(both, "lastEdited", ""))
        assertEquals(second, openingAccount(both, "lastEdited", "gone"))
    }

    @Test
    fun missingBlobEditorUsesOverlayOrUiSnapshot() {
        val ui = newAccountSettings().copy(appTextZoom = 140)
        val data = mapOf(
            userImapHost to "imap.example",
            userImapPort to "993",
            userSmtpHost to "smtp.example",
            userSmtpPort to "587",
            userUsername to "ada",
            userEmail to "ada@example",
        )
        val listed = listOf(AccountChoice("a", "Ada", drawer = true))
        val loaded = missingBlobEditor(listed, "a", data, ui, "secret", "smtp-secret", false, false)
        val expect = overlayAccountFields(newAccountSettings(), data).withUiOf(ui)
        assertEquals("a", loaded.editingId)
        assertEquals(expect, loaded.settings)
        assertEquals(140, loaded.settings.appTextZoom)
        assertEquals("secret", loaded.password)
        assertEquals("smtp-secret", loaded.smtpPassword)
        assertTrue(loaded.accounts.single().editing)
        assertEquals("a", loaded.accounts.single().id)

        val corrupt = listOf(AccountChoice("bad", "Bad", drawer = false, corrupt = true))
        val skipped = missingBlobEditor(corrupt, "bad", data, ui, "secret", "", true, true)
        assertNull(skipped.editingId)
        assertEquals(ui, skipped.settings)
        assertEquals("", skipped.password)
        assertFalse(skipped.accounts.single().editing)
        assertEquals("bad", skipped.accounts.single().id)

        val broken = data + (userImapPort to "nope")
        val unreadable = missingBlobEditor(listed, "a", broken, ui, "secret", "", false, false)
        assertNull(unreadable.editingId)
        assertEquals(ui, unreadable.settings)
        assertFalse(unreadable.accounts.single().editing)
        assertEquals("a", unreadable.accounts.single().id)
    }
}
