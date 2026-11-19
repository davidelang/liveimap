package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.reader.ReaderDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScopeRoutingTest {
    @Before
    fun tree() {
        installGeneratedSettings()
    }

    @Test
    fun scopePicksTheBlob() {
        assertEquals(SettingScope.Ui, settingsRegistry.getValue("indexPageSize").scope)
        assertEquals(SettingScope.Ui, settingsRegistry.getValue("theme").scope)
        assertEquals(SettingScope.Account, settingsRegistry.getValue("imapHost").scope)
        assertEquals(true, settingsRegistry.getValue("imapHost").server)
        assertEquals(SettingScope.Account, settingsRegistry.getValue("friendlyName").scope)
        val account = Settings(friendlyName = "Ada")
        assertEquals("Ada", account.getSetting("friendlyName"))
        assertEquals("indexPageSize=40", updateGlobalRecord("", "indexPageSize", "40"))
    }

    @Test
    fun differingAccountsDoNotOpenAReviewLine() {
        val shared = decodeSettings("""{"readerDisplay":"Flowed","displayImages":false}""")
        val html = decodeSettings("""{"readerDisplay":"Html","displayImages":true}""")
        val mono = decodeSettings("""{"readerDisplay":"Monospaced","displayImages":false}""")
        val (docs, lines) = reconcileScope(shared, mapOf("a" to html, "b" to mono))
        val (nextUi, nextAccounts) = docs
        assertTrue(lines.isEmpty())
        assertEquals(shared.encodeUi(), nextUi.encodeUi())
        assertEquals(ReaderDisplay.Flowed, nextUi.readerDisplay)
        assertFalse(nextUi.displayImages)
        assertFalse(nextAccounts.getValue("a").encode().contains("readerDisplay"))
        assertFalse(nextAccounts.getValue("a").encode().contains("displayImages"))
        assertFalse(nextAccounts.getValue("b").encode().contains("readerDisplay"))
        val again = reconcileScope(nextUi, nextAccounts)
        assertTrue(again.second.isEmpty())
        assertEquals(nextUi.encodeUi(), again.first.first.encodeUi())
    }

    @Test
    fun aMissingSharedValueStaysMissingWhenAccountsDiffer() {
        val shared = decodeSettings("""{"theme":"Dark"}""")
        val html = decodeSettings("""{"readerDisplay":"Html","displayImages":true}""")
        val mono = decodeSettings("""{"readerDisplay":"Monospaced","displayImages":false}""")
        val (docs, lines) = reconcileScope(shared, mapOf("a" to html, "b" to mono))
        val (nextUi, nextAccounts) = docs
        assertTrue(lines.isEmpty())
        assertEquals(shared.encodeUi(), nextUi.encodeUi())
        assertFalse("readerDisplay" in nextUi.storedKeys)
        assertFalse("displayImages" in nextUi.storedKeys)
        assertFalse("readerDisplay" in nextAccounts.getValue("a").storedKeys)
        assertFalse("displayImages" in nextAccounts.getValue("b").storedKeys)
        val again = reconcileScope(nextUi, nextAccounts)
        assertTrue(again.second.isEmpty())
        assertEquals(shared.encodeUi(), again.first.first.encodeUi())
    }

    @Test
    fun agreeingAccountCopiesMoveOntoTheSharedDocument() {
        val shared = decodeSettings("""{"theme":"Dark"}""")
        val html = decodeSettings("""{"readerDisplay":"Html","displayImages":true}""")
        val (docs, lines) = reconcileScope(shared, mapOf("a" to html, "b" to html))
        val (nextUi, nextAccounts) = docs
        assertTrue(lines.isEmpty())
        assertEquals(ReaderDisplay.Html, nextUi.readerDisplay)
        assertTrue(nextUi.displayImages)
        assertTrue("readerDisplay" in nextUi.storedKeys)
        assertTrue("displayImages" in nextUi.storedKeys)
        assertFalse("readerDisplay" in nextAccounts.getValue("a").storedKeys)
        assertFalse("displayImages" in nextAccounts.getValue("b").storedKeys)
    }

    @Test
    fun anAccountSettingDisagreementOverwritesNeitherSide() {
        val shared = decodeSettings("""{"friendlyName":"FromUi"}""")
        val ada = decodeSettings("""{"friendlyName":"Ada"}""")
        val bea = decodeSettings("""{"friendlyName":"Bea"}""")
        val (docs, lines) = reconcileScope(shared, mapOf("a" to ada, "b" to bea))
        val (nextUi, nextAccounts) = docs
        assertTrue(lines.isEmpty())
        assertEquals(shared.encodeUi(), nextUi.encodeUi())
        assertEquals(ada.encode(), nextAccounts.getValue("a").encode())
        assertEquals(bea.encode(), nextAccounts.getValue("b").encode())
        val again = reconcileScope(nextUi, nextAccounts)
        assertTrue(again.second.isEmpty())
        assertEquals(shared.encodeUi(), again.first.first.encodeUi())
    }

    @Test
    fun agreeingAccountCopiesLeaveTheSharedDocument() {
        val shared = decodeSettings("""{"friendlyName":"Ada"}""")
        val ada = decodeSettings("""{"friendlyName":"Ada"}""")
        val (docs, lines) = reconcileScope(shared, mapOf("a" to ada, "b" to ada))
        val (nextUi, nextAccounts) = docs
        assertTrue(lines.isEmpty())
        assertFalse("friendlyName" in nextUi.storedKeys)
        assertEquals("Ada", nextAccounts.getValue("a").friendlyName)
        assertEquals("Ada", nextAccounts.getValue("b").friendlyName)
        assertTrue("friendlyName" in nextAccounts.getValue("a").storedKeys)
        assertTrue("friendlyName" in nextAccounts.getValue("b").storedKeys)
    }
}
