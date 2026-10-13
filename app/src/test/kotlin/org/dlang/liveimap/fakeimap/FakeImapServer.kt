package org.dlang.liveimap.fakeimap

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class FakeImapProfile(val capability: String) {
    Minimal("IMAP4rev1"),
    Cyrus22("IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES IDLE"),
    /** F40: a server that offers STARTTLS (and LOGINDISABLED until TLS), for stripping tests. */
    StartTls("IMAP4rev1 STARTTLS LOGINDISABLED LITERAL+ IDLE"),
}

enum class FakeImapStep {
    Close,
    Silent,
    Bad,
    No,
    Bye,
    NewUidValidity,
    ExpungeDuringFetch,
    /** F40: answer the next command as a man in the middle would after stripping STARTTLS. */
    StripStartTls,
    /** F40: the next SELECT/FETCH is preceded by [FakeImapServer.vanishedFlood] VANISHED (EARLIER) lines. */
    VanishedFlood,
}

class FakeImapServer(
    profile: FakeImapProfile,
    private val capabilityLine: String = profile.capability,
) : Closeable {
    private val listen = ServerSocket()
    private val running = AtomicBoolean(true)
    private val steps = ConcurrentLinkedQueue<FakeImapStep>()
    private val thread: Thread

    /** F40: every client command as the server parsed it, literals inlined (in order). */
    val received = ConcurrentLinkedQueue<String>()

    /** F40: how many VANISHED lines a [FakeImapStep.VanishedFlood] sends. */
    @Volatile var vanishedFlood: Int = 5000

    /** F40: untagged lines sent in one write right after the IDLE continuation (an IDLE burst). */
    @Volatile var idleBurst: List<String> = emptyList()

    @Volatile
    private var current: Socket? = null

    val port: Int
    val boundHost: String

    init {
        listen.bind(InetSocketAddress("127.0.0.1", 0))
        port = listen.localPort
        boundHost = listen.inetAddress.hostAddress ?: ""
        thread = Thread({ acceptLoop() }, "fake-imap")
        thread.isDaemon = true
        thread.start()
    }

    fun script(step: FakeImapStep) {
        steps.add(step)
    }

    override fun close() {
        running.set(false)
        try {
            listen.close()
        } catch (e: IOException) {
        }
        try {
            current?.close()
        } catch (e: IOException) {
        }
        thread.join(2_000)
    }

    private fun acceptLoop() {
        while (running.get()) {
            val accepted = try {
                listen.accept()
            } catch (e: IOException) {
                break
            }
            accepted.tcpNoDelay = true
            current = accepted
            try {
                serve(accepted)
            } catch (e: IOException) {
            } finally {
                current = null
                try {
                    accepted.close()
                } catch (e: IOException) {
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        fun send(line: String) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }
        send("* OK fake ready")
        var stripping = false
        while (true) {
            val line = readCommand(reader, ::send) ?: return
            received.add(line)
            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            val tag = tokens[0]
            val command = if (tokens.size >= 2) tokens[1].uppercase(Locale.ROOT) else ""
            when (val step = steps.poll()) {
                FakeImapStep.Close -> {
                    socket.close()
                    return
                }
                FakeImapStep.Silent -> continue
                FakeImapStep.Bad -> send("$tag BAD")
                FakeImapStep.No -> send("$tag NO")
                FakeImapStep.Bye -> {
                    send("* BYE")
                    socket.close()
                    return
                }
                FakeImapStep.StripStartTls -> {
                    // A relay that removes STARTTLS: the capability loses it and the command is refused.
                    stripping = true
                    when (command) {
                        "CAPABILITY" -> {
                            send("* CAPABILITY " + capabilityLine.split(' ').filter { !it.equals("STARTTLS", true) }.joinToString(" "))
                            send("$tag OK")
                        }
                        else -> send("$tag BAD unknown command")
                    }
                }
                else -> {
                    val uidValidity = if (step == FakeImapStep.NewUidValidity) 99 else 17
                    val expungeDuringFetch = step == FakeImapStep.ExpungeDuringFetch
                    if (command == "LOGOUT") {
                        send("* BYE")
                        send("$tag OK")
                        return
                    }
                    if (step == FakeImapStep.VanishedFlood && (command == "SELECT" || command == "FETCH" || command == "UID")) {
                        val flood = StringBuilder()
                        for (i in 0 until vanishedFlood) flood.append("* VANISHED (EARLIER) ${2 * i + 1}:${2 * i + 2}\r\n")
                        writer.write(flood.toString())
                        writer.flush()
                    }
                    if (command == "IDLE") {
                        val burst = StringBuilder("+ idling\r\n")
                        for (l in idleBurst) burst.append(l).append("\r\n")
                        writer.write(burst.toString())
                        writer.flush()
                        val done = reader.readLine() ?: return
                        received.add(done)
                        send(if (done.equals("DONE", true)) "$tag OK IDLE terminated" else "$tag BAD expected DONE")
                        continue
                    }
                    if (command == "STARTTLS" && stripping) {
                        send("$tag BAD unknown command")
                        continue
                    }
                    reply(command, tag, tokens, ::send, uidValidity, expungeDuringFetch)
                }
            }
        }
    }

    private fun reply(
        command: String,
        tag: String,
        tokens: List<String>,
        send: (String) -> Unit,
        uidValidity: Int,
        expungeDuringFetch: Boolean,
    ) {
        when (command) {
            "CAPABILITY" -> {
                send("* CAPABILITY $capabilityLine")
                send("$tag OK")
            }
            "LOGIN" -> {
                val user = tokens.getOrNull(2).orEmpty()
                val password = tokens.getOrNull(3).orEmpty()
                if (capabilityLine.split(' ').any { it.equals("LOGINDISABLED", true) }) {
                    send("$tag NO [PRIVACYREQUIRED] LOGIN disabled")
                } else if (user.isEmpty() || password.isEmpty()) {
                    send("$tag NO")
                } else {
                    send("$tag OK")
                }
            }
            "LIST" -> {
                send("* LIST (\\Noinferiors) NIL INBOX")
                send("$tag OK")
            }
            "SELECT" -> {
                send("* 1 EXISTS")
                send("* OK [UIDVALIDITY $uidValidity] UIDs valid")
                send("$tag OK [READ-WRITE]")
            }
            "FETCH" -> {
                if (expungeDuringFetch) send("* 1 EXPUNGE")
                send("* 1 FETCH (FLAGS (\\Seen))")
                send("$tag OK")
            }
            else -> send("$tag BAD")
        }
    }

    /**
     * One client command. A line ending in a literal ({n} synchronizing: the server answers "+"; {n+}: LITERAL+,
     * no wait) is followed by n characters and the rest of the command; the literal is inlined as a quoted token.
     */
    private fun readCommand(reader: BufferedReader, send: (String) -> Unit): String? {
        val out = StringBuilder()
        var line = reader.readLine() ?: return null
        while (true) {
            val m = literalAtEnd.find(line)
            if (m == null) {
                out.append(line)
                return out.toString()
            }
            out.append(line.substring(0, m.range.first))
            val n = m.groupValues[1].toInt()
            if (m.groupValues[2].isEmpty()) send("+ go ahead")
            val buf = CharArray(n)
            var got = 0
            while (got < n) {
                val r = reader.read(buf, got, n - got)
                if (r < 0) return null
                got += r
            }
            out.append('"').append(String(buf)).append('"')
            line = reader.readLine() ?: return null
        }
    }

    private val literalAtEnd = Regex("""\{(\d+)(\+?)\}$""")

    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = line.length
        while (i < n) {
            while (i < n && line[i] == ' ') i++
            if (i >= n) break
            if (line[i] == '"') {
                i++
                val start = i
                while (i < n && line[i] != '"') i++
                out.add(line.substring(start, i))
                if (i < n && line[i] == '"') i++
            } else {
                val start = i
                while (i < n && line[i] != ' ') i++
                out.add(line.substring(start, i))
            }
        }
        return out
    }
}
