package org.dlang.liveimap.settings

/**
 * Automatic layout defaults from what the user can read, not from device classes. Inputs are the window in dp
 * (Android already enlarges dp on TVs, cars and watches for viewing distance) and the effective font scale
 * (system font scale x app text size). Text metrics at scale 1: ~7 dp per average character, ~20 dp per line.
 * Re-evaluated on every configuration change (rotation, fold/unfold, window resize, font change).
 * Every output is only a default; each has its own user setting.
 */
enum class PaneArrangement { SideBySide, Stacked }
enum class FolderPlacement { None, Beside, AboveList }
enum class PreviewSize { None, Small, Large }

data class LayoutPlan(
    val panes: Int,                 // 1 = one pane at a time; 2 = list + reader; 3 = folders + list + reader
    val arrangement: PaneArrangement,
    val folders: FolderPlacement,
    val readerFullScreen: Boolean,  // the message opens full screen instead of in a split
    val listShare: Float,           // share of the list/reader span given to the list (side by side: width; stacked: height)
    val preview: PreviewSize,       // preview lines in list rows
    val columns: Int,               // readable characters across the window
    val lines: Int,                 // readable lines down the window
)

const val CharDp = 7.0
const val LineDp = 20.0
const val ListMinChars = 40
const val ReaderMinChars = 60
const val FolderMinChars = 24
const val GutterChars = 2
const val ListMinLines = 8
const val ReaderMinLines = 16
const val FolderMinLines = 8
/** Stacked list over reader: ~20 list lines + ~40 reader lines. */
const val StackMinLines = 60
/** Folders above the list in the left column: ~16 folder lines + ~40 list lines. */
const val FoldersAboveMinLines = 56

fun planLayout(widthDp: Int, heightDp: Int, fontScale: Float): LayoutPlan {
    val scale = fontScale.coerceAtLeast(0.5f)
    val cols = (widthDp / (CharDp * scale)).toInt()
    val lines = (heightDp / (LineDp * scale)).toInt()
    val bothWide = ListMinChars + GutterChars + ReaderMinChars
    val sideBySide = cols >= bothWide && lines >= ReaderMinLines
    val stacked = !sideBySide && cols >= ReaderMinChars && lines >= StackMinLines
    // Folders: their own column when wide enough for three; else above the list in the left column when it is
    // tall enough for both (short, wide screens such as a car dash cannot).
    val folders = when {
        !sideBySide -> FolderPlacement.None
        cols >= FolderMinChars + bothWide + 2 * GutterChars + 30 -> FolderPlacement.Beside
        lines >= FoldersAboveMinLines -> FolderPlacement.AboveList
        else -> FolderPlacement.None
    }
    val panes = when {
        sideBySide && folders != FolderPlacement.None -> 3
        sideBySide || stacked -> 2
        else -> 1
    }
    val listShare = when {
        sideBySide -> {
            val span = cols - if (folders == FolderPlacement.Beside) FolderMinChars + GutterChars else 0
            val extra = span - bothWide
            ((ListMinChars + extra / 3.0) / span).toFloat().coerceIn(0.25f, 0.5f)
        }
        stacked -> 0.4f
        else -> 1f
    }
    val preview = when {
        lines < 12 || cols < 30 -> PreviewSize.None
        lines >= 40 && (if (sideBySide) cols * listShare else cols.toFloat()) >= 50 -> PreviewSize.Large
        else -> PreviewSize.Small
    }
    return LayoutPlan(
        panes = panes,
        arrangement = if (stacked) PaneArrangement.Stacked else PaneArrangement.SideBySide,
        folders = folders,
        readerFullScreen = panes == 1,
        listShare = listShare,
        preview = preview,
        columns = cols,
        lines = lines,
    )
}
