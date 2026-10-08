package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JmapCallTest {
    @Test
    fun basicAuthorization() {
        assertEquals("Basic dXNlcjpzZWNyZXQ=", jmapBasicAuthorization("user", "secret"))
        assertEquals("Basic dXNlcjo=", jmapBasicAuthorization("user", ""))
        for (username in listOf("", " ", "\t", "\n")) {
            assertFails("jmap username is empty") { jmapBasicAuthorization(username, "secret") }
        }
    }

    @Test
    fun postsOnceAndReturnsTheBody() {
        val url = "https://example.com/jmap/"
        val ders = listOf(byteArrayOf(9, 8), byteArrayOf(7))
        val post = CallPost(200, """{"ok":true}""", ders)
        val result = jmapCall(url, """{"a":1}""", "Basic dXNlcjpzZWNyZXQ=", "ab", post::post, post::trust)
        assertEquals(200, result.status)
        assertEquals("""{"ok":true}""", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals(listOf(url), post.urls)
        assertEquals(listOf("""{"a":1}"""), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf(url), post.reads)
        assertEquals(post.urls, post.closes)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun blankApiUrlDoesNotPost() {
        for (url in listOf("", " ", "\t", "\n")) {
            val post = CallPost(200, "secret")
            assertFails("jmap api url is empty") {
                jmapCall(url, "{}", "Basic x", "ab", post::post, post::trust)
            }
            assertEquals(url, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun nonHttpsDoesNotPost() {
        for (url in listOf("http://example.com/jmap", "HTTP://example.com/jmap", "ftp://example.com/jmap")) {
            val post = CallPost(200, "secret")
            assertFails("jmap api url is not https") {
                jmapCall(url, "{}", "Basic x", "ab", post::post)
            }
            assertEquals(url, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun uppercaseHttpsPosts() {
        val url = "HTTPS://example.com/jmap"
        val post = CallPost(200, "ok")
        val result = jmapCall(url, "{}", "Basic x", "ab", post::post, post::trust)
        assertEquals(200, result.status)
        assertEquals("example.com", post.trusted.single().host)
        assertEquals(listOf(url), post.urls)
    }

    @Test
    fun nullHostDoesNotPost() {
        val post = CallPost(200, "secret")
        assertFails("jmap api url is not https") {
            jmapCall("https:///jmap", "{}", "Basic x", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun ipv6DropsOneBracketPair() {
        val url = "https://[2001:db8::1]/jmap"
        val ders = listOf(byteArrayOf(4))
        val post = CallPost(200, "v6", ders)
        val result = jmapCall(url, "{}", "Basic x", "ab", post::post, post::trust)
        assertEquals("v6", result.body)
        assertEquals("2001:db8::1", post.trusted.single().host)
        assertSame(ders, post.trusted.single().ders)
        assertEquals("ab", post.trusted.single().pin)
    }

    @Test
    fun trustFailureDoesNotReadBody() {
        val url = "https://example.com/jmap/"
        val post = CallPost(200, "secret")
        assertFails("certificate changed") {
            jmapCall(url, "{}", "Basic x", "ab", post::post) { _, _, _ -> "certificate changed" }
        }
        assertEquals(listOf(url), post.urls)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val url = "https://example.com/jmap/"
        val post = CallPost(200, "secret", ders = emptyList())
        assertFails("empty certificate chain") {
            jmapCall(url, "{}", "Basic x", "ab", post::post)
        }
        assertEquals(listOf(url), post.urls)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
    }

    @Test
    fun redirectDoesNotReadBodyOrPostAgain() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val url = "https://example.com/jmap/"
            val post = CallPost(code, "secret")
            assertFails("jmap api redirect") {
                jmapCall(url, "{}", "Basic x", "ab", post::post, post::trust)
            }
            assertEquals("$code", listOf(url), post.urls)
            assertEquals("$code", emptyList<String>(), post.reads)
            assertEquals(post.urls, post.closes)
        }
    }

    @Test
    fun otherStatusReturnsTheBody() {
        val url = "https://example.com/jmap/"
        val post = CallPost(401, "no")
        val result = jmapCall(url, "{}", "Basic x", "ab", post::post, post::trust)
        assertEquals(401, result.status)
        assertEquals("no", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals(listOf(url), post.reads)
    }

    @Test
    fun levelPostsTheTopRequestAndReturnsInbox() {
        val session = sampleSession()
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = CallPost(200, sampleInbox, ders)
        val mailboxes = jmapMailboxLevel(session, null, "user", "secret", "ab", post::post, post::trust)
        assertEquals(listOf("mb1"), mailboxes.map { it.id })
        assertEquals("Inbox", mailboxes.single().name)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(jmapMailboxLevelRequest("A1", null)), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun childLevelPostsTheParent() {
        val post = CallPost(200, sampleInbox)
        val mailboxes = jmapMailboxLevel(
            sampleSession(),
            "mb1",
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("mb1", mailboxes.single().id)
        assertEquals(listOf(jmapMailboxLevelRequest("A1", "mb1")), post.bodies)
    }

    @Test
    fun blankAccountDoesNotPost() {
        for (account in listOf(null, "", " ", "\t", "\n")) {
            val post = CallPost(200, sampleInbox)
            assertFails("jmap account id is empty") {
                jmapMailboxLevel(sampleSession(account = account), null, "user", "secret", "ab", post::post)
            }
            assertEquals("$account", emptyList<String>(), post.urls)
        }
    }

    @Test
    fun blankUsernameDoesNotPost() {
        val post = CallPost(200, sampleInbox)
        assertFails("jmap username is empty") {
            jmapMailboxLevel(sampleSession(), null, " ", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun levelStatusThrows() {
        val post = CallPost(401, "no")
        assertFails("jmap api status 401") {
            jmapMailboxLevel(sampleSession(), null, "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
    }

    @Test
    fun childrenReturnParentIds() {
        val session = sampleSession()
        val post = CallPost(200, sampleChildren)
        val parents = jmapMailboxChildren(
            session,
            listOf("mb1", "mb2"),
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(setOf("mb1"), parents)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(jmapMailboxChildProbe("A1", listOf("mb1", "mb2"))), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
    }

    @Test
    fun absentParentIdIsSkipped() {
        val body = """{"methodResponses":[["Mailbox/get",{"list":[{"parentId":"mb1"},{},{"parentId":null}]},"1"]]}"""
        val post = CallPost(200, body)
        val parents = jmapMailboxChildren(
            sampleSession(),
            listOf("mb1"),
            "user",
            "",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(setOf("mb1"), parents)
        assertEquals(listOf("Basic dXNlcjo="), post.authorizations)
    }

    @Test
    fun emptyMailboxIdsDoNotPost() {
        val post = CallPost(200, sampleChildren)
        assertFails("jmap mailbox ids are empty") {
            jmapMailboxChildren(sampleSession(), emptyList(), "user", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun childrenStatusThrows() {
        val post = CallPost(401, "no")
        assertFails("jmap api status 401") {
            jmapMailboxChildren(
                sampleSession(),
                listOf("mb1"),
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(1, post.urls.size)
    }
}

private fun assertFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun sampleSession(
    apiUrl: String = "https://example.com/jmap/",
    account: String? = "A1",
): JmapSession {
    return JmapSession(
        username = "user",
        apiUrl = apiUrl,
        downloadUrl = "https://example.com/download",
        uploadUrl = "https://example.com/upload",
        eventSourceUrl = "https://example.com/event",
        state = "s",
        capabilityIds = setOf(JMAP_MAIL),
        primaryMailAccountId = account,
    )
}

private data class CallSeen(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class CallPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val closes = mutableListOf<String>()
    val trusted = mutableListOf<CallSeen>()

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

            override fun close() {
                closes.add(url)
            }
        }
    }

    fun trust(host: String, ders: List<ByteArray>, pin: String): String {
        trusted.add(CallSeen(host, ders, pin))
        return ""
    }
}

private val sampleInbox = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2}]},"1"]]}"""

private val sampleChildren = """{"methodResponses":[["Mailbox/get",{"list":[{"parentId":"mb1"},{"parentId":null},{"parentId":"mb1"}]},"1"]]}"""
