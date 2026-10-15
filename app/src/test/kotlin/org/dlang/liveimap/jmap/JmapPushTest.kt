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

    private companion object {
        const val stateData = """{"changed":{"A1":{"Email":"s2"}}}"""
        val sampleLf = "event: state\ndata: $stateData\n\nevent: ping\ndata: {}\n"
    }
}
