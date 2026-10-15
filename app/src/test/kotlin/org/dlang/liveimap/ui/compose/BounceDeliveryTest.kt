package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BounceDeliveryTest {
    @Test
    fun switchChoosesDelivery() {
        assertEquals(BounceDelivery.Submission, bounceDelivery(true))
        assertEquals(BounceDelivery.Smtp, bounceDelivery(false))
    }

    @Test
    fun smtpBounceWritesBeforeSendAndKeepsAnUncertainOutcome() {
        val compose = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
        ).map(::File).first { it.exists() }.readText()
        val start = compose.indexOf("BounceDelivery.Smtp ->")
        val end = compose.indexOf("BounceDelivery.Submission ->")
        val loop = compose.substring(start, end)
        val write = loop.indexOf("writeCopy(")
        val send = loop.indexOf("sendSmtp(")
        val skip = loop.indexOf("if (acceptedUids.contains(uid)) continue")
        val add = loop.indexOf("acceptedUids.add(uid)")
        assertTrue(write in 0 until send)
        assertTrue(skip in 0 until send && send < add)
        val fail = loop.indexOf("catch (error: MailFailure)")
        val failBody = loop.substring(fail, loop.indexOf("acceptedUids.add(uid)", fail))
        assertTrue(failBody.contains("smtpUncertain"))
    }
}
