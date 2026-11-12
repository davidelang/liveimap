package org.dlang.liveimap.fakeimap

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JVM helper that starts the pinned fakeimap binary, matching tools/test-servers/fakeimap_pin.py.
 *
 * Expected locations after a version is locked and emerged:
 *   third_party/fakeimap/artifact/fakeimap
 *   third_party/fakeimap/src/personalities/<name>.json  (after fetch-deps ro)
 *
 * There is no fallback to a host checkout, a PATH binary, or the removed
 * in-process FakeImapServer. [requireReady] throws if git_sha is still the
 * 40-zero placeholder or the binary / personality tree is missing.
 *
 * Scratch files go under sandbox/tmp-rescue/fakeimap-jvm/, never /tmp.
 */
object FakeimapPin {
    const val PLACEHOLDER_SHA = "0000000000000000000000000000000000000000"

    fun repoRoot(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
            val pin = dir.resolve("third_party/fakeimap/libpin.toml")
            val build = dir.resolve("build_app")
            if (Files.isRegularFile(pin) && Files.isRegularFile(build)) return dir
            dir = dir.parent ?: error(
                "fakeimap pin: cannot find the LiveIMAP repo root from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
        error("fakeimap pin: cannot find the LiveIMAP repo root from user.dir=" + System.getProperty("user.dir"))
    }

    fun pinDir(): Path = repoRoot().resolve("third_party/fakeimap")
    fun pinFile(): Path = pinDir().resolve("libpin.toml")
    fun artifact(): Path = pinDir().resolve("artifact/fakeimap")
    fun personalities(): Path = pinDir().resolve("src/personalities")

    fun gitSha(): String {
        val text = Files.readString(pinFile())
        val m = Regex("""(?m)^git_sha\s*=\s*"([^"]*)"""").find(text)
            ?: error("fakeimap pin: ${pinFile()} has no git_sha")
        return m.groupValues[1].trim()
    }

    fun requireReady() {
        if (!Files.isRegularFile(pinFile())) {
            error("fakeimap pin: ${pinFile()} is missing")
        }
        val sha = gitSha()
        if (sha.isEmpty() || sha == PLACEHOLDER_SHA || sha == "UNSELECTED") {
            error(
                "fakeimap pin: git_sha is UNSELECTED ($PLACEHOLDER_SHA). " +
                    "Lock a commit in ${pinFile()}, then run " +
                    "./third_party/fetch-deps ro fakeimap && " +
                    "./third_party/fetch-deps build --record fakeimap",
            )
        }
        val bin = artifact()
        if (!Files.isRegularFile(bin) || !Files.isExecutable(bin)) {
            error(
                "fakeimap pin: git_sha is $sha but $bin is not an executable. " +
                    "Emerge with: ./third_party/fetch-deps ro fakeimap && " +
                    "./third_party/fetch-deps build --record fakeimap",
            )
        }
        if (!Files.isDirectory(personalities())) {
            error(
                "fakeimap pin: ${personalities()} is missing. " +
                    "Materialize with: ./third_party/fetch-deps ro fakeimap",
            )
        }
    }

    fun personalityPath(name: String): Path {
        requireReady()
        val path = personalities().resolve("$name.json")
        if (!Files.isRegularFile(path)) {
            error("fakeimap pin: no personality for $name at $path")
        }
        return path
    }

    fun scratchDir(label: String): Path {
        val root = repoRoot().resolve("sandbox/tmp-rescue/fakeimap-jvm")
        Files.createDirectories(root)
        return Files.createTempDirectory(root, "$label-")
    }

    fun writeDataset(
        destDir: Path,
        user: String = "user",
        password: String = "secret",
        uidValidity: Int = 17,
        messageFile: Path? = null,
    ): Path {
        Files.createDirectories(destDir)
        val eml = messageFile ?: destDir.resolve("1.eml").also {
            Files.writeString(
                it,
                "From: Lab Sender <sender@lab.test>\r\n" +
                    "To: $user <user@lab.test>\r\n" +
                    "Subject: Lab\r\n" +
                    "Date: Wed, 01 Jan 2020 00:00:00 +0000\r\n" +
                    "\r\n" +
                    "one message\r\n",
            )
        }
        val rel = destDir.relativize(eml).toString()
        val json =
            """{
              |  "accounts": [{"username": ${jsonString(user)}, "password": ${jsonString(password)}}],
              |  "mailboxes": [
              |    {
              |      "name": "INBOX",
              |      "uid_validity": $uidValidity,
              |      "messages": [
              |        {"file": ${jsonString(rel)}, "flags": ["\\Seen"], "internal_date": "01-Jan-2020 00:00:00 +0000"}
              |      ]
              |    }
              |  ]
              |}
              |""".trimMargin()
        Files.writeString(destDir.resolve("dataset.json"), json)
        return destDir
    }

    fun writePersonality(
        dest: Path,
        greeting: String = "fakeimap ready",
        preLogin: List<String>,
        postLogin: List<String> = preLogin,
        delimiter: String = "/",
        logoutBye: Boolean = true,
    ): Path {
        Files.createDirectories(dest.parent)
        val fetchOrder = listOf(
            "FLAGS", "UID", "INTERNALDATE", "RFC822.SIZE", "ENVELOPE",
            "BODYSTRUCTURE", "RFC822", "RFC822.HEADER", "RFC822.TEXT",
            "BODY[]", "BODY.PEEK[]",
        )
        val json = buildString {
            append("{\n")
            append("  \"greeting\": ${jsonString(greeting)},\n")
            append("  \"delimiter\": ${jsonString(delimiter)},\n")
            append("  \"pre_login_capabilities\": ${jsonValue(preLogin)},\n")
            append("  \"post_login_capabilities\": ${jsonValue(postLogin)},\n")
            append("  \"fetch_item_order\": ${jsonValue(fetchOrder)},\n")
            append("  \"logout_bye\": $logoutBye\n")
            append("}\n")
        }
        Files.writeString(dest, json)
        return dest
    }

    fun pemPair(dir: Path): Pair<Path, Path> {
        Files.createDirectories(dir)
        val cert = dir.resolve("cert.pem")
        val key = dir.resolve("key.pem")
        val pb = ProcessBuilder(
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", key.toString(), "-out", cert.toString(),
            "-days", "2", "-nodes", "-subj", "/CN=localhost",
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        if (!proc.waitFor(30, TimeUnit.SECONDS) || proc.exitValue() != 0) {
            error("fakeimap pin: openssl failed to write a test certificate (exit ${proc.exitValue()}): $out")
        }
        return cert to key
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is String -> jsonString(v)
        is Path -> jsonString(v.toAbsolutePath().toString())
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            jsonString(it.key.toString()) + ":" + jsonValue(it.value)
        }
        is Iterable<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        else -> jsonString(v.toString())
    }
}

class FakeimapServer(
    val work: Path,
    val process: Process,
    val bound: Map<String, Any?>,
    val logPath: Path,
    val errPath: Path,
) : Closeable {
    val listen: String
        get() = bound["listen"] as? String
            ?: error("fakeimap pin: bound JSON has no listen address: $bound")

    val listenHost: String
        get() = listen.substringBeforeLast(":")

    val listenPort: Int
        get() = listen.substringAfterLast(":").toInt()

    fun control(req: Map<String, Any?>, timeoutMs: Long = 5_000): Map<String, Any?> {
        val path = bound["control"] as? String
            ?: error("fakeimap pin: bound JSON has no control socket")
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(path))
            val writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
            writer.write(FakeimapPin.jsonValue(req))
            writer.write("\n")
            writer.flush()
            val line = reader.readLine()
                ?: error("fakeimap pin: no control reply for ${req["op"]}")
            val reply = parseJsonObject(line)
            if (reply["ok"] != true) {
                error("fakeimap pin: control ${req["op"]} failed: ${reply["error"] ?: reply}")
            }
            return reply
        }
    }

    fun armFault(req: Map<String, Any?>): Map<String, Any?> =
        control(mapOf("op" to "arm-fault") + req)

    fun commandLog(): List<String> {
        if (!Files.isRegularFile(logPath)) return emptyList()
        return Files.readAllLines(logPath).mapNotNull { line ->
            if (!line.trimStart().startsWith("{")) return@mapNotNull null
            val rec = parseJsonObject(line)
            if (rec["kind"] == "command") rec["text"] as? String else null
        }
    }

    override fun close() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        fun start(
            label: String,
            personality: Path,
            dataset: Path,
            implicitTls: Boolean = false,
            cert: Path? = null,
            key: Path? = null,
        ): FakeimapServer {
            FakeimapPin.requireReady()
            val work = FakeimapPin.scratchDir(label)
            val control = work.resolve("control.sock")
            Files.deleteIfExists(control)
            val logPath = work.resolve("fakeimap.jsonl")
            val errPath = work.resolve("fakeimap.err")
            val cfg = work.resolve("config.json")
            val keys = linkedMapOf<String, Any?>(
                "personality" to personality.toAbsolutePath().toString(),
                "dataset" to dataset.toAbsolutePath().toString(),
                "control" to control.toAbsolutePath().toString(),
                "log_path" to logPath.toAbsolutePath().toString(),
            )
            if (implicitTls) {
                keys["implicit_tls_listen"] = "127.0.0.1:0"
            } else {
                keys["listen"] = "127.0.0.1:0"
            }
            if (cert != null && key != null) {
                keys["cert_file"] = cert.toAbsolutePath().toString()
                keys["key_file"] = key.toAbsolutePath().toString()
            }
            Files.writeString(cfg, FakeimapPin.jsonValue(keys) + "\n")
            val pb = ProcessBuilder(FakeimapPin.artifact().toString(), cfg.toAbsolutePath().toString())
            pb.directory(FakeimapPin.repoRoot().toFile())
            pb.redirectError(errPath.toFile())
            val proc = pb.start()
            val line = try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                readBoundLine(reader, proc, errPath)
            } catch (e: Exception) {
                proc.destroyForcibly()
                throw e
            }
            val bound = parseJsonObject(line)
            return FakeimapServer(work, proc, bound, logPath, errPath)
        }

        private fun readBoundLine(reader: BufferedReader, proc: Process, errPath: Path): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val buf = StringBuilder()
            while (System.nanoTime() < deadline) {
                if (reader.ready()) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        val line = buf.toString().trim()
                        if (line.isNotEmpty()) return line
                    } else if (c != '\r'.code) {
                        buf.append(c.toChar())
                    }
                    continue
                }
                if (!proc.isAlive) break
                Thread.sleep(20)
            }
            if (reader.ready()) {
                val rest = reader.readLine()
                if (!rest.isNullOrBlank()) return rest.trim()
            }
            val err = if (Files.isRegularFile(errPath)) Files.readString(errPath) else ""
            error(
                "fakeimap pin: process wrote no bound-address JSON to stdout " +
                    "(alive=${proc.isAlive}, exit=${if (proc.isAlive) "n/a" else proc.exitValue()}, see $errPath): $err",
            )
        }
    }
}

internal fun parseJsonObject(text: String): Map<String, Any?> {
    val t = text.trim()
    require(t.startsWith("{") && t.endsWith("}")) { "not a JSON object: $text" }
    return parseValue(t) as Map<String, Any?>
}

private fun parseValue(src: String): Any? {
    val s = src.trim()
    return when {
        s == "null" -> null
        s == "true" -> true
        s == "false" -> false
        s.startsWith("\"") -> unquote(s)
        s.startsWith("{") -> parseObject(s)
        s.startsWith("[") -> parseArray(s)
        else -> s.toLongOrNull() ?: s.toDoubleOrNull() ?: s
    }
}

private fun parseObject(s: String): Map<String, Any?> {
    val inner = s.substring(1, s.length - 1).trim()
    if (inner.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, Any?>()
    var i = 0
    val chars = inner.toCharArray()
    while (i < chars.size) {
        while (i < chars.size && chars[i].isWhitespace()) i++
        require(i < chars.size && chars[i] == '"') { "expected key in $s" }
        val (key, next) = readString(inner, i)
        i = next
        while (i < chars.size && chars[i].isWhitespace()) i++
        require(i < chars.size && chars[i] == ':') { "expected : in $s" }
        i++
        val (valueText, after) = readValue(inner, i)
        out[key] = parseValue(valueText)
        i = after
        while (i < chars.size && chars[i].isWhitespace()) i++
        if (i < chars.size && chars[i] == ',') i++
    }
    return out
}

private fun parseArray(s: String): List<Any?> {
    val inner = s.substring(1, s.length - 1).trim()
    if (inner.isEmpty()) return emptyList()
    val out = ArrayList<Any?>()
    var i = 0
    while (i < inner.length) {
        while (i < inner.length && inner[i].isWhitespace()) i++
        if (i >= inner.length) break
        val (valueText, after) = readValue(inner, i)
        out.add(parseValue(valueText))
        i = after
        while (i < inner.length && inner[i].isWhitespace()) i++
        if (i < inner.length && inner[i] == ',') i++
    }
    return out
}

private fun readString(s: String, start: Int): Pair<String, Int> {
    require(s[start] == '"')
    var i = start + 1
    val buf = StringBuilder()
    while (i < s.length) {
        val c = s[i]
        if (c == '\\') {
            require(i + 1 < s.length)
            buf.append(unescape(s[i + 1]))
            i += 2
            continue
        }
        if (c == '"') return buf.toString() to (i + 1)
        buf.append(c)
        i++
    }
    error("unterminated string")
}

private fun unquote(s: String): String {
    val inner = s.substring(1, s.length - 1)
    val buf = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c == '\\' && i + 1 < inner.length) {
            buf.append(unescape(inner[i + 1]))
            i += 2
        } else {
            buf.append(c)
            i++
        }
    }
    return buf.toString()
}

private fun unescape(c: Char): Char = when (c) {
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    '"' -> '"'
    '\\' -> '\\'
    else -> c
}

private fun readValue(s: String, start: Int): Pair<String, Int> {
    var i = start
    while (i < s.length && s[i].isWhitespace()) i++
    if (i >= s.length) error("expected value")
    return when (s[i]) {
        '"' -> {
            val (_, end) = readString(s, i)
            s.substring(i, end) to end
        }
        '{', '[' -> {
            val end = skipBalanced(s, i)
            s.substring(i, end) to end
        }
        else -> {
            val end = (i until s.length).firstOrNull { s[it] == ',' || s[it] == '}' || s[it] == ']' }
                ?: s.length
            s.substring(i, end).trim() to end
        }
    }
}

private fun skipBalanced(s: String, start: Int): Int {
    var depth = 0
    var i = start
    var inStr = false
    var esc = false
    val open = s[start]
    val close = if (open == '{') '}' else ']'
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
        } else {
            when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        i++
    }
    error("unbalanced $open")
}
