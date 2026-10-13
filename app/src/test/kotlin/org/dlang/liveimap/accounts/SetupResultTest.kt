package org.dlang.liveimap.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F77: only an account created during this setup is reported to the AccountManager caller. */
class SetupResultTest {
    @Test fun existingAccountIsNotReported() {
        assertNull(newSetupAccount(setOf("dlang@lang.hm"), listOf("dlang@lang.hm")))
        assertEquals("new@x", newSetupAccount(setOf("dlang@lang.hm"), listOf("dlang@lang.hm", "new@x")))
        assertNull(newSetupAccount(emptySet(), emptyList()))
    }
}
