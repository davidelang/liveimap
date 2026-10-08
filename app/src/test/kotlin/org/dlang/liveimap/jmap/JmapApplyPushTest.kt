package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JmapApplyPushTest {
    @Test
    fun newStatePostsTheStoredSinceState() {
        val gate = ApplyGate(200, stateThenPing("s2"))
        val changes = apply(gate)
        assertEquals("s1", changes?.oldState)
        assertEquals("s2", changes?.newState)
        assertEquals(listOf("e2"), changes?.created)
        assertEquals(listOf("e1"), changes?.updated)
        assertEquals(listOf("e0"), changes?.destroyed)
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), gate.opens)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), gate.posts)
        assertEquals(listOf("https://example.com/jmap/"), gate.postUrls)
    }

    @Test
    fun pingThenStatePostsTheStoredSinceState() {
        val body = "event: ping\ndata: {}\n\nevent: state\ndata: ${emailState("s2")}\n"
        val gate = ApplyGate(200, body)
        val changes = apply(gate)
        assertEquals("s2", changes?.newState)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), gate.posts)
    }

    @Test
    fun missingAccountThenStatePostsTheStoredSinceState() {
        val body = "event: state\ndata: ${emailState("s9", "A2")}\n\n" +
            "event: state\ndata: ${emailState("s2")}\n"
        val gate = ApplyGate(200, body)
        apply(gate)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), gate.posts)
    }

    @Test
    fun sameStateDoesNotPost() {
        val gate = ApplyGate(200, stateThenPing("s1"))
        assertNull(apply(gate))
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), gate.opens)
        assertEquals(emptyList<String>(), gate.posts)
    }

    @Test
    fun firstSameStateIgnoresALaterState() {
        val body = "event: state\ndata: ${emailState("s1")}\n\n" +
            "event: state\ndata: ${emailState("s2")}\n"
        val gate = ApplyGate(200, body)
        assertNull(apply(gate))
        assertEquals(emptyList<String>(), gate.posts)
    }

    @Test
    fun pingOrNoEmailStateDoesNotPost() {
        val ping = ApplyGate(200, "event: ping\ndata: {}\n")
        assertNull(apply(ping))
        assertEquals(emptyList<String>(), ping.posts)
        val emptyChanged = ApplyGate(200, "event: state\ndata: {}\n")
        assertNull(apply(emptyChanged))
        assertEquals(emptyList<String>(), emptyChanged.posts)
    }

    @Test
    fun laterBadEventAfterANewStateIsIgnored() {
        val body = "event: state\ndata: ${emailState("s2")}\n\nevent: state\ndata: nope\n"
        val gate = ApplyGate(200, body)
        val changes = apply(gate)
        assertEquals("s2", changes?.newState)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), gate.posts)
    }

    @Test
    fun badPushBodyDoesNotPost() {
        val blank = ApplyGate(200, "")
        assertApplyFails("jmap push response is empty") { apply(blank) }
        assertEquals(emptyList<String>(), blank.posts)
        val notText = ApplyGate(200, "event: state\ndata: {\"changed\":{\"A1\":{\"Email\":1}}}\n")
        assertApplyFails("jmap push email state is not text") { apply(notText) }
        assertEquals(emptyList<String>(), notText.posts)
    }

    @Test
    fun blankChecksDoNotOpen() {
        val gate = ApplyGate(200, stateThenPing("s2"))
        val trust = { _: String, _: List<ByteArray>, _: String -> "" }
        for (account in listOf(null, "", " ", "\t", "\n")) {
            assertApplyFails("jmap account id is empty") {
                jmapApplyPush(
                    applySession(account),
                    "",
                    " ",
                    "",
                    "pin",
                    gate::open,
                    gate::post,
                    trust,
                )
            }
        }
        for (since in listOf("", " ", "\t", "\n")) {
            assertApplyFails("jmap changes state is empty") {
                jmapApplyPush(
                    applySession(),
                    since,
                    " ",
                    "",
                    "pin",
                    gate::open,
                    gate::post,
                    trust,
                )
            }
        }
        for (username in listOf("", " ", "\t", "\n")) {
            assertApplyFails("jmap username is empty") {
                jmapApplyPush(
                    applySession(),
                    "s1",
                    username,
                    "",
                    "pin",
                    gate::open,
                    gate::post,
                    trust,
                )
            }
        }
        assertEquals(emptyList<String>(), gate.opens)
        assertEquals(emptyList<String>(), gate.posts)
    }

    @Test
    fun pushStatus401DoesNotPost() {
        val gate = ApplyGate(401, "")
        assertApplyFails("jmap push status 401") { apply(gate) }
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), gate.opens)
        assertEquals(emptyList<String>(), gate.posts)
    }

    @Test
    fun emptyPasswordPosts() {
        val gate = ApplyGate(200, stateThenPing("s2"))
        val changes = apply(gate, password = "")
        assertEquals("s2", changes?.newState)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), gate.posts)
    }

    @Test
    fun defaultTrustDoesNotPost() {
        val gate = ApplyGate(200, stateThenPing("s2"))
        assertApplyFails("empty certificate chain") {
            jmapApplyPush(
                applySession(),
                "s1",
                "user",
                "secret",
                "pin",
                gate::open,
                gate::post,
            )
        }
        assertEquals(emptyList<String>(), gate.posts)
    }

    private fun apply(
        gate: ApplyGate,
        session: JmapSession = applySession(),
        since: String = "s1",
        username: String = "user",
        password: String = "secret",
    ): JmapEmailChanges? {
        return jmapApplyPush(
            session,
            since,
            username,
            password,
            "pin",
            gate::open,
            gate::post,
        ) { _, _, _ -> "" }
    }

    private fun assertApplyFails(text: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(text, failure.text)
        }
    }

    private class ApplyGate(
        private val pushCode: Int,
        private val pushText: String,
    ) {
        val opens = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val postUrls = mutableListOf<String>()

        fun open(url: String): JmapHttpExchange {
            opens.add(url)
            return exchange(pushCode, pushText)
        }

        fun post(url: String, body: String, authorization: String): JmapHttpExchange {
            postUrls.add(url)
            posts.add(body)
            return exchange(200, changesSample)
        }

        private fun exchange(code: Int, text: String): JmapHttpExchange {
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = emptyList()

                override fun header(name: String): String? = null

                override fun body(): String = text

                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val eventUrl = "https://example.com/event"
        val changesSample = """{"methodResponses":[["Email/changes",{"oldState":"s1","newState":"s2","hasMoreChanges":false,"created":["e2"],"updated":["e1"],"destroyed":["e0"]},"0"]]}"""

        fun emailState(state: String, account: String = "A1"): String {
            return """{"changed":{"$account":{"Email":"$state"}}}"""
        }

        fun stateThenPing(state: String): String {
            return "event: state\ndata: ${emailState(state)}\n\nevent: ping\ndata: {}\n"
        }

        fun applySession(account: String? = "A1"): JmapSession {
            return JmapSession(
                username = "user",
                apiUrl = "https://example.com/jmap/",
                downloadUrl = "https://example.com/download",
                uploadUrl = "https://example.com/upload",
                eventSourceUrl = eventUrl,
                state = "s",
                capabilityIds = setOf(JMAP_MAIL),
                primaryMailAccountId = account,
            )
        }
    }
}
