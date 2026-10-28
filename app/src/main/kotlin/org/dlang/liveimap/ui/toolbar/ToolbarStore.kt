package org.dlang.liveimap.ui.toolbar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.dlang.liveimap.settings.percentDecode
import org.dlang.liveimap.settings.percentEncode
import org.dlang.liveimap.settings.readUiLine
import org.dlang.liveimap.settings.saveGlobal

data class ToolbarRecord(
    val indexBar: IndexBarLayout = defaultIndexBar(),
    val selectionBar: SelectionBarLayout = defaultSelectionBar(),
    val folderBar: FolderBarLayout = defaultFolderBar(),
    val readerToolbar: ReaderToolbarLayout? = null,
    val composeBar: ComposeBarLayout = defaultComposeBar(),
    val toolbarRows: Int = 2,
)

object ToolbarStore {
    var record by mutableStateOf(ToolbarRecord())
        private set

    fun indexBar(): IndexBarLayout = record.indexBar
    fun selectionBar(): SelectionBarLayout = record.selectionBar
    fun folderBar(): FolderBarLayout = record.folderBar
    fun readerToolbar(): ReaderToolbarLayout? = record.readerToolbar
    fun composeBar(): ComposeBarLayout = record.composeBar
    fun toolbarRows(): Int = record.toolbarRows

    fun install(uiRecord: String) {
        val line = readUiLine(uiRecord, "toolbars")
        record = if (line.isNullOrEmpty()) ToolbarRecord() else decodeToolbarValue(line)
    }

    fun replace(next: ToolbarRecord) {
        if (next.toolbarRows !in 1..4) throw IllegalArgumentException("toolbarRows")
        record = next
    }

    fun encodeValue(): String = encodeToolbarValue(record)

    suspend fun save(context: Context) {
        saveGlobal(context, "toolbars", encodeValue())
    }
}

fun encodeToolbarValue(record: ToolbarRecord): String {
    val reader = record.readerToolbar?.let { encodeReaderToolbar(it) }.orEmpty()
    val inner = listOf(
        "indexBar=" + encodeIndexBar(record.indexBar),
        "selectionBar=" + encodeSelectionBar(record.selectionBar),
        "folderBar=" + encodeFolderBar(record.folderBar),
        "readerToolbar=$reader",
        "composeBar=" + encodeComposeBar(record.composeBar),
        "toolbarRows=" + record.toolbarRows,
    ).joinToString("\n")
    return percentEncode(inner)
}

fun decodeToolbarValue(value: String): ToolbarRecord {
    val lines = percentDecode(value).lines().filter { it.isNotEmpty() }
    var index = defaultIndexBar()
    var selection = defaultSelectionBar()
    var folder = defaultFolderBar()
    var reader: ReaderToolbarLayout? = null
    var compose = defaultComposeBar()
    var rows = 2
    for (line in lines) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq)
        val text = line.substring(eq + 1)
        when (key) {
            "indexBar" -> index = parseIndexBar(text)
            "selectionBar" -> selection = parseSelectionBar(text)
            "folderBar" -> folder = parseFolderBar(text)
            "readerToolbar" -> reader = if (text.isEmpty()) null else parseReaderToolbar(text)
            "composeBar" -> compose = parseComposeBar(text)
            "toolbarRows" -> rows = text.toInt()
        }
    }
    if (rows !in 1..4) throw IllegalArgumentException("toolbarRows")
    return ToolbarRecord(index, selection, folder, reader, compose, rows)
}

fun effectiveReaderToolbar(): ReaderToolbarLayout = ToolbarStore.readerToolbar() ?: defaultReaderToolbar()
