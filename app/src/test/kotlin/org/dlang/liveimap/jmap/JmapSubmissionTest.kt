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

    @Test
    fun submitPostsTheA1RequestAndReturnsS1() {
        val post = SubmitPost(200, createdResponse)
        val account = session(JMAP_MAIL, JMAP_SUBMISSION)
        val recipients = listOf("ada@example.com", "bob@example.com")
        val id = jmapSubmit(
            account,
            "M1",
            "me@example.com",
            recipients,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("S1", id)
        assertEquals(listOf(account.apiUrl), post.urls)
        assertEquals(
            listOf(jmapEmailSubmissionRequest("A1", "M1", "me@example.com", recipients)),
            post.bodies,
        )
        assertEquals(listOf(jmapBasicAuthorization("user", "secret")), post.authorizations)
        assertEquals(listOf(submissionRequest), post.bodies)
    }

    @Test
    fun notCreatedDescriptionIsThrownUnchanged() {
        val post = SubmitPost(200, notCreatedResponse)
        assertFails("over quota") {
            jmapSubmit(
                session(JMAP_SUBMISSION),
                "M1",
                "me@example.com",
                listOf("ada@example.com", "bob@example.com"),
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(1, post.urls.size)
    }

    @Test
    fun refusedCallsDoNotPost() {
        val recipients = listOf("ada@example.com", "bob@example.com")
        for (ids in listOf(emptyArray<String>(), arrayOf(JMAP_MAIL))) {
            val post = SubmitPost(200, createdResponse)
            assertFails("jmap submission is not offered") {
                jmapSubmit(
                    session(*ids),
                    " ",
                    " ",
                    recipients,
                    " ",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
            assertEquals(emptyList<String>(), post.urls)
        }
        for (account in listOf(null, "", " ", "\t", "\n")) {
            val post = SubmitPost(200, createdResponse)
            assertFails("jmap account id is empty") {
                jmapSubmit(
                    session(JMAP_SUBMISSION, account = account),
                    " ",
                    "me@example.com",
                    recipients,
                    " ",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
            assertEquals("$account", emptyList<String>(), post.urls)
        }
        for (email in listOf("", " ", "\t", "\n")) {
            val post = SubmitPost(200, createdResponse)
            assertFails("jmap message id is empty") {
                jmapSubmit(
                    session(JMAP_SUBMISSION),
                    email,
                    "me@example.com",
                    recipients,
                    " ",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
            assertEquals(email, emptyList<String>(), post.urls)
        }
        for (username in listOf("", " ", "\t", "\n")) {
            val post = SubmitPost(200, createdResponse)
            assertFails("jmap username is empty") {
                jmapSubmit(
                    session(JMAP_SUBMISSION),
                    "M1",
                    "me@example.com",
                    recipients,
                    username,
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
            assertEquals(username, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun status500Throws() {
        val post = SubmitPost(500, "no")
        assertFails("jmap api status 500") {
            jmapSubmit(
                session(JMAP_SUBMISSION),
                "M1",
                "me@example.com",
                listOf("ada@example.com", "bob@example.com"),
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf("https://example.com/jmap/"), post.urls)
    }

    @Test
    fun deliverUploadsImportsAndReturnsS1() {
        val bytes = byteArrayOf(1, 2, 3)
        val upload = DeliverUpload(201, uploadCreated)
        val post = DeliverPost(listOf(200 to importCreated, 200 to submissionCreated))
        val account = session(JMAP_SUBMISSION)
        val recipients = listOf("ada@example.com")
        val id = jmapDeliver(
            account,
            bytes,
            "mbSent",
            "me@example.com",
            recipients,
            "user",
            "secret",
            "ab",
            upload::send,
            post::post,
            post::trust,
        )
        assertEquals("S1", id)
        assertEquals(listOf("https://example.com/upload/A1/"), upload.urls)
        assertEquals(1, upload.bodies.size)
        assertTrue(bytes.contentEquals(upload.bodies[0]))
        assertEquals(listOf("message/rfc822"), upload.types)
        assertEquals(listOf(jmapBasicAuthorization("user", "secret")), upload.authorizations)
        assertEquals(listOf(account.apiUrl, account.apiUrl), post.urls)
        assertEquals(
            listOf(
                jmapEmailImportRequest("A1", "B1", "mbSent"),
                jmapEmailSubmissionRequest("A1", "M1", "me@example.com", recipients),
            ),
            post.bodies,
        )
        assertEquals(listOf(jmapBasicAuthorization("user", "secret"), jmapBasicAuthorization("user", "secret")), post.authorizations)
    }

    @Test
    fun refusedDeliverCallsNeitherCallback() {
        val bytes = byteArrayOf(1, 2, 3)
        for (ids in listOf(emptyArray<String>(), arrayOf(JMAP_MAIL))) {
            val upload = DeliverUpload(201, uploadCreated)
            val post = DeliverPost(listOf(200 to importCreated, 200 to createdResponse))
            assertFails("jmap submission is not offered") {
                jmapDeliver(
                    session(*ids),
                    bytes,
                    "mbSent",
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
        for (mailbox in listOf("", " ", "\t", "\n")) {
            val upload = DeliverUpload(201, uploadCreated)
            val post = DeliverPost(listOf(200 to importCreated))
            assertFails("jmap mailbox id is empty") {
                jmapDeliver(
                    session(JMAP_SUBMISSION),
                    bytes,
                    mailbox,
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
            assertEquals(mailbox, emptyList<String>(), upload.urls)
            assertEquals(mailbox, emptyList<String>(), post.urls)
        }
        for (from in listOf("", " ", "\t", "\n")) {
            val upload = DeliverUpload(201, uploadCreated)
            val post = DeliverPost(listOf(200 to importCreated))
            assertFails("jmap submission from is empty") {
                jmapDeliver(
                    session(JMAP_SUBMISSION),
                    bytes,
                    "mbSent",
                    from,
                    listOf("ada@example.com"),
                    "user",
                    "secret",
                    "ab",
                    upload::send,
                    post::post,
                    post::trust,
                )
            }
            assertEquals(from, emptyList<String>(), upload.urls)
            assertEquals(from, emptyList<String>(), post.urls)
        }
        val emptyRecipients = DeliverUpload(201, uploadCreated)
        val emptyPost = DeliverPost(listOf(200 to importCreated))
        assertFails("jmap submission recipient is empty") {
            jmapDeliver(
                session(JMAP_SUBMISSION),
                bytes,
                "mbSent",
                "me@example.com",
                emptyList(),
                "user",
                "secret",
                "ab",
                emptyRecipients::send,
                emptyPost::post,
                emptyPost::trust,
            )
        }
        assertEquals(emptyList<String>(), emptyRecipients.urls)
        assertEquals(emptyList<String>(), emptyPost.urls)
        for (recipient in listOf("", " ", "\t", "\n")) {
            val upload = DeliverUpload(201, uploadCreated)
            val post = DeliverPost(listOf(200 to importCreated))
            assertFails("jmap submission recipient is empty") {
                jmapDeliver(
                    session(JMAP_SUBMISSION),
                    bytes,
                    "mbSent",
                    "me@example.com",
                    listOf(recipient),
                    "user",
                    "secret",
                    "ab",
                    upload::send,
                    post::post,
                    post::trust,
                )
            }
            assertEquals(recipient, emptyList<String>(), upload.urls)
            assertEquals(recipient, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun failedUploadCallsNoJsonPost() {
        val bytes = byteArrayOf(1, 2, 3)
        val upload = DeliverUpload(500, "no")
        val post = DeliverPost(listOf(200 to importCreated, 200 to createdResponse))
        assertFails("jmap upload status 500") {
            jmapDeliver(
                session(JMAP_SUBMISSION),
                bytes,
                "mbSent",
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
        assertEquals(listOf("https://example.com/upload/A1/"), upload.urls)
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun failedImportCallsNoSubmissionPost() {
        val bytes = byteArrayOf(1, 2, 3)
        val upload = DeliverUpload(201, uploadCreated)
        val post = DeliverPost(listOf(500 to "no", 200 to createdResponse))
        assertFails("jmap api status 500") {
            jmapDeliver(
                session(JMAP_SUBMISSION),
                bytes,
                "mbSent",
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
        assertEquals(1, upload.urls.size)
        assertEquals(listOf(jmapEmailImportRequest("A1", "B1", "mbSent")), post.bodies)
        val unread = DeliverUpload(201, uploadCreated)
        val badImport = DeliverPost(
            listOf(
                200 to """{"methodResponses":[["Email/import",{"notCreated":{"k1":{"description":"over quota"}}},"0"]]}""",
                200 to createdResponse,
            ),
        )
        assertFails("over quota") {
            jmapDeliver(
                session(JMAP_SUBMISSION),
                bytes,
                "mbSent",
                "me@example.com",
                listOf("ada@example.com"),
                "user",
                "secret",
                "ab",
                unread::send,
                badImport::post,
                badImport::trust,
            )
        }
        assertEquals(1, badImport.bodies.size)
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

    private class SubmitPost(
        private val code: Int,
        private val responseBody: String,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String>()

        fun post(url: String, body: String, authorization: String): JmapHttpExchange {
            urls.add(url)
            bodies.add(body)
            authorizations.add(authorization)
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))

                override fun header(name: String): String? = null

                override fun body(): String = responseBody

                override fun close() {}
            }
        }

        fun trust(host: String, ders: List<ByteArray>, pin: String): String = ""
    }

    private class DeliverUpload(
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

    private class DeliverPost(
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

    private val uploadCreated =
        """{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":3}"""

    private val importCreated =
        """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"M1"}}},"0"]]}"""

    private val submissionCreated =
        """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":"S1"}}},"0"]]}"""

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
