package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {
    @Test
    fun hostAndMismatch() {
        val html = "<p><a href=\"https://evil.example/login\">https://www.mybank.com/login</a></p>" +
            "<a href='https://news.example.org/a'>Read at example.org</a>" +
            "<a href=https://other.test/x>click here</a>"
        val bad = checkLink("https://evil.example/login", html)
        assertEquals("evil.example", bad.host)
        assertEquals("www.mybank.com", bad.claimedHost)
        assertEquals("", checkLink("https://news.example.org/a", html).claimedHost)  // subdomain: same site
        assertEquals("", checkLink("https://other.test/x", html).claimedHost)  // no host in text
        assertEquals("", checkLink("https://evil.example/login", null).claimedHost)
    }

    @Test
    fun nestedTagsAndEntities() {
        val html = "<a href=\"https://x.test/?a=1&amp;b=2\"><b>pay</b><span>pal.com</span></a>"
        // Tags split the text into "pay pal.com": the shown domain is pal.com, not x.test.
        assertEquals("pal.com", checkLink("https://x.test/?a=1&b=2", html).claimedHost)
    }

    @Test
    fun idnShowsAsciiForm() {
        val c = checkLink("https://xn--pypal-4ve.com/", null)  // "pаypal" with Cyrillic a
        assertTrue(c.host != "xn--pypal-4ve.com")
        assertEquals("xn--pypal-4ve.com", c.asciiHost)
        assertEquals("", checkLink("https://plain.example/", null).asciiHost)
    }

    @Test
    fun nonWebAndJunk() {
        assertEquals("", checkLink("mailto:a@b.c", "<a href=\"mailto:a@b.c\">bank.com</a>").host)
        assertEquals("", checkLink("not a url", "").host)
        val big = "<a href=\"https://a.test\">x</a>".repeat(100000)
        assertEquals("", checkLink("https://a.test", big).claimedHost)
    }
}
