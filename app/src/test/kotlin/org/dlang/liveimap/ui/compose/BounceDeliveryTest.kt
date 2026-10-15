package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BounceDeliveryTest {
    @Test
    fun switchChoosesDelivery() {
        assertEquals(BounceDelivery.Submission, bounceDelivery(true))
        assertEquals(BounceDelivery.Smtp, bounceDelivery(false))
    }

    @Test
    fun stillToSendDropsAcceptedUids() {
        val uids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 3L), bounceUidsStillToSend(uids, setOf(1L)))
        assertEquals(listOf(3L), bounceUidsStillToSend(uids, setOf(1L, 2L)))
        assertEquals(emptyList<Long>(), bounceUidsStillToSend(uids, setOf(1L, 2L, 3L)))
        assertEquals(listOf(4L), bounceUidsStillToSend(listOf(4L), setOf(1L)))
    }

    @Test
    fun recipientSpecsAreEveryAddrSpec() {
        assertEquals(listOf("a@x", "b@y"), bounceRecipientSpecs("a@x, b@y"))
        assertEquals(listOf("a@x"), bounceRecipientSpecs("Ann <a@x>"))
        assertEquals(emptyList<String>(), bounceRecipientSpecs(""))
        assertEquals(emptyList<String>(), bounceRecipientSpecs("   "))
    }

    @Test
    fun smtpBounceDeletesAfterAcceptAndSkipsRememberedUids() {
        val compose = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
        ).map(::File).first { it.exists() }.readText()
        assertTrue(compose.contains("rememberSaveable(seed.mailbox, seed.uidValidity)"))
        val smtpStart = compose.indexOf("BounceDelivery.Smtp ->")
        val jmapStart = compose.indexOf("BounceDelivery.Submission ->")
        val smtp = compose.substring(smtpStart, jmapStart)
        val jmapEnd = compose.indexOf("notice = null", jmapStart)
        val jmap = compose.substring(jmapStart, jmapEnd)
        assertTrue(smtp.indexOf("bounceUidsStillToSend") in 0 until smtp.indexOf("fetchRfc822"))
        assertTrue(jmap.indexOf("bounceUidsStillToSend") in 0 until jmap.indexOf("fetchRfc822"))
        assertFalse(smtp.contains("HashSet"))
        assertFalse(smtp.contains("acceptedUids.contains"))
        val write = smtp.indexOf("writeCopy(")
        val send = smtp.indexOf("sendSmtp(")
        assertTrue(write in 0 until send)
        val sendFail = smtp.indexOf("catch (error: MailFailure)")
        val sendReturn = smtp.indexOf("return@launchLocked false", sendFail)
        val sendBody = smtp.substring(sendFail, sendReturn)
        assertTrue(sendBody.contains("deviceCopyAfterSmtpFailure"))
        assertTrue(sendBody.contains("smtpUncertain"))
        assertFalse(sendBody.contains("deleteCopy"))
        assertFalse(sendBody.contains("addBounceAccepted"))
        val noFcc = smtp.indexOf("if (!account.bounceFcc)")
        val deleteNoFcc = smtp.indexOf("deleteCopy(", noFcc)
        val addNoFcc = smtp.indexOf("addBounceAccepted(", noFcc)
        val continueNoFcc = smtp.indexOf("continue", noFcc)
        val append = smtp.indexOf("session.append(")
        assertTrue(send < noFcc && deleteNoFcc in noFcc until append)
        assertTrue(deleteNoFcc in noFcc until continueNoFcc && addNoFcc in noFcc until continueNoFcc)
        val appendFail = smtp.indexOf("catch (error: MailFailure)", append)
        val appendReturn = smtp.indexOf("return@launchLocked false", appendFail)
        val appendBody = smtp.substring(appendFail, appendReturn)
        assertFalse(appendBody.contains("deleteCopy"))
        assertTrue(appendBody.contains("writeCopy("))
        assertTrue(appendBody.contains("addBounceAccepted"))
        val deleteAfterAppend = smtp.indexOf("deleteCopy(", appendReturn)
        assertTrue(deleteAfterAppend > append)
    }
}
