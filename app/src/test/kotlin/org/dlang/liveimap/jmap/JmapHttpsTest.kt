package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JmapHttpsTest {
    @Test
    fun okReturnsBodyFinalUrlAndTrustInputs() {
        val url = "https://example.com/.well-known/jmap"
        val ders = listOf(byteArrayOf(9, 8), byteArrayOf(7))
        val script = Script(
            listOf(Step(url, 200, body = """{"ok":true}""", ders = ders)),
        )
        val result = fetch(script, "pin-1").get(url)
        assertEquals(200, result.status)
        assertEquals("""{"ok":true}""", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals(listOf(url), script.opens)
        assertEquals(listOf(url), script.reads)
        assertEquals(script.opens, script.closes)
        val seen = script.trusted.single()
        assertEquals("example.com", seen.host)
        assertSame(ders, seen.ders)
        assertEquals("pin-1", seen.pin)
    }

    @Test
    fun trustFailureDoesNotReadBody() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(listOf(Step(url, 200, body = "secret")))
        val client = JmapHttpsFetch("pin", script::open) { _, _, _ -> "certificate changed" }
        try {
            client.get(url)
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("certificate changed", failure.text)
        }
        assertEquals(listOf(url), script.opens)
        assertEquals(emptyList<String>(), script.reads)
        assertEquals(script.opens, script.closes)
    }

    @Test
    fun defaultTrustIsPeerTrust() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(listOf(Step(url, 200, body = "secret", ders = emptyList())))
        val client = JmapHttpsFetch("pin", script::open)
        try {
            client.get(url)
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("empty certificate chain", failure.text)
        }
        assertEquals(emptyList<String>(), script.reads)
        assertEquals(listOf(url), script.closes)
    }

    @Test
    fun absolutePathLocation() {
        assertFollows(
            "https://example.com/.well-known/jmap",
            "/jmap/session",
            "https://example.com/jmap/session",
        )
    }

    @Test
    fun relativeLocation() {
        assertFollows(
            "https://example.com/.well-known/jmap",
            "session",
            "https://example.com/.well-known/session",
        )
    }

    @Test
    fun protocolRelativeLocation() {
        val from = "https://example.com/.well-known/jmap"
        val next = "https://other.example/jmap"
        val script = assertFollows(from, "//other.example/jmap", next)
        assertEquals(listOf("example.com", "other.example"), script.trusted.map { it.host })
    }

    @Test
    fun ipv6HostDropsOneBracketPair() {
        val url = "https://[2001:db8::1]/.well-known/jmap"
        val script = Script(listOf(Step(url, 200, body = "v6")))
        val result = fetch(script).get(url)
        assertEquals("v6", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals("2001:db8::1", script.trusted.single().host)
    }

    @Test
    fun redirectStatusesFollow() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val from = "https://example.com/start"
            val next = "https://example.com/next"
            val script = Script(
                listOf(
                    Step(from, code, location = next),
                    Step(next, 200, body = "ok"),
                ),
            )
            val result = fetch(script).get(from)
            assertEquals("$code", 200, result.status)
            assertEquals("$code", "ok", result.body)
            assertEquals("$code", next, result.finalUrl)
            assertEquals("$code", listOf(from, next), script.opens)
            assertEquals("$code", listOf(next), script.reads)
            assertEquals(script.opens, script.closes)
        }
    }

    @Test
    fun httpRedirectIsNotOpened() {
        val from = "https://example.com/.well-known/jmap"
        for (location in listOf("http://example.com/jmap", "HTTP://example.com/jmap")) {
            val script = Script(listOf(Step(from, 302, location = location)))
            try {
                fetch(script).get(from)
                throw AssertionError("expected JmapFailure")
            } catch (failure: JmapFailure) {
                assertEquals("jmap redirect is not https", failure.text)
            }
            assertEquals(listOf(from), script.opens)
            assertEquals(emptyList<String>(), script.reads)
            assertEquals(script.opens, script.closes)
        }
    }

    @Test
    fun blankLocationIsRejected() {
        val from = "https://example.com/.well-known/jmap"
        for (location in listOf<String?>(null, "", " ", "\t")) {
            val script = Script(listOf(Step(from, 301, location = location)))
            try {
                fetch(script).get(from)
                throw AssertionError("expected JmapFailure")
            } catch (failure: JmapFailure) {
                assertEquals("jmap redirect lacks location", failure.text)
            }
            assertEquals(listOf(from), script.opens)
            assertEquals(emptyList<String>(), script.reads)
            assertEquals(script.opens, script.closes)
        }
    }

    @Test
    fun fiveRedirectsThenOk() {
        val urls = (0..5).map { "https://example.com/step$it" }
        val steps = urls.mapIndexed { index, url ->
            if (index == urls.lastIndex) {
                Step(url, 200, body = "done")
            } else {
                Step(url, 302, location = urls[index + 1])
            }
        }
        val script = Script(steps)
        val result = fetch(script).get(urls.first())
        assertEquals(200, result.status)
        assertEquals("done", result.body)
        assertEquals(urls.last(), result.finalUrl)
        assertEquals(urls, script.opens)
        assertEquals(listOf(urls.last()), script.reads)
        assertEquals(script.opens, script.closes)
    }

    @Test
    fun sixthRedirectHitsLimit() {
        val urls = (0..6).map { "https://example.com/step$it" }
        val steps = (0..5).map { index ->
            Step(urls[index], 308, location = urls[index + 1])
        }
        val script = Script(steps)
        try {
            fetch(script).get(urls.first())
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals("jmap redirect limit", failure.text)
        }
        assertEquals(urls.take(6), script.opens)
        assertEquals(emptyList<String>(), script.reads)
        assertEquals(script.opens, script.closes)
    }

    @Test
    fun httpRequestDoesNotOpen() {
        for (url in listOf("http://example.com/.well-known/jmap", "HTTP://example.com/jmap")) {
            val script = Script(emptyList())
            try {
                fetch(script).get(url)
                throw AssertionError("expected JmapFailure")
            } catch (failure: JmapFailure) {
                assertEquals("jmap fetch is not https", failure.text)
            }
            assertEquals(emptyList<String>(), script.opens)
            assertEquals(emptyList<String>(), script.reads)
        }
    }

    @Test
    fun uppercaseHttpsIsFetched() {
        val url = "HTTPS://Example.COM/.well-known/jmap"
        val script = Script(listOf(Step(url, 200, body = "ok")))
        val result = fetch(script).get(url)
        assertEquals("ok", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals("Example.COM", script.trusted.single().host)
        assertEquals(script.opens, script.closes)
    }

    @Test
    fun notFoundReturnsStatusAndBody() {
        val url = "https://example.com/.well-known/jmap"
        val script = Script(listOf(Step(url, 404, body = "missing")))
        val result = fetch(script).get(url)
        assertEquals(404, result.status)
        assertEquals("missing", result.body)
        assertEquals(url, result.finalUrl)
        assertEquals(listOf(url), script.reads)
        assertEquals(script.opens, script.closes)
    }

    private fun assertFollows(from: String, location: String, next: String): Script {
        val script = Script(
            listOf(
                Step(from, 302, location = location),
                Step(next, 200, body = "mail"),
            ),
        )
        val result = fetch(script).get(from)
        assertEquals(200, result.status)
        assertEquals("mail", result.body)
        assertEquals(next, result.finalUrl)
        assertEquals(listOf(from, next), script.opens)
        assertEquals(listOf(next), script.reads)
        assertEquals(script.opens, script.closes)
        return script
    }

    private fun fetch(script: Script, pin: String = "pin"): JmapHttpsFetch =
        JmapHttpsFetch(pin, script::open, script::trust)
}

private data class SeenTrust(
    val host: String,
    val ders: List<ByteArray>,
    val pin: String,
)

private data class Step(
    val url: String,
    val status: Int,
    val location: String? = null,
    val body: String = "",
    val ders: List<ByteArray> = listOf(byteArrayOf(1)),
)

private class Script(private val steps: List<Step>) {
    val opens = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val closes = mutableListOf<String>()
    val trusted = mutableListOf<SeenTrust>()

    fun open(url: String): JmapHttpExchange {
        opens.add(url)
        val step = steps.getOrNull(opens.size - 1) ?: error("opened $url")
        if (step.url != url) error("opened $url")
        return object : JmapHttpExchange {
            override val status: Int = step.status

            override fun peerDer(): List<ByteArray> = step.ders

            override fun header(name: String): String? =
                if (name == "Location") step.location else null

            override fun body(): String {
                reads.add(url)
                return step.body
            }

            override fun close() {
                closes.add(url)
            }
        }
    }

    fun trust(host: String, ders: List<ByteArray>, pin: String): String {
        trusted.add(SeenTrust(host, ders, pin))
        return ""
    }
}
