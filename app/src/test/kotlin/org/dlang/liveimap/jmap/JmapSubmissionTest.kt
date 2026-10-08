package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSubmissionTest {
    @Test
    fun requestMatchesTheA1Document() {
        assertEquals(
            submissionRequest,
            jmapEmailSubmissionRequest(
                "A1",
                "M1",
                "me@example.com",
                listOf("ada@example.com", "bob@example.com"),
            ),
        )
    }

    @Test
    fun quotedAccountIdEscapesQuote() {
        val quoted = submissionRequest.replace("\"A1\"", "\"a\\\"b\"")
        assertEquals(
            quoted,
            jmapEmailSubmissionRequest(
                "a\"b",
                "M1",
                "me@example.com",
                listOf("ada@example.com", "bob@example.com"),
            ),
        )
    }

    @Test
    fun tabInMailFromIsEscaped() {
        val quoted = submissionRequest.replace("\"me@example.com\"", "\"a\\tb\"")
        assertEquals(
            quoted,
            jmapEmailSubmissionRequest(
                "A1",
                "M1",
                "a\tb",
                listOf("ada@example.com", "bob@example.com"),
            ),
        )
    }

    @Test
    fun blankFieldsThrow() {
        for (blank in listOf("", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapEmailSubmissionRequest(blank, "M1", "me@example.com", listOf("ada@example.com"))
            }
            assertFails("jmap message id is empty") {
                jmapEmailSubmissionRequest("A1", blank, "me@example.com", listOf("ada@example.com"))
            }
            assertFails("jmap submission from is empty") {
                jmapEmailSubmissionRequest("A1", "M1", blank, listOf("ada@example.com"))
            }
            assertFails("jmap submission recipient is empty") {
                jmapEmailSubmissionRequest("A1", "M1", "me@example.com", listOf(blank))
            }
        }
        assertFails("jmap submission recipient is empty") {
            jmapEmailSubmissionRequest("A1", "M1", "me@example.com", emptyList())
        }
        assertFails("jmap submission recipient is empty") {
            jmapEmailSubmissionRequest(
                "A1",
                "M1",
                "me@example.com",
                listOf("ada@example.com", " "),
            )
        }
    }

    @Test
    fun offersSubmissionOnlyForTheSubmissionCapability() {
        assertFalse(session().offersSubmission())
        assertFalse(session(JMAP_MAIL).offersSubmission())
        assertTrue(session(JMAP_MAIL).offersMail())
        assertFalse(session("urn:ietf:params:jmap:submissionx").offersSubmission())
        assertTrue(session(JMAP_SUBMISSION).offersSubmission())
        assertFalse(session(JMAP_SUBMISSION).offersMail())
        assertTrue(session(JMAP_MAIL, JMAP_SUBMISSION).offersSubmission())
        assertTrue(session(JMAP_MAIL, JMAP_SUBMISSION).offersMail())
    }

    @Test
    fun createdIdIsS1() {
        assertEquals("S1", jmapSubmissionId(createdResponse))
    }

    @Test
    fun notCreatedDescriptionIsTheFailure() {
        assertFails("over quota") { jmapSubmissionId(notCreatedResponse) }
        assertFails("jmap submission was not created") {
            jmapSubmissionId(notCreatedBlank)
        }
        assertFails("jmap submission was not created") { jmapSubmissionId(missingId) }
        assertFails("jmap submission was not created") { jmapSubmissionId(nonTextId) }
        assertFails("jmap submission was not created") { jmapSubmissionId(wrongMethod) }
        assertFails("jmap submission was not created") { jmapSubmissionId("") }
        assertFails("jmap submission was not created") { jmapSubmissionId("nope") }
    }

    @Test
    fun blankCreatedIdThrows() {
        for (id in listOf("", " ", "\t", "\n")) {
            val text = createdResponse.replace("\"S1\"", quotedJson(id))
            assertFails("jmap submission id is empty") { jmapSubmissionId(text) }
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

    private fun session(vararg ids: String) = JmapSession(
        username = "user@example.com",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download/{accountId}/{blobId}/{name}",
        uploadUrl = "https://example.com/upload/{accountId}/",
        eventSourceUrl = "https://example.com/event/?types={types}",
        state = "s1",
        capabilityIds = ids.toSet(),
        primaryMailAccountId = "A1",
    )

    private fun quotedJson(text: String): String {
        val out = StringBuilder()
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private val submissionRequest =
        """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail","urn:ietf:params:jmap:submission"],"methodCalls":[["EmailSubmission/set",{"accountId":"A1","create":{"k1":{"emailId":"M1","envelope":{"mailFrom":{"email":"me@example.com"},"rcptTo":[{"email":"ada@example.com"},{"email":"bob@example.com"}]}}}},"0"]]}"""

    private val createdResponse =
        """{"methodResponses":[["EmailSubmission/set",{"accountId":"A1","created":{"k1":{"id":"S1"}}},"0"]],"sessionState":"s"}"""

    private val notCreatedResponse =
        """{"methodResponses":[["EmailSubmission/set",{"notCreated":{"k1":{"description":"over quota"}}},"0"]]}"""

    private val notCreatedBlank =
        """{"methodResponses":[["EmailSubmission/set",{"notCreated":{"k1":{"description":" "}}},"0"]]}"""

    private val missingId =
        """{"methodResponses":[["EmailSubmission/set",{"accountId":"A1"},"0"]]}"""

    private val nonTextId =
        """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":1}}},"0"]]}"""

    private val wrongMethod =
        """{"methodResponses":[["Email/set",{"created":{"k1":{"id":"S1"}}},"0"]]}"""
}
