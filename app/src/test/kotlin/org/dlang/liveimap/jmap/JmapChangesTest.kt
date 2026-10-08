package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapChangesTest {
    @Test
    fun requestMatches() {
        assertEquals(changesRequest, jmapEmailChangesRequest("A1", "s1"))
        assertFalse(changesRequest.contains("maxChanges"))
    }

    @Test
    fun quotedAccountAndState() {
        val quoted = changesRequest.replace("\"A1\"", "\"a\\\"b\"").replace("\"s1\"", "\"s\\\"1\"")
        assertEquals(quoted, jmapEmailChangesRequest("a\"b", "s\"1"))
    }

    @Test
    fun blankAccountOrStateDoesNotPost() {
        val post = JmapChangesPost(200, changesSample)
        assertChangesFails("jmap account id is empty") {
            jmapEmailChangesRequest(" ", " ")
        }
        for (account in listOf("", " ", "\t", "\n")) {
            assertChangesFails("jmap account id is empty") {
                jmapEmailChangesRequest(account, "s1")
            }
            assertChangesFails("jmap account id is empty") {
                jmapEmailChanges(changesSession(account), "s1", "user", "secret", "ab", post::post)
            }
        }
        assertChangesFails("jmap account id is empty") {
            jmapEmailChanges(changesSession(null), "s1", "user", "secret", "ab", post::post)
        }
        for (state in listOf("", " ", "\t", "\n")) {
            assertChangesFails("jmap changes state is empty") {
                jmapEmailChangesRequest("A1", state)
            }
            assertChangesFails("jmap changes state is empty") {
                jmapEmailChanges(changesSession(), state, "user", "secret", "ab", post::post)
            }
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun sampleBodyReturnsTheThreeLists() {
        val changes = parseJmapEmailChanges(changesSample)
        assertEquals("s1", changes.oldState)
        assertEquals("s2", changes.newState)
        assertFalse(changes.hasMoreChanges)
        assertEquals(listOf("e2"), changes.created)
        assertEquals(listOf("e1"), changes.updated)
        assertEquals(listOf("e0"), changes.destroyed)
    }

    @Test
    fun missingHasMoreChangesIsFalseAndMissingListsAreEmpty() {
        val text = """{"methodResponses":[["Email/changes",{"oldState":"s1","newState":"s2"},"0"]]}"""
        val changes = parseJmapEmailChanges(text)
        assertEquals("s1", changes.oldState)
        assertEquals("s2", changes.newState)
        assertFalse(changes.hasMoreChanges)
        assertEquals(emptyList<String>(), changes.created)
        assertEquals(emptyList<String>(), changes.updated)
        assertEquals(emptyList<String>(), changes.destroyed)
        val more = """{"methodResponses":[["Email/get",{"list":[]},"1"],["Email/changes",{"oldState":"s1","newState":"s2","hasMoreChanges":true,"destroyed":["e0"]},"0"]]}"""
        val rest = parseJmapEmailChanges(more)
        assertTrue(rest.hasMoreChanges)
        assertEquals(emptyList<String>(), rest.created)
        assertEquals(emptyList<String>(), rest.updated)
        assertEquals(listOf("e0"), rest.destroyed)
    }

    @Test
    fun parseFailures() {
        assertChangesFails("jmap changes response is empty") { parseJmapEmailChanges("") }
        assertChangesFails("jmap changes response is empty") { parseJmapEmailChanges(" \n") }
        for (text in listOf("[]", "1", "nope", "{")) {
            assertChangesFails("jmap changes response is not an object") {
                parseJmapEmailChanges(text)
            }
        }
        for (text in listOf(
            "{}",
            """{"methodResponses":[]}""",
            """{"methodResponses":[["Email/get",{"list":[]},"0"]]}""",
            """{"methodResponses":[["Email/changes",[],"0"]]}""",
        )) {
            assertChangesFails("jmap changes response lacks changes") {
                parseJmapEmailChanges(text)
            }
        }
        assertChangesFails("jmap changes state is not text") {
            parseJmapEmailChanges(changesArgs("""{"newState":"s2"}"""))
        }
        assertChangesFails("jmap changes state is not text") {
            parseJmapEmailChanges(changesArgs("""{"oldState":1,"newState":"s2"}"""))
        }
        assertChangesFails("jmap changes state is not text") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1"}"""))
        }
        assertChangesFails("jmap changes state is not text") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":null}"""))
        }
        assertChangesFails("jmap changes hasMoreChanges is not a boolean") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":"s2","hasMoreChanges":1}"""))
        }
        assertChangesFails("jmap changes hasMoreChanges is not a boolean") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":"s2","hasMoreChanges":"false"}"""))
        }
        assertChangesFails("jmap changes ids are not a list") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":"s2","created":"e2"}"""))
        }
        assertChangesFails("jmap changes ids are not a list") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":"s2","updated":null}"""))
        }
        assertChangesFails("jmap changes id is not text") {
            parseJmapEmailChanges(changesArgs("""{"oldState":"s1","newState":"s2","destroyed":[1]}"""))
        }
    }

    @Test
    fun changesPostsTheRequest() {
        val ders = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val post = JmapChangesPost(200, changesSample, ders)
        val changes = jmapEmailChanges(
            changesSession(),
            "s1",
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("s1", changes.oldState)
        assertEquals("s2", changes.newState)
        assertFalse(changes.hasMoreChanges)
        assertEquals(listOf("e2"), changes.created)
        assertEquals(listOf("e1"), changes.updated)
        assertEquals(listOf("e0"), changes.destroyed)
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), post.bodies)
        assertEquals(listOf(changesRequest), post.bodies)
        assertEquals(listOf("Basic dXNlcjpzZWNyZXQ="), post.authorizations)
        assertEquals(listOf("https://example.com/jmap/"), post.reads)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun status401ThrowsAndDoesNotReturnLists() {
        val post = JmapChangesPost(401, changesSample)
        assertChangesFails("jmap api status 401") {
            jmapEmailChanges(changesSession(), "s1", "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf(changesRequest), post.bodies)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val post = JmapChangesPost(200, changesSample, ders = emptyList())
        assertChangesFails("empty certificate chain") {
            jmapEmailChanges(changesSession(), "s1", "user", "secret", "ab", post::post)
        }
        assertEquals(listOf(changesRequest), post.bodies)
        assertEquals(emptyList<String>(), post.reads)
    }
}

private fun assertChangesFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun changesSession(account: String? = "A1"): JmapSession {
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

private fun changesArgs(args: String): String {
    return """{"methodResponses":[["Email/changes",$args,"0"]]}"""
}

private data class JmapChangesTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class JmapChangesPost(
    private val code: Int,
    private val responseBody: String,
    private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
) {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<JmapChangesTrust>()

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
        trusted.add(JmapChangesTrust(host, ders, pin))
        return ""
    }
}

private val changesRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/changes",{"accountId":"A1","sinceState":"s1"},"0"]]}"""

private val changesSample = """{"methodResponses":[["Email/changes",{"oldState":"s1","newState":"s2","hasMoreChanges":false,"created":["e2"],"updated":["e1"],"destroyed":["e0"]},"0"]]}"""
