package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyChainCertsCheckTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun subjectIsReported() {
        assertEquals("CN=liveimap-test", KeyChainCerts.check(res("client-cert-test.p12"), "test-pass"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun certWithoutKeyRejected() {
        KeyChainCerts.check(res("client-cert-nokey.p12"), "test-pass")
    }
}
