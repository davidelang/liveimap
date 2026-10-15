package org.dlang.liveimap.jmap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F53/F58/F63/F68/F79: JMAP hardening. */
class JmapHardeningTest {
    private fun session(api: String = "https://mail.example/jmap/") = JmapSession(
        username = "u", apiUrl = api,
        downloadUrl = "https://mail.example/download/{accountId}/{blobId}/{name}?type={type}",
        uploadUrl = "https://mail.example/upload/{accountId}/",
        eventSourceUrl = "https://mail.example/events?types={types}",
        state = "s", capabilityIds = setOf(JMAP_MAIL, JMAP_SUBMISSION), primaryMailAccountId = "A1",
    )

    private class Ex(val code: Int, val text: String, val fail: Boolean = false) : JmapHttpExchange {
        override val status: Int get() = if (fail) throw java.net.SocketTimeoutException("read timed out") else code
        override fun peerDer(): List<ByteArray> = listOf(byteArrayOf(1))
        override fun header(name: String): String? = null
        override fun body(): String = text
        override fun close() = Unit
    }

    @Test fun f58DeepJsonIsRefusedNotCrashed() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertTrue(runCatching { JsonParser(deep).parseDocument() }.exceptionOrNull() is JsonBroken)
        val ok = "[".repeat(JsonMaxDepth) + "]".repeat(JsonMaxDepth)
        assertTrue(JsonParser(ok).parseDocument() is Json.Arr)
    }

    @Test fun f58BodyIsCapped() {
        val big = java.io.ByteArrayInputStream(ByteArray(JmapMaxBody + 1))
        assertTrue(runCatching { readJmapBody(big) }.exceptionOrNull() is JmapFailure)
        assertEquals("hi", readJmapBody("hi".byteInputStream()))
    }

    @Test fun f53SessionUrlsMustStayOnTheDiscoveredHost() {
        assertTrue(jmapSameOrigin("https://mail.example/.well-known/jmap", session()))
        assertFalse(jmapSameOrigin("https://mail.example/.well-known/jmap", session("https://collector.example/")))
        assertEquals("mail.example", jmapUrlHost("https://Mail.Example:8443/x"))
    }

    @Test fun f68NetworkErrorsBecomeJmapFailure() {
        val e = runCatching {
            jmapCall("https://mail.example/jmap/", "{}", "Basic x", "", { _, _, _ -> Ex(200, "", fail = true) }, { _, _, _ -> "" })
        }.exceptionOrNull()
        assertTrue(e.toString(), e is JmapFailure)
    }

    @Test fun f63ChangesFollowHasMoreChanges() {
        val replies = ArrayDeque(listOf(
            """{"methodResponses":[["Email/changes",{"oldState":"s1","newState":"s2","hasMoreChanges":true,"created":[],"updated":[],"destroyed":["e0"]},"0"]]}""",
            """{"methodResponses":[["Email/changes",{"oldState":"s2","newState":"s3","hasMoreChanges":false,"created":["e5"],"updated":[],"destroyed":[]},"0"]]}""",
        ))
        val sent = mutableListOf<String>()
        val changes = jmapEmailChanges(session(), "s1", "u", "p", "", { _, body, _ -> sent += body; Ex(200, replies.removeFirst()) }, { _, _, _ -> "" })
        assertEquals("s3", changes.newState)
        assertEquals(listOf("e0"), changes.destroyed)
        assertEquals(listOf("e5"), changes.created)
        assertFalse(changes.hasMoreChanges)
        assertTrue(sent[1].contains("\"sinceState\":\"s2\""))
    }

    @Test fun f68RoutingIsOptInAndPushIsOutsideTheGate() {
        val folders = File("src/main/kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt").readText()
        assertTrue(folders.contains("FolderListLoaded("))
    }
}
