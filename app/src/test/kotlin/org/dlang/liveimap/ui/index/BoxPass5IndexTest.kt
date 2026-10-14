package org.dlang.liveimap.ui.index

import org.dlang.liveimap.settings.DeletePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxPass5IndexTest {
    /** F142: a search's select may not re-date the home folder's listed rows. */
    @Test fun searchDoesNotMaskAHomeEpochChange() {
        assertTrue(searchEpochMasks(homeFolder = true, listed = 7L, selected = 8L))
        assertFalse(searchEpochMasks(homeFolder = true, listed = 7L, selected = 7L))
        assertFalse(searchEpochMasks(homeFolder = true, listed = null, selected = 8L))
        assertFalse(searchEpochMasks(homeFolder = false, listed = 7L, selected = 8L))
        assertFalse(searchEpochMasks(homeFolder = true, listed = 7L, selected = 0L))
    }

    /** F90: a folder only the server flags \Trash never escalates to permanent deletion. */
    @Test fun serverFlaggedTrashKeepsTheUsersPolicy() {
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.MarkDeleted, inTrash = true, uidPlus = true, trashKnown = true, trashNamedByUser = false),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.MoveToTrash, inTrash = true, uidPlus = true, trashKnown = true, trashNamedByUser = false),
        )
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = true, uidPlus = true, trashKnown = true, trashNamedByUser = false),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = true, uidPlus = false, trashKnown = true, trashNamedByUser = false),
        )
        // A Trash the user named keeps the old rule.
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.MarkDeleted, inTrash = true, uidPlus = true, trashKnown = true, trashNamedByUser = true),
        )
    }

    /** Home multi-select and a cross-folder hit pass trashNamedByUser; a Trash confirm does not expunge the home rows. */
    @Test fun trashConfirmDoesNotExpungeTheHomeSelection() {
        assertEquals(
            DeletePolicy.MarkDeleted,
            policyForListedDelete(
                DeletePolicy.MarkDeleted,
                folder = "INBOX",
                home = "INBOX",
                trash = "Trash",
                uidPlus = true,
                trashNamedByUser = true,
                permanentOnlyInTrash = true,
            ),
        )
        assertEquals(
            DeletePolicy.DeletePermanently,
            policyForListedDelete(
                DeletePolicy.MarkDeleted,
                folder = "Trash",
                home = "INBOX",
                trash = "Trash",
                uidPlus = true,
                trashNamedByUser = true,
                permanentOnlyInTrash = true,
            ),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            policyForListedDelete(
                DeletePolicy.MarkDeleted,
                folder = "Trash",
                home = "INBOX",
                trash = "Trash",
                uidPlus = true,
                trashNamedByUser = false,
                permanentOnlyInTrash = false,
            ),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            policyForListedDelete(
                DeletePolicy.MarkDeleted,
                folder = "inbox",
                home = "INBOX",
                trash = "Trash",
                uidPlus = true,
                trashNamedByUser = false,
                permanentOnlyInTrash = true,
            ),
        )
    }

    /** A removed hit drops the same position from order and orderMailboxes; page fetches use that position. */
    @Test fun removedHitLeavesOrderAndItsMailboxTogether() {
        val home = dropOrderedHits(
            listOf(7L, 7L, 8L),
            listOf("INBOX", "Sent", "Sent"),
            setOf(7L),
            actedFolder = null,
            indexMailbox = "INBOX",
        )
        assertEquals(listOf(7L, 8L), home.first)
        assertEquals(listOf("Sent", "Sent"), home.second)
        assertEquals(home.first.size, home.second.size)

        val sent = dropOrderedHits(
            listOf(7L, 7L, 8L),
            listOf("INBOX", "Sent", "Sent"),
            setOf(7L),
            actedFolder = "Sent",
            indexMailbox = "INBOX",
        )
        assertEquals(listOf(7L, 8L), sent.first)
        assertEquals(listOf("INBOX", "Sent"), sent.second)
        assertEquals(
            listOf("INBOX" to listOf(7L), "Sent" to listOf(8L)),
            pageMailboxGroups(sent.first, sent.second, 0, sent.first.size),
        )

        val folded = dropOrderedHits(
            listOf(1L),
            listOf("INBOX"),
            setOf(1L),
            actedFolder = "inbox",
            indexMailbox = "INBOX",
        )
        assertEquals(emptyList<Long>(), folded.first)
        assertEquals(emptyList<String>(), folded.second)

        val plain = dropOrderedHits(
            listOf(1L, 2L, 3L),
            emptyList(),
            setOf(2L),
            actedFolder = null,
            indexMailbox = "INBOX",
        )
        assertEquals(listOf(1L, 3L), plain.first)
        assertTrue(plain.second.isEmpty())

        val foreign = dropOrderedHits(
            listOf(1L, 2L, 3L),
            emptyList(),
            setOf(2L),
            actedFolder = "Sent",
            indexMailbox = "INBOX",
        )
        assertEquals(listOf(1L, 2L, 3L), foreign.first)
        assertTrue(foreign.second.isEmpty())
    }
}
