package org.dlang.liveimap.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCertStoreTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun importLoadRemove() {
        val dir = Files.createTempDirectory("cc").toFile()
        val p12 = res("client-cert-test.p12")
        val path = ClientCertStore.import(dir, p12, "test-pass")
        val got = ClientCertStore.load(path)!!
        assertArrayEquals(p12, got.p12)
        assertEquals("test-pass", got.password)
        assertTrue(File(path).parentFile == dir)
        ClientCertStore.remove(path)
        assertNull(ClientCertStore.load(path))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun subjectIsReported() {
        assertEquals("CN=liveimap-test", ClientCertStore.check(res("client-cert-test.p12"), "test-pass"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPasswordRejected() {
        ClientCertStore.import(Files.createTempDirectory("cc").toFile(), res("client-cert-test.p12"), "nope")
    }

    @Test(expected = IllegalArgumentException::class)
    fun certWithoutKeyRejected() {
        ClientCertStore.check(res("client-cert-nokey.p12"), "test-pass")
    }

    @Test
    fun blankOrMissingLoadsNull() {
        assertNull(ClientCertStore.load(""))
        assertNull(ClientCertStore.load("/nonexistent/x.p12"))
    }
}
