package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapFolderScreenModelTest {
    @Test
    fun lineOmitsAZeroUnreadCount() {
        assertEquals("Inbox 2", jmapFolderLine(screenRow("mb1", "Inbox", unread = 2)))
        assertEquals("Later", jmapFolderLine(screenRow("mb2", "Later")))
    }

    @Test
    fun depthWalksParentsAndStops() {
        val inbox = screenRow("mb1", "Inbox", unread = 2, hasChildren = true)
        val work = screenRow("c1", "Work", parentId = "mb1")
        val deep = screenRow("c2", "Deep", parentId = "c1")
        val rows = listOf(inbox, work, deep)
        assertEquals(0, jmapFolderDepth(rows, 0))
        assertEquals(1, jmapFolderDepth(rows, 1))
        assertEquals(2, jmapFolderDepth(rows, 2))
        assertEquals("Inbox 2", jmapFolderLabel(rows, 0))
        assertEquals("  Work", jmapFolderLabel(rows, 1))
        assertEquals("    Deep", jmapFolderLabel(rows, 2))
        val missingParent = listOf(screenRow("c1", "Work", parentId = "gone"))
        assertEquals(0, jmapFolderDepth(missingParent, 0))
        val cycle = listOf(
            screenRow("a", "A", parentId = "b"),
            screenRow("b", "B", parentId = "a"),
        )
        assertEquals(1, jmapFolderDepth(cycle, 0))
        assertFolderScreenFails("jmap folder index is outside the list") {
            jmapFolderDepth(rows, -1)
        }
        assertFolderScreenFails("jmap folder index is outside the list") {
            jmapFolderDepth(rows, rows.size)
        }
    }

    @Test
    fun rowsWithoutDescendantsKeepTheAncestor() {
        val inbox = screenRow("mb1", "Inbox", hasChildren = true)
        val work = screenRow("c1", "Work", parentId = "mb1", hasChildren = true)
        val deep = screenRow("c2", "Deep", parentId = "c1")
        val later = screenRow("mb2", "Later")
        val rows = listOf(inbox, work, deep, later)
        assertEquals(listOf("mb1", "mb2"), jmapRowsWithoutDescendants(rows, "mb1").map { it.id })
        assertEquals(
            listOf("mb1", "c1", "mb2"),
            jmapRowsWithoutDescendants(rows, "c1").map { it.id },
        )
        val orphan = listOf(work, later)
        assertEquals(listOf("mb2"), jmapRowsWithoutDescendants(orphan, "mb1").map { it.id })
    }

    @Test
    fun expandInsertsTheChildLevelAndCollapseRemovesIt() {
        val post = ScreenLevelPost(
            listOf(
                200 to screenTwoMailboxes,
                200 to screenChildProbe,
                200 to screenWork,
                200 to screenEmptyList,
            ),
        )
        val model = JmapFolderScreenModel(
            screenSession(),
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        model.loadTop()
        assertEquals(listOf("mb1", "mb2"), model.rows.map { it.id })
        assertEquals(listOf(null, null), model.rows.map { it.parentId })
        assertEquals("Inbox 2", jmapFolderLabel(model.rows, 0))
        assertEquals("Later", jmapFolderLabel(model.rows, 1))
        assertEquals(true, model.rows[0].hasChildren)
        assertEquals(false, model.rows[1].hasChildren)
        model.toggle("mb1")
        assertEquals(
            listOf(
                jmapMailboxLevelRequest("A1", null),
                jmapMailboxChildProbe("A1", listOf("mb1", "mb2")),
                jmapMailboxLevelRequest("A1", "mb1"),
                jmapMailboxChildProbe("A1", listOf("c1")),
            ),
            post.bodies,
        )
        assertEquals(listOf("mb1", "c1", "mb2"), model.rows.map { it.id })
        assertEquals(listOf(null, "mb1", null), model.rows.map { it.parentId })
        assertEquals("Work", model.rows[1].name)
        assertEquals("  Work", jmapFolderLabel(model.rows, 1))
        val posted = post.bodies.size
        model.toggle("mb1")
        assertEquals(listOf("mb1", "mb2"), model.rows.map { it.id })
        assertEquals(posted, post.bodies.size)
        model.toggle("mb2")
        model.toggle("missing")
        assertEquals(posted, post.bodies.size)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = ScreenLevelPost(listOf(200 to screenTwoMailboxes), ders = emptyList())
        val model = JmapFolderScreenModel(screenSession(), "user", "secret", "ab", post::post)
        assertFolderScreenFails("empty certificate chain") {
            model.loadTop()
        }
    }
}

private fun assertFolderScreenFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun screenRow(
    id: String,
    name: String,
    unread: Long = 0,
    parentId: String? = null,
    hasChildren: Boolean = false,
): JmapFolderRow {
    return JmapFolderRow(
        id = id,
        name = name,
        role = if (id == "mb1") "inbox" else null,
        total = if (id == "mb1") 3 else 0,
        unread = unread,
        hasChildren = hasChildren,
        parentId = parentId,
    )
}

private fun screenSession(): JmapSession {
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

private class ScreenLevelPost(
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

private val screenTwoMailboxes = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2},{"id":"mb2","name":"Later","parentId":null}]},"1"]]}"""

private val screenChildProbe = """{"methodResponses":[["Mailbox/get",{"list":[{"parentId":"mb1"},{"parentId":null}]},"1"]]}"""

private val screenWork = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"c1","name":"Work","parentId":"mb1"}]},"1"]]}"""

private val screenEmptyList = """{"methodResponses":[["Mailbox/get",{"list":[]},"1"]]}"""
