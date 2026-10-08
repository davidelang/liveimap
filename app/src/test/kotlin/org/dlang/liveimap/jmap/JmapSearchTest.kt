package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSearchTest {
    @Test
    fun searchRequestMatches() {
        assertEquals(searchRequest, jmapMessageSearchRequest("A1", "mb1", "hello", 0, 60))
    }

    @Test
    fun quotedAccountMailboxAndText() {
        val quoted = searchRequest
            .replace("\"A1\"", "\"a\\\"b\"")
            .replace("\"mb1\"", "\"m\\\"b\"")
            .replace("\"hello\"", "\"h\\\"i\"")
        assertEquals(quoted, jmapMessageSearchRequest("a\"b", "m\"b", "h\"i", 0, 60))
    }

    @Test
    fun checksRunInOrderAndDoNotPost() {
        val post = JmapSearchPost(200, searchSample)
        assertSearchFails("jmap account id is empty") {
            jmapMessageSearchRequest(" ", "", " ", -1, 0)
        }
        assertSearchFails("jmap mailbox id is empty") {
            jmapMessageSearchRequest("A1", " ", " ", -1, 0)
        }
        assertSearchFails("jmap search text is empty") {
            jmapMessageSearchRequest("A1", "mb1", " \n", -1, 0)
        }
        assertSearchFails("jmap message position is invalid") {
            jmapMessageSearchRequest("A1", "mb1", "hello", -1, 0)
        }
        assertSearchFails("jmap message limit is invalid") {
            jmapMessageSearchRequest("A1", "mb1", "hello", 0, 0)
        }
        for (account in listOf("", " ", "\t", "\n")) {
            assertSearchFails("jmap account id is empty") {
                jmapMessageSearch(
                    searchSession(account),
                    "",
                    " ",
                    -1,
                    0,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
        }
        assertSearchFails("jmap account id is empty") {
            jmapMessageSearch(
                searchSession(null),
                "mb1",
                "hello",
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertSearchFails("jmap mailbox id is empty") {
            jmapMessageSearch(
                searchSession(),
                " ",
                " ",
                -1,
                0,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertSearchFails("jmap search text is empty") {
            jmapMessageSearch(
                searchSession(),
                "mb1",
                " ",
                -1,
                0,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertSearchFails("jmap message position is invalid") {
            jmapMessageSearch(
                searchSession(),
                "mb1",
                "hello",
                -1,
                0,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        for (limit in listOf(0, 121, -1)) {
            assertSearchFails("jmap message limit is invalid") {
                jmapMessageSearch(
                    searchSession(),
                    "mb1",
                    "hello",
                    0,
                    limit,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun sampleBodyIsOneMessage() {
        val page = parseJmapMessages(searchSample)
        assertEquals(1L, page.total)
        val row = page.messages.single()
        assertEquals("e1", row.id)
        assertEquals("t", row.threadId)
        assertEquals("Hello", row.subject)
        assertEquals("Ada", row.from)
        assertEquals("2020-01-02T03:04:05Z", row.receivedAt)
        assertEquals("Hi", row.preview)
        assertEquals(4L, row.size)
        assertTrue(row.unread)
    }

    @Test
    fun emptyGetListIsAnEmptyPage() {
        val text = """{"methodResponses":[["Email/query",{"ids":[],"total":0},"0"],["Email/get",{"list":[]},"1"]]}"""
        val post = JmapSearchPost(200, text)
        val page = jmapMessageSearch(
            searchSession(),
            "mb1",
            "hello",
            0,
            60,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(0L, page.total)
        assertEquals(emptyList<JmapMessage>(), page.messages)
        assertEquals(listOf(searchRequest), post.bodies)
    }

    @Test
    fun searchPostsTheRequestAndReadsThePage() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = JmapSearchPost(200, searchSample, ders)
        val page = jmapMessageSearch(
            searchSession(),
            "mb1",
            "hello",
            0,
            60,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals(1L, page.total)
        assertEquals("e1", page.messages.single().id)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(jmapMessageSearchRequest("A1", "mb1", "hello", 0, 60)), post.bodies)
        assertEquals(listOf(searchRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun status401ThrowsAfterPost() {
        val post = JmapSearchPost(401, "no")
        assertSearchFails("jmap api status 401") {
            jmapMessageSearch(
                searchSession(),
                "mb1",
                "hello",
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf(searchRequest), post.bodies)
    }

    @Test
    fun lacksGetThrowsAfterPost() {
        val post = JmapSearchPost(200, """{"methodResponses":[["Email/query",{"ids":["e1"],"total":1},"0"]]}""")
        assertSearchFails("jmap message response lacks get") {
            jmapMessageSearch(
                searchSession(),
                "mb1",
                "hello",
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf(searchRequest), post.bodies)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = JmapSearchPost(200, searchSample, ders = emptyList())
        assertSearchFails("empty certificate chain") {
            jmapMessageSearch(
                searchSession(),
                "mb1",
                "hello",
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(listOf(searchRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
    }

    @Test
    fun modelSearchPostsAtPositionZero() {
        val post = JmapSearchPost(200, searchSample)
        val model = searchModel(post)
        val page = model.search("mb1", "hello")
        assertEquals(listOf(jmapMessageSearchRequest("A1", "mb1", "hello", 0, 60)), post.bodies)
        assertEquals(1L, page.total)
        assertEquals("Hello", page.messages.single().subject)
    }

    @Test
    fun modelSearchBlankTextDoesNotPost() {
        val post = JmapSearchPost(200, searchSample)
        val model = searchModel(post)
        for (text in listOf("", " ", "\t", "\n")) {
            assertSearchFails("jmap search text is empty") { model.search("mb1", text) }
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun messagesStillLoadsTheWindow() {
        val post = JmapSearchPost(200, searchSample)
        val model = searchModel(post)
        val page = model.messages("mb1")
        assertEquals(listOf(jmapMessageWindowRequest("A1", "mb1", 0, 60)), post.bodies)
        assertFalse(post.bodies.single().contains("\"text\":"))
        assertEquals(1L, page.total)
    }
}

private fun assertSearchFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun searchModel(post: JmapSearchPost, account: String? = "A1"): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        searchSession(account),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
    )
}

private fun searchSession(account: String? = "A1"): JmapSession {
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

private data class JmapSearchTrustSeen(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class JmapSearchPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<JmapSearchTrustSeen>()

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
        trusted.add(JmapSearchTrustSeen(host, ders, pin))
        return ""
    }
}

private val searchRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/query",{"accountId":"A1","filter":{"operator":"AND","conditions":[{"inMailbox":"mb1"},{"text":"hello"}]},"sort":[{"property":"receivedAt","isAscending":false}],"position":0,"limit":60,"calculateTotal":true},"0"],["Email/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":["id","threadId","keywords","size","receivedAt","subject","from","preview"]},"1"]]}"""

private val searchSample = """{"methodResponses":[["Email/query",{"ids":["e1"],"total":1},"0"],["Email/get",{"list":[{"id":"e1","threadId":"t","keywords":{},"size":4,"receivedAt":"2020-01-02T03:04:05Z","subject":"Hello","from":[{"name":"Ada","email":"ada@example.com"}],"preview":"Hi"}]},"1"]]}"""
