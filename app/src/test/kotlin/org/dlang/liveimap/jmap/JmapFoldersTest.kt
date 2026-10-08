package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapFoldersTest {
    @Test
    fun rowsKeepOrderAndSkipIdsThatAreNotMailboxes() {
        val later = JmapMailbox("mb2", "Later", null, null, 4, 0, 0)
        val inbox = JmapMailbox("mb1", "Inbox", null, "inbox", 1, 3, 2)
        val rows = jmapFolderRows(listOf(later, inbox), setOf("mb1", "other"))
        assertEquals(listOf("mb2", "mb1"), rows.map { it.id })
        assertEquals("Later", rows[0].name)
        assertEquals(null, rows[0].role)
        assertEquals(0L, rows[0].total)
        assertEquals(0L, rows[0].unread)
        assertEquals(false, rows[0].hasChildren)
        assertEquals("Inbox", rows[1].name)
        assertEquals("inbox", rows[1].role)
        assertEquals(3L, rows[1].total)
        assertEquals(2L, rows[1].unread)
        assertEquals(true, rows[1].hasChildren)
        assertEquals(emptyList<JmapFolderRow>(), jmapFolderRows(emptyList(), setOf("mb1")))
    }

    @Test
    fun twoMailboxLevelReturnsInboxThenLater() {
        val post = FolderLevelPost(listOf(200 to twoMailboxes, 200 to childProbe))
        val rows = jmapFolderLevel(folderSession(), null, "user", "secret", "ab", post::post, post::trust)
        assertEquals(2, rows.size)
        assertEquals("mb1", rows[0].id)
        assertEquals("Inbox", rows[0].name)
        assertEquals("inbox", rows[0].role)
        assertEquals(3L, rows[0].total)
        assertEquals(2L, rows[0].unread)
        assertEquals(true, rows[0].hasChildren)
        assertEquals("mb2", rows[1].id)
        assertEquals("Later", rows[1].name)
        assertEquals(null, rows[1].role)
        assertEquals(0L, rows[1].total)
        assertEquals(0L, rows[1].unread)
        assertEquals(false, rows[1].hasChildren)
        assertEquals(
            listOf(
                jmapMailboxLevelRequest("A1", null),
                jmapMailboxChildProbe("A1", listOf("mb1", "mb2")),
            ),
            post.bodies,
        )
    }

    @Test
    fun parentPostsLevelRequestThenChildProbe() {
        val post = FolderLevelPost(listOf(200 to oneMailbox, 200 to childProbe))
        val rows = jmapFolderLevel(folderSession(), "mb1", "user", "secret", "ab", post::post, post::trust)
        assertEquals("mb1", rows.single().id)
        assertEquals(true, rows.single().hasChildren)
        assertEquals(
            listOf(
                jmapMailboxLevelRequest("A1", "mb1"),
                jmapMailboxChildProbe("A1", listOf("mb1")),
            ),
            post.bodies,
        )
    }

    @Test
    fun emptyLevelPostsOnceAndReturnsNoRows() {
        val post = FolderLevelPost(listOf(200 to emptyListBody, 200 to childProbe))
        val rows = jmapFolderLevel(folderSession(), null, "user", "secret", "ab", post::post, post::trust)
        assertEquals(emptyList<JmapFolderRow>(), rows)
        assertEquals(listOf(jmapMailboxLevelRequest("A1", null)), post.bodies)
    }

    @Test
    fun status401Throws() {
        val post = FolderLevelPost(listOf(401 to "no", 200 to childProbe))
        assertFolderFails("jmap api status 401") {
            jmapFolderLevel(folderSession(), "mb1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf(jmapMailboxLevelRequest("A1", "mb1")), post.bodies)
    }

    @Test
    fun childProbeFailurePropagates() {
        val post = FolderLevelPost(listOf(200 to oneMailbox, 401 to "no"))
        assertFolderFails("jmap api status 401") {
            jmapFolderLevel(folderSession(), "mb1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(2, post.bodies.size)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = FolderLevelPost(listOf(200 to twoMailboxes), ders = emptyList())
        assertFolderFails("empty certificate chain") {
            jmapFolderLevel(folderSession(), null, "user", "secret", "ab", post::post)
        }
        assertEquals(1, post.bodies.size)
    }
}

private fun assertFolderFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun folderSession(): JmapSession {
    return JmapSession(
        username = "user",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download",
        uploadUrl = "https://example.com/upload",
        eventSourceUrl = "https://example.com/event",
        state = "s",
        capabilityIds = setOf(JMAP_MAIL),
        primaryMailAccountId = "A1",
    )
}

private class FolderLevelPost(
    private val replies: List<Pair<Int, String>>,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val bodies = mutableListOf<String>()
    private var index = 0

    fun post(url: String, body: String, authorization: String): JmapHttpExchange {
        bodies.add(body)
        val reply = replies[index]
        index += 1
        val code = reply.first
        val text = reply.second
        return object : JmapHttpExchange {
            override val status: Int = code

            override fun peerDer(): List<ByteArray> = ders

            override fun header(name: String): String? = null

            override fun body(): String = text

            override fun close() = Unit
        }
    }

    fun trust(host: String, peerCertificates: List<ByteArray>, pin: String): String = ""
}

private val twoMailboxes = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2},{"id":"mb2","name":"Later","parentId":null}]},"1"]]}"""

private val oneMailbox = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2}]},"1"]]}"""

private val childProbe = """{"methodResponses":[["Mailbox/get",{"list":[{"parentId":"mb1"},{"parentId":null}]},"1"]]}"""

private val emptyListBody = """{"methodResponses":[["Mailbox/get",{"list":[]},"1"]]}"""
