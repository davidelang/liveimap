package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapDeleteTest {
    @Test
    fun destroyRequestMatches() {
        assertEquals(destroyRequest, jmapDestroyRequest("A1", "e1"))
    }

    @Test
    fun quotedIds() {
        val quotedDestroy = destroyRequest.replace("\"A1\"", "\"a\\\"b\"").replace("\"e1\"", "\"e\\\"1\"")
        assertEquals(quotedDestroy, jmapDestroyRequest("a\"b", "e\"1"))
    }

    @Test
    fun blankAccountIdDoesNotPost() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap account id is empty") {
                jmapDestroyRequest(account, "e1")
            }
        }
    }

    @Test
    fun blankMessageIdDoesNotPost() {
        for (emailId in listOf("", " ", "\t", "\n")) {
            assertDeleteFails("jmap message id is empty") {
                jmapDestroyRequest("A1", emailId)
            }
        }
    }
}

private fun assertDeleteFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private val destroyRequest = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/set",{"accountId":"A1","destroy":["e1"]},"0"]]}"""
