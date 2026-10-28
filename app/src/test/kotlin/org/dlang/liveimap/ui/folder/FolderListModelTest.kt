package org.dlang.liveimap.ui.folder

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FolderListModelTest {



    @Test
    fun searchKeepsEmptyPrefixAndExcludesMisses() {
        val empty = FolderRow("", "ignored", true, 0, null, false, namespaceRoot = true)
        val archive = FolderRow("Archive", "Archive", false, 0, null, false)
        val inbox = FolderRow("INBOX", "INBOX", false, 0, null, false)
        assertEquals("(empty prefix)", folderDisplayName(empty, "(empty prefix)"))
        assertEquals(listOf(""), folderRowsMatchingName(listOf(empty, archive, inbox), "zzz", "(empty prefix)").map { it.mailbox })
        assertEquals(listOf(""), folderRowsMatchingName(listOf(empty, archive), "ignored", "(empty prefix)").map { it.mailbox })
        assertEquals(listOf("", "Archive"), folderRowsMatchingName(listOf(empty, archive, inbox), "arch", "(empty prefix)").map { it.mailbox })
    }


    @Test
    fun nextUnseenFolderSkipsEmptyCountsAndWrapsOnce() {
        val ordered = listOf(
            "INBOX" to 0,
            "Archive" to 2,
            "Lists" to 0,
            "Later" to 1,
        )
        assertEquals("Archive", nextUnseenFolder("INBOX", ordered))
        assertEquals("Later", nextUnseenFolder("Archive", ordered))
        assertEquals("Archive", nextUnseenFolder("Later", ordered))
        assertEquals("Archive", nextUnseenFolder("not-in-the-list", ordered))
        assertEquals(
            null,
            nextUnseenFolder("Later", listOf("INBOX" to 0, "Archive" to 0, "Lists" to 0, "Later" to 1)),
        )
        val withNull = listOf(
            "INBOX" to null,
            "Archive" to 2,
            "Lists" to null,
            "Later" to 1,
        )
        assertEquals("Archive", nextUnseenFolder("INBOX", withNull))
        assertEquals("Later", nextUnseenFolder("Archive", withNull))
        assertEquals("Archive", nextUnseenFolder("Later", withNull))
        assertEquals(null, nextUnseenFolder("Later", listOf("INBOX" to null, "Later" to 1)))
    }






}

