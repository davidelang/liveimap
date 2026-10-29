package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
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
}
