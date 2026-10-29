package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
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
}
