package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** F145, F146, F147: behaviour of jmapSend against a scripted server. */
class JmapSubmitOutcomeTest {
    private class Ex(override val status: Int, private val text: String) : JmapHttpExchange {
        override fun peerDer() = listOf(byteArrayOf(1))
        override fun header(name: String): String? = null
        override fun body() = text
        override fun close() {}
    }

    /** Answers by method name; a null answer is a network failure (the request may have arrived). */
    private class Server(val answers: Map<String, (String) -> JmapHttpExchange?>) {
        val calls = mutableListOf<String>()
        fun post(url: String, body: String, auth: String): JmapHttpExchange {
            val method = Regex("\"methodCalls\":\\[\\[\"([A-Za-z/]+)\"").find(body)!!.groupValues[1]
            calls += method
            return answers[method]?.invoke(body) ?: throw JmapFailure("jmap connection failed: read timed out")
        }
        fun upload(url: String, bytes: ByteArray, type: String, auth: String): JmapHttpExchange =
            Ex(201, """{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":3}""")
    }

    private val session = JmapSession(
        username = "user", apiUrl = "https://example.com/jmap/", downloadUrl = "d", uploadUrl = "https://example.com/u/",
        eventSourceUrl = "e", state = "s", capabilityIds = setOf(JMAP_MAIL, JMAP_SUBMISSION), primaryMailAccountId = "A1",
    )
    private val identities = """{"methodResponses":[["Identity/get",{"list":[{"id":"Iacct","email":"me@example.com"},{"id":"Ialt","email":"alt@example.com"}]},"0"]]}"""
    private val names = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mbSent","name":"Sent","parentId":null},{"id":"mbDrafts","name":"Drafts","parentId":null}]},"0"]]}"""
    private val imported = """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"M1"}}},"0"]]}"""
    private val created = """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":"S1"}}},"0"]]}"""
    private val message = "From: Alt <alt@example.com>\r\nTo: ada@example.com\r\nSubject: x\r\n\r\nhi\r\n".toByteArray()

    private fun base(extra: Map<String, (String) -> JmapHttpExchange?>) = mapOf<String, (String) -> JmapHttpExchange?>(
        "Identity/get" to { _ -> Ex(200, identities) },
        "Mailbox/get" to { _ -> Ex(200, names) },
        "Email/import" to { _ -> Ex(200, imported) },
    ) + extra

    private fun send(server: Server, sent: String = "Sent", notices: MutableList<String> = mutableListOf()) = jmapSend(
        JmapOffer.Mail(session), message, "me@example.com", listOf("ada@example.com"), "user", "pw", "",
        server::upload, server::post, { _, _, _ -> "" }, sentName = sent, saveCopy = sent.isNotEmpty(),
        draftsName = "Drafts", onNotice = { notices += it },
    )

    @Test
    fun identityAndEnvelopeFollowTheMessageFrom() {
        var submission = ""
        val server = Server(base(mapOf("EmailSubmission/set" to { b -> submission = b; Ex(200, created) })))
        assertEquals("S1", send(server))
        assertTrue(submission, submission.contains("\"identityId\":\"Ialt\""))
        assertTrue(submission, submission.contains("\"mailFrom\":{\"email\":\"alt@example.com\"}"))
    }

    @Test
    fun lostAnswerSettledAsSentByQueryKeepsTheSentCopy() {
        val server = Server(base(mapOf(
            "EmailSubmission/set" to { _ -> null },
            "EmailSubmission/query" to { _ -> Ex(200, """{"methodResponses":[["EmailSubmission/query",{"ids":["S9"]},"0"]]}""") },
        )))
        assertEquals("S9", send(server))
        assertEquals(false, "Email/set" in server.calls) // never destroyed
    }

    @Test
    fun lostAnswerThatCannotBeSettledIsMaybeSentAndNothingIsDeleted() {
        val server = Server(base(mapOf("EmailSubmission/set" to { _ -> null }, "EmailSubmission/query" to { _ -> null })))
        try { send(server); fail() } catch (_: JmapMaybeSent) {}
        assertEquals(false, "Email/set" in server.calls)
    }

    @Test
    fun queryShowingNoSubmissionIsADefiniteFailureAndRemovesTheCopy() {
        val server = Server(base(mapOf(
            "EmailSubmission/set" to { _ -> null },
            "EmailSubmission/query" to { _ -> Ex(200, """{"methodResponses":[["EmailSubmission/query",{"ids":[]},"0"]]}""") },
            "Email/set" to { _ -> Ex(200, """{"methodResponses":[["Email/set",{"destroyed":["M1"]},"0"]]}""") },
        )))
        try { send(server); fail() } catch (f: JmapFailure) { assertEquals(false, f is JmapMaybeSent) }
        assertTrue("Email/set" in server.calls)
    }

    @Test
    fun refusedSubmissionIsDefinite() {
        val server = Server(base(mapOf(
            "EmailSubmission/set" to { _ -> Ex(200, """{"methodResponses":[["EmailSubmission/set",{"notCreated":{"k1":{"type":"forbiddenFrom","description":"no"}}},"0"]]}""") },
            "Email/set" to { _ -> Ex(200, "{}") },
        )))
        try { send(server); fail() } catch (f: JmapFailure) { assertEquals(false, f is JmapMaybeSent) }
    }

    @Test
    fun stagingIsAMarkedSeenDraftAndAnUndestroyedOneIsRemovedOrReported() {
        var importBody = ""
        val notices = mutableListOf<String>()
        val server = Server(base(mapOf(
            "Email/import" to { b -> importBody = b; Ex(200, imported) },
            "EmailSubmission/set" to { _ -> Ex(200, created) }, // no implicit Email/set: destroy ignored
            "Email/set" to { _ -> Ex(200, """{"methodResponses":[["Email/set",{"notDestroyed":{"M1":{"type":"x"}}},"0"]]}""") },
        )))
        assertEquals("S1", send(server, sent = "", notices = notices))
        assertTrue(importBody, importBody.contains("\"mbDrafts\":true"))
        for (k in JMAP_STAGING_KEYWORDS) assertTrue(importBody, importBody.contains("\"$k\":true"))
        assertEquals(listOf(JMAP_STAGING_LEFT), notices)
    }

    @Test
    fun stagingDestroyedBySubmissionNeedsNothingMore() {
        val notices = mutableListOf<String>()
        val server = Server(base(mapOf(
            "EmailSubmission/set" to { _ -> Ex(200, """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":"S1"}}},"0"],["Email/set",{"destroyed":["M1"]},"0"]]}""") },
        )))
        assertEquals("S1", send(server, sent = "", notices = notices))
        assertEquals(emptyList<String>(), notices)
        assertEquals(1, server.calls.count { it == "Email/set" } + 1) // no explicit destroy
    }
}
