package org.dlang.liveimap.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class TypeMismatchDialogTest {
    @get:Rule val rule = createComposeRule()

    // The lab's "Spoof html as jpeg" attachment: claims image/jpeg as cat.jpg, content is HTML.
    private val html = "<!DOCTYPE html><html><body>lab</body></html>".toByteArray()

    private fun show(share: Boolean, calls: MutableList<String>) {
        val m = typeMismatch("image/jpeg", "cat.jpg", html)
        assertNotNull("sniffer must flag html-as-jpeg", m)
        val openAs = safeAttachmentMime(m!!.detected.mime.substringBefore('/'), m.detected.mime.substringAfter('/'))
        rule.setContent {
            TypeMismatchDialog("cat.jpg", m, openAs, share, { calls += "dismiss" }, { calls += "save" }, { calls += "open:$openAs" })
        }
    }

    @Test fun showsTypesAndOpensAsDetected() {
        val calls = mutableListOf<String>()
        show(false, calls)
        rule.onNodeWithText("Attachment type does not match").assertIsDisplayed()
        rule.onNodeWithText("Claimed type: image/jpeg", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Open as", substring = true).onFirst().performClick()
        assertEquals(1, calls.size)
        assert(calls[0].startsWith("open:")) { calls.toString() }
        assert(!calls[0].contains("html")) { "must not open as live HTML: $calls" }
    }

    @Test fun shareSaveCancel() {
        val calls = mutableListOf<String>()
        show(true, calls)
        rule.onAllNodesWithText("Share as", substring = true).onFirst().assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("save", "dismiss"), calls)
    }
}
