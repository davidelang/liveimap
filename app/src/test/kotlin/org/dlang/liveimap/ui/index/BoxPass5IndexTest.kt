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
}
