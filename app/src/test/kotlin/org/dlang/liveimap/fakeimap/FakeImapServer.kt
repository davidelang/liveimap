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
import java.util.concurrent.atomic.AtomicBoolean

enum class FakeImapProfile(val capability: String) {
    Minimal("IMAP4rev1"),
    Cyrus22("IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES IDLE"),
}

class FakeImapServer(private val profile: FakeImapProfile) : Closeable {
    private val listen = ServerSocket()
    private val running = AtomicBoolean(true)
    private val thread: Thread

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
        while (true) {
            val line = reader.readLine() ?: return
            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            val tag = tokens[0]
            val command = if (tokens.size >= 2) tokens[1].uppercase(Locale.ROOT) else ""
            if (command == "LOGOUT") {
                send("* BYE")
                send("$tag OK")
                return
            }
            reply(command, tag, tokens, ::send)
        }
    }

    private fun reply(
        command: String,
        tag: String,
        tokens: List<String>,
        send: (String) -> Unit,
    ) {
        when (command) {
            "CAPABILITY" -> {
                send("* CAPABILITY ${profile.capability}")
                send("$tag OK")
            }
            "LOGIN" -> {
                val user = tokens.getOrNull(2).orEmpty()
                val password = tokens.getOrNull(3).orEmpty()
                if (user.isEmpty() || password.isEmpty()) {
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
                send("* OK [UIDVALIDITY 17] UIDs valid")
                send("$tag OK [READ-WRITE]")
            }
            "FETCH" -> {
                send("* 1 FETCH (FLAGS (\\Seen))")
                send("$tag OK")
            }
            else -> send("$tag BAD")
        }
    }

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
