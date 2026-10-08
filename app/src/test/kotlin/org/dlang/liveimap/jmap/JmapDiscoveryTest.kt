package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapDiscoveryTest {
    @Test
    fun port443OmitsPort() {
        assertEquals(
            "https://example.com/.well-known/jmap",
            jmapDiscoveryUrl("example.com", 443),
        )
    }

    @Test
    fun otherPortsAreIncluded() {
        assertEquals(
            "https://example.com:8443/.well-known/jmap",
            jmapDiscoveryUrl("example.com", 8443),
        )
        assertEquals(
            "https://example.com:1/.well-known/jmap",
            jmapDiscoveryUrl("example.com", 1),
        )
        assertEquals(
            "https://example.com:65535/.well-known/jmap",
            jmapDiscoveryUrl("example.com", 65535),
        )
        assertEquals(
            "https://[2001:db8::1]:8443/.well-known/jmap",
            jmapDiscoveryUrl("2001:db8::1", 8443),
        )
    }

    @Test
    fun ipv6GainsOrKeepsBrackets() {
        val expected = "https://[2001:db8::1]/.well-known/jmap"
        assertEquals(expected, jmapDiscoveryUrl("2001:db8::1", 443))
        assertEquals(expected, jmapDiscoveryUrl("[2001:db8::1]", 443))
        assertEquals(
            "https://[2001:DB8::1]/.well-known/jmap",
            jmapDiscoveryUrl("2001:DB8::1", 443),
        )
    }

    @Test
    fun blankHostIsEmpty() {
        assertUrlFailure("", 443, "jmap host is empty")
        assertUrlFailure(" ", 443, "jmap host is empty")
        assertUrlFailure("\t", 443, "jmap host is empty")
        assertUrlFailure("\n", 443, "jmap host is empty")
    }

    @Test
    fun badHostCharacters() {
        assertUrlFailure("example.com/jmap", 443, "jmap host is not a name")
        assertUrlFailure("example\\com", 443, "jmap host is not a name")
        assertUrlFailure("ex ample.com", 443, "jmap host is not a name")
        assertUrlFailure(" user.example", 443, "jmap host is not a name")
        assertUrlFailure("user@example.com", 443, "jmap host is not a name")
        assertUrlFailure("https://example.com", 443, "jmap host is not a name")
        assertUrlFailure("example.com:443", 443, "jmap host is not a name")
    }

    @Test
    fun badIpv6Host() {
        assertUrlFailure("2001:db8::1g", 443, "jmap host is not a name")
        assertUrlFailure("2001:db8::1/64", 443, "jmap host is not a name")
        assertUrlFailure("[2001:db8::1", 443, "jmap host is not a name")
        assertUrlFailure("2001:db8::1]", 443, "jmap host is not a name")
        assertUrlFailure("[[2001:db8::1]]", 443, "jmap host is not a name")
        assertUrlFailure("[]", 443, "jmap host is not a name")
        assertUrlFailure("[2001:db8::1]extra", 443, "jmap host is not a name")
    }

    @Test
    fun portOutsideRange() {
        assertUrlFailure("example.com", 0, "jmap port is invalid")
        assertUrlFailure("example.com", -1, "jmap port is invalid")
        assertUrlFailure("example.com", 65536, "jmap port is invalid")
    }

    @Test
    fun mailBodyOnHttpsIsMail() {
        assertMail(discoverJmap(200, mailBody, "https://example.com/jmap/session"))
        assertMail(discoverJmap(200, mailBody, "HTTPS://example.com/session"))
    }

    @Test
    fun refusedOrUnreadableIsNone() {
        assertEquals(
            JmapOffer.None,
            discoverJmap(200, coreOnly, "https://example.com/jmap/session"),
        )
        assertEquals(
            JmapOffer.None,
            discoverJmap(404, "nope", "https://example.com/.well-known/jmap"),
        )
        assertEquals(
            JmapOffer.None,
            discoverJmap(200, "nope", "https://example.com/.well-known/jmap"),
        )
        assertEquals(
            JmapOffer.None,
            discoverJmap(200, mailBody, "http://example.com/.well-known/jmap"),
        )
        assertEquals(JmapOffer.None, discoverJmap(200, mailBody, ""))
        assertEquals(JmapOffer.None, discoverJmap(200, mailBody, " "))
        assertEquals(JmapOffer.None, discoverJmap(200, mailBody, "example.com/jmap"))
        assertEquals(
            JmapOffer.None,
            discoverJmap(200, mailBody, "https://exam ple.com/jmap"),
        )
        assertEquals(JmapOffer.None, discoverJmap(201, mailBody, "https://example.com/session"))
    }

    @Test
    fun discoverAtRequestsBuiltUrl() {
        var requested: String? = null
        val offer = discoverAt("example.com", 443) { url ->
            requested = url
            JmapHttpResult(200, mailBody, "https://example.com/jmap/session")
        }
        assertEquals("https://example.com/.well-known/jmap", requested)
        assertMail(offer)
    }

    @Test
    fun discoverAtRequestsNonDefaultPort() {
        var requested: String? = null
        var called = false
        val offer = discoverAt("example.com", 8443) { url ->
            called = true
            requested = url
            JmapHttpResult(404, "nope", url)
        }
        assertTrue(called)
        assertEquals("https://example.com:8443/.well-known/jmap", requested)
        assertEquals(JmapOffer.None, offer)
    }

    @Test
    fun discoverAtPropagatesFetchException() {
        val failure = IllegalStateException("fetch failed")
        try {
            discoverAt("example.com", 443) { throw failure }
            throw AssertionError("expected fetch failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
    }

    @Test
    fun discoverAtDoesNotFetchABadHost() {
        var called = false
        try {
            discoverAt(" ", 443) {
                called = true
                JmapHttpResult(200, mailBody, "https://example.com/session")
            }
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("jmap host is empty", failure.text)
        }
        assertFalse(called)
    }

    private fun assertMail(offer: JmapOffer) {
        when (offer) {
            is JmapOffer.Mail -> assertEquals("A1", offer.session.primaryMailAccountId)
            JmapOffer.None -> throw AssertionError("expected Mail")
        }
    }

    private fun assertUrlFailure(host: String, port: Int, message: String) {
        try {
            jmapDiscoveryUrl(host, port)
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(message, failure.text)
        }
    }
}

private val mailBody = """
    {
      "capabilities": {
        "urn:ietf:params:jmap:core": {},
        "urn:ietf:params:jmap:mail": {
          "maxMailboxesPerEmail": null,
          "maxSizeAttachmentsPerEmail": 10000000,
          "emailQuerySortOptions": ["receivedAt"],
          "mayCreateTopLevelMailbox": true
        }
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

private val coreOnly = """
    {
      "capabilities": {
        "urn:ietf:params:jmap:core": {}
      },
      "accounts": {},
      "primaryAccounts": {},
      "username": "user@example.com",
      "apiUrl": "https://example.com/jmap/",
      "downloadUrl": "https://example.com/download/{accountId}/{blobId}/{name}",
      "uploadUrl": "https://example.com/upload/{accountId}/",
      "eventSourceUrl": "https://example.com/event/?types={types}",
      "state": "s1"
    }
""".trimIndent()
