package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JmapMailSessionTest {
    @Test
    fun quotingEscapesControlAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", JmapMailSession.q("a\"b\\c\n"))
    }

    @Test
    fun arrivalUidKeepsTheFirstAssignment() {
        val t = JmapMailSession.Uids()
        val start = t.next
        assertEquals(start, t.uid("n", 200L))
        assertEquals(start + 1, t.next)
        val left = t.next
        val older = t.uid("o", 100L)
        val same = t.uid("s", 200L)
        assertTrue(older >= left && same >= left && older != same)
        assertTrue(t.next > older && t.next > same)
        assertEquals(start, t.uid("n", 200L))
        assertEquals(older, t.uid("o", 100L))
        assertEquals(same, t.uid("s", 200L))
    }

    @Test
    fun accountWithoutSubmissionIsNotOffered() {
        val text = """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:submission":{}},
            "accounts":{"A1":{"accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"A1"},
            "username":"u","apiUrl":"https://x/api","downloadUrl":"https://x/d","uploadUrl":"https://x/u","eventSourceUrl":"https://x/e","state":"s"}"""
        assertEquals(false, parseJmapSession(text).offersSubmission())
        val withIt = text.replace(""""accountCapabilities":{"urn:ietf:params:jmap:mail":{}}""", """"accountCapabilities":{"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:submission":{}}""")
        assertEquals(true, parseJmapSession(withIt).offersSubmission())
        assertEquals(true, parseJmapSession(text.replace(""""accountCapabilities":{"urn:ietf:params:jmap:mail":{}}""", "")).offersSubmission())
    }

    @Test
    fun eventSourceTemplateExpandsLevelOneAndStaysOnHost() {
        assertEquals(
            "https://mail.example/ev?types=Email&closeafter=state&ping=120",
            jmapEventSourceUrl("https://mail.example/ev?types={types}&closeafter={closeafter}&ping={ping}"),
        )
        assertEquals("https://mail.example/ev/Email/state?x=", jmapEventSourceUrl("https://mail.example/ev/{types}/{closeafter}?x={other}"))
        for (bad in listOf(
            "https://mail.example/ev?t={+types}",
            "https://mail.example/ev?t={types",
            "https://{types}.example/ev",
            "http://mail.example/ev?t={types}",
        )) {
            try {
                jmapEventSourceUrl(bad)
                fail(bad)
            } catch (_: JmapFailure) {
            }
        }
        assertEquals("https://mail.example/ev?types=Email&closeafter=state&ping=120", jmapEventSourceUrl("https://mail.example/ev"))
    }
}
