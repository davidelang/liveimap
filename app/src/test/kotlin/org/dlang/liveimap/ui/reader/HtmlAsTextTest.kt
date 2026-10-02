package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlAsTextTest {
    @Test
    fun linkListScriptAndAmp() {
        val text = htmlAsText(
            "<p>Hello &amp; world</p><li>One</li><script type=\"text/javascript\">alert(1)</script>" +
                "<a href=\"https://example.com\">link</a>",
        )
        assertEquals("Hello & world\n* One\nlink <https://example.com>", text)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("script"))
    }

    @Test
    fun blockBreaksImageAndEntities() {
        assertEquals("Title\nNext", htmlAsText("<h1>Title</h1>Next"))
        assertEquals("a\nb", htmlAsText("a<br>b"))
        assertEquals("a\nb", htmlAsText("<div>a</div><div>b</div>"))
        assertEquals("q\nafter", htmlAsText("<blockquote>q</blockquote>after"))
        assertEquals("row", htmlAsText("<tr>row</tr>"))
        assertEquals("cat", htmlAsText("<img alt=\"cat\">"))
        assertEquals("[image]", htmlAsText("<img>"))
        assertEquals("[image]", htmlAsText("<img alt=\"\">"))
        assertEquals("A&<>\"\u00A0", htmlAsText("&#65;&amp;&lt;&gt;&quot;&nbsp;"))
        assertEquals("A", htmlAsText("&#x41;"))
        assertEquals("A", htmlAsText("&#X41;"))
        assertEquals("a\n\nb", htmlAsText("a\n\n\n\nb"))
        assertEquals("See", htmlAsText("<style>body{}</style>See"))
        assertFalse(htmlAsText("<style>body{}</style>See").contains("body"))
        assertEquals("text", htmlAsText("<a href=\"\">text</a>"))
        assertEquals("text", htmlAsText("<a>text</a>"))
        assertEquals(
            "link <https://example.com>",
            htmlAsText("<a href=\"https://example.com\"><b>link</b></a>"),
        )
        assertEquals("t <https://e.com?a=1&b=2>", htmlAsText("<a href=\"https://e.com?a=1&amp;b=2\">t</a>"))
    }
}
