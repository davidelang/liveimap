package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8SafetyTest {
    @Test fun mutf7RoundTrip() {
        listOf("Entwürfe", "Gesendete Objekte", "R&D", "日本語", "Café ☕ 😀", "&").forEach {
            assertEquals(it, Mutf7.decodeOrNull(Mutf7.encode(it)))
        }
        assertEquals("Entw&APw-rfe", Mutf7.encode("Entwürfe"))
        assertEquals("R&-D", Mutf7.encode("R&D"))
        assertEquals("&ZeVnLIqe-", Mutf7.encode("日本語")) // RFC 3501 example
    }

    @Test fun strictDecode() {
        assertNull(Mutf7.decodeOrNull("R&D"))       // no terminator
        assertNull(Mutf7.decodeOrNull("&AGE-"))     // encoded ASCII 'a' is not valid mUTF-7
        assertNull(Mutf7.decodeOrNull("Café"))      // already UTF-8
    }

    @Test fun savedNamesOnUtf8Session() {
        assertEquals("Entwürfe", Mutf7.savedForUtf8("Entw&APw-rfe"))
        assertEquals("INBOX/Sent", Mutf7.savedForUtf8("INBOX/Sent"))
        assertEquals("Café", Mutf7.savedForUtf8("Café"))
        assertEquals("R&D", Mutf7.savedForUtf8("R&D"))   // not mUTF-7: kept
    }

    @Test fun idnDomainsBecomePunycode() {
        assertEquals("info@xn--bcher-kva.de", asciiDomainAddress("info@bücher.de"))
        assertEquals("a@example.com", asciiDomainAddress("a@example.com"))
        assertEquals(emptyList<String>(), needsSmtpUtf8(listOf("info@bücher.de", "a@b.c")))
        assertEquals(listOf("jürgen@xn--bcher-kva.de"), needsSmtpUtf8(listOf("jürgen@bücher.de")))
    }
}
