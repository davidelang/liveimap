package org.dlang.liveimap.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrustTest {
    @Test
    fun emptyChainIsRejected() {
        assertTrue(PeerTrust.check("imap.example", emptyList()).isNotEmpty())
    }
}
