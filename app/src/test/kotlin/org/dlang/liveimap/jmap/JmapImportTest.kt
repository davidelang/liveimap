package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapImportTest {
    @Test
    fun requestMatchesTheA1Document() {
        assertEquals(importRequest, jmapEmailImportRequest("A1", "B1", "mbSent"))
    }

    @Test
    fun quotedMailboxIdEscapesQuote() {
        val quoted = importRequest.replace("\"mbSent\"", "\"a\\\"b\"")
        assertEquals(quoted, jmapEmailImportRequest("A1", "B1", "a\"b"))
    }

    @Test
    fun blankFieldsThrow() {
        for (blank in listOf("", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapEmailImportRequest(blank, "B1", "mbSent")
            }
            assertFails("jmap blob id is empty") {
                jmapEmailImportRequest("A1", blank, "mbSent")
            }
            assertFails("jmap mailbox id is empty") {
                jmapEmailImportRequest("A1", "B1", blank)
            }
        }
    }

    @Test
    fun createdIdIsM1() {
        assertEquals("M1", jmapImportedEmailId(createdResponse))
    }

    @Test
    fun notCreatedDescriptionIsTheFailure() {
        assertFails("over quota") { jmapImportedEmailId(notCreatedResponse) }
        assertFails("jmap import was not created") {
            jmapImportedEmailId(notCreatedBlank)
        }
        assertFails("jmap import was not created") { jmapImportedEmailId(missingId) }
        assertFails("jmap import was not created") { jmapImportedEmailId(nonTextId) }
        assertFails("jmap import was not created") { jmapImportedEmailId(wrongMethod) }
        assertFails("jmap import was not created") { jmapImportedEmailId("") }
        assertFails("jmap import was not created") { jmapImportedEmailId("nope") }
    }

    @Test
    fun blankCreatedIdThrows() {
        for (id in listOf("", " ", "\t", "\n")) {
            val text = createdResponse.replace("\"M1\"", quotedJson(id))
            assertFails("jmap import id is empty") { jmapImportedEmailId(text) }
        }
    }

    private fun assertFails(message: String, call: () -> Unit) {
        try {
            call()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(message, failure.text)
        }
    }

    private fun quotedJson(text: String): String {
        val out = StringBuilder()
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private val importRequest =
        """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/import",{"accountId":"A1","emails":{"k1":{"blobId":"B1","mailboxIds":{"mbSent":true}}}},"0"]]}"""

    private val createdResponse =
        """{"methodResponses":[["Email/import",{"accountId":"A1","created":{"k1":{"id":"M1","blobId":"B1","threadId":"T1","size":12}}},"0"]],"sessionState":"s"}"""

    private val notCreatedResponse =
        """{"methodResponses":[["Email/import",{"notCreated":{"k1":{"description":"over quota"}}},"0"]]}"""

    private val notCreatedBlank =
        """{"methodResponses":[["Email/import",{"notCreated":{"k1":{"description":" "}}},"0"]]}"""

    private val missingId =
        """{"methodResponses":[["Email/import",{"accountId":"A1"},"0"]]}"""

    private val nonTextId =
        """{"methodResponses":[["Email/import",{"created":{"k1":{"id":1}}},"0"]]}"""

    private val wrongMethod =
        """{"methodResponses":[["Email/set",{"created":{"k1":{"id":"M1"}}},"0"]]}"""
}
