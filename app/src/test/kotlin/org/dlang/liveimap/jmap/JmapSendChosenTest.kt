package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSendChosenTest {
    @Test
    fun offReturnsSmtpAndCallsNothing() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to sentResponse))
        val choice = jmapSendChosen(
            false,
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
        assertEquals(JmapSendChoice.Smtp, choice)
        assertEquals(emptyList<String>(), upload.urls)
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun noneThrowsAndCallsNothing() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(200 to sentResponse))
        assertFails("jmap submission is not offered") {
            jmapSendChosen(
                true,
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
            jmapSendChosen(
                true,
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
            jmapSendChosen(
                true,
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
                200 to sentResponse,
                200 to importCreated,
                200 to submissionCreated,
            ),
        )
        val choice = jmapSendChosen(
            true,
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
        )
        val authorization = jmapBasicAuthorization("user", "secret")
        assertEquals(JmapSendChoice.Submitted("S1"), choice)
        assertEquals(listOf("https://example.com/upload/A1/"), upload.urls)
        assertEquals(listOf("message/rfc822"), upload.types)
        assertTrue(bytes.contentEquals(upload.bodies[0]))
        assertEquals(listOf(authorization), upload.authorizations)
        assertEquals(
            listOf(
                "https://example.com/jmap/",
                "https://example.com/jmap/",
                "https://example.com/jmap/",
            ),
            post.urls,
        )
        assertEquals(
            listOf(
                jmapSentMailboxRequest("A1"),
                jmapEmailImportRequest("A1", "B1", "mbSent"),
                jmapEmailSubmissionRequest("A1", "M1", "me@example.com", listOf("ada@example.com")),
            ),
            post.bodies,
        )
        assertEquals(listOf(authorization, authorization, authorization), post.authorizations)
    }

    @Test
    fun queryStatus500RecordsNoUpload() {
        val upload = SendUpload(201, uploadCreated)
        val post = SendPost(listOf(500 to "no"))
        assertFails("jmap api status 500") {
            jmapSendChosen(
                true,
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

    private val uploadCreated =
        """{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":3}"""

    private val importCreated =
        """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"M1"}}},"0"]]}"""

    private val submissionCreated =
        """{"methodResponses":[["EmailSubmission/set",{"created":{"k1":{"id":"S1"}}},"0"]]}"""
}
