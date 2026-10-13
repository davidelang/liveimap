package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** David's drawer rule: the account name row opens that account's full folder list. */
class DrawerAccountNameTest {
    private val nav = listOf("src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        .map(::File).first { it.exists() }.readText()

    @Test fun accountNamesAreTappableAndOpenAllFolders() {
        assertEquals(2, Regex("testTag\\(\"drawer-account-name\"\\)").findAll(nav).count())
        assertTrue(nav.contains(".clickable { onAccountAllFolders(account) }"))
        assertTrue(nav.contains(".clickable(onClick = onAllFolders)"))
        assertTrue(nav.contains("onAccountAllFolders = { account -> openFoldersRow(account) }"))
    }
}
