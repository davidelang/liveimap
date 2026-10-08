package org.dlang.liveimap.jmap

import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class JmapAccountTest {
    @Test
    fun blankHostIsNoneAndDoesNotOpen() {
        for (host in listOf("", " ", "\t", "\n")) {
            var opened = false
            val offer = offerForAccount(
                AccountSettings(imapHost = host),
                open = {
                    opened = true
                    error("opened")
                },
            )
            assertEquals(host, JmapOffer.None, offer)
            assertFalse(host, opened)
        }
    }

    @Test
    fun exampleHostOffersMailOnPort443() {
        val url = "https://example.com/.well-known/jmap"
        val ders = listOf(byteArrayOf(9, 8), byteArrayOf(7))
        val script = Script(url, 200, mailBody, ders)
        val offer = offerForAccount(
            AccountSettings(imapHost = "example.com", imapPort = 993, certPin = "ab"),
            script::open,
            script::trust,
        )
        assertMail(offer)
        assertEquals(listOf(url), script.opens)
        assertEquals(listOf(url), script.reads)
        val seen = script.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("ab", seen.pin)
    }

    @Test
    fun notFoundIsNone() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(url, 404, "missing", listOf(byteArrayOf(1)))
        val offer = offerForAccount(
            AccountSettings(imapHost = "example.com", imapPort = 993, certPin = "ab"),
            script::open,
            script::trust,
        )
        assertEquals(JmapOffer.None, offer)
        assertEquals(listOf(url), script.opens)
    }

    @Test
    fun certificateChangedDoesNotReadBody() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(url, 200, "secret", listOf(byteArrayOf(1)))
        try {
            offerForAccount(
                AccountSettings(imapHost = "example.com", certPin = "ab"),
                script::open,
            ) { _, _, _ -> "certificate changed" }
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("certificate changed", failure.text)
        }
        assertEquals(listOf(url), script.opens)
        assertEquals(emptyList<String>(), script.reads)
    }

    @Test
    fun redirectLimitPropagates() {
        val failure = JmapFailure("jmap redirect limit")
        var trusted = false
        try {
            offerForAccount(
                AccountSettings(imapHost = "example.com", certPin = "ab"),
                open = { throw failure },
            ) { _, _, _ ->
                trusted = true
                ""
            }
            throw AssertionError("expected JmapFailure")
        } catch (caught: JmapFailure) {
            assertSame(failure, caught)
        }
        assertFalse(trusted)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(url, 200, mailBody, emptyList())
        try {
            offerForAccount(
                AccountSettings(imapHost = "example.com"),
                script::open,
            )
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("empty certificate chain", failure.text)
        }
        assertEquals(emptyList<String>(), script.reads)
    }

    private fun assertMail(offer: JmapOffer) {
        when (offer) {
            is JmapOffer.Mail -> assertEquals("A1", offer.session.primaryMailAccountId)
            JmapOffer.None -> throw AssertionError("expected Mail")
        }
    }
}

private val mailBody = """
    {
      "capabilities": {
        "urn:ietf:params:jmap:core": {},
        "urn:ietf:params:jmap:mail": {}
      },
      "accounts": {},
      "primaryAccounts": { "urn:ietf:params:jmap:mail": "A1" },
      "username": "user@example.com",
      "apiUrl": "https://example.com/jmap/",
      "downloadUrl": "https://example.com/download/{accountId}/{blobId}/{name}",
      "uploadUrl": "https://example.com/upload/{accountId}/",
      "eventSourceUrl": "https://example.com/event/?types={types}",
      "state": "s1"
    }
""".trimIndent()

private data class SeenTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private class Script(
    private val url: String,
    private val status: Int,
    private val body: String,
    private val ders: List<ByteArray>,
) {
    val opens = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val trusted = mutableListOf<SeenTrust>()

    fun open(requested: String): JmapHttpExchange {
        opens.add(requested)
        if (requested != url) error("opened $requested")
        return object : JmapHttpExchange {
            override val status: Int = this@Script.status

            override fun peerDer(): List<ByteArray> = ders

            override fun header(name: String): String? = null

            override fun body(): String {
                reads.add(requested)
                return body
            }

            override fun close() = Unit
        }
    }

    fun trust(host: String, ders: List<ByteArray>, pin: String): String {
        trusted.add(SeenTrust(host, ders, pin))
        return ""
    }
}
