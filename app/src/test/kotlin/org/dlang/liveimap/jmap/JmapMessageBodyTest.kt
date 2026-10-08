package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JmapMessageBodyTest {
    @Test
    fun requestIsThePlainTextGet() {
        assertEquals(bodyRequest, jmapMessageBodyRequest("A1", "e1"))
    }

    @Test
    fun quotedAccountId() {
        val quoted = bodyRequest.replace("\"A1\"", "\"a\\\"b\"")
        assertEquals(quoted, jmapMessageBodyRequest("a\"b", "e1"))
    }

    @Test
    fun blankAccountIdDoesNotPost() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertBodyFails("jmap account id is empty") {
                jmapMessageBodyRequest(account, "e1")
            }
            val post = BodyReadPost(200, twoPartSample)
            assertBodyFails("jmap account id is empty") {
                jmapMessageBody(bodySession(account), "e1", "user", "secret", "ab", post::post)
            }
            assertEquals(account, emptyList<String>(), post.urls)
            val model = bodyModel(post, account)
            assertBodyFails("jmap account id is empty") { model.body("e1") }
            assertEquals(account, emptyList<String>(), post.urls)
        }
        val post = BodyReadPost(200, twoPartSample)
        assertBodyFails("jmap account id is empty") {
            jmapMessageBody(bodySession(null), "e1", "user", "secret", "ab", post::post)
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun blankMessageIdDoesNotPost() {
        for (emailId in listOf("", " ", "\t", "\n")) {
            assertBodyFails("jmap message id is empty") {
                jmapMessageBodyRequest("A1", emailId)
            }
            val post = BodyReadPost(200, twoPartSample)
            assertBodyFails("jmap message id is empty") {
                jmapMessageBody(bodySession(), emailId, "user", "secret", "ab", post::post)
            }
            assertEquals(emailId, emptyList<String>(), post.urls)
            val model = bodyModel(post)
            assertBodyFails("jmap message id is empty") { model.body(emailId) }
            assertEquals(emailId, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun twoPartSampleIsHiThere() {
        assertEquals("Hi there", parseJmapMessageBody(twoPartSample))
    }

    @Test
    fun emptyListIsEmptyText() {
        val text = """{"methodResponses":[["Email/get",{"list":[]},"0"]]}"""
        assertEquals("", parseJmapMessageBody(text))
    }

    @Test
    fun absentOrEmptyTextBodyIsEmpty() {
        assertEquals("", parseJmapMessageBody(email("""{"id":"e1"}""")))
        assertEquals("", parseJmapMessageBody(email("""{"textBody":[]}""")))
    }

    @Test
    fun textBodyThatIsNotAListFails() {
        assertBodyFails("jmap message text is not a list") {
            parseJmapMessageBody(email("""{"textBody":{"partId":"1"}}"""))
        }
        assertBodyFails("jmap message text is not a list") {
            parseJmapMessageBody(email("""{"textBody":null}"""))
        }
    }

    @Test
    fun skipsPartsThatCannotBeRead() {
        val text = email(
            """{"textBody":[{"partId":1},{},{"partId":"9"},{"partId":"1"},{"partId":"2"},{"partId":"3"},{"partId":"4"}],"bodyValues":{"1":"Hi","2":{"value":1},"3":{"value":" there","isTruncated":false},"4":null}}""",
        )
        assertEquals(" there", parseJmapMessageBody(text))
        assertEquals("", parseJmapMessageBody(email("""{"textBody":[{"partId":"1"}]}""")))
    }

    @Test
    fun blankOrBrokenDocumentFails() {
        assertBodyFails("jmap message response is empty") { parseJmapMessageBody("") }
        assertBodyFails("jmap message response is empty") { parseJmapMessageBody(" \n") }
        for (text in listOf("[]", "1", "\"x\"", "{", "nope")) {
            assertBodyFails("jmap message response is not an object") { parseJmapMessageBody(text) }
        }
        assertBodyFails("jmap message response lacks get") { parseJmapMessageBody("{}") }
        assertBodyFails("jmap message response lacks get") {
            parseJmapMessageBody("""{"methodResponses":[["Email/query",{"ids":[]},"0"]]}""")
        }
    }

    @Test
    fun bodyPostsTheRequestAndReturnsTheText() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = BodyReadPost(200, twoPartSample, ders)
        val text = jmapMessageBody(
            bodySession(),
            "e1",
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("Hi there", text)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(bodyRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
        val model = bodyModel(post)
        assertEquals("Hi there", model.body("e1"))
        assertEquals(listOf(bodyRequest, bodyRequest), post.bodies)
    }

    @Test
    fun status401Throws() {
        val post = BodyReadPost(401, "no")
        assertBodyFails("jmap api status 401") {
            jmapMessageBody(bodySession(), "e1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf(bodyRequest), post.bodies)
        val model = bodyModel(post)
        assertBodyFails("jmap api status 401") { model.body("e1") }
        assertEquals(listOf(bodyRequest, bodyRequest), post.bodies)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = BodyReadPost(200, twoPartSample, ders = emptyList())
        assertBodyFails("empty certificate chain") {
            jmapMessageBody(bodySession(), "e1", "user", "secret", "ab", post::post)
        }
        assertEquals(listOf(bodyRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
    }
}

private fun assertBodyFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun bodySession(account: String? = "A1"): JmapSession {
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

private fun bodyModel(post: BodyReadPost, account: String? = "A1"): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        bodySession(account),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
    )
}

private fun email(item: String): String {
    return """{"methodResponses":[["Email/get",{"list":[$item]},"0"]]}"""
}

private data class BodyTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class BodyReadPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<BodyTrust>()

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
        trusted.add(BodyTrust(host, ders, pin))
        return ""
    }
}

private val bodyRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/get",{"accountId":"A1","ids":["e1"],"properties":["textBody","bodyValues"],"fetchTextBodyValues":true,"maxBodyValueBytes":32768},"0"]]}"""

private val twoPartSample = """{"methodResponses":[["Email/get",{"list":[{"textBody":[{"partId":"1"},{"partId":"2"}],"bodyValues":{"1":{"value":"Hi","isTruncated":true},"2":{"value":" there"}}}]},"0"]]}"""
