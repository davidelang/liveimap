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
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FolderTreeTest {






    /** #22 (sun): 1323 of 1327 STATUS re-asked counts LIST-STATUS had already returned. */
    @Test
    fun listStatusCountsAreNotAskedAgain() {
        val listed = FolderRow("INBOX.a", "a", false, 1, null, false, messages = 5, delimiter = '.')
        val uncounted = FolderRow("INBOX.b", "b", false, 1, null, false, delimiter = '.')
        val root = FolderRow("user.", "user.", true, 0, null, false, namespaceRoot = true, delimiter = '.')
        val rows = listOf(listed, uncounted, root)
        assertEquals(listOf(uncounted), statusDue(rows, listStatus = true, slower = false, countedAt = emptyMap(), now = 1L))
        // asked once: not again while fresh, again after CountFreshMillis
        assertEquals(emptyList<FolderRow>(), statusDue(rows, true, false, mapOf("INBOX.b" to 1L), 2L))
        assertEquals(listOf(uncounted), statusDue(rows, true, false, mapOf("INBOX.b" to 1L), 1L + CountFreshMillis))
        // the explicit "STATUS for visible folders" fallback still asks every visible row
        assertEquals(listOf(listed, uncounted), statusDue(rows, true, true, emptyMap(), 1L))
        assertEquals(listOf(listed, uncounted), statusDue(rows, false, true, emptyMap(), 1L))
        // no LIST-STATUS and no fallback: nothing
        assertEquals(emptyList<FolderRow>(), statusDue(rows, false, false, emptyMap(), 1L))
    }

    @Test
    fun folderIconKeyPrefersSettingsThenSpecialUseThenInbox() {
        assertEquals("inbox", folderIconKey("INBOX", "", "", "", "", null))
        assertEquals("send", folderIconKey("INBOX", "INBOX", "", "", "", null))
        assertEquals("send", folderIconKey("INBOX.sent-mail", "INBOX.sent-mail", "", "", "", null))
        assertEquals("folder", folderIconKey("", "", "", "", "", null))
        assertEquals("drafts", folderIconKey("INBOX.Drafts", "", "INBOX.Drafts", "", "", null))
        assertEquals("report", folderIconKey("INBOX.Junk", "", "", "INBOX.Junk", "", null))
        assertEquals("contacts", folderIconKey("INBOX.contacts", "", "", "", "INBOX.contacts", null))
        assertEquals("folder", folderIconKey("Sent", "", "", "", "", null))
        assertEquals("folder", folderIconKey("Inbox", "", "", "", "", null))
        assertEquals("delete", folderIconKey("INBOX.Trash", "", "", "", "", "\\Trash"))
        assertEquals("send", folderIconKey("INBOX.sent-mail", "", "", "", "", "\\Sent"))
        assertEquals("drafts", folderIconKey("INBOX.Drafts", "", "", "", "", "\\Drafts"))
        assertEquals("report", folderIconKey("INBOX.Junk", "", "", "", "", "\\Junk"))
        assertEquals("folder", folderIconKey("Archive", "", "", "", "", "\\Archive"))
        assertEquals("drafts", folderIconKey("INBOX.sent-mail", "", "INBOX.sent-mail", "", "", "\\Sent"))
        assertEquals("send", folderIconKey("box", "", "", "", "", "\\Junk \\Sent"))
    }

    @Test
    fun folderRowDescriptionIncludesCountsAndExpansion() {
        assertEquals(
            "kernel, 97625 messages, 97608 unread, collapsed",
            folderRowDescription("kernel", "97625 messages", "97608 unread", "collapsed"),
        )
        assertEquals("INBOX", folderRowDescription("INBOX", null, null, null))
        assertEquals(
            "(empty prefix), expanded",
            folderRowDescription("(empty prefix)", null, null, "expanded"),
        )
    }
}

