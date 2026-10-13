package org.dlang.liveimap.jmap

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.dlang.liveimap.engine.PeerTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F40/F02: a JMAP request carrying credentials is never written to a peer that fails the account's trust
 * verdict; the verdict runs inside the handshake (pinJmapTls). Real TLS on loopback, self-signed key.
 */
class JmapCredentialsAfterTrustTest {
    private val password = "changeit".toCharArray()

    private fun serverKeyStore(): KeyStore {
        val dir = Files.createTempDirectory("jmaptls").toFile()
        val file = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
            "-keystore", file.path, "-storepass", String(password), "-keypass", String(password),
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    }

    /** Starts a one-shot TLS server; returns its port and what it received after the handshake. */
    private fun server(store: KeyStore): Pair<Int, () -> String> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val got = AtomicReference("")
        val done = CountDownLatch(1)
        Thread {
            try {
                socket.accept().use { s ->
                    s.soTimeout = 5000
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    try {
                        while (true) {
                            val n = s.getInputStream().read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got.set(out.toString(Charsets.ISO_8859_1.name()))
                            if (got.get().contains("\r\n\r\n") && got.get().contains("secret-body")) {
                                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                                s.getOutputStream().flush()
                                break
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                socket.close()
                done.countDown()
            }
        }.apply { isDaemon = true }.start()
        return socket.localPort to { done.await(10, TimeUnit.SECONDS); got.get() }
    }

    private fun post(port: Int, pin: String): Result<Int> = runCatching {
        val c = URL("https://localhost:$port/jmap").openConnection() as HttpsURLConnection
        pinJmapTls(c, pin)
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Basic c2VjcmV0LWNyZWRz")
        c.outputStream.use { it.write("secret-body".toByteArray()) }
        c.responseCode
    }

    @Test fun nothingIsWrittenToAnUntrustedPeer() {
        val store = serverKeyStore()
        val (port, received) = server(store)
        val wrongPin = "00".repeat(32)
        assertTrue(post(port, wrongPin).isFailure)
        val got = received()
        assertFalse(got.contains("Authorization"))
        assertFalse(got.contains("secret"))
    }

    @Test fun thePinnedPeerGetsTheRequest() {
        val store = serverKeyStore()
        val leaf = store.getCertificate("s")
        val pin = PeerTrust.offer(listOf(leaf.encoded))!![4]
        val (port, received) = server(store)
        val result = post(port, pin)
        assertEquals(200, result.getOrThrow())
        assertTrue(received().contains("Authorization: Basic c2VjcmV0LWNyZWRz"))
    }
}
