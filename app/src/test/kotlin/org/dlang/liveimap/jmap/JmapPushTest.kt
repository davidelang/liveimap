package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JmapPushTest {
    @Test
    fun eventSourceUrlAddsEmailClose() {
        assertEquals(
            "https://example.com/jmap/event?types=Email&closeafter=state&ping=120",
            jmapEventSourceUrl("https://example.com/jmap/event"),
        )
        assertEquals(
            "https://example.com/jmap/event?x=1&types=Email&closeafter=state&ping=120",
            jmapEventSourceUrl("https://example.com/jmap/event?x=1"),
        )
        assertEquals(
            "HTTPS://example.com/jmap/event?types=Email&closeafter=state&ping=120",
            jmapEventSourceUrl("HTTPS://example.com/jmap/event"),
        )
    }

    @Test
    fun blankHttpAndFragmentDoNotOpen() {
        val gate = PushGate(200, sampleLf)
        for (url in listOf("", " ", "\t", "\n")) {
            assertPushFails("jmap event source url is empty") {
                jmapReadPush(url, "pin", gate::open) { _, _, _ -> "" }
            }
        }
        for (url in listOf("http://example.com/jmap/event", "HTTP://example.com/jmap/event")) {
            assertPushFails("jmap event source url is not https") {
                jmapReadPush(url, "pin", gate::open) { _, _, _ -> "" }
            }
        }
        for (url in listOf(
            "https://example.com/jmap/event#frag",
            "https://example.com/jmap/event?x=1#y",
        )) {
            assertPushFails("jmap event source url has a fragment") {
                jmapReadPush(url, "pin", gate::open) { _, _, _ -> "" }
            }
        }
        assertEquals(emptyList<String>(), gate.opens)
        assertEquals(emptyList<String>(), gate.reads)
    }

    @Test
    fun sampleIsStateThenPing() {
        assertSample(parseJmapPushEvents(sampleLf))
        assertSample(parseJmapPushEvents(sampleLf.replace("\n", "\r\n")))
        val packed = "event:state\ndata: $stateData\n"
        val events = parseJmapPushEvents(packed)
        assertEquals("state", events.single().name)
        assertEquals("s2", jmapPushEmailState(events.single().data, "A1"))
    }

    @Test
    fun dataLinesCommentsAndDefaultName() {
        val joined = parseJmapPushEvents("data: one\ndata: two\n")
        assertEquals("message", joined.single().name)
        assertEquals("one\ntwo", joined.single().data)
        val skipped = parseJmapPushEvents(": keep\n\nevent: ping\ndata: {}\n")
        assertEquals("ping", skipped.single().name)
        assertEquals("{}", skipped.single().data)
        assertNull(jmapPushEmailState(skipped.single().data, "A1"))
    }

    @Test
    fun blankBodyAndBadState() {
        for (body in listOf("", " ", "\n", "\r\n")) {
            assertPushFails("jmap push response is empty") { parseJmapPushEvents(body) }
        }
        for (account in listOf("", " ", "\t", "\n")) {
            assertPushFails("jmap account id is empty") { jmapPushEmailState("{}", account) }
        }
        for (text in listOf("[]", "1", "nope", "{", "\"s2\"")) {
            assertPushFails("jmap push data is not an object") { jmapPushEmailState(text, "A1") }
        }
        assertPushFails("jmap push email state is not text") {
            jmapPushEmailState("""{"changed":{"A1":{"Email":1}}}""", "A1")
        }
        assertPushFails("jmap push email state is not text") {
            jmapPushEmailState("""{"changed":{"A1":{"Email":null}}}""", "A1")
        }
        assertNull(jmapPushEmailState("""{"changed":{"A1":{}}}""", "A1"))
        assertNull(jmapPushEmailState("{}", "A1"))
    }

    @Test
    fun readPushOpensTheBuiltUrlOnce() {
        for (raw in listOf(
            "https://example.com/jmap/event",
            "https://example.com/jmap/event?x=1",
        )) {
            val gate = PushGate(200, sampleLf)
            val events = jmapReadPush(raw, "pin", gate::open) { _, _, _ -> "" }
            assertEquals(listOf(jmapEventSourceUrl(raw)), gate.opens)
            assertEquals("s2", jmapPushEmailState(events[0].data, "A1"))
            assertNull(jmapPushEmailState(events[1].data, "A1"))
        }
    }

    @Test
    fun status401DoesNotParse() {
        val gate = PushGate(401, "")
        assertPushFails("jmap push status 401") {
            jmapReadPush("https://example.com/jmap/event", "pin", gate::open) { _, _, _ -> "" }
        }
        assertEquals(
            listOf("https://example.com/jmap/event?types=Email&closeafter=state&ping=120"),
            gate.opens,
        )
    }

    @Test
    fun untrustedCertificateDoesNotReadTheBody() {
        val gate = PushGate(200, sampleLf)
        assertPushFails("certificate untrusted") {
            jmapReadPush("https://example.com/jmap/event", "pin", gate::open) { _, _, _ ->
                "certificate untrusted"
            }
        }
        assertEquals(emptyList<String>(), gate.reads)
    }

    @Test
    fun defaultTrustDoesNotReadTheBody() {
        val gate = PushGate(200, sampleLf)
        assertPushFails("empty certificate chain") {
            jmapReadPush("https://example.com/jmap/event", "pin", gate::open)
        }
        assertEquals(emptyList<String>(), gate.reads)
    }

    private fun assertSample(events: List<JmapPushEvent>) {
        assertEquals(2, events.size)
        assertEquals("state", events[0].name)
        assertEquals(stateData, events[0].data)
        assertEquals("s2", jmapPushEmailState(events[0].data, "A1"))
        assertNull(jmapPushEmailState(events[0].data, "A2"))
        assertEquals("ping", events[1].name)
        assertEquals("{}", events[1].data)
        assertNull(jmapPushEmailState(events[1].data, "A1"))
    }

    private fun assertPushFails(text: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(text, failure.text)
        }
    }

    private class PushGate(private val code: Int, private val text: String) {
        val opens = mutableListOf<String>()
        val reads = mutableListOf<String>()

        fun open(url: String): JmapHttpExchange {
            opens.add(url)
            val seen = reads
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = emptyList()

                override fun header(name: String): String? = null

                override fun body(): String {
                    seen.add(url)
                    return text
                }

                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val stateData = """{"changed":{"A1":{"Email":"s2"}}}"""
        val sampleLf = "event: state\ndata: $stateData\n\nevent: ping\ndata: {}\n"
    }
}
