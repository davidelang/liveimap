package org.dlang.liveimap.jmap

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.TrafficWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapTrafficTest {
    private fun serve(handler: (String, ByteArray) -> Pair<Int, ByteArray>): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            val (status, response) = handler(exchange.requestMethod, body)
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return server to server.address.port
    }

    private fun records(root: File) = TrafficLog.recordsIn(File(File(root, "traffic"), "acct-1"))

    private fun assertPrivate(root: File, secret: String, requestBody: String, responseBody: String) {
        val all = records(root)
        val text = all.joinToString("\n") { String(it.bytes, Charsets.UTF_8) }
        assertTrue(text.contains("Authorization: "))
        assertFalse(text.contains(secret))
        val marker = TrafficLog.privateMarker(secret.toByteArray(Charsets.UTF_8).size)
        assertTrue(all.any { it.dir == '>' && it.bytes.contentEquals(marker) })
        assertTrue(all.any { it.bytes.contentEquals(requestBody.toByteArray()) })
        assertTrue(all.any { it.bytes.contentEquals(responseBody.toByteArray()) })
    }

    @Test
    fun postUploadGetAndEventSourceHideTheAuthorizationValue() {
        val secret = "Basic c2VjcmV0LXZhbHVl"
        val response = "{\"ok\":true}"
        val (server, port) = serve { _, _ -> 200 to response.toByteArray() }
        val root = Files.createTempDirectory("jmap-traffic").toFile()
        val traffic = TrafficLog.writer(root, "acct-1", 0) ?: error("writer")
        try {
            val url = "http://127.0.0.1:$port/jmap"
            val postBody = "{\"methodCalls\":[]}"
            platformJmapPost(url, postBody, secret, "", "acct-1", traffic).body()
            val upload = "raw-bytes".toByteArray()
            platformJmapUpload(url, upload, "application/octet-stream", secret, "", "acct-1", traffic).body()
            platformJmapGet(url, secret, "", "acct-1", traffic).bytes()
            platformJmapEventSource(url, secret, "", "acct-1", traffic).body()
            traffic.sync()
            assertPrivate(root, secret, postBody, response)
            assertTrue(records(root).any { it.bytes.contentEquals(upload) })
            assertTrue(records(root).any { it.conn == "jmap" })
        } finally {
            server.stop(0)
        }
    }
}
