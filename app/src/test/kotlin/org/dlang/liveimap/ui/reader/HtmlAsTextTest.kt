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
        assertEquals("Hello & world\n* One\nlink [1]\n\n[1] https://example.com", text)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("script"))
    }

    @Test
    fun blockBreaksImageAndEntities() {
        assertEquals("Title\nNext", htmlAsText("<h1>Title</h1>Next"))
        assertEquals("a\nb", htmlAsText("a<br>b"))
        assertEquals("a\nb", htmlAsText("<div>a</div><div>b</div>"))
        assertEquals("> q\nafter", htmlAsText("<blockquote>q</blockquote>after"))
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
            "link [1]\n\n[1] https://example.com",
            htmlAsText("<a href=\"https://example.com\"><b>link</b></a>"),
        )
        assertEquals(
            "a [1]b [1]\n\n[1] https://e.com",
            htmlAsText("<a href=\"https://e.com\">a</a><a href=\"https://e.com\">b</a>"),
        )
        assertEquals("x", htmlAsText("<a href=\"javascript:alert(1)\">x</a>"))
        assertEquals("y", htmlAsText("<a href=\"data:text/plain,a\">y</a>"))
        assertEquals("m [1]\n\n[1] mailto:a@b.c", htmlAsText("<a href=\"mailto:a@b.c\">m</a>"))
        assertEquals("t [1]\n\n[1] https://e.com?a=1&b=2", htmlAsText("<a href=\"https://e.com?a=1&amp;b=2\">t</a>"))
        assertEquals("1. a\n2. b", htmlAsText("<ol><li>a</li><li>b</li></ol>"))
        assertEquals("a b", htmlAsText("<table><tr><td>a</td><td>b</td></tr></table>"))
        assertEquals("a  b\nc", htmlAsText("<pre>a  b\nc</pre>"))
    }

    @Test(timeout = 30_000)
    fun deeplyNestedHtmlRendersWithoutRecursion() {
        val n = 100_000
        val html = "<div>".repeat(n) + "deep text" + "</div>".repeat(n) + "<blockquote>q</blockquote>after"
        val text = htmlAsText(html)
        assertEquals("deep text\n> q\nafter", text)
    }
}
