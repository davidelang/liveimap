package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneLayoutTest {
    private fun plan(w: Int, h: Int) = planLayout(w, h, 1f)

    @Test fun roundTrip() {
        val text = "H0.25(folders,V0.5(list,message))"
        val node = decodePaneLayout(text)!!
        assertEquals(text, node.encode())
        assertEquals(3, node.panelCount())
        assertNull(decodePaneLayout("H0.5(list"))
        assertNull(decodePaneLayout("H1.5(list,message)"))
        assertNull(decodePaneLayout(""))
    }

    @Test fun rules() {
        assertEquals(PaneLayoutProblem.NoMessageList, paneLayoutProblem(decodePaneLayout("H0.5(folders,message)")!!))
        assertEquals(PaneLayoutProblem.Duplicate, paneLayoutProblem(decodePaneLayout("H0.5(list,list)")!!))
        assertEquals(PaneLayoutProblem.MessageNotBesideList,
            paneLayoutProblem(decodePaneLayout("H0.5(V0.5(list,folders),V0.5(drawer,message))")!!))
        assertNull(paneLayoutProblem(decodePaneLayout("H0.5(V0.35(folders,list),message)")!!))
        assertNull(paneLayoutProblem(decodePaneLayout("V0.4(message,list)")!!))
    }

    @Test fun autoMatchesThePlanner() {
        // phone: one pane
        assertEquals("list", autoPaneLayout(plan(400, 800)).encode())
        val wide = plan(2560, 1600)
        val tree = autoPaneLayout(wide)
        assertEquals(wide.panes, tree.panelCount())
        if (wide.folders == FolderPlacement.Beside) {
            assertEquals(PaneContent.Folders, tree.leaves().first())
            // folders shown: the drawer stays hidden even when a drawer panel is asked for
            assertEquals(tree, autoPaneLayout(wide, drawerPane = true))
        }
        assertNull(paneLayoutProblem(tree))
    }

    @Test fun editing() {
        var t: PaneNode = PaneNode.Leaf(PaneContent.MessageList)
        t = t.splitAt(emptyList(), SplitAxis.Horizontal, PaneContent.Message)
        t = t.splitAt(listOf(0), SplitAxis.Vertical, PaneContent.Folders)
        assertEquals("H0.5(V0.5(list,folders),message)", t.encode())
        t = t.setContent(listOf(0, 1), PaneContent.AccountDrawer)
        assertEquals(listOf(PaneContent.MessageList, PaneContent.AccountDrawer, PaneContent.Message), t.leaves())
        t = t.removeAt(listOf(0, 1)).withRatio(emptyList(), 0.3f)
        assertEquals("H0.3(list,message)", t.encode())
    }
}

class DrawerPlanTest {
    @Test fun drawerPaneOnlyWhenRoomy() {
        // Boox portrait ~1100x1467 dp: at 1.0 the folder column fits but not a drawer pane too
        val wide = planLayout(2560, 1600, 1f)
        assertEquals(true, wide.drawerPane || wide.folders == FolderPlacement.Beside)
        assertEquals(false, planLayout(1100, 1467, 1.3f).drawerPane)
        assertEquals(false, planLayout(400, 800, 1f).drawerPane)
        assertEquals(true, planLayout(1400, 900, 1f).let { it.drawerPane == (it.columns >= 40 + 2 + 60 + 2 + 40 + (if (it.folders == FolderPlacement.Beside) 26 else 0)) })
    }
}
