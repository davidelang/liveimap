package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapOpenFolderTest {
    @Test
    fun lineUsesFromAndSubject() {
        assertEquals("Ada  Hello", jmapMessageLine(openMessage("Ada", "Hello")))
        assertEquals("Hello", jmapMessageLine(openMessage("", "Hello")))
        assertEquals("Ada  No subject", jmapMessageLine(openMessage("Ada", "")))
        assertEquals("Ada  No subject", jmapMessageLine(openMessage("Ada", "   ")))
        assertEquals("No subject", jmapMessageLine(openMessage("", "")))
        assertEquals("No subject", jmapMessageLine(openMessage("  ", " ")))
        val unread = openMessage("Ada", "Hello").copy(unread = true)
        assertEquals("Ada  Hello", jmapMessageLine(unread))
    }

    @Test
    fun messagesPostsTheWindow() {
        val post = OpenFolderPost(200, openSamplePage)
        val model = openModel(post)
        val page = model.messages("mb1")
        assertEquals(listOf(jmapMessageWindowRequest("A1", "mb1", 0, 60)), post.bodies)
        assertEquals(5L, page.total)
        assertEquals("e1", page.messages.single().id)
        assertEquals("Ada  Hello", jmapMessageLine(page.messages.single()))
    }

    @Test
    fun status401ThrowsAndPostsTheWindow() {
        val post = OpenFolderPost(401, "no")
        val model = openModel(post)
        try {
            model.messages("mb1")
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("jmap api status 401", failure.text)
        }
        assertEquals(listOf(jmapMessageWindowRequest("A1", "mb1", 0, 60)), post.bodies)
    }
}

private fun openModel(post: OpenFolderPost): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        openSession(),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
    )
}

private fun openMessage(from: String, subject: String): JmapMessage {
    return JmapMessage(
        id = "e1",
        threadId = "t1",
        subject = subject,
        from = from,
        receivedAt = "",
        preview = "",
        unread = false,
        size = 0L,
    )
}

private fun openSession(): JmapSession {
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

private class OpenFolderPost(
    private val code: Int,
    private val responseBody: String,
) {
    val bodies = mutableListOf<String>()

    fun post(url: String, body: String, authorization: String): JmapHttpExchange {
        bodies.add(body)
        return object : JmapHttpExchange {
            override val status: Int = code

            override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))

            override fun header(name: String): String? = null

            override fun body(): String = responseBody

            override fun close() = Unit
        }
    }

    fun trust(host: String, peerCertificates: List<ByteArray>, pin: String): String = ""
}

private val openSamplePage = """{"methodResponses":[["Email/query",{"ids":["e1"],"total":5},"0"],["Email/get",{"list":[{"id":"e1","threadId":"t1","keywords":{"${'$'}seen":true},"size":100,"receivedAt":"2026-10-08T00:00:00Z","subject":"Hello","from":[{"name":"Ada","email":"ada@example.com"}],"preview":"Hi"}]},"1"]]}"""
