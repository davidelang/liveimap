package org.dlang.liveimap.debug.acceptance

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceMatrixTest {
    @Test
    fun overrideHidesTokensAndTheirParameterisedForms() {
        val s = HidingSession(FakeLab(setOf("IMAP4rev1", "SORT", "SORT=DISPLAY", "THREAD=REFERENCES", "IDLE", "UIDPLUS")), setOf("SORT", "THREAD"))
        assertEquals(setOf("IMAP4rev1", "IDLE", "UIDPLUS"), s.capabilities)
        assertFalse(s.featureCaps.threadReferences)
        assertTrue(s.featureCaps.uidPlus)
    }

    @Test
    fun variantsNeverTouchTheStoredAccount() {
        val account = AccountSettings(username = "liveimap", imapHost = "lab.test")
        val forced = variantAccount(account, acceptanceVariants.single { it.forceFallbacks })
        assertTrue(forced.forceSlowerFallbacks)
        assertFalse(account.forceSlowerFallbacks)
        assertEquals(account, variantAccount(account, plainVariant))
        assertTrue(acceptanceVariants.map { it.name }.containsAll(listOf("no SORT", "no CONDSTORE/QRESYNC", "slow fallbacks forced")))
    }

    @Test
    fun matrixRunsEachServerAndVariantAndClosesEveryServer() = runBlocking {
        val closed = mutableListOf<String>()
        val tests = acceptanceTests.take(2)
        fun server(label: String, ok: Boolean) = MatrixServer(label, open = { v ->
            if (!ok) null to "refused" else {
                val account = AccountSettings(username = "someone", imapHost = "example.com")
                AcceptanceContext(variantSession(FakeLab(setOf("IMAP4rev1", "SORT")), v), variantAccount(account, v)) { OpenResult.Connected } to ""
            }
        }, close = { closed += label })
        val rows = runMatrix(listOf(server("a", true), server("b", false)), listOf(plainVariant, acceptanceVariants[1]), tests)
        assertEquals(listOf("a", "b"), closed)
        assertEquals(2 * 2 + 2, rows.size)
        assertEquals(setOf("plain", "no SORT"), rows.filter { it.server == "a" }.map { it.variant }.toSet())
        assertEquals(listOf("fail", "fail"), rows.filter { it.server == "b" }.map { it.result })
        val grid = matrixGrid(rows)
        assertTrue(grid, grid.contains("# col 1: a / plain"))
        assertTrue(grid, grid.lines().any { it.startsWith("login") && it.trimEnd().endsWith("F   F") })
    }
}
