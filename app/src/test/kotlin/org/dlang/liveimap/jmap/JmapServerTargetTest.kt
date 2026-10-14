package org.dlang.liveimap.jmap

import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapServerTargetTest {
    private fun url(field: String) = jmapServerTarget(field).discoveryUrl

    @Test
    fun hostAloneUses443() {
        assertEquals(JmapServerTarget("mail.example", 443, "https://mail.example/.well-known/jmap"), jmapServerTarget("mail.example"))
        assertEquals("https://mail.example/.well-known/jmap", url("  mail.example "))
    }

    @Test
    fun hostPortAndIpv6Forms() {
        assertEquals(JmapServerTarget("10.2.3.133", 33110, "https://10.2.3.133:33110/.well-known/jmap"), jmapServerTarget("10.2.3.133:33110"))
        assertEquals("https://mail.example/.well-known/jmap", url("mail.example:443"))
        assertEquals("https://[::1]:8443/.well-known/jmap", url("[::1]:8443"))
        assertEquals("https://[::1]/.well-known/jmap", url("[::1]"))
        assertEquals("https://[fe80::2]/.well-known/jmap", url("fe80::2"))
    }

    @Test
    fun httpsUrls() {
        assertEquals(JmapServerTarget("mail.example", 33110, "https://mail.example:33110/.well-known/jmap"), jmapServerTarget("https://mail.example:33110"))
        assertEquals("https://mail.example/.well-known/jmap", url("https://mail.example/"))
        assertEquals("https://mail.example:33110/jmap/session", url("HTTPS://mail.example:33110/jmap/session"))
        assertEquals("https://[::1]:9/jmap", url("https://[::1]:9/jmap"))
        assertEquals("https://mail.example/jmap", url("https://mail.example:443/jmap"))
    }

    @Test
    fun refused() {
        for (bad in listOf("", " ", "https://u:p@mail.example", "https://mail.example/?x=1", "https://mail.example/#f",
                "mail.example:0", "mail.example:65536", "mail.example:", "mail.example:12a", "mail.example:99999999", "[::1", "[::1]x",
                "[::1]:", "mail example", "a/b", "user@mail.example", "ftp://mail.example", "https://")) {
            try { jmapServerTarget(bad); fail(bad) } catch (_: JmapFailure) {}
        }
    }

    @Test
    fun offerForAccountFetchesTheFieldsUrl() {
        val opened = mutableListOf<String>()
        offerForAccount(AccountSettings(imapHost = "10.2.3.133:33110"), open = { u ->
            opened += u
            object : JmapHttpExchange {
                override val status = 404
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun header(name: String): String? = null
                override fun body() = ""
                override fun close() {}
            }
        }, trust = { _, _, _ -> "" })
        assertEquals(listOf("https://10.2.3.133:33110/.well-known/jmap"), opened)
    }

    @After fun forget() = JmapPlainHttp.clear()

    private fun exchange(status: Int, body: String = "", location: String? = null, onDer: () -> Unit = {}) = object : JmapHttpExchange {
        override val status = status
        override fun peerDer(): List<ByteArray> { onDer(); return emptyList() }
        override fun header(name: String): String? = if (name == "Location") location else null
        override fun body() = body
        override fun close() {}
    }

    @Test
    fun plainHttpUrlsAreAcceptedWhenTyped() {
        assertEquals(JmapServerTarget("10.2.3.133", 31923, "http://10.2.3.133:31923/.well-known/jmap", true), jmapServerTarget("http://10.2.3.133:31923"))
        assertEquals(JmapServerTarget("mail.example", 80, "http://mail.example/jmap", true), jmapServerTarget("HTTP://mail.example:80/jmap"))
        assertFalse(jmapServerTarget("https://mail.example").plainHttp)
    }

    @Test
    fun onlyTheConfiguredOriginMayBePlain() {
        assertFalse(jmapUrlAllowed("http://mail.example:31923/jmap"))
        JmapPlainHttp.allow("http://mail.example:31923/.well-known/jmap")
        assertTrue(jmapUrlAllowed("http://mail.example:31923/jmap/"))
        assertTrue(jmapUrlAllowed("http://MAIL.example:31923/jmap/{accountId}"))
        assertFalse(jmapUrlAllowed("http://mail.example/jmap"))      // port 80 is another origin
        assertFalse(jmapUrlAllowed("http://other.example:31923/jmap"))
        assertTrue(jmapUrlAllowed("https://other.example/jmap"))
        assertFalse(jmapUrlAllowed("ftp://mail.example:31923/"))
    }

    @Test
    fun plainDiscoveryFollowsItsOwnRedirectAndSkipsCertificates() {
        val opened = mutableListOf<String>()
        var trusted = 0
        val session = """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},"accounts":{"a":{}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"a"},"username":"u","apiUrl":"http://h.example:31923/jmap/","downloadUrl":"http://h.example:31923/jmap/download/{accountId}/{blobId}/{name}?accept={type}","uploadUrl":"http://h.example:31923/jmap/upload/{accountId}/","eventSourceUrl":"http://h.example:31923/jmap/eventsource/","state":"0"}"""
        val offer = offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { u ->
            opened += u
            if (u.endsWith("/.well-known/jmap")) exchange(301, location = "http://h.example:31923/jmap") else exchange(200, session)
        }, trust = { _, _, _ -> trusted++; "certificate untrusted" })
        assertTrue(offer.toString(), offer is JmapOffer.Mail)
        assertEquals(listOf("http://h.example:31923/.well-known/jmap", "http://h.example:31923/jmap"), opened)
        assertEquals(0, trusted)
    }

    @Test
    fun plainRedirectToAnotherOriginOrAnHttpsAccountGoingPlainIsRefused() {
        val toOther = runCatching {
            offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { u ->
                if (u.contains("31923")) exchange(301, location = "http://evil.example/jmap") else exchange(200, "{}")
            }, trust = { _, _, _ -> "" })
        }
        assertTrue(toOther.exceptionOrNull() is JmapFailure)
        val downgrade = runCatching {
            offerForAccount(AccountSettings(imapHost = "secure.example"), open = { u ->
                if (u.startsWith("https")) exchange(301, location = "http://secure.example/jmap") else exchange(200, "{}")
            }, trust = { _, _, _ -> "" })
        }
        assertTrue(downgrade.exceptionOrNull() is JmapFailure)
    }

    @Test
    fun apiCallsOverTheConfiguredOriginSkipTrustOthersStillRefused() {
        JmapPlainHttp.allow("http://h.example:31923/")
        var trusted = 0
        val ok = jmapCall("http://h.example:31923/jmap/", "{}", "Basic x", "", { _, _, _ -> exchange(200, "{}") }, { _, _, _ -> trusted++; "bad" })
        assertEquals(200, ok.status)
        assertEquals(0, trusted)
        try { jmapCall("http://h.example:8080/jmap/", "{}", "Basic x", "", { _, _, _ -> exchange(200) }); fail() } catch (_: JmapFailure) {}
    }

    private val labSession = """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},"accounts":{"a":{}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"a"},"username":"u","apiUrl":"http://h.example:31923/jmap/","downloadUrl":"http://h.example:31923/jmap/download/{accountId}/{blobId}/{name}?accept={type}","uploadUrl":"http://h.example:31923/jmap/upload/{accountId}/","eventSourceUrl":"http://h.example:31923/jmap/eventsource/","state":"0"}"""

    private fun blob(status: Int, body: String) = object : JmapBlobExchange {
        override val status = status
        override fun peerDer(): List<ByteArray> = emptyList()
        override fun bytes() = body.toByteArray()
        override fun close() {}
    }

    @Test
    fun sessionNeedingLoginIsFetchedOnceWithCredentialsAtTheDiscoveredUrl() {
        val gets = mutableListOf<Pair<String, String>>()
        val offer = offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { u ->
            if (u.endsWith("/.well-known/jmap")) exchange(301, location = "http://h.example:31923/jmap") else exchange(401)
        }, trust = { _, _, _ -> "" }, authorization = "Basic eA==", get = { u, a -> gets += u to a; blob(200, labSession) })
        assertTrue(offer.toString(), offer is JmapOffer.Mail)
        assertEquals(listOf("http://h.example:31923/jmap" to "Basic eA=="), gets)
    }

    @Test
    fun credentialsAreNeverSentWhenTheSessionIsOpenOrNoPasswordIsGiven() {
        var gets = 0
        val open = offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { exchange(200, labSession) },
            trust = { _, _, _ -> "" }, authorization = "Basic eA==", get = { _, _ -> gets++; blob(200, labSession) })
        assertTrue(open is JmapOffer.Mail)
        val none = offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { exchange(401) },
            trust = { _, _, _ -> "" }, get = { _, _ -> gets++; blob(200, labSession) })
        assertEquals(JmapOffer.None, none)
        assertEquals(0, gets)
    }

    @Test
    fun refusedLoginAndUntrustedCertificateFail() {
        val refused = runCatching {
            offerForAccount(AccountSettings(imapHost = "http://h.example:31923"), open = { exchange(401) },
                trust = { _, _, _ -> "" }, authorization = "Basic eA==", get = { _, _ -> blob(401, "") })
        }.exceptionOrNull()
        assertTrue(refused is JmapFailure)
        val untrusted = runCatching {
            jmapAuthenticatedSession("https://h.example/jmap", "Basic eA==", "", { _, _ -> blob(200, labSession) }, { _, _, _ -> "certificate untrusted" })
        }.exceptionOrNull()
        assertEquals("certificate untrusted", (untrusted as JmapFailure).text)
    }
}
