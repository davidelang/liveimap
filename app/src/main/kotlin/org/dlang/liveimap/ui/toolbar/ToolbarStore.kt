package org.dlang.liveimap.ui.toolbar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.percentDecode
import org.dlang.liveimap.settings.percentEncode

data class ToolbarRecord(
    val indexBar: IndexBarLayout = defaultIndexBar(),
    val selectionBar: SelectionBarLayout = defaultSelectionBar(),
    val folderBar: FolderBarLayout = defaultFolderBar(),
    val readerToolbar: ReaderToolbarLayout? = null,
    val composeBar: ComposeBarLayout = defaultComposeBar(),
)

object ToolbarStore {
    var record by mutableStateOf(ToolbarRecord())
        private set

    fun indexBar(): IndexBarLayout = record.indexBar
    fun selectionBar(): SelectionBarLayout = record.selectionBar
    fun folderBar(): FolderBarLayout = record.folderBar
    fun readerToolbar(): ReaderToolbarLayout? = record.readerToolbar
    fun composeBar(): ComposeBarLayout = record.composeBar
    fun indexRows(): Int = record.indexBar.rows
    fun selectionRows(): Int = record.selectionBar.rows
    fun folderRows(): Int = record.folderBar.rows
    fun readerRows(): Int = (record.readerToolbar ?: defaultReaderToolbar()).rows
    fun composeRows(): Int = record.composeBar.rows

    fun install(settings: Settings) {
        assignToolbarRecord {
            record = ToolbarRecord(
                indexBar = settings.indexBar,
                selectionBar = settings.selectionBar,
                folderBar = settings.folderBar,
                readerToolbar = settings.readerToolbar,
                composeBar = settings.composeBar,
            )
        }
    }

    fun install(uiRecord: String) {
        if (uiRecord.isBlank()) {
            assignToolbarRecord { record = ToolbarRecord() }
            return
        }
        try {
            install(decodeSettings(uiRecord))
        } catch (_: Exception) {
            // A bad UI document keeps the bars already on screen.
        }
    }

    fun replace(next: ToolbarRecord) {
        val rows = listOf(
            next.indexBar.rows,
            next.selectionBar.rows,
            next.folderBar.rows,
            (next.readerToolbar ?: defaultReaderToolbar()).rows,
            next.composeBar.rows,
        )
        if (rows.any { it !in 1..4 }) throw IllegalArgumentException("toolbarRows")
        record = next
    }

    fun encodeValue(): String = encodeToolbarValue(record)

    suspend fun save(context: Context) {
        val current = SettingsHub.uiNow()
        val next = current.copy(
            indexBar = record.indexBar,
            selectionBar = record.selectionBar,
            folderBar = record.folderBar,
            readerToolbar = record.readerToolbar ?: defaultReaderToolbar(),
            composeBar = record.composeBar,
        )
        SettingsHub.saveUiKeys(
            context,
            next,
            setOf("indexBar", "selectionBar", "folderBar", "readerToolbar", "composeBar"),
        )
    }
}

// A background caller posts when a main looper exists. No main looper: assign here and do not throw.
private fun assignToolbarRecord(assign: () -> Unit) {
    val mine = runCatching { android.os.Looper.myLooper() }.getOrNull()
    val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
    if (main == null || mine === main) {
        assign()
    } else {
        android.os.Handler(main).post(assign)
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
        "indexRows=" + record.indexBar.rows,
        "selectionRows=" + record.selectionBar.rows,
        "folderRows=" + record.folderBar.rows,
        "readerRows=" + (record.readerToolbar ?: defaultReaderToolbar()).rows,
        "composeRows=" + record.composeBar.rows,
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
    var indexRows = 2
    var selectionRows = 2
    var folderRows = 2
    var readerRows = 2
    var composeRows = 2
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
            "indexRows" -> indexRows = text.toInt()
            "selectionRows" -> selectionRows = text.toInt()
            "folderRows" -> folderRows = text.toInt()
            "readerRows" -> readerRows = text.toInt()
            "composeRows" -> composeRows = text.toInt()
            "toolbarRows" -> {
                indexRows = text.toInt()
                selectionRows = indexRows
                folderRows = indexRows
                readerRows = indexRows
                composeRows = indexRows
            }
        }
    }
    val counts = listOf(indexRows, selectionRows, folderRows, readerRows, composeRows)
    if (counts.any { it !in 1..4 }) throw IllegalArgumentException("toolbarRows")
    return ToolbarRecord(
        index.copy(rows = indexRows),
        selection.copy(rows = selectionRows),
        folder.copy(rows = folderRows),
        reader?.copy(rows = readerRows),
        compose.copy(rows = composeRows),
    )
}

fun effectiveReaderToolbar(): ReaderToolbarLayout = ToolbarStore.readerToolbar() ?: defaultReaderToolbar()
