package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSeenTest {
    @Test
    fun requestSetsSeen() {
        assertEquals(seenRequest, jmapSeenRequest("A1", "e1"))
    }

    @Test
    fun quotedIds() {
        val quoted = seenRequest.replace("\"A1\"", "\"a\\\"b\"").replace("\"e1\"", "\"e\\\"1\"")
        assertEquals(quoted, jmapSeenRequest("a\"b", "e\"1"))
    }

    @Test
    fun blankAccountIdDoesNotPost() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertSeenFails("jmap account id is empty") {
                jmapSeenRequest(account, "e1")
            }
            val post = SeenMarkPost(200, seenUpdated)
            assertSeenFails("jmap account id is empty") {
                jmapMarkSeen(seenSession(account), "e1", "user", "secret", "ab", post::post)
            }
            assertEquals(account, emptyList<String>(), post.urls)
            val model = seenModel(post, account)
            assertSeenFails("jmap account id is empty") { model.markSeen("e1") }
            assertEquals(account, emptyList<String>(), post.urls)
        }
        val post = SeenMarkPost(200, seenUpdated)
        assertSeenFails("jmap account id is empty") {
            jmapMarkSeen(seenSession(null), "e1", "user", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
        val model = seenModel(post, null)
        assertSeenFails("jmap account id is empty") { model.markSeen("e1") }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun blankMessageIdDoesNotPost() {
        for (emailId in listOf("", " ", "\t", "\n")) {
            assertSeenFails("jmap message id is empty") {
                jmapSeenRequest("A1", emailId)
            }
            val post = SeenMarkPost(200, seenUpdated)
            assertSeenFails("jmap message id is empty") {
                jmapMarkSeen(seenSession(), emailId, "user", "secret", "ab", post::post)
            }
            assertEquals(emailId, emptyList<String>(), post.urls)
            val model = seenModel(post)
            assertSeenFails("jmap message id is empty") { model.markSeen(emailId) }
            assertEquals(emailId, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun updatedWithTheIdIsSet() {
        assertTrue(jmapSeenWasSet(seenUpdated, "e1"))
        assertTrue(jmapSeenWasSet(seenUpdatedObject, "e1"))
        assertFalse(jmapSeenWasSet(seenNotUpdated, "e1"))
        assertFalse(jmapSeenWasSet(seenUpdated, "other"))
        assertFalse(jmapSeenWasSet("""{"methodResponses":[["Email/get",{"updated":{"e1":null}},"0"]]}""", "e1"))
        for (text in listOf("", " \n", "nope", "[]", "1", "{}", """{"updated":{"e1":null}}""")) {
            assertFalse(jmapSeenWasSet(text, "e1"))
        }
    }

    @Test
    fun markSeenPostsTheRequest() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = SeenMarkPost(200, seenUpdated, ders)
        jmapMarkSeen(seenSession(), "e1", "user", "secret", "ab", post::post, post::trust)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(seenRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
        val model = seenModel(post)
        model.markSeen("e1")
        assertEquals(listOf(seenRequest, seenRequest), post.bodies)
    }

    @Test
    fun status401Throws() {
        val post = SeenMarkPost(401, "no")
        assertSeenFails("jmap api status 401") {
            jmapMarkSeen(seenSession(), "e1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf(seenRequest), post.bodies)
        val model = seenModel(post)
        assertSeenFails("jmap api status 401") { model.markSeen("e1") }
        assertEquals(listOf(seenRequest, seenRequest), post.bodies)
    }

    @Test
    fun notUpdatedThrows() {
        val post = SeenMarkPost(200, seenNotUpdated)
        assertSeenFails("jmap seen was not set") {
            jmapMarkSeen(seenSession(), "e1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf(seenRequest), post.bodies)
        val model = seenModel(post)
        assertSeenFails("jmap seen was not set") { model.markSeen("e1") }
        assertEquals(listOf(seenRequest, seenRequest), post.bodies)
    }

    @Test
    fun markSeenOnOpenFalseDoesNotPost() {
        val post = SeenMarkPost(200, seenUpdated)
        val model = seenModel(post, markSeenOnOpen = false)
        model.markSeen("e1")
        model.markSeen("")
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = SeenMarkPost(200, seenUpdated, ders = emptyList())
        assertSeenFails("empty certificate chain") {
            jmapMarkSeen(seenSession(), "e1", "user", "secret", "ab", post::post)
        }
        assertEquals(listOf(seenRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
    }
}

private fun assertSeenFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun seenSession(account: String? = "A1"): JmapSession {
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

private fun seenModel(
    post: SeenMarkPost,
    account: String? = "A1",
    markSeenOnOpen: Boolean = true,
): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        seenSession(account),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
        markSeenOnOpen,
    )
}

private data class SeenMarkTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class SeenMarkPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<SeenMarkTrust>()

    fun post(url: String, body: String, authorization: String): JmapHttpExchange {
        urls.add(url)
        bodies.add(body)
        authorizations.add(authorization)
        return object : JmapHttpExchange {
            override val status: Int = code

            override fun peerDer(): List<ByteArray> = ders

            override fun header(name: String): String? = null

            override fun body(): String {
                reads.add(url)
                return responseBody
            }

            override fun close() = Unit
        }
    }

    fun trust(host: String, ders: List<ByteArray>, pin: String): String {
        trusted.add(SeenMarkTrust(host, ders, pin))
        return ""
    }
}

private val seenRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/set",{"accountId":"A1","update":{"e1":{"keywords/${'$'}seen":true}}},"0"]]}"""

private val seenUpdated = """{"methodResponses":[["Email/set",{"updated":{"e1":null}},"0"]]}"""

private val seenUpdatedObject = """{"methodResponses":[["Email/set",{"updated":{"e1":{}}},"0"]]}"""

private val seenNotUpdated = """{"methodResponses":[["Email/set",{"notUpdated":{"e1":{"type":"invalidPatch"}}},"0"]]}"""
