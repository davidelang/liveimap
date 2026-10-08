package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JmapSearchStepsTest {
    @Test
    fun oneAndTermMatchesSearchRequest() {
        assertEquals(
            jmapMessageSearchRequest("A1", "mb1", "hello", 0, 60),
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), 0, 60),
        )
        assertEquals(
            jmapMessageSearchRequest("A1", "mb1", "hello", 4, 120),
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), 4, 120),
        )
        assertEquals(
            jmapMessageSearchRequest("a\"b", "m\"b", "h\"i", 0, 60),
            jmapMessageSearchStepsRequest("a\"b", "m\"b", "AND", listOf("h\"i"), 0, 60),
        )
    }

    @Test
    fun filtersMatchTheOperator() {
        assertEquals(
            stepsRequest("{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"text\":\"hello\"},{\"text\":\"world\"}]}"),
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello", "world"), 0, 60),
        )
        assertEquals(
            stepsRequest("{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"operator\":\"OR\",\"conditions\":[{\"text\":\"hello\"}]}]}"),
            jmapMessageSearchStepsRequest("A1", "mb1", "OR", listOf("hello"), 0, 60),
        )
        assertEquals(
            stepsRequest("{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"operator\":\"OR\",\"conditions\":[{\"text\":\"hello\"},{\"text\":\"world\"}]}]}"),
            jmapMessageSearchStepsRequest("A1", "mb1", "OR", listOf("hello", "world"), 0, 60),
        )
        assertEquals(
            stepsRequest("{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"operator\":\"NOT\",\"conditions\":[{\"text\":\"hello\"}]}]}"),
            jmapMessageSearchStepsRequest("A1", "mb1", "NOT", listOf("hello"), 0, 60),
        )
        assertEquals(
            stepsRequest("{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"text\":\"world\"},{\"text\":\"hello\"}]}"),
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("world", "hello"), 0, 60),
        )
    }

    @Test
    fun quotedTextUsesTheIdQuoting() {
        val base = jmapMessageSearchRequest("a\"b", "m\"b", "h\"i", 2, 7)
        val old = "{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"m\\\"b\"},{\"text\":\"h\\\"i\"}]}"
        val filter = "{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"m\\\"b\"},{\"operator\":\"OR\",\"conditions\":[{\"text\":\"h\\\"i\"},{\"text\":\"w\\nx\"}]}]}"
        assertEquals(
            base.replace(old, filter),
            jmapMessageSearchStepsRequest("a\"b", "m\"b", "OR", listOf("h\"i", "w\nx"), 2, 7),
        )
    }

    @Test
    fun checksRunInOrderAndDoNotReturnABody() {
        assertStepsFails("jmap account id is empty") {
            jmapMessageSearchStepsRequest(" ", "", "NO", emptyList(), -1, 0)
        }
        assertStepsFails("jmap mailbox id is empty") {
            jmapMessageSearchStepsRequest("A1", " ", "NO", emptyList(), -1, 0)
        }
        assertStepsFails("jmap search operator is invalid") {
            jmapMessageSearchStepsRequest("A1", "mb1", "NO", emptyList(), -1, 0)
        }
        assertStepsFails("jmap search text is empty") {
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", emptyList(), -1, 0)
        }
        assertStepsFails("jmap search text is empty") {
            jmapMessageSearchStepsRequest("A1", "mb1", "OR", listOf("hello", " "), -1, 0)
        }
        assertStepsFails("jmap search not takes one term") {
            jmapMessageSearchStepsRequest("A1", "mb1", "NOT", listOf("hello", "world"), -1, 0)
        }
        assertStepsFails("jmap message position is invalid") {
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), -1, 60)
        }
        assertStepsFails("jmap message limit is invalid") {
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), 0, 0)
        }
        assertStepsFails("jmap message limit is invalid") {
            jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), 0, 121)
        }
    }

    @Test
    fun refusalsDoNotPost() {
        val post = StepsSearchPost(200, stepsSample)
        for (account in listOf("", " ", "\t", "\n")) {
            assertStepsFails("jmap account id is empty") {
                jmapMessageSearchSteps(
                    stepsSession(account),
                    "",
                    "NO",
                    emptyList(),
                    -1,
                    0,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                )
            }
        }
        assertStepsFails("jmap account id is empty") {
            jmapMessageSearchSteps(
                stepsSession(null),
                "mb1",
                "AND",
                listOf("hello"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertStepsFails("jmap mailbox id is empty") {
            jmapMessageSearchSteps(
                stepsSession(),
                " ",
                "NO",
                emptyList(),
                -1,
                0,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertStepsFails("jmap search operator is invalid") {
            jmapMessageSearchSteps(
                stepsSession(),
                "mb1",
                "XOR",
                listOf("hello"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertStepsFails("jmap search text is empty") {
            jmapMessageSearchSteps(
                stepsSession(),
                "mb1",
                "AND",
                listOf(" \n"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertStepsFails("jmap search not takes one term") {
            jmapMessageSearchSteps(
                stepsSession(),
                "mb1",
                "NOT",
                listOf("hello", "world"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun searchPostsTheRequestAndReadsThePage() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = StepsSearchPost(200, stepsSample, ders)
        val page = jmapMessageSearchSteps(
            stepsSession(),
            "mb1",
            "OR",
            listOf("hello", "world"),
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
        assertEquals(
            listOf(jmapMessageSearchStepsRequest("A1", "mb1", "OR", listOf("hello", "world"), 0, 60)),
            post.bodies,
        )
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun status401ThrowsAfterPost() {
        val post = StepsSearchPost(401, "no")
        assertStepsFails("jmap api status 401") {
            jmapMessageSearchSteps(
                stepsSession(),
                "mb1",
                "NOT",
                listOf("hello"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(
            listOf(jmapMessageSearchStepsRequest("A1", "mb1", "NOT", listOf("hello"), 0, 60)),
            post.bodies,
        )
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = StepsSearchPost(200, stepsSample, ders = emptyList())
        assertStepsFails("empty certificate chain") {
            jmapMessageSearchSteps(
                stepsSession(),
                "mb1",
                "AND",
                listOf("hello"),
                0,
                60,
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(
            listOf(jmapMessageSearchStepsRequest("A1", "mb1", "AND", listOf("hello"), 0, 60)),
            post.bodies,
        )
        assertEquals(emptyList<String>(), post.reads)
    }

    @Test
    fun modelSearchStepsPostsAtPositionZero() {
        val post = StepsSearchPost(200, stepsSample)
        val model = stepsModel(post)
        val page = model.searchSteps("mb1", "OR", listOf("hello", "world"))
        assertEquals(
            listOf(jmapMessageSearchStepsRequest("A1", "mb1", "OR", listOf("hello", "world"), 0, 60)),
            post.bodies,
        )
        assertEquals(1L, page.total)
        assertEquals("Hello", page.messages.single().subject)
    }

    @Test
    fun modelNotWithTwoTermsDoesNotPost() {
        val post = StepsSearchPost(200, stepsSample)
        val model = stepsModel(post)
        assertStepsFails("jmap search not takes one term") {
            model.searchSteps("mb1", "NOT", listOf("hello", "world"))
        }
        assertEquals(emptyList<String>(), post.urls)
    }
}

private fun stepsRequest(filter: String): String {
    val base = jmapMessageSearchRequest("A1", "mb1", "hello", 0, 60)
    val old = "{\"operator\":\"AND\",\"conditions\":[{\"inMailbox\":\"mb1\"},{\"text\":\"hello\"}]}"
    return base.replace(old, filter)
}

private fun assertStepsFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun stepsModel(post: StepsSearchPost, account: String? = "A1"): JmapFolderScreenModel {
    return JmapFolderScreenModel(
        stepsSession(account),
        "user",
        "secret",
        "ab",
        post::post,
        post::trust,
    )
}

private fun stepsSession(account: String? = "A1"): JmapSession {
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

private data class StepsTrustSeen(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class StepsSearchPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<StepsTrustSeen>()

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
        trusted.add(StepsTrustSeen(host, ders, pin))
        return ""
    }
}

private val stepsSample = """{"methodResponses":[["Email/query",{"ids":["e1"],"total":1},"0"],["Email/get",{"list":[{"id":"e1","threadId":"t","keywords":{},"size":4,"receivedAt":"2020-01-02T03:04:05Z","subject":"Hello","from":[{"name":"Ada","email":"ada@example.com"}],"preview":"Hi"}]},"1"]]}"""
