package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JmapPushListTest {
    @Test
    fun emailGetStateIsKept() {
        val page = parseJmapMessages(
            """{"methodResponses":[["Email/get",{"state":"s1","list":[]},"1"]]}""",
        )
        assertNull(page.total)
        assertEquals(emptyList<JmapMessage>(), page.messages)
        assertEquals("s1", page.emailState)
    }

    @Test
    fun missingOrBlankStateIsNull() {
        val missing = """{"methodResponses":[["Email/get",{"list":[]},"1"]]}"""
        assertNull(parseJmapMessages(missing).emailState)
        for (state in listOf("", " ")) {
            val text = """{"methodResponses":[["Email/get",{"state":"$state","list":[]},"1"]]}"""
            assertEquals(state, null, parseJmapMessages(text).emailState)
        }
        val tab = "{\"methodResponses\":[[\"Email/get\",{\"state\":\"\\t\",\"list\":[]},\"1\"]]}"
        val newline = "{\"methodResponses\":[[\"Email/get\",{\"state\":\"\\n\",\"list\":[]},\"1\"]]}"
        assertNull(parseJmapMessages(tab).emailState)
        assertNull(parseJmapMessages(newline).emailState)
    }

    @Test
    fun numericStateIsNotText() {
        val text = """{"methodResponses":[["Email/get",{"state":1,"list":[]},"1"]]}"""
        assertPushListFails("jmap message state is not text") { parseJmapMessages(text) }
    }

    private fun assertPushListFails(text: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(text, failure.text)
        }
    }
}
