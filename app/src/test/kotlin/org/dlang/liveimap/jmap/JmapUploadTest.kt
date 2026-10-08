package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JmapUploadTest {
    @Test
    fun substitutesTheAccountId() {
        assertEquals(
            "https://example.com/upload/A1/",
            jmapUploadUrl("https://example.com/upload/{accountId}/", "A1"),
        )
        assertEquals(
            "https://example.com/upload/a%2Fb/",
            jmapUploadUrl("https://example.com/upload/{accountId}/", "a/b"),
        )
        assertEquals(
            "https://example.com/upload/a%20b/",
            jmapUploadUrl("https://example.com/upload/{accountId}/", "a b"),
        )
        assertEquals(
            "https://example.com/a%2Fb/a%2Fb",
            jmapUploadUrl("https://example.com/{accountId}/{accountId}", "a/b"),
        )
        assertEquals(
            "https://example.com/upload/a b/",
            jmapUploadUrl("https://example.com/upload/a b/", "A1"),
        )
        assertEquals(
            "https://example.com/upload/a~b._-c/",
            jmapUploadUrl("https://example.com/upload/{accountId}/", "a~b._-c"),
        )
    }

    @Test
    fun blankUrlOrAccountThrows() {
        for (blank in listOf("", " ", "\t", "\n")) {
            assertFails("jmap upload url is empty") { jmapUploadUrl(blank, "A1") }
            assertFails("jmap account id is empty") {
                jmapUploadUrl("https://example.com/upload/{accountId}/", blank)
            }
        }
    }

    @Test
    fun blobIdIsB1() {
        assertEquals(
            "B1",
            jmapBlobId("""{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":4}"""),
        )
    }

    @Test
    fun missingOrNonTextBlobIdThrows() {
        assertFails("jmap blob id is missing") { jmapBlobId("""{"accountId":"A1"}""") }
        assertFails("jmap blob id is missing") { jmapBlobId("""{"blobId":1}""") }
        assertFails("jmap blob id is missing") { jmapBlobId("""{"blobId":null}""") }
        assertFails("jmap blob id is missing") { jmapBlobId("""{"blobId":{"id":"B1"}}""") }
        assertFails("jmap blob id is missing") { jmapBlobId("[]") }
        assertFails("jmap blob id is missing") { jmapBlobId("nope") }
        assertFails("jmap blob id is missing") { jmapBlobId("") }
    }

    @Test
    fun blankBlobIdThrows() {
        for (id in listOf("", " ", "\t", "\n")) {
            assertFails("jmap blob id is empty") { jmapBlobId("""{"blobId":${quotedJson(id)}}""") }
        }
    }

    @Test
    fun a1UploadPostsOnceAndReturnsB1() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val ders = listOf(byteArrayOf(9, 8), byteArrayOf(7))
        val post = UploadPost(201, blobDocument, ders)
        val id = jmapUpload(
            uploadSession(),
            bytes,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("B1", id)
        assertEquals(listOf("https://example.com/upload/A1/"), post.urls)
        assertEquals(1, post.bodies.size)
        assertSame(bytes, post.bodies.single())
        assertEquals(listOf("message/rfc822"), post.types)
        assertEquals(listOf(jmapBasicAuthorization("user", "secret")), post.authorizations)
        assertEquals(listOf("https://example.com/upload/A1/"), post.reads)
        assertEquals(post.urls, post.closes)
        val seen = post.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun refusedUploadsPostNothing() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(201, blobDocument)
        assertFails("jmap upload is empty") {
            jmapUpload(uploadSession(), byteArrayOf(), "user", "secret", "ab", post::post, post::trust)
        }
        for (account in listOf(null, "", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapUpload(
                    uploadSession(account = account),
                    bytes,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
        }
        for (url in listOf("", " ", "\t", "\n")) {
            assertFails("jmap upload url is empty") {
                jmapUpload(
                    uploadSession(uploadUrl = url),
                    bytes,
                    "user",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
        }
        for (username in listOf("", " ", "\t", "\n")) {
            assertFails("jmap username is empty") {
                jmapUpload(
                    uploadSession(),
                    bytes,
                    username,
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                )
            }
        }
        assertEquals(emptyList<String>(), post.urls)
        assertEquals(emptyList<String>(), post.closes)
    }

    @Test
    fun nonHttpsPostsNothing() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        for (url in listOf(
            "http://example.com/upload/{accountId}/",
            "HTTP://example.com/upload/{accountId}/",
            "ftp://example.com/upload/{accountId}/",
        )) {
            val post = UploadPost(201, blobDocument)
            assertFails("jmap upload url is not https") {
                jmapUpload(uploadSession(uploadUrl = url), bytes, "user", "secret", "ab", post::post)
            }
            assertEquals(url, emptyList<String>(), post.urls)
        }
    }

    @Test
    fun emptyBytesWinOverABadUrl() {
        val post = UploadPost(201, blobDocument)
        assertFails("jmap upload is empty") {
            jmapUpload(
                uploadSession(uploadUrl = "http://example.com/upload/{accountId}/"),
                byteArrayOf(),
                "user",
                "secret",
                "ab",
                post::post,
            )
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun uppercaseHttpsPosts() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(201, blobDocument)
        val id = jmapUpload(
            uploadSession(uploadUrl = "HTTPS://example.com/upload/{accountId}/"),
            bytes,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("B1", id)
        assertEquals(listOf("HTTPS://example.com/upload/A1/"), post.urls)
        assertEquals("example.com", post.trusted.single().host)
    }

    @Test
    fun nullHostPostsThenFailsWithoutTrust() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(201, blobDocument)
        assertFails("jmap upload url is not https") {
            jmapUpload(
                uploadSession(uploadUrl = "https:///{accountId}"),
                bytes,
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
            )
        }
        assertEquals(listOf("https:///A1"), post.urls)
        assertEquals(emptyList<UploadSeen>(), post.trusted)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
    }

    @Test
    fun ipv6DropsOneBracketPair() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val ders = listOf(byteArrayOf(4))
        val post = UploadPost(201, blobDocument, ders)
        val id = jmapUpload(
            uploadSession(uploadUrl = "https://[2001:db8::1]/upload/{accountId}/"),
            bytes,
            "user",
            "secret",
            "ab",
            post::post,
            post::trust,
        )
        assertEquals("B1", id)
        assertEquals("2001:db8::1", post.trusted.single().host)
        assertSame(ders, post.trusted.single().ders)
        assertEquals("ab", post.trusted.single().pin)
    }

    @Test
    fun trustFailureClosesWithoutReading() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(201, blobDocument)
        assertFails("certificate changed") {
            jmapUpload(
                uploadSession(),
                bytes,
                "user",
                "secret",
                "ab",
                post::post,
            ) { _, _, _ -> "certificate changed" }
        }
        assertEquals(listOf("https://example.com/upload/A1/"), post.urls)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(201, blobDocument, ders = emptyList())
        assertFails("empty certificate chain") {
            jmapUpload(uploadSession(), bytes, "user", "secret", "ab", post::post)
        }
        assertEquals(listOf("https://example.com/upload/A1/"), post.urls)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
    }

    @Test
    fun redirectClosesWithoutReading() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        for (code in listOf(301, 302, 303, 307, 308)) {
            val post = UploadPost(code, blobDocument)
            assertFails("jmap upload redirect") {
                jmapUpload(uploadSession(), bytes, "user", "secret", "ab", post::post, post::trust)
            }
            assertEquals("$code", listOf("https://example.com/upload/A1/"), post.urls)
            assertEquals("$code", emptyList<String>(), post.reads)
            assertEquals(post.urls, post.closes)
        }
    }

    @Test
    fun status500Throws() {
        val bytes = "mail".toByteArray(Charsets.UTF_8)
        val post = UploadPost(500, "no")
        assertFails("jmap upload status 500") {
            jmapUpload(uploadSession(), bytes, "user", "secret", "ab", post::post, post::trust)
        }
        assertEquals(listOf("https://example.com/upload/A1/"), post.urls)
        assertEquals(emptyList<String>(), post.reads)
        assertEquals(post.urls, post.closes)
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
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun uploadSession(
        uploadUrl: String = "https://example.com/upload/{accountId}/",
        account: String? = "A1",
    ): JmapSession {
        return JmapSession(
            username = "user",
            apiUrl = "https://example.com/jmap/",
            downloadUrl = "https://example.com/download",
            uploadUrl = uploadUrl,
            eventSourceUrl = "https://example.com/event",
            state = "s",
            capabilityIds = setOf(JMAP_MAIL),
            primaryMailAccountId = account,
        )
    }

    private class UploadPost(
        private val code: Int,
        private val responseBody: String,
        private val ders: List<ByteArray> = listOf(byteArrayOf(1)),
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val types = mutableListOf<String>()
        val authorizations = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val closes = mutableListOf<String>()
        val trusted = mutableListOf<UploadSeen>()

        fun post(
            url: String,
            bytes: ByteArray,
            contentType: String,
            authorization: String,
        ): JmapHttpExchange {
            urls.add(url)
            bodies.add(bytes)
            types.add(contentType)
            authorizations.add(authorization)
            val posted = url
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = ders

                override fun header(name: String): String? = null

                override fun body(): String {
                    reads.add(posted)
                    return responseBody
                }

                override fun close() {
                    closes.add(posted)
                }
            }
        }

        fun trust(host: String, ders: List<ByteArray>, pin: String): String {
            trusted.add(UploadSeen(host, ders, pin))
            return ""
        }
    }

    private data class UploadSeen(
        val host: String,
        val ders: List<ByteArray>,
        val pin: String,
    )
}

private const val blobDocument =
    """{"accountId":"A1","blobId":"B1","type":"message/rfc822","size":4}"""
