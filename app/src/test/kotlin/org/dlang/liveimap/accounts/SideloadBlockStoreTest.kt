package org.dlang.liveimap.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class SideloadBlockStoreTest {
    @Test
    fun noPlay() {
        assertEquals(
            BackupAvailability.Unavailable(BackupReason.NoPlay),
            BlockStorePasswords.available(),
        )
    }
}
