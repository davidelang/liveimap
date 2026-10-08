package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapSessionTest {
    @Test
    fun sampleOffersMail() {
        val session = parseJmapSession(sample)
        assertTrue(session.offersMail())
        assertEquals("A1", session.primaryMailAccountId)
        assertEquals("user@example.com", session.username)
        assertEquals("https://example.com/jmap/", session.apiUrl)
        assertEquals(
            "https://example.com/download/{accountId}/{blobId}/{name}",
            session.downloadUrl,
        )
        assertEquals("https://example.com/upload/{accountId}/", session.uploadUrl)
        assertEquals("https://example.com/event/?types={types}", session.eventSourceUrl)
        assertEquals("s1", session.state)
        assertEquals(setOf("urn:ietf:params:jmap:core", JMAP_MAIL), session.capabilityIds)
    }

    @Test
    fun coreOnlyDoesNotOfferMail() {
        val session = parseJmapSession(coreOnly)
        assertFalse(session.offersMail())
        assertNull(session.primaryMailAccountId)
        assertEquals(setOf("urn:ietf:params:jmap:core"), session.capabilityIds)
        assertEquals("user@example.com", session.username)
    }

    @Test
    fun missingPrimaryMailAccountIsNull() {
        val session = parseJmapSession(sessionText(primaryAccounts = "{}"))
        assertTrue(session.offersMail())
        assertNull(session.primaryMailAccountId)
    }

    @Test
    fun blankTextIsEmpty() {
        assertFails("", "jmap session is empty")
        assertFails(" \n", "jmap session is empty")
    }

    @Test
    fun nonObject() {
        assertFails("[]", "jmap session is not an object")
        assertFails("nope", "jmap session is not an object")
        assertFails("{", "jmap session is not an object")
        assertFails(sample + "x", "jmap session is not an object")
    }

    @Test
    fun trailingWhitespaceIsAllowed() {
        val session = parseJmapSession(" \n$sample\n\t")
        assertTrue(session.offersMail())
        assertEquals("A1", session.primaryMailAccountId)
    }

    @Test
    fun missingField() {
        for (name in fieldNames) {
            assertFails(sessionText(omit = name), "jmap session lacks $name")
        }
        assertFails(
            sample.replace("\"apiUrl\": \"https://example.com/jmap/\",\n", ""),
            "jmap session lacks apiUrl",
        )
    }

    @Test
    fun apiUrlNotText() {
        assertFails(sessionText(apiUrl = "1"), "jmap session apiUrl is not text")
        assertFails(
            sample.replace("\"apiUrl\": \"https://example.com/jmap/\"", "\"apiUrl\": 1"),
            "jmap session apiUrl is not text",
        )
    }

    @Test
    fun objectFieldsRejectNonObjects() {
        assertFails(sessionText(accounts = "[]"), "jmap session accounts is not an object")
        assertFails(sessionText(capabilities = "[]"), "jmap session capabilities is not an object")
        assertFails(
            sessionText(primaryAccounts = "[]"),
            "jmap session primaryAccounts is not an object",
        )
        assertFails(
            sample.replace("\"accounts\": {}", "\"accounts\": []"),
            "jmap session accounts is not an object",
        )
    }

    @Test
    fun stringEscapes() {
        val escaped = "\"a\\\"b\\\\c\\/\\b\\f\\n\\r\\t\\u00e9\\u00C9\\uD83D\\uDE00\""
        val session = parseJmapSession(sessionText(username = escaped, state = "\"s\\u00e9\""))
        assertEquals("a\"b\\c/\b\u000c\n\r\t\u00e9\u00C9\uD83D\uDE00", session.username)
        assertEquals("s\u00e9", session.state)
        assertEquals(
            "a\"b",
            parseJmapSession(sessionText(username = "\"a\\\"b\"")).username,
        )
        assertEquals(
            "s\u00e9",
            parseJmapSession(
                sample.replace("\"state\": \"s1\"", "\"state\": \"s\\u00e9\""),
            ).state,
        )
    }

    @Test
    fun primaryMailAccountNotText() {
        assertFails(
            sessionText(primaryAccounts = "{\"$JMAP_MAIL\": 2}"),
            "jmap session primary mail account is not text",
        )
        assertFails(
            sample.replace(
                "\"urn:ietf:params:jmap:mail\": \"A1\"",
                "\"urn:ietf:params:jmap:mail\": 2",
            ),
            "jmap session primary mail account is not text",
        )
    }

    @Test
    fun repeatedUsernameKeepsLater() {
        val text = sessionText(extra = "\"username\": \"later@example.com\"")
        assertEquals("later@example.com", parseJmapSession(text).username)
        val rewritten = sample.replace(
            "\"username\": \"user@example.com\"",
            "\"username\": \"first@example.com\", \"username\": \"later@example.com\"",
        )
        val session = parseJmapSession(rewritten)
        assertEquals("later@example.com", session.username)
        assertTrue(session.offersMail())
        assertEquals("A1", session.primaryMailAccountId)
    }

    @Test
    fun skippedValuesParse() {
        val capabilities = "{\"$JMAP_MAIL\": {\"a\": false, \"b\": [true, null, 1, {\"c\": false}]}}"
        val accounts = "{\"A1\": {\"name\": \"a\\\"b\"}}"
        val session = parseJmapSession(
            sessionText(capabilities = capabilities, accounts = accounts),
        )
        assertTrue(session.offersMail())
        assertEquals("A1", session.primaryMailAccountId)
        val falseCapability = parseJmapSession(
            sessionText(capabilities = "{\"$JMAP_MAIL\": false}"),
        )
        assertTrue(falseCapability.offersMail())
    }

    private fun assertFails(text: String, message: String) {
        try {
            parseJmapSession(text)
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(message, failure.text)
        }
    }

    private fun sessionText(
        capabilities: String =
            "{\"$CORE\": {}, \"$JMAP_MAIL\": {\"maxMailboxesPerEmail\": null, " +
                "\"maxSizeAttachmentsPerEmail\": 10000000, " +
                "\"emailQuerySortOptions\": [\"receivedAt\"], " +
                "\"mayCreateTopLevelMailbox\": true}}",
        accounts: String = "{}",
        primaryAccounts: String = "{\"$JMAP_MAIL\": \"A1\"}",
        username: String = "\"user@example.com\"",
        apiUrl: String = "\"https://example.com/jmap/\"",
        downloadUrl: String = "\"https://example.com/download/{accountId}/{blobId}/{name}\"",
        uploadUrl: String = "\"https://example.com/upload/{accountId}/\"",
        eventSourceUrl: String = "\"https://example.com/event/?types={types}\"",
        state: String = "\"s1\"",
        omit: String? = null,
        extra: String? = null,
    ): String {
        val entries = ArrayList<String>()
        fun put(name: String, value: String) {
            if (name != omit) entries.add("\"$name\": $value")
        }
        put("capabilities", capabilities)
        put("accounts", accounts)
        put("primaryAccounts", primaryAccounts)
        put("username", username)
        put("apiUrl", apiUrl)
        put("downloadUrl", downloadUrl)
        put("uploadUrl", uploadUrl)
        put("eventSourceUrl", eventSourceUrl)
        put("state", state)
        if (extra != null) entries.add(extra)
        return "{" + entries.joinToString(",") + "}"
    }
}

private const val CORE = "urn:ietf:params:jmap:core"

private val fieldNames = listOf(
    "capabilities",
    "accounts",
    "primaryAccounts",
    "username",
    "apiUrl",
    "downloadUrl",
    "uploadUrl",
    "eventSourceUrl",
    "state",
)

private val sample = """
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
