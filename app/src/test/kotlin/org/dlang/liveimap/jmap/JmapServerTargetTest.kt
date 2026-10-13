package org.dlang.liveimap.jmap

import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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
        for (bad in listOf("", " ", "http://mail.example", "https://u:p@mail.example", "https://mail.example/?x=1", "https://mail.example/#f",
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
}
