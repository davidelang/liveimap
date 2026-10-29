package org.dlang.liveimap.debug.acceptance

import org.dlang.liveimap.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceMatrixTest {
    @Test
    fun variantsNeverTouchTheStoredAccount() {
        val account = Settings(username = "liveimap", imapHost = "lab.test")
        val forced = variantAccount(account, acceptanceVariants.single { it.forceFallbacks })
        assertTrue(forced.forceSlowerFallbacks)
        assertFalse(account.forceSlowerFallbacks)
        assertEquals(account, variantAccount(account, plainVariant))
        assertTrue(acceptanceVariants.map { it.name }.containsAll(listOf("no SORT", "no CONDSTORE/QRESYNC", "slow fallbacks forced")))
    }
}
