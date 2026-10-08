package org.dlang.liveimap.jmap

import org.dlang.liveimap.settings.DeletePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapDeleteTest {
    @Test
    fun destroyRequestMatches() {
        assertEquals(destroyRequest, jmapDestroyRequest("A1", "e1"))
    }

    @Test
    fun moveRequestMatches() {
        assertEquals(moveRequest, jmapMoveRequest("A1", "e1", "mb1", "t1"))
    }

    @Test
    fun quotedIds() {
        val quotedDestroy = destroyRequest.replace("\"A1\"", "\"a\\\"b\"").replace("\"e1\"", "\"e\\\"1\"")
        assertEquals(quotedDestroy, jmapDestroyRequest("a\"b", "e\"1"))
        val quotedMove = moveRequest
            .replace("\"A1\"", "\"a\\\"b\"")
            .replace("\"e1\"", "\"e\\\"1\"")
            .replace("mailboxIds/mb1", "mailboxIds/m\\\"b")
            .replace("mailboxIds/t1", "mailboxIds/t\\\"1")
        assertEquals(quotedMove, jmapMoveRequest("a\"b", "e\"1", "m\"b", "t\"1"))
    }

    @Test
    fun blankAccountIdDoesNotPost() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap account id is empty") {
                jmapDestroyRequest(account, "e1")
            }
            assertDeleteFails("jmap account id is empty") {
                jmapMoveRequest(account, "e1", "mb1", "t1")
            }
            val post = DeletePost(200, deleteDestroyed)
            assertDeleteFails("jmap account id is empty") {
                jmapDelete(
                    deleteSession(account),
                    "e1",
                    "mb1",
                    "t1",
                    DeletePolicy.DeletePermanently,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
            assertEquals(account, emptyList<String>(), post.urls)
            val model = deleteModel(post, account = account)
            assertDeleteFails("jmap account id is empty") { model.deleteMessage("e1", "mb1") }
            assertEquals(account, emptyList<String>(), post.urls)
        }
        val post = DeletePost(200, deleteDestroyed)
        assertDeleteFails("jmap account id is empty") {
            jmapDelete(
                deleteSession(null),
                "e1",
                "mb1",
                "t1",
                DeletePolicy.MoveToTrash,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun blankMessageIdDoesNotPost() {
        for (emailId in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap message id is empty") {
                jmapDestroyRequest("A1", emailId)
            }
            assertDeleteFails("jmap message id is empty") {
                jmapMoveRequest("A1", emailId, "mb1", "t1")
            }
            val post = DeletePost(200, deleteDestroyed)
            assertDeleteFails("jmap message id is empty") {
                jmapDelete(
                    deleteSession(),
                    emailId,
                    "mb1",
                    "t1",
                    DeletePolicy.DeletePermanently,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
            assertEquals(emailId, emptyList<String>(), post.urls)
            val model = deleteModel(post, DeletePolicy.MoveToTrash)
            assertDeleteFails("jmap message id is empty") { model.deleteMessage(emailId, "mb1") }
            assertEquals(emailId, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun blankMailboxIdDoesNotBuildAMove() {
        for (mailboxId in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap mailbox id is empty") {
                jmapMoveRequest("A1", "e1", mailboxId, "t1")
            }
            assertDeleteFails("jmap mailbox id is empty") {
                jmapMoveRequest("A1", "e1", "mb1", mailboxId)
            }
        }
    }

    @Test
    fun destroyedOrUpdatedIsDone() {
        assertTrue(jmapDeleteWasDone(deleteDestroyed, "e1"))
        assertTrue(jmapDeleteWasDone(deleteUpdated, "e1"))
        assertTrue(jmapDeleteWasDone(deleteUpdatedObject, "e1"))
        assertFalse(jmapDeleteWasDone(deleteNotDestroyed, "e1"))
        assertFalse(jmapDeleteWasDone(deleteNotUpdated, "e1"))
        assertFalse(jmapDeleteWasDone(deleteDestroyed, "other"))
        assertFalse(jmapDeleteWasDone(deleteUpdated, "other"))
        assertFalse(
            jmapDeleteWasDone(
                """{"methodResponses":[["Email/get",{"destroyed":["e1"]},"0"]]}""",
                "e1",
            ),
        )
        for (text in listOf("", " \n", "nope", "[]", "1", "{}", """{"destroyed":["e1"]}""")) {
            assertFalse(jmapDeleteWasDone(text, "e1"))
        }
    }

    @Test
    fun deletePermanentlyPostsDestroy() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = DeletePost(200, deleteDestroyed, ders)
        jmapDelete(
            deleteSession(),
            "e1",
            "",
            "",
            DeletePolicy.DeletePermanently,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(destroyRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
        val model = deleteModel(post, DeletePolicy.DeletePermanently)
        model.deleteMessage("e1", "")
        assertEquals(listOf(destroyRequest, destroyRequest), post.bodies)
    }

    @Test
    fun moveToTrashPostsTheMove() {
        val post = DeletePost(200, deleteUpdated)
        jmapDelete(
            deleteSession(),
            "e1",
            "mb1",
            "t1",
            DeletePolicy.MoveToTrash,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(listOf(moveRequest), post.bodies)
    }

    @Test
    fun moveToTrashSameMailboxPostsDestroy() {
        val post = DeletePost(200, deleteDestroyed)
        jmapDelete(
            deleteSession(),
            "e1",
            "mb1",
            "mb1",
            DeletePolicy.MoveToTrash,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(listOf(destroyRequest), post.bodies)
    }

    @Test
    fun blankTrashDoesNotPost() {
        val post = DeletePost(200, deleteDestroyed)
        for (trash in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap trash is not set") {
                jmapDelete(
                    deleteSession(),
                    "e1",
                    "mb1",
                    trash,
                    DeletePolicy.MoveToTrash,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
        }
        assertEquals(emptyList<String>(), post.urls)
        val model = deleteModel(post, DeletePolicy.MoveToTrash)
        assertDeleteFails("jmap trash is not set") { model.deleteMessage("e1", "mb1") }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun blankFromMailboxDoesNotPost() {
        val post = DeletePost(200, deleteUpdated)
        for (from in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap mailbox id is empty") {
                jmapDelete(
                    deleteSession(),
                    "e1",
                    from,
                    "t1",
                    DeletePolicy.MoveToTrash,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
        }
        assertDeleteFails("jmap trash is not set") {
            jmapDelete(
                deleteSession(),
                "e1",
                "",
                "",
                DeletePolicy.MoveToTrash,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun markDeletedDoesNotPost() {
        val post = DeletePost(200, deleteDestroyed)
        assertDeleteFails("jmap delete marks nothing") {
            jmapDelete(
                deleteSession(),
                "e1",
                "mb1",
                "t1",
                DeletePolicy.MarkDeleted,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(emptyList<String>(), post.urls)
        val model = deleteModel(post)
        assertDeleteFails("jmap delete marks nothing") { model.deleteMessage("e1", "mb1") }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun status401Throws() {
        val post = DeletePost(401, "no")
        assertDeleteFails("jmap api status 401") {
            jmapDelete(
                deleteSession(),
                "e1",
                "mb1",
                "t1",
                DeletePolicy.DeletePermanently,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf(destroyRequest), post.bodies)
    }

    @Test
    fun notDoneThrows() {
        val destroyed = DeletePost(200, deleteNotDestroyed)
        assertDeleteFails("jmap delete was not done") {
            jmapDelete(
                deleteSession(),
                "e1",
                "",
                "",
                DeletePolicy.DeletePermanently,
                "user",
                "secret",
                "ab",
                destroyed::post,
                destroyed::trust,
            )
        }
        assertEquals(listOf(destroyRequest), destroyed.bodies)
        val updated = DeletePost(200, deleteNotUpdated)
        assertDeleteFails("jmap delete was not done") {
            jmapDelete(
                deleteSession(),
                "e1",
                "mb1",
                "t1",
                DeletePolicy.MoveToTrash,
                "user",
                "secret",
                "ab",
                updated::post,
                updated::trust,
            )
        }
        assertEquals(listOf(moveRequest), updated.bodies)
    }

    @Test
    fun moveToTrashUsesTheLoadedTrashRole() {
        val post = DeletePost(
            listOf(
                200 to deleteMailboxes,
                200 to deleteEmptyChildren,
                200 to deleteUpdated,
            ),
        )
        val model = deleteModel(post, DeletePolicy.MoveToTrash)
        model.loadTop()
        assertEquals("t1", model.rows.first { it.role == "trash" }.id)
        val posted = post.bodies.size
        model.deleteMessage("e1", "mb1")
        assertEquals(moveRequest, post.bodies[posted])
        assertEquals(posted + 1, post.bodies.size)
    }

    @Test
    fun moveFromTheTrashMailboxPostsDestroy() {
        val post = DeletePost(
            listOf(
                200 to deleteTrashMailbox,
                200 to deleteEmptyChildren,
                200 to deleteDestroyed,
            ),
        )
        val model = deleteModel(post, DeletePolicy.MoveToTrash)
        model.loadTop()
        model.deleteMessage("e1", "mb1")
        assertEquals(destroyRequest, post.bodies.last())
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = DeletePost(200, deleteDestroyed, ders = emptyList())
        assertDeleteFails("empty certificate chain") {
            jmapDelete(
                deleteSession(),
                "e1",
                "",
                "",
                DeletePolicy.DeletePermanently,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(listOf(destroyRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
    }
}

private fun assertDeleteFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun deleteSession(account: String? = "A1"): JmapSession {
    return JmapSession(
        username = "user",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download",
        uploadUrl = "https://example.com/upload",
        eventSourceUrl = "https://example.com/event",
        state = "s",
        capabilityIds = setOf(JMAP_MAIL),
        primaryMailAccountId = account,
    )
}

private fun deleteModel(
    post: DeletePost,
    policy: DeletePolicy = DeletePolicy.MarkDeleted,
    account: String? = "A1",
): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        deleteSession(account),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
        deletePolicy = policy,
    )
}

private data class DeleteTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class DeletePost(
    private val replies: List<Pair<Int, String>>,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    constructor(code: Int, responseBody: String, ders: List<ByteArray> = listOf(byteArrayOf(1))) :
        this(listOf(code to responseBody), ders)

    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<DeleteTrust>()
    private var index = 0

    fun post(url: String, body: String, authorization: String): JmapHttpExchange {
        urls.add(url)
        bodies.add(body)
        authorizations.add(authorization)
        val reply = replies[index]
        if (index < replies.lastIndex) index += 1
        val code = reply.first
        val text = reply.second
        return object : JmapHttpExchange {
            override val status: Int = code

            override fun peerDer(): List<ByteArray> = ders

            override fun header(name: String): String? = null

            override fun body(): String {
                reads.add(url)
                return text
            }

            override fun close() = Unit
        }
    }

    fun trust(host: String, ders: List<ByteArray>, pin: String): String {
        trusted.add(DeleteTrust(host, ders, pin))
        return ""
    }
}

private val destroyRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/set",{"accountId":"A1","destroy":["e1"]},"0"]]}"""

private val moveRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/set",{"accountId":"A1","update":{"e1":{"mailboxIds/mb1":null,"mailboxIds/t1":true}}},"0"]]}"""

private val deleteDestroyed = """{"methodResponses":[["Email/set",{"destroyed":["e1"]},"0"]]}"""

private val deleteUpdated = """{"methodResponses":[["Email/set",{"updated":{"e1":null}},"0"]]}"""

private val deleteUpdatedObject = """{"methodResponses":[["Email/set",{"updated":{"e1":{}}},"0"]]}"""

private val deleteNotDestroyed = """{"methodResponses":[["Email/set",{"notDestroyed":{"e1":{"type":"notFound"}}},"0"]]}"""

private val deleteNotUpdated = """{"methodResponses":[["Email/set",{"notUpdated":{"e1":{"type":"invalidPatch"}}},"0"]]}"""

private val deleteMailboxes = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox"},{"id":"t1","name":"Trash","parentId":null,"role":"trash"}]},"1"]]}"""

private val deleteTrashMailbox = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Trash","parentId":null,"role":"trash"}]},"1"]]}"""

private val deleteEmptyChildren = """{"methodResponses":[["Mailbox/get",{"list":[]},"1"]]}"""
