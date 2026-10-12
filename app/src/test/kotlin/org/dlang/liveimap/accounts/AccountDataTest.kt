package org.dlang.liveimap.accounts

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.newAccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDataTest {
    @Test
    fun chooseAccountKeepsTheSelectedId() {
        val only = ManagedAccount("one", mapOf(userAccountId to "one"), "")
        assertEquals(only, chooseAccount(listOf(only), null))

        val first = ManagedAccount("one", mapOf(userAccountId to "one"), "")
        val second = ManagedAccount("two", mapOf(userAccountId to "two"), "")
        assertEquals(second, chooseAccount(listOf(first, second), "two"))

        val plainA = ManagedAccount("a", mapOf(userAccountId to "a"), "")
        val plainB = ManagedAccount("b", mapOf(userAccountId to "b"), "")
        assertEquals(plainA, chooseAccount(listOf(plainA, plainB), "missing"))

        assertNull(chooseAccount(emptyList(), "missing"))
        assertEquals("smtpPassword.abc", smtpPasswordKeyFor("abc"))
    }

    @Test
    fun accountRemovalKeepsTheLastAccount() {
        val only = ManagedAccount("one", mapOf(userAccountId to "only"), "")
        val kept = accountRemoval(listOf(only), "only", "kept")
        assertFalse(kept.remove)
        assertEquals("kept", kept.selectedId)

        val a = ManagedAccount("a", mapOf(userAccountId to "a"), "")
        val b = ManagedAccount("b", mapOf(userAccountId to "b"), "")
        val removeB = accountRemoval(listOf(a, b), "b", "a")
        assertTrue(removeB.remove)
        assertEquals("a", removeB.selectedId)
        val removeA = accountRemoval(listOf(a, b), "a", "a")
        assertTrue(removeA.remove)
        assertEquals("b", removeA.selectedId)

        val blank = accountRemoval(listOf(a, b), "", "a")
        assertFalse(blank.remove)
        assertEquals("a", blank.selectedId)
        val unknown = accountRemoval(listOf(a, b), "missing", "a")
        assertFalse(unknown.remove)
        assertEquals("a", unknown.selectedId)

        val c = ManagedAccount("c", mapOf(userAccountId to "c"), "")
        val migrated = ManagedAccount(
            "b",
            mapOf(userAccountId to "b"),
            "",
        )
        val removeSelected = accountRemoval(listOf(c, migrated, a), "a", "a")
        assertTrue(removeSelected.remove)
        assertEquals("c", removeSelected.selectedId) // first remaining; no migrated-account preference
    }

    @Test
    fun addedAccountStartsFromNewDefaults() {
        val fresh = newAccountSettings()
        val userData = accountUserData("new", fresh, null)
        val loaded = overlayAccountFields(decodeAccountSettings(fresh.encode()), userData)
        assertEquals(fresh, loaded)
        assertEquals("", loaded.imapHost)
        assertEquals("", loaded.username)
        assertEquals("", loaded.email)
        assertEquals("@", accountVisibleName(loaded))
    }

    private fun sampleSettings(): AccountSettings = AccountSettings(
        imapHost = "imap.example.com",
        imapPort = 993,
        smtpHost = "smtp.example.com",
        smtpPort = 587,
        username = "ada",
        email = "ada@example.com",
        sentMailbox = "Sent",
    )
}

private class FakeCipher(private val decryptFails: Boolean = false) : AccountCipher {
    override fun encrypt(accountId: String, password: String): String {
        val raw = password.toByteArray(Charsets.UTF_8)
        val flipped = ByteArray(raw.size) { index -> (raw[index].toInt() xor 0xA5).toByte() }
        val body = java.util.Base64.getEncoder().withoutPadding().encodeToString(flipped)
        return "v1:$body"
    }

    override fun decrypt(accountId: String, passwordSlot: String): String {
        if (decryptFails) error("cipher failed")
        val flipped = java.util.Base64.getDecoder().decode(passwordSlot.removePrefix("v1:"))
        val raw = ByteArray(flipped.size) { index -> (flipped[index].toInt() xor 0xA5).toByte() }
        return raw.toString(Charsets.UTF_8)
    }
}
