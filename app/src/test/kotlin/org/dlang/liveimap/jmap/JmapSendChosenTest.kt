package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSendChosenTest {
    @Test
    fun noneThrowsAndCallsNothing() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to sentResponse))
        assertFails("jmap submission is not offered") {
            jmapSend(
                JmapOffer.None,
                byteArrayOf(1, 2, 3),
                "me@example.com",
                listOf("ada@example.com"),
                "user",
                "secret",
                "ab",
                upload::send,
                post::post,
                post::trust,
            )
        }
        assertEquals(emptyList<String>(), upload.urls)
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun missingCapabilityThrowsAndCallsNothing() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to sentResponse))
        assertFails("jmap submission is not offered") {
            jmapSend(
                JmapOffer.Mail(session(JMAP_MAIL)),
                byteArrayOf(1, 2, 3),
                "me@example.com",
                listOf("ada@example.com"),
                "user",
                "secret",
                "ab",
                upload::send,
                post::post,
                post::trust,
            )
        }
        assertEquals(emptyList<String>(), upload.urls)
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun nullAccountThrowsAndRecordsNothing() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to sentResponse))
        assertFails("jmap account id is empty") {
            jmapSend(
                JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION, account = null)),
                byteArrayOf(1, 2, 3),
                "me@example.com",
                listOf("ada@example.com"),
                "user",
                "secret",
                "ab",
                upload::send,
                post::post,
                post::trust,
            )
        }
        assertEquals(emptyList<String>(), upload.urls)
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun successReturnsS1() {
        val bytes = byteArrayOf(1, 2, 3)
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(
            listOf(
                200 to identityResponse,
                200 to namesResponse,
                200 to importCreated,
                200 to submissionCreated,
            ),
        )
        val choice = jmapSend(
            JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION)),
            bytes,
            "me@example.com",
            listOf("ada@example.com"),
            "user",
            "secret",
            "ab",
            upload::send,
            post::post,
            post::trust,
            sentName = "Sent",
        )
        val authorization = jmapBasicAuthorization("user", "secret")
        assertEquals("S1", choice)
        assertEquals(listOf("https://example.com/upload/A1/"), upload.urls)
        assertEquals(listOf("message/rfc822"), upload.types)
        assertTrue(bytes.contentEquals(upload.bodies[0]))
        assertEquals(listOf(authorization), upload.authorizations)
        assertEquals(
            List(4) { "https://example.com/jmap/" },
            post.urls,
        )
        assertEquals(
            listOf(
                jmapIdentityRequest("A1"),
                jmapMailboxNamesRequest("A1"),
                jmapEmailImportRequest("A1", "B1", "mbSent"),
                jmapEmailSubmissionRequest("A1", "M1", "me@example.com", listOf("ada@example.com"), identityId = "I1"),
            ),
            post.bodies,
        )
        assertEquals(List(4) { authorization }, post.authorizations)
    }

    @Test
    fun blankSentStagesInDraftsAndDestroysAfterSubmit() {
        val bytes = byteArrayOf(1, 2, 3)
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(
            listOf(
                200 to identityResponse,
                200 to draftsNames,
                200 to importCreated,
                200 to submissionCreated,
            ),
        )
        val choice = jmapSend(
            JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION)),
            bytes,
            "me@example.com",
            listOf("ada@example.com"),
            "user",
            "secret",
            "ab",
            upload::send,
            post::post,
            post::trust,
            draftsName = "Drafts",
        )
        val authorization = jmapBasicAuthorization("user", "secret")
        assertEquals("S1", choice)
        assertEquals(listOf("https://example.com/upload/A1/"), upload.urls)
        assertEquals(listOf("message/rfc822"), upload.types)
        assertTrue(bytes.contentEquals(upload.bodies[0]))
        assertEquals(listOf(authorization), upload.authorizations)
        assertEquals(
            List(4) { "https://example.com/jmap/" },
            post.urls,
        )
        assertEquals(
            listOf(
                jmapIdentityRequest("A1"),
                jmapMailboxNamesRequest("A1"),
                jmapEmailImportRequest("A1", "B1", "mbDrafts"),
                jmapEmailSubmissionRequest("A1", "M1", "me@example.com", listOf("ada@example.com"), destroyEmail = true, identityId = "I1"),
            ),
            post.bodies,
        )
        assertEquals(List(4) { authorization }, post.authorizations)
    }

    @Test
    fun blankSentWithoutAConfiguredDraftsFailsCleanlyBeforeUpload() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to identityResponse))
        try {
            jmapSend(JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION)), byteArrayOf(1), "me@example.com", listOf("ada@example.com"),
                "user", "secret", "ab", upload::send, post::post, post::trust)
            throw AssertionError("expected JmapNoDraftsFolder")
        } catch (e: JmapNoDraftsFolder) {
            assertEquals("a Drafts (Postponed) folder must be set to send over JMAP without a Sent folder", e.text)
        }
        assertEquals(emptyList<String>(), upload.urls)
        assertEquals(1, post.urls.size) // only the identity lookup; no Inbox or role fallback
    }

    @Test
    fun noIdentityForTheFromAddressFailsBeforeUpload() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to """{"methodResponses":[["Identity/get",{"list":[{"id":"I9","email":"other@example.com"}]},"0"]]}"""))
        try {
            jmapSend(JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION)), byteArrayOf(1), "Me@Example.com", listOf("ada@example.com"),
                "user", "secret", "ab", upload::send, post::post, post::trust, sentName = "Sent")
            throw AssertionError("expected JmapNoIdentity")
        } catch (e: JmapNoIdentity) {
            assertEquals("no JMAP identity matches the From address Me@Example.com", e.text)
        }
        assertEquals(emptyList<String>(), upload.urls)
        // Case-insensitive exact match, never the first identity.
        val list = """{"methodResponses":[["Identity/get",{"list":[{"id":"I9","email":"other@example.com"},{"id":"I1","email":"me@example.com"}]},"0"]]}"""
        assertEquals("I1", jmapMatchingIdentityId(list, "ME@example.com"))
        assertEquals(null, jmapMatchingIdentityId(list, "me@example.co"))
    }

    @Test
    fun queryStatus500RecordsNoUpload() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(500 to "no"))
        assertFails("jmap api status 500") {
            jmapSend(
                JmapOffer.Mail(session(JMAP_MAIL, JMAP_SUBMISSION)),
                byteArrayOf(1, 2, 3),
                "me@example.com",
                listOf("ada@example.com"),
                "user",
                "secret",
                "ab",
                upload::send,
                post::post,
                post::trust,
            )
        }
        assertEquals(emptyList<String>(), upload.urls)
    }

    private fun assertFails(message: String, call: () -> Unit) {
        try {
            call()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(message, failure.text)
        }
    }

    private fun session(vararg ids: String, account: String? = "A1") = JmapSession(
        username = "user@example.com",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download/{accountId}/{blobId}/{name}",
        uploadUrl = "https://example.com/upload/{accountId}/",
        eventSourceUrl = "https://example.com/event/?types={types}",
        state = "s1",
        capabilityIds = ids.toSet(),
        primaryMailAccountId = account,
    )

    private class SendUpload(
        private val code: Int,
        private val responseBody: String,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val types = mutableListOf<String>()
        val authorizations = mutableListOf<String>()

        fun send(
            url: String,
            bytes: ByteArray,
            contentType: String,
            authorization: String,
        ): JmapHttpExchange {
            urls.add(url)
            bodies.add(bytes)
            types.add(contentType)
            authorizations.add(authorization)
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))

                override fun header(name: String): String? = null

                override fun body(): String = responseBody

                override fun close() {}
            }
        }
    }

    private class SendPost(
        private val steps: List<Pair<Int, String>>,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String>()

        fun post(url: String, body: String, authorization: String): JmapHttpExchange {
            val step = steps[urls.size]
            urls.add(url)
            bodies.add(body)
            authorizations.add(authorization)
            return object : JmapHttpExchange {
                override val status: Int = step.first

                override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))

                override fun header(name: String): String? = null

                override fun body(): String = step.second

                override fun close() {}
            }
        }

        fun trust(host: String, ders: List<ByteArray>, pin: String): String = ""
    }

    private val sentResponse =
        """{"methodResponses":[["Mailbox/query",{"ids":["mbSent"]},"0"],["Mailbox/get",{"list":[{"id":"mbSent","role":"sent"}]},"1"]]}"""

    private val identityResponse =
        """{"methodResponses":[["Identity/get",{"list":[{"id":"I0","email":"x@example.com"},{"id":"I1","email":"me@example.com"}]},"0"]]}"""
    private val namesResponse =
        """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mbSent","name":"Sent","parentId":null}]},"0"]]}"""
    private val draftsNames =
        """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mbInbox","name":"Inbox","parentId":null,"role":"inbox"},{"id":"mbDrafts","name":"Drafts","parentId":null}]},"0"]]}"""
    private val uploadCreated =
        """{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":3}"""

    private val importCreated =
        """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"M1"}}},"0"]]}"""

    private val submissionCreated =
        """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":"S1"}}},"0"]]}"""
}
