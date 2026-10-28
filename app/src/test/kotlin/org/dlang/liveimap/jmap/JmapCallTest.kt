package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class JmapCallTest {
    @Test
    fun basicAuthorization() {
        assertEquals("Basic dXNlcjpzZWNyZXQ=", jmapBasicAuthorization("user", "secret"))
        assertEquals("Basic dXNlcjo=", jmapBasicAuthorization("user", ""))
        for (username in listOf("", " ")) {
            assertFails("jmap username is empty") { jmapBasicAuthorization(username, "secret") }
        }
    }
}

private fun assertFails(message: String, call: () -> Unit) {
    try {
        call()
        fail("expected $message")
    } catch (error: JmapFailure) {
        assertEquals(message, error.text)
    }
}
