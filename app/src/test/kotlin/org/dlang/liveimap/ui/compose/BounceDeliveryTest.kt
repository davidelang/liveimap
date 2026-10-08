package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class BounceDeliveryTest {
    @Test
    fun switchChoosesDelivery() {
        assertEquals(BounceDelivery.Submission, bounceDelivery(true))
        assertEquals(BounceDelivery.Smtp, bounceDelivery(false))
    }
}
