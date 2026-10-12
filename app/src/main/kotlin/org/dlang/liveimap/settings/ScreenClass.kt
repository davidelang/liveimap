package org.dlang.liveimap.settings

/**
 * Screen classes for the Automatic pane layout, from the window's real size in inches (dp / 160) and its
 * aspect ratio (long side / short side). Re-evaluated on every configuration change, so rotation and
 * fold/unfold pick a new class. An explicit Off/Wide/On setting always wins.
 */
enum class ScreenClass {
    /** ~3.5 x 7 in phone: tall and narrow (aspect >= 1.6), short side under 4 in. */
    NarrowPhone,
    /** ~7 in diagonal, near square (aspect < 1.3): an unfolded folding phone. */
    FoldableSquare,
    /** ~6 in page-shaped (aspect 1.3-1.6) tablet or e-reader. */
    SmallPage,
    /** ~10-11 in page-shaped tablet. */
    Page10,
    /** ~13 in and larger page-shaped tablet. */
    Page13,
}

enum class PaneArrangement { SideBySide, TopBottom }

/** Default layout of a class: 1 = single pane, 2 = list + reader, 3 = folders + list + reader. */
data class PaneDefault(val screen: ScreenClass, val landscape: Boolean, val panes: Int, val arrangement: PaneArrangement)

fun classifyScreen(widthDp: Int, heightDp: Int): PaneDefault {
    val w = widthDp / 160.0
    val h = heightDp / 160.0
    val short = minOf(w, h)
    val long = maxOf(w, h)
    val landscape = w > h
    val aspect = if (short > 0) long / short else 1.0
    val diagonal = Math.sqrt(w * w + h * h)
    val screen = when {
        aspect >= 1.6 && short < 4.0 -> ScreenClass.NarrowPhone
        aspect < 1.3 && diagonal < 9.0 -> ScreenClass.FoldableSquare
        diagonal < 8.5 -> ScreenClass.SmallPage
        diagonal < 12.0 -> ScreenClass.Page10
        else -> ScreenClass.Page13
    }
    val side = PaneArrangement.SideBySide
    return when (screen) {
        ScreenClass.NarrowPhone -> PaneDefault(screen, landscape, 1, side)
        ScreenClass.FoldableSquare -> PaneDefault(screen, landscape, 2, side)
        ScreenClass.SmallPage -> PaneDefault(screen, landscape, 1, side)
        ScreenClass.Page10 -> if (landscape) PaneDefault(screen, true, 2, side)
            else PaneDefault(screen, false, 1, side)
        ScreenClass.Page13 -> if (landscape) PaneDefault(screen, true, 3, side)
            else PaneDefault(screen, false, 2, PaneArrangement.TopBottom)
    }
}
