package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeChromeTest {
    @Test
    fun composeIsDirty() {
        assertFalse(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Hello",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = false,
            ),
        )
        assertTrue(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Changed",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = false,
            ),
        )
        assertTrue(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Hello",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = true,
            ),
        )
    }
}
