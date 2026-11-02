package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossAccountTest {
    @Test
    fun listPost() {
        fun p(h: String) = listPostAddress(parseRfc822("$h\r\nSubject: s\r\n\r\nb".toByteArray(), "ISO-8859-1"))
        assertEquals("dev@lists.x", p("List-Post: <mailto:dev@lists.x?subject=hi>"))
        assertEquals(null, p("List-Post: NO (posting not allowed)"))
        assertEquals(null, p("From: a@x"))
        // F59: bad escapes give null (no crash), CR/LF never reach RCPT TO, '+' stays '+'
        assertEquals(null, p("List-Post: <mailto:%>"))
        assertEquals(null, p("List-Post: <mailto:%zz@x>"))
        assertEquals(null, p("List-Post: <mailto:a@x%0D%0ARCPT TO:<e@vil>>"))
        assertEquals(null, p("List-Post: <mailto:a b@x>"))
        assertEquals(null, p("List-Post: <mailto:nodomain>"))
        assertEquals("list+post@x", p("List-Post: <mailto:list+post@x>"))
        assertEquals("dev@lists.x", p("List-Post: <mailto:dev%40lists.x>"))
        // F95: an escaped comma cannot smuggle a second recipient
        assertEquals(null, p("List-Post: <mailto:list@example.org%2C%20attacker@evil.example>"))
        assertEquals(null, p("List-Post: =?utf-8?Q?=3Cmailto:a@x=3E?="))  // decodes to a mailto only if decoded
    }

    @Test
    fun sentToPrefersOwnServersDeliveryHeader() {
        fun m(h: String) = parseRfc822("$h\r\nSubject: s\r\n\r\nb".toByteArray(), "ISO-8859-1")
        // F65: attacker Cc's the work address; the server's top Delivered-To says it came to home
        val forged = m("Delivered-To: home@x\r\nDelivered-To: work@corp\r\nTo: home@x\r\nCc: work@corp")
        assertEquals(listOf("home@x"), sentToAddresses(forged, "ISO-8859-1").map { addrSpec(it) })
        assertEquals(listOf("a@x", "b@x"), sentToAddresses(m("To: a@x\r\nCc: b@x"), "ISO-8859-1").map { addrSpec(it) })
        assertEquals(listOf("me@x"), sentToAddresses(m("X-Original-To: me@x\r\nTo: list@x"), "ISO-8859-1").map { addrSpec(it) })
    }
}
