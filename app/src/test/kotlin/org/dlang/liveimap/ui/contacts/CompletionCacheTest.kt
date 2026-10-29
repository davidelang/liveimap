package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F62: completion data is cached per (account, mailbox), failures are not cached, writes invalidate. */
class CompletionCacheTest {
    @Test fun keyedAndInvalidated() {
        assertFalse(pineBookIsLoaded("a", "addressbook"))
        forgetCompletionCaches()
    }

    @Test fun permissionNeededIsNotAnEmptyBook() {
        forgetCompletionCaches()
        var setCalls = 0
        val denied = loadAndroidContactSets {
            setCalls++
            throw SecurityException("denied")
        }
        assertTrue(denied is AndroidContacts.PermissionNeeded)
        val deniedAgain = loadAndroidContactSets {
            setCalls++
            throw SecurityException("denied")
        }
        assertTrue(deniedAgain is AndroidContacts.PermissionNeeded)
        assertEquals(2, setCalls)
        var emptyCalls = 0
        val empty = loadAndroidContactSets {
            emptyCalls++
            emptyList()
        }
        val ready = empty as AndroidContacts.Ready
        assertEquals(emptyList<AndroidContactSet>(), ready.value)
        val cached = loadAndroidContactSets {
            emptyCalls++
            error("cached empty book")
        }
        assertEquals(1, emptyCalls)
        assertEquals(emptyList<AndroidContactSet>(), (cached as AndroidContacts.Ready).value)
        forgetCompletionCaches()
        var entryCalls = 0
        val deniedEntries = loadAndroidEntries {
            entryCalls++
            throw SecurityException("denied")
        }
        assertTrue(deniedEntries is AndroidContacts.PermissionNeeded)
        val emptyEntries = loadAndroidEntries {
            entryCalls++
            emptyMap()
        }
        assertTrue((emptyEntries as AndroidContacts.Ready).value.isEmpty())
        val cachedEntries = loadAndroidEntries {
            entryCalls++
            error("cached empty book")
        }
        assertEquals(2, entryCalls)
        assertTrue((cachedEntries as AndroidContacts.Ready).value.isEmpty())
        forgetCompletionCaches()
    }
}
