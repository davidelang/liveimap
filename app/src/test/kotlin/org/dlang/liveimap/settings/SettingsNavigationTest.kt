package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun routesAreThePagesAndFolderLists() {
        val nav = listOf(
            File("src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt"),
            File("app/src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt"),
        ).first { it.isFile }.readText()
        assertTrue(nav.contains("composable(\"settings\")"))
        assertTrue(nav.contains("composable(\"settings/global\")"))
        assertTrue(nav.contains("composable(\"settings/accounts\")"))
        assertTrue(nav.contains("settings/account/{accountId}/editor/{editorId}"))
        assertTrue(nav.contains("settings/account/{accountId}"))
        assertTrue(nav.contains("folderLists/expanded"))
        assertTrue(nav.contains("folderLists/views"))
        assertTrue(nav.contains("folderLists/starts"))
        assertFalse(nav.contains("toolbar" + "AccountId"))
        assertFalse(nav.contains("settings/folders/"))
    }
}
