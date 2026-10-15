package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.TlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class JmapSentMailboxTest {
    @Test
    fun sendUsesOnlyTheConfiguredSentNameNeverTheRoleOrGuesses() {
        val all = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"c1","name":"Sent","parentId":"p"},{"id":"t2","name":"Sent","parentId":null},{"id":"r","name":"Sent Items","parentId":null,"role":"sent"}]},"0"]]}"""
        val bodies = mutableListOf<String>()
        val post: (String, String, String) -> JmapHttpExchange = { _, body, _ ->
            bodies += body
            object : JmapHttpExchange {
                override val status = 200
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun header(name: String): String? = null
                override fun body() = all
                override fun close() {}
            }
        }
        val noTrust: (String, List<ByteArray>, String) -> String = { _, _, _ -> "" }
        assertEquals("t2", jmapFindSentMailbox(session("A1"), "user", "pw", "", post, noTrust, sentName = "Sent"))
        // Blank setting: no copy, and no server lookup at all (role metadata never decides).
        bodies.clear()
        assertEquals(null, jmapFindSentMailbox(session("A1"), "user", "pw", "", post, noTrust, sentName = ""))
        assertEquals(0, bodies.size)
        // Configured but missing: a clear error, never another folder.
        try {
            jmapFindSentMailbox(session("A1"), "user", "pw", "", post, noTrust, sentName = "Sent Messages")
            org.junit.Assert.fail()
        } catch (missing: JmapSentFolderMissing) {
            assertEquals("Sent Messages", missing.name)
        }
        assertEquals(null, jmapMailboxIdByPath(all, "Drafts"))
        // F148: a configured nested path resolves like the folder list names it.
        val nested = all.replace("\"parentId\":\"p\"", "\"parentId\":\"p\"},{\"id\":\"p\",\"name\":\"Archive\",\"parentId\":null")
        assertEquals("c1", jmapMailboxIdByPath(nested, "Archive/Sent"))
        assertEquals("t2", jmapMailboxIdByPath(nested, "Sent"))
        assertEquals("c1", jmapFindSentMailbox(session("A1"), "user", "pw", "", { _, _, _ -> object : JmapHttpExchange {
            override val status = 200
            override fun peerDer() = listOf(byteArrayOf(1))
            override fun header(name: String): String? = null
            override fun body() = nested
            override fun close() {}
        } }, noTrust, sentName = "Archive/Sent"))
        // A parent cycle never matches.
        assertEquals(null, jmapMailboxIdByPath("""{"methodResponses":[["Mailbox/get",{"list":[{"id":"a","name":"A","parentId":"b"},{"id":"b","name":"B","parentId":"a"}]},"0"]]}""", "B/A"))
    }

    @Test
    fun acceptInvalidFindsConfiguredSentWhenUntrusted() {
        val post = mailboxPost(sentNamesBody)
        val untrusted: (String, List<ByteArray>, String) -> String = { _, _, _ -> "certificate untrusted" }
        assertEquals(
            "t2",
            jmapFindSentMailbox(
                session("A1"), "user", "pw", "", post, untrusted, sentName = "Sent",
                policy = TlsPolicy(acceptInvalidCert = true),
            ),
        )
        assertFails("certificate untrusted") {
            jmapFindSentMailbox(session("A1"), "user", "pw", "", post, untrusted, sentName = "Sent")
        }
    }

    @Test
    fun acceptInvalidStillRejectsCertificateChangedOnSentLookup() {
        val changed: (String, List<ByteArray>, String) -> String = { _, _, _ -> "certificate changed" }
        assertFails("certificate changed") {
            jmapFindSentMailbox(
                session("A1"), "user", "pw", "", mailboxPost(sentNamesBody), changed, sentName = "Sent",
                policy = TlsPolicy(acceptInvalidCert = true),
            )
        }
    }

    @Test
    fun noCopySubmissionDestroysTheStagedEmail() {
        val plain = jmapEmailSubmissionRequest("A", "E", "a@b", listOf("c@d"))
        val staged = jmapEmailSubmissionRequest("A", "E", "a@b", listOf("c@d"), destroyEmail = true)
        assertEquals(false, plain.contains("onSuccessDestroyEmail"))
        assertEquals(true, staged.contains("\"onSuccessDestroyEmail\":[\"#k1\"]"))
    }

    @Test
    fun blankAccountPostsNothing() {
        val post = SentPost(200, sentResponse)
        for (account in listOf(null, "", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") {
                jmapFindSentMailbox(
                    session(account),
                    "user",
                    "secret",
                    "ab",
                    post::post,
                    post::trust,
                    sentName = "Sent",
                )
            }
        }
        assertEquals(emptyList<String>(), post.urls)
    }

    @Test
    fun status500Throws() {
        val post = SentPost(500, "no")
        assertFails("jmap api status 500") {
            jmapFindSentMailbox(
                session("A1"),
                "user",
                "secret",
                "ab",
                post::post,
                post::trust,
                sentName = "Sent",
            )
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

    private fun mailboxPost(body: String): (String, String, String) -> JmapHttpExchange = { _, _, _ ->
        object : JmapHttpExchange {
            override val status = 200
            override fun peerDer() = listOf(byteArrayOf(1))
            override fun header(name: String): String? = null
            override fun body() = body
            override fun close() {}
        }
    }

    private val sentNamesBody =
        """{"methodResponses":[["Mailbox/get",{"list":[{"id":"c1","name":"Sent","parentId":"p"},{"id":"t2","name":"Sent","parentId":null},{"id":"r","name":"Sent Items","parentId":null,"role":"sent"}]},"0"]]}"""

    private fun session(account: String?) = JmapSession(
        username = "user@example.com",
        apiUrl = "https://example.com/jmap/",
        downloadUrl = "https://example.com/download/{accountId}/{blobId}/{name}",
        uploadUrl = "https://example.com/upload/{accountId}/",
        eventSourceUrl = "https://example.com/event/?types={types}",
        state = "s1",
        capabilityIds = setOf(JMAP_MAIL),
        primaryMailAccountId = account,
    )

    private class SentPost(
        private val code: Int,
        private val responseBody: String,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String>()
        val pins = mutableListOf<String>()

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

        fun trust(host: String, ders: List<ByteArray>, pin: String): String {
            pins.add(pin)
            return ""
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

    private val sentRequest =
        """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Mailbox/query",{"accountId":"A1","filter":{"role":"sent"}},"0"],["Mailbox/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["id","role"]},"1"]]}"""

    private val sentResponse =
        """{"methodResponses":[["Mailbox/query",{"ids":["mbSent"]},"0"],["Mailbox/get",{"list":[{"id":"mbSent","role":"sent"}]},"1"]]}"""

    private val emptyIds =
        """{"methodResponses":[["Mailbox/query",{"ids":[]},"0"]]}"""

    private val missingQuery =
        """{"methodResponses":[["Mailbox/get",{"list":[{"id":"mbSent","role":"sent"}]},"1"]]}"""

    private val nonTextId =
        """{"methodResponses":[["Mailbox/query",{"ids":[1]},"0"]]}"""
}
