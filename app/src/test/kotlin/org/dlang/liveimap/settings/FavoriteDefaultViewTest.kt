package org.dlang.liveimap.settings

import org.dlang.liveimap.window.StartupFavorite
import org.dlang.liveimap.window.StartupProblem
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.startupStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteDefaultViewTest {
    private val inbox = FolderFavorite(false, "INBOX", '.')
    private val work = FolderFavorite(false, "Work", '.')
    private val lists = FolderFavorite(true, "Lists", '.')

    private fun startup(list: List<FolderFavorite>) =
        startupStack("favorite", "", "a", setOf("a"), "INBOX", startupFavoriteOf(list)?.let { StartupFavorite(it.node, it.mailbox) })

    @Test fun resolutionIsTaggedThenFirstThenReported() {
        val tagged = withDefaultView(listOf(inbox, work, lists), work, true)
        assertEquals(WindowStack.drawer("a").pushMailboxIndex("Work"), startup(tagged).stack)
        assertEquals(WindowStack.drawer("a").pushMailboxIndex("INBOX"), startup(listOf(inbox, work)).stack)
        assertEquals(StartupProblem.NoFavorite, startup(emptyList()).problem)
    }

    @Test fun anInvalidTaggedFavoriteIsReportedNotSwappedForTheFirst() {
        val broken = FolderFavorite(false, "", '.')
        val plan = startup(withDefaultView(listOf(inbox, broken), broken, true))
        assertEquals(StartupProblem.FavoriteInvalid, plan.problem)
        assertEquals(WindowStack.drawer("a"), plan.stack)
    }

    @Test fun taggingMovesTheTagAndDeletingTheTaggedOneFallsToTheFirst() {
        var list = withDefaultView(listOf(inbox, work, lists), work, true)
        list = withDefaultView(list, lists, true)
        assertEquals(listOf(false, false, true), list.map { it.defaultView })
        // Only deleting the tagged one triggers the notice.
        assertEquals(false, deletesDefaultView(list, work))
        assertEquals(true, deletesDefaultView(list, lists))
        list = list.filterNot { it.mailbox == "Lists" }
        assertEquals("INBOX", startupFavoriteOf(list)?.mailbox)
        assertNull(withDefaultView(list, inbox, false).firstOrNull { it.defaultView })
    }

    @Test fun theTagSurvivesBothStoredFormsAndOldDataHasNone() {
        val list = withDefaultView(listOf(inbox, work), work, true)
        assertEquals(list, parseFavorites(encodeFavorites(list)))
        assertEquals(list, readFavorites(favoritesJson(list)))
        // Data written before the tag, in either form, reads untagged.
        assertEquals(listOf(false, false), parseFavorites("0|INBOX|.|;0|Work|.|").map { it.defaultView })
        assertEquals(listOf(false, false), readFavorites(favoritesJson(listOf(inbox, work))).map { it.defaultView })
        // Two tags (hand-edited) keep the first.
        assertEquals(listOf(true, false), parseFavorites("0|INBOX|.||d;0|Work|.||d").map { it.defaultView })
    }
}
