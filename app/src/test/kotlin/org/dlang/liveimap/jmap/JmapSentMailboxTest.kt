package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapSentMailboxTest {
    @Test
    fun requestMatchesTheA1Document() {
        assertEquals(sentRequest, jmapSentMailboxRequest("A1"))
    }

    @Test
    fun quotedAccountIdEscapesQuote() {
        val quoted = sentRequest.replace("\"A1\"", "\"a\\\"b\"")
        assertEquals(quoted, jmapSentMailboxRequest("a\"b"))
    }

    @Test
    fun blankAccountIdThrows() {
        for (blank in listOf("", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") { jmapSentMailboxRequest(blank) }
        }
    }

    @Test
    fun sampleResponseReturnsMbSent() {
        assertEquals("mbSent", jmapSentMailboxId(sentResponse))
    }

    @Test
    fun missingSentMailboxThrows() {
        assertFails("jmap sent mailbox is missing") { jmapSentMailboxId(emptyIds) }
        assertFails("jmap sent mailbox is missing") { jmapSentMailboxId(missingQuery) }
        assertFails("jmap sent mailbox is missing") { jmapSentMailboxId(nonTextId) }
        assertFails("jmap sent mailbox is missing") { jmapSentMailboxId("") }
        for (id in listOf("", " ", "\t", "\n")) {
            val text = sentResponse.replace("\"mbSent\"", quotedJson(id))
            assertFails("jmap sent mailbox is missing") { jmapSentMailboxId(text) }
        }
    }

    @Test
    fun postReturnsMbSent() {
        val post = SentPost(200, sentResponse)
        val id = jmapFindSentMailbox(
            session("A1"),
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("mbSent", id)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(sentRequest), post.bodies)
        assertEquals(listOf(jmapBasicAuthorization("user", "secret")), post.authorizations)
        assertEquals(listOf("ab"), post.pins)
    }

    @Test
    fun blankAccountPostsNothing() {
        val post = SentPost(200, sentResponse)
        for (account in listOf(null, "", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapFindSentMailbox(
                    session(account),
                    "user",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun status500Throws() {
        val post = SentPost(500, "no")
        assertFails("jmap api status 500") {
            jmapFindSentMailbox(
                session("A1"),
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
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

    private fun session(account: String?) = JmapSession(
        username = "user@example.com",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download/{accountId}/{blobId}/{name}",
        uploadUrl = "https://example.com/upload/{accountId}/",
        eventSourceUrl = "https://example.com/event/?types={types}",
        state = "s1",
        capabilityIds = setOf(JMAP_MAIL),
        primaryMailAccountId = account,
    )

    private class SentPost(
        private val code: Int,
        private val responseBody: String,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String>()
        val pins = mutableListOf<String>()

        fun post(url: String, body: String, authorization: String): JmapHttpExchange {
            urls.add(url)
            bodies.add(body)
            authorizations.add(authorization)
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))

                override fun header(name: String): String? = null

                override fun body(): String = responseBody

                override fun close() {}
            }
        }

        fun trust(host: String, ders: List<ByteArray>, pin: String): String {
            pins.add(pin)
            return ""
        }
    }

    private fun quotedJson(text: String): String {
        val out = StringBuilder()
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private val sentRequest =
        """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Mailbox/query",{"accountId":"A1","filter":{"role":"sent"}},"0"],["Mailbox/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["id","role"]},"1"]]}"""

    private val sentResponse =
        """{"methodResponses":[["Mailbox/query",{"ids":["mbSent"]},"0"],["Mailbox/get",{"list":[{"id":"mbSent","role":"sent"}]},"1"]]}"""

    private val emptyIds =
        """{"methodResponses":[["Mailbox/query",{"ids":[]},"0"]]}"""

    private val missingQuery =
        """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mbSent","role":"sent"}]},"1"]]}"""

    private val nonTextId =
        """{"methodResponses":[["Mailbox/query",{"ids":[1]},"0"]]}"""
}
