package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class JmapMessagesTest {
    @Test
    fun windowRequest() {
        assertEquals(windowRequest, jmapMessageWindowRequest("A1", "mb1", 0, 60))
    }

    @Test
    fun quotedAccountId() {
        val quoted = windowRequest.replace("\"A1\"", "\"a\\\"b\"")
        assertEquals(quoted, jmapMessageWindowRequest("a\"b", "mb1", 0, 60))
    }

    @Test
    fun blankAccountIdDoesNotPost() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapMessageWindowRequest(account, "mb1", 0, 60)
            }
            val post = MessageWindowPost(200, samplePage)
            assertFails("jmap account id is empty") {
                jmapMessageWindow(messageSession(account), "mb1", 0, 60, "user", "secret", "ab", post::post)
            }
            assertEquals(account, emptyList<String>(), post.urls)
        }
        val post = MessageWindowPost(200, samplePage)
        assertFails("jmap account id is empty") {
            jmapMessageWindow(messageSession(null), "mb1", 0, 60, "user", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun blankMailboxIdDoesNotPost() {
        for (mailbox in listOf("", " ", "\t", "\n")) {
            assertFails("jmap mailbox id is empty") {
                jmapMessageWindowRequest("A1", mailbox, 0, 60)
            }
            val post = MessageWindowPost(200, samplePage)
            assertFails("jmap mailbox id is empty") {
                jmapMessageWindow(messageSession(), mailbox, 0, 60, "user", "secret", "ab", post::post)
            }
            assertEquals(mailbox, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun invalidPositionDoesNotPost() {
        assertFails("jmap message position is invalid") {
            jmapMessageWindowRequest("A1", "mb1", -1, 60)
        }
        val post = MessageWindowPost(200, samplePage)
        assertFails("jmap message position is invalid") {
            jmapMessageWindow(messageSession(), "mb1", -1, 60, "user", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun invalidLimitDoesNotPost() {
        for (limit in listOf(0, 121, -1)) {
            assertFails("jmap message limit is invalid") {
                jmapMessageWindowRequest("A1", "mb1", 0, limit)
            }
            val post = MessageWindowPost(200, samplePage)
            assertFails("jmap message limit is invalid") {
                jmapMessageWindow(messageSession(), "mb1", 0, limit, "user", "secret", "ab", post::post)
            }
            assertEquals("$limit", emptyList<String>(), post.urls)
        }
    }

    @Test
    fun limitEdgesStayInTheRequest() {
        assertEquals(true, jmapMessageWindowRequest("A1", "mb1", 0, 1).contains("\"limit\":1"))
        assertEquals(true, jmapMessageWindowRequest("A1", "mb1", 0, 120).contains("\"limit\":120"))
    }

    @Test
    fun samplePage() {
        val page = parseJmapMessages(samplePage)
        assertEquals(5L, page.total)
        val row = page.messages.single()
        assertEquals("e1", row.id)
        assertEquals("t1", row.threadId)
        assertEquals("Hello", row.subject)
        assertEquals("Ada", row.from)
        assertEquals("2026-10-08T00:00:00Z", row.receivedAt)
        assertEquals("Hi", row.preview)
        assertEquals(false, row.unread)
        assertEquals(100L, row.size)
    }

    @Test
    fun shortObjectIsUnreadAndEmpty() {
        val row = parseJmapMessages(page("""{"id":"e2"}""", total = null)).messages.single()
        assertEquals("e2", row.id)
        assertEquals("", row.threadId)
        assertEquals("", row.subject)
        assertEquals("", row.from)
        assertEquals("", row.receivedAt)
        assertEquals("", row.preview)
        assertEquals(true, row.unread)
        assertEquals(0L, row.size)
    }

    @Test
    fun emailWinsWhenNameIsBlank() {
        val row = parseJmapMessages(
            page("""{"id":"e3","from":[{"email":"bob@example.com"}]}"""),
        ).messages.single()
        assertEquals("bob@example.com", row.from)
    }

    @Test
    fun missingQueryTotalIsNull() {
        val withoutQuery = """{"methodResponses":[["Email/get",{"list":[{"id":"e2"}]},"1"]]}"""
        assertNull(parseJmapMessages(withoutQuery).total)
        val withoutTotal = """{"methodResponses":[["Email/query",{"ids":["e2"]},"0"],["Email/get",{"list":[{"id":"e2"}]},"1"]]}"""
        val page = parseJmapMessages(withoutTotal)
        assertNull(page.total)
        assertEquals("e2", page.messages.single().id)
    }

    @Test
    fun digitTotalFitsInALong() {
        val text = page("""{"id":"e1"}""", total = "9223372036854775807")
        assertEquals(Long.MAX_VALUE, parseJmapMessages(text).total)
        assertEquals(0L, parseJmapMessages(page("""{"id":"e1"}""", total = "0")).total)
    }

    @Test
    fun otherTotalIsNotANumber() {
        for (bad in listOf("1.0", "-1", "1e2", "true", "null", "\"5\"", "9223372036854775808")) {
            assertFails("jmap message number is not a number") {
                parseJmapMessages(page("""{"id":"e1"}""", total = bad))
            }
        }
    }

    @Test
    fun lacksGet() {
        assertFails("jmap message response lacks get") { parseJmapMessages("{}") }
        assertFails("jmap message response lacks get") {
            parseJmapMessages("""{"methodResponses":[["Email/query",{"ids":["e1"],"total":5},"0"]]}""")
        }
    }

    @Test
    fun blankResponse() {
        assertFails("jmap message response is empty") { parseJmapMessages("") }
        assertFails("jmap message response is empty") { parseJmapMessages(" \n") }
    }

    @Test
    fun responseNotObject() {
        for (text in listOf("[]", "nope", "{", samplePage + "x", "1", "null")) {
            assertFails("jmap message response is not an object") { parseJmapMessages(text) }
        }
    }

    @Test
    fun idText() {
        assertFails("jmap message lacks id") { parseJmapMessages(page("""{"subject":"Hello"}""")) }
        assertFails("jmap message lacks id") { parseJmapMessages(page("1")) }
        assertFails("jmap message id is not text") { parseJmapMessages(page("""{"id":1}""")) }
    }

    @Test
    fun textFields() {
        for (name in listOf("threadId", "subject", "receivedAt", "preview")) {
            assertFails("jmap message $name is not text") {
                parseJmapMessages(page("""{"id":"e1","$name":1}"""))
            }
        }
    }

    @Test
    fun size() {
        val row = parseJmapMessages(page("""{"id":"e1","size":9223372036854775807}""")).messages.single()
        assertEquals(Long.MAX_VALUE, row.size)
        for (bad in listOf("1.0", "-1", "1e2", "true", "null", "\"3\"", "9223372036854775808")) {
            assertFails("jmap message number is not a number") {
                parseJmapMessages(page("""{"id":"e1","size":$bad}"""))
            }
        }
    }

    @Test
    fun unreadFollowsSeen() {
        assertEquals(true, parseJmapMessages(page("""{"id":"e1"}""")).messages.single().unread)
        assertEquals(true, parseJmapMessages(page("""{"id":"e1","keywords":{}}""")).messages.single().unread)
        assertEquals(true, parseJmapMessages(page("""{"id":"e1","keywords":[]}""")).messages.single().unread)
        assertEquals(
            false,
            parseJmapMessages(page("""{"id":"e1","keywords":{"${'$'}seen":true}}""")).messages.single().unread,
        )
    }

    @Test
    fun fromList() {
        assertEquals(
            "",
            parseJmapMessages(page("""{"id":"e1","from":[]}""")).messages.single().from,
        )
        assertEquals(
            "Ada",
            parseJmapMessages(
                page("""{"id":"e1","from":[{"name":"Ada","email":"ada@example.com"},{"name":"Bob"}]}"""),
            ).messages.single().from,
        )
        assertFails("jmap message from is not a list") {
            parseJmapMessages(page("""{"id":"e1","from":{"name":"Ada"}}"""))
        }
    }

    @Test
    fun postsTheWindowAndReadsThePage() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = MessageWindowPost(200, samplePage, ders)
        val page = jmapMessageWindow(
            messageSession(),
            "mb1",
            0,
            60,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(5L, page.total)
        assertEquals("e1", page.messages.single().id)
        assertEquals("Ada", page.messages.single().from)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(windowRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun status401Throws() {
        val post = MessageWindowPost(401, "no")
        assertFails("jmap api status 401") {
            jmapMessageWindow(
                messageSession(),
                "mb1",
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf(windowRequest), post.bodies)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = MessageWindowPost(200, samplePage, ders = emptyList())
        assertFails("empty certificate chain") {
            jmapMessageWindow(messageSession(), "mb1", 0, 60, "user", "secret", "ab", post::post)
        }
        assertEquals(listOf(windowRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
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

private fun messageSession(account: String? = "A1"): JmapSession {
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

private data class MessageTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class MessageWindowPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val closes = mutableListOf<String>()
    val trusted = mutableListOf<MessageTrust>()

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
        trusted.add(MessageTrust(host, ders, pin))
        return ""
    }
}

private fun page(items: String, total: String? = "5"): String {
    val query = if (total == null) {
        """["Email/query",{"ids":[]},"0"]"""
    } else {
        """["Email/query",{"ids":[],"total":$total},"0"]"""
    }
    return """{"methodResponses":[$query,["Email/get",{"list":[$items]},"1"]]}"""
}

private val windowRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/query",{"accountId":"A1","filter":{"inMailbox":"mb1"},"sort":[{"property":"receivedAt","isAscending":false}],"position":0,"limit":60,"calculateTotal":true},"0"],["Email/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":["id","threadId","keywords","size","receivedAt","subject","from","preview"]},"1"]]}"""

private val samplePage = """{"methodResponses":[["Email/query",{"ids":["e1"],"total":5},"0"],["Email/get",{"list":[{"id":"e1","threadId":"t1","keywords":{"${'$'}seen":true},"size":100,"receivedAt":"2026-10-08T00:00:00Z","subject":"Hello","from":[{"name":"Ada","email":"ada@example.com"}],"preview":"Hi"}]},"1"]]}"""
