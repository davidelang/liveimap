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
}
