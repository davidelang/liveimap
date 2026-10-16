package org.dlang.liveimap.ui.toolbar

import java.io.File
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarAccountTest {
    @Test
    fun oneFieldStaysTheOthers() {
        val current = AccountSettings(
            indexBar = defaultIndexBar(),
            selectionBar = defaultSelectionBar(),
            folderBar = defaultFolderBar(),
            readerToolbar = resetReaderToolbar(),
            composeBar = defaultComposeBar(),
            toolbarRows = 3,
        )
        val nextIndex = current.indexBar.copy(toolbar = listOf(IndexBarAction.Refresh))
        val indexed = applyToolbarField(current, indexBar = nextIndex)
        assertEquals(nextIndex, indexed.indexBar)
        assertEquals(current.selectionBar, indexed.selectionBar)
        assertEquals(current.folderBar, indexed.folderBar)
        assertEquals(current.readerToolbar, indexed.readerToolbar)
        assertEquals(current.composeBar, indexed.composeBar)
        assertEquals(current.toolbarRows, indexed.toolbarRows)
        val nextSelection = current.selectionBar.copy(toolbar = listOf(SelectionBarAction.Flag))
        val selected = applyToolbarField(current, selectionBar = nextSelection)
        assertEquals(current.indexBar, selected.indexBar)
        assertEquals(nextSelection, selected.selectionBar)
        assertEquals(current.folderBar, selected.folderBar)
        assertEquals(current.readerToolbar, selected.readerToolbar)
        assertEquals(current.composeBar, selected.composeBar)
        assertEquals(current.toolbarRows, selected.toolbarRows)
    }

    @Test
    fun editorWritesOneFieldInsideUpdate() {
        val editor = source("kotlin/org/dlang/liveimap/ui/toolbar/ToolbarEditorScreen.kt")
        assertFalse(editor.contains("Mutex"))
        assertFalse(editor.contains("toolbarIo"))
        assertTrue(editor.contains("rememberCoroutineScope()"))
        assertTrue(editor.contains("accountId.isBlank()"))
        assertTrue(editor.contains("forAccount(accountId)"))
        val fields = listOf("indexBar", "selectionBar", "folderBar", "readerToolbar", "composeBar", "toolbarRows")
        val copies = Regex("""\.copy\(([^)]*)\)""").findAll(editor).toList()
        assertEquals(fields.size, copies.size)
        for (copy in copies) {
            val args = copy.groupValues[1]
            val named = fields.filter { Regex("""\b$it\s*=""").containsMatchIn(args) }
            assertEquals(args, 1, named.size)
        }
        for (field in fields) {
            val call = "store.update { applyToolbarField(it, $field = next) }"
            val at = editor.indexOf(call)
            assertTrue(call, at > 0)
            val persist = editor.lastIndexOf("fun persist", at)
            assertTrue(persist >= 0)
            assertTrue(editor.substring(persist, at).contains("applyToolbarField"))
        }
    }

    @Test
    fun navigationStoresTheAccountTheScreenPassed() {
        val nav = source("kotlin/org/dlang/liveimap/ui/NavHost.kt")
        assertTrue(nav.contains("toolbarAccountId.value = accountId"))
        assertTrue(nav.contains("rememberSaveable { toolbarAccountId.value }"))
        for (screen in listOf("Index", "Selection", "Folders", "Reader", "Compose")) {
            assertTrue(nav.contains("ToolbarEditorScreen(ToolbarScreen.$screen, accountId)"))
        }
        assertTrue(nav.contains("onCustomize = { accountId -> openToolbar(\"index\", accountId) }"))
        assertTrue(nav.contains("onCustomizeSelection = { accountId -> openToolbar(\"selection\", accountId) }"))
        assertTrue(nav.contains("onCustomize = { accountId -> openToolbar(\"folders\", accountId) }"))
        assertTrue(nav.contains("onCustomize = { accountId -> openToolbar(\"reader\", accountId) }"))
        assertTrue(nav.contains("onCustomize = { accountId -> openToolbar(\"compose\", accountId) }"))
        assertTrue(nav.contains("onOpenPanel = { name, accountId -> openToolbar(name, accountId) }"))
        assertTrue(source("kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt").contains("onCustomize(accountId)"))
        assertTrue(source("kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt").contains("onCustomizeSelection(accountId)"))
        assertTrue(source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt").contains("onCustomize(accountId)"))
        assertTrue(source("kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt").contains("onCustomize(accountId)"))
        assertTrue(source("kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt").contains("onCustomize(accountId)"))
        val settings = source("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        for (name in listOf("index", "selection", "folders", "reader", "compose")) {
            assertTrue(settings.contains("onOpenPanel(\"$name\", editor.editingId.orEmpty())"))
        }
    }

    private fun source(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
}
