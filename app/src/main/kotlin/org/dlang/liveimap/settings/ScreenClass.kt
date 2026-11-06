package org.dlang.liveimap.settings

/**
 * Preview size from what the user can read, not a pane layout. Inputs are the window in dp
 * (Android already enlarges dp on TVs, cars and watches for viewing distance) and the effective font scale
 * (system font scale x app text size). Text metrics at scale 1: ~7 dp per average character, ~20 dp per line.
 * Re-evaluated on every configuration change (rotation, fold/unfold, window resize, font change).
 * Panes, folder placement, arrangement, and the drawer come from the selected [org.dlang.liveimap.window.LayoutRecord],
 * not from this plan. One selected id is used at every size.
 */
enum class PreviewSize { None, Small, Large }

data class LayoutPlan(
    val preview: PreviewSize,
    val columns: Int,
    val lines: Int,
)

const val CharDp = 7.0
const val LineDp = 20.0
const val ListMinChars = 40
const val ReaderMinChars = 60
const val GutterChars = 2
const val ReaderMinLines = 16

fun planLayout(widthDp: Int, heightDp: Int, fontScale: Float): LayoutPlan {
    val scale = fontScale.coerceAtLeast(0.5f)
    val cols = (widthDp / (CharDp * scale)).toInt()
    val lines = (heightDp / (LineDp * scale)).toInt()
    val bothWide = ListMinChars + GutterChars + ReaderMinChars
    val sideBySide = cols >= bothWide && lines >= ReaderMinLines
    val listShare = if (sideBySide) {
        val extra = cols - bothWide
        ((ListMinChars + extra / 3.0) / cols).toFloat().coerceIn(0.25f, 0.5f)
    } else {
        1f
    }
    val preview = when {
        lines < 12 || cols < 30 -> PreviewSize.None
        lines >= 40 && (if (sideBySide) cols * listShare else cols.toFloat()) >= 50 -> PreviewSize.Large
        else -> PreviewSize.Small
    }
    return LayoutPlan(preview = preview, columns = cols, lines = lines)
}
