package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryDeliveryTest {
    @Test
    fun fourPairs() {
        assertEquals(RetryDelivery.Append, retryDelivery(true, true))
        assertEquals(RetryDelivery.Append, retryDelivery(false, true))
        assertEquals(RetryDelivery.Submission, retryDelivery(true, false))
        assertEquals(RetryDelivery.Smtp, retryDelivery(false, false))
    }
}
