package org.dlang.liveimap.accounts

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMigrationTest {
    @Test
    fun matchCopiesHostAndPasswordAndDeletesOld() = runBlocking {
        val settings = sampleSettings()
        val password = "pw-9f3c-secret"
        val legacy = FakeLegacy(settings.encode(), password)
        val map = FakeAccountMap()
        val cipher = FakeCipher()
        val logs = mutableListOf<String>()

        val outcome = migrateAccount(
            legacy,
            map,
            cipher,
            newAccountId = { "acct-1" },
            log = { logs.add(it) },
        )

        assertEquals(MigrationOutcome.Migrated, outcome)
        val account = map.stored!!
        assertEquals(liveimapAccountType, "org.dlang.liveimap")
        assertEquals("ada@example.com", account.name)
        assertEquals("acct-1", account.userData[userAccountId])
        assertEquals(accountSchemaValue, account.userData[userSchema])
        assertEquals(migratedFromDatastoreV1, account.userData[userMigratedFrom])
        val loaded = overlayAccountFields(
            decodeAccountSettings(map.prefs.getValue("acct-1")),
            account.userData,
        )
        assertEquals(settings.imapHost, loaded.imapHost)
        assertEquals(settings, loaded)
        assertEquals(password, cipher.decrypt("acct-1", account.passwordSlot))
        assertFalse(account.passwordSlot == password)
        assertFalse(account.passwordSlot.contains(password))
        assertNull(legacy.encodedText)
        assertTrue(legacy.passwordDeleted)
        assertTrue(logs.none { it.contains(password) })
    }

    @Test
    fun emptyEmailUsesUsernameAtHost() = runBlocking {
        val settings = sampleSettings().copy(email = "")
        val legacy = FakeLegacy(settings.encode(), "pw-9f3c-secret")
        val map = FakeAccountMap()

        migrateAccount(legacy, map, FakeCipher(), newAccountId = { "acct-2" })

        assertEquals("ada@imap.example.com", map.stored!!.name)
        assertEquals("acct-2", map.stored!!.userData[userAccountId])
    }

    @Test
    fun readbackHostMismatchKeepsOldPassword() = runBlocking {
        val settings = sampleSettings()
        val password = "pw-9f3c-secret"
        val legacy = FakeLegacy(settings.encode(), password)
        val map = FakeAccountMap(readHost = "other.example")
        val logs = mutableListOf<String>()

        val outcome = migrateAccount(
            legacy,
            map,
            FakeCipher(),
            newAccountId = { "acct-1" },
            log = { logs.add(it) },
        )

        assertEquals(MigrationOutcome.Failed, outcome)
        assertEquals(password, legacy.storedPassword)
        assertFalse(legacy.passwordDeleted)
        assertEquals(settings.encode(), legacy.encodedText)
        assertNull(map.stored!!.userData[userMigratedFrom])
        assertTrue(logs.none { it.contains(password) })
    }

    @Test
    fun cipherFailureKeepsOldPassword() = runBlocking {
        val settings = sampleSettings()
        val password = "pw-9f3c-secret"
        val legacy = FakeLegacy(settings.encode(), password)
        val map = FakeAccountMap()

        val outcome = migrateAccount(
            legacy,
            map,
            FakeCipher(decryptFails = true),
            newAccountId = { "acct-1" },
        )

        assertEquals(MigrationOutcome.Failed, outcome)
        assertEquals(password, legacy.storedPassword)
        assertFalse(legacy.passwordDeleted)
        assertEquals(settings.encode(), legacy.encodedText)
        assertNull(map.stored?.userData?.get(userMigratedFrom))
    }

    @Test
    fun secondCallAfterMatchChangesNothing() = runBlocking {
        val settings = sampleSettings()
        val legacy = FakeLegacy(settings.encode(), "pw-9f3c-secret")
        val map = FakeAccountMap()
        val cipher = FakeCipher()
        migrateAccount(legacy, map, cipher, newAccountId = { "acct-1" })
        val before = map.snapshot()
        val encoded = legacy.encodedText
        val deleted = legacy.passwordDeleted

        val outcome = migrateAccount(legacy, map, cipher, newAccountId = { "acct-changed" })

        assertEquals(MigrationOutcome.Already, outcome)
        assertEquals(before, map.snapshot())
        assertEquals(encoded, legacy.encodedText)
        assertEquals(deleted, legacy.passwordDeleted)
        assertEquals("acct-1", map.stored!!.userData[userAccountId])
    }

    @Test
    fun absentOldKeyDoesNothing() = runBlocking {
        val legacy = FakeLegacy(encoded = null, password = "pw-9f3c-secret")
        val map = FakeAccountMap()

        val outcome = migrateAccount(legacy, map, FakeCipher(), newAccountId = { "acct-1" })

        assertEquals(MigrationOutcome.NothingToDo, outcome)
        assertNull(map.stored)
        assertEquals("pw-9f3c-secret", legacy.storedPassword)
        assertFalse(legacy.passwordDeleted)
    }

    @Test
    fun markerSkipsEvenIfOldPasswordRemains() = runBlocking {
        val legacy = FakeLegacy(sampleSettings().encode(), "pw-9f3c-secret")
        val map = FakeAccountMap()
        map.stored = ManagedAccount(
            name = "ada@example.com",
            userData = mapOf(
                userAccountId to "acct-1",
                userMigratedFrom to migratedFromDatastoreV1,
            ),
            passwordSlot = "kept-slot",
        )
        val before = map.snapshot()

        val outcome = migrateAccount(legacy, map, FakeCipher(), newAccountId = { "acct-new" })

        assertEquals(MigrationOutcome.Already, outcome)
        assertEquals(before, map.snapshot())
        assertEquals("pw-9f3c-secret", legacy.storedPassword)
        assertFalse(legacy.passwordDeleted)
        assertEquals(sampleSettings().encode(), legacy.encodedText)
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

private class FakeLegacy(encoded: String?, password: String) : LegacyAccount {
    var encodedText: String? = encoded
    var storedPassword: String = password
    var passwordDeleted: Boolean = false

    override suspend fun encoded(): String? = encodedText

    override suspend fun password(): String = storedPassword

    override suspend fun deleteEncoded() {
        encodedText = null
    }

    override suspend fun deletePassword() {
        passwordDeleted = true
        storedPassword = ""
    }
}

private class FakeAccountMap(private val readHost: String? = null) : AccountMap {
    var stored: ManagedAccount? = null
    val prefs: MutableMap<String, String> = linkedMapOf()

    fun snapshot(): Pair<ManagedAccount?, Map<String, String>> = stored to prefs.toMap()

    override suspend fun accounts(): List<ManagedAccount> {
        val stored = stored ?: return emptyList()
        val host = readHost ?: return listOf(stored)
        return listOf(stored.copy(userData = stored.userData + (userImapHost to host)))
    }

    override suspend fun write(name: String, userData: Map<String, String>, passwordSlot: String) {
        stored = ManagedAccount(name, userData, passwordSlot)
    }

    override suspend fun preference(accountId: String): String? = prefs[accountId]

    override suspend fun putPreference(accountId: String, value: String) {
        prefs[accountId] = value
    }
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
