package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSafetyTest {
    @Test fun metaRefreshIsRemovedInEveryForm() {
        for (tag in listOf(
            """<meta http-equiv="refresh" content="0;url=https://evil.test/">""",
            """<META HTTP-EQUIV='Refresh' CONTENT='1; URL=intent://x'>""",
            """<meta content="0;url=x" http-equiv=refresh />""",
            """<meta  http-equiv = " refresh " content="0">""",
        )) {
            assertEquals(tag, "<p>a</p><p>b</p>", stripMetaRefresh("<p>a</p>$tag<p>b</p>"))
        }
    }

    @Test fun otherMetaTagsStay() {
        val html = """<meta charset="utf-8"><meta http-equiv="Content-Type" content="text/html"><meta name="refresh">"""
        assertEquals(html, stripMetaRefresh(html))
    }

    @Test fun onlyAMainFrameTapPrompts() {
        assertTrue(navigationMayPrompt(hasGesture = true, isRedirect = false, isForMainFrame = true))
        assertFalse(navigationMayPrompt(hasGesture = false, isRedirect = false, isForMainFrame = true))
        assertFalse(navigationMayPrompt(hasGesture = true, isRedirect = true, isForMainFrame = true))
        assertFalse(navigationMayPrompt(hasGesture = true, isRedirect = false, isForMainFrame = false))
    }

    @Test fun linkSchemesAreClassified() {
        for (u in listOf("https://example.test/a", "HTTP://x", " mailto:a@b.test")) assertEquals(u, LinkSafety.Safe, linkSafety(u))
        for (u in listOf("tel:+15551234", "sms:123", "geo:0,0", "market://details?id=x", "ftp://x"))
            assertEquals(u, LinkSafety.Warn, linkSafety(u))
        for (u in listOf(
            "javascript:alert(1)", "JaVaScRiPt:x", "file:///sdcard/x", "content://com.x/y", "intent://x#Intent;end",
            "data:text/html,<script>", "android-app://com.x", "about:blank", "relative/path", ":nothing", "1abc:x",
        )) assertEquals(u, LinkSafety.Blocked, linkSafety(u))
    }

    @Test fun showImagesLetsOnlyImagesThrough() {
        val img = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        assertTrue(remoteRequestAllowed("https", img, isForMainFrame = false))
        assertTrue(remoteRequestAllowed("HTTP", img, isForMainFrame = false))
        assertFalse(remoteRequestAllowed("https", "text/css,*/*;q=0.1", isForMainFrame = false))
        assertFalse(remoteRequestAllowed("https", "*/*", isForMainFrame = false))       // fonts, scripts, xhr
        assertFalse(remoteRequestAllowed("https", null, isForMainFrame = false))
        assertFalse(remoteRequestAllowed("ftp", img, isForMainFrame = false))
        assertFalse(remoteRequestAllowed("file", img, isForMainFrame = false))
        assertTrue(remoteRequestAllowed(null, null, isForMainFrame = true))             // the message page itself
    }
}

class MailHtmlSanitizeTest {
    @org.junit.Test fun formsFramesAndHandlersGo() {
        val out = sanitizeMailHtml(
            "<p style=\"color:red\" onclick=\"x()\">hi</p><form action=\"https://evil.example/p\" method=\"post\">" +
                "<input name=\"pw\"><button formaction=\"https://evil.example/q\">Go</button></form>" +
                "<iframe src=\"https://evil.example/f\"></iframe><base href=\"https://evil.example/\">" +
                "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example/\"><link rel=\"stylesheet\" href=\"https://evil.example/c\">" +
                "<object data=\"x\"></object><embed src=\"x\"><img src=\"https://ok.example/i.png\" onerror=\"y()\">",
        )
        org.junit.Assert.assertFalse(out, out.contains("evil.example"))
        org.junit.Assert.assertFalse(out, out.contains("onclick") || out.contains("onerror"))
        org.junit.Assert.assertTrue(out, out.contains("style=\"color:red\"") && out.contains(">hi<"))
        org.junit.Assert.assertTrue(out, out.contains("https://ok.example/i.png"))
    }

    @org.junit.Test fun networkMainFrameBlocked() {
        org.junit.Assert.assertFalse(remoteRequestAllowed("https", "text/html", isForMainFrame = true))
        org.junit.Assert.assertFalse(remoteRequestAllowed("http", "image/png", isForMainFrame = true))
        org.junit.Assert.assertTrue(remoteRequestAllowed("data", null, isForMainFrame = true))
    }

    @org.junit.Test fun themedPageCarriesCsp() {
        val page = themedHtml("<p>x</p>", false, 0, 0)
        org.junit.Assert.assertTrue(page, page.contains("Content-Security-Policy") && page.contains("form-action 'none'"))
    }
}
