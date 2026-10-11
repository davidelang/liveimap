package org.dlang.liveimap.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ClientCertStoreTest {
    /** Stand-in for the AndroidKeyStore sealer: reversible, and visibly not the plaintext. */
    private object FakeSealer : Sealer {
        override fun seal(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed[0] == 1.toByte())
            return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    @Before
    fun fakeSealer() {
        ClientCertStore.sealer = FakeSealer
    }

    @Test
    fun passwordIsSealedNotPlain() {
        val dir = Files.createTempDirectory("cc").toFile()
        val path = ClientCertStore.import(dir, res("client-cert-test.p12"), "test-pass")
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(2, names.size)
        assertTrue(names.any { it.endsWith(".pw.sealed") })
        assertFalse(names.any { it.endsWith(".pw") })
        val sealed = File(path.removeSuffix(".p12") + ".pw.sealed").readBytes()
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("test-pass"))
    }

    @Test
    fun plainPasswordFileIsNotRead() {
        // No migration: a stray plaintext .pw beside a .p12 is ignored.
        val dir = createTempDir()
        java.io.File(dir, "old.p12").writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(dir, "old.pw").writeText("test-pass")
        assertEquals(null, ClientCertStore.load(java.io.File(dir, "old.p12").absolutePath))
        assertTrue(java.io.File(dir, "old.pw").exists())
    }

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
