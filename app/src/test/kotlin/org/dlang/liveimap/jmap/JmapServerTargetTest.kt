package org.dlang.liveimap.jmap

import org.dlang.liveimap.settings.Settings
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
        assertFalse(jmapUrlAllowed("A", "http://mail.example:31923/jmap"))
        JmapPlainHttp.allow("A", "http://mail.example:31923/.well-known/jmap")
        assertTrue(jmapUrlAllowed("A", "http://mail.example:31923/jmap/"))
        assertTrue(jmapUrlAllowed("A", "http://MAIL.example:31923/jmap/{accountId}"))
        assertFalse(jmapUrlAllowed("A", "http://mail.example/jmap"))      // port 80 is another origin
        assertFalse(jmapUrlAllowed("A", "http://other.example:31923/jmap"))
        assertTrue(jmapUrlAllowed("A", "https://other.example/jmap"))
        assertFalse(jmapUrlAllowed("A", "ftp://mail.example:31923/"))
    }

    @Test
    fun httpOriginIsAllowedOnlyForThatAccount() {
        JmapPlainHttp.allow("A", "http://a.example/.well-known/jmap")
        JmapPlainHttp.allow("B", "http://b.example/jmap")
        assertTrue(jmapUrlAllowed("A", "http://a.example/jmap"))
        assertFalse(jmapUrlAllowed("B", "http://a.example/jmap"))
        assertTrue(jmapUrlAllowed("A", "https://other.example/jmap"))
        assertFalse(jmapUrlAllowed("", "http://a.example/jmap"))
        JmapPlainHttp.drop("A")
        assertFalse(jmapUrlAllowed("A", "http://a.example/jmap"))
        assertFalse(jmapUrlAllowed("B", "http://a.example/jmap"))
        assertTrue(jmapUrlAllowed("B", "http://b.example/jmap"))
    }

    @Test
    fun verdictSkipsTheCertificateOnlyForTheAllowingAccount() {
        JmapPlainHttp.allow("A", "http://a.example/jmap")
        assertEquals("", jmapVerdict("A", "http://a.example/jmap") { "certificate untrusted" })
        assertEquals("certificate untrusted", jmapVerdict("B", "http://a.example/jmap") { "certificate untrusted" })
    }

    private fun blob(status: Int, body: String) = object : JmapBlobExchange {
        override val status = status
        override fun peerDer(): List<ByteArray> = emptyList()
        override fun bytes() = body.toByteArray()
        override fun close() {}
    }

    @Test
    fun pathOnlySessionUrlsResolveAgainstTheSessionOrigin() {
        JmapPlainHttp.allow("A", "http://h.example:31923/")
        val body = """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},"accounts":{"a":{}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"a"},"username":"u","apiUrl":"/jmap/","downloadUrl":"/jmap/download/{accountId}/{blobId}/{name}?accept={type}","uploadUrl":"/jmap/upload/{accountId}/","eventSourceUrl":"/jmap/eventsource/?types={types}&closeafter={closeafter}&ping={ping}","state":"0"}"""
        val offer = discoverJmap(200, body, "http://h.example:31923/jmap", "A") as JmapOffer.Mail
        assertEquals("http://h.example:31923/jmap/", offer.session.apiUrl)
        assertEquals("http://h.example:31923/jmap/eventsource/?types={types}&closeafter={closeafter}&ping={ping}", offer.session.eventSourceUrl)
        assertEquals(JmapOffer.None, discoverJmap(200, body.replace("\"/jmap/\"", "\"//evil.example/jmap/\""), "http://h.example:31923/jmap", "A"))
    }
}

