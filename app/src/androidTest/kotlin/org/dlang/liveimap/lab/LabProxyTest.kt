package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual timing runs against a real account configured in the app (David-approved): a loopback proxy inside
 * the app process logs in with the account's stored credentials and hands the host script an already
 * authenticated connection (greeting "* PREAUTH"), through `adb forward`. The password never leaves the app
 * process and is never logged. Args: labAccountMatch (substring of the IMAP host), labProxyPort, labProxySeconds.
 * The app's chosen account is restored right after reading the credentials.
 */
@RunWith(AndroidJUnit4::class)
class LabProxyTest {
    private fun arg(name: String) = InstrumentationRegistry.getArguments().getString(name).orEmpty()

    @Test
    fun authenticatedProxy() {
        val match = arg("labAccountMatch")
        assumeTrue("labAccountMatch not given", match.isNotEmpty())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore.shared(ctx)
        val seen = mutableListOf<String>()
        data class Target(val host: String, val port: Int, val tls: String, val user: String, val password: String)
        val target = runBlocking {
            val accounts = store.listAccounts()
            val original = accounts.firstOrNull { it.chosen }?.id
            var found: Target? = null
            try {
                for (a in accounts) {
                    store.selectAccount(a.id)
                    val s = store.load()
                    seen += "${a.name} -> ${s.username}@${s.imapHost}"
                    if (s.imapHost.contains(match, ignoreCase = true) || a.name.contains(match, ignoreCase = true)) {
                        found = Target(s.imapHost, s.imapPort, s.tlsMode.name, s.username, store.password())
                        break
                    }
                }
            } finally {
                if (original != null) store.selectAccount(original)
            }
            found
        } ?: throw IllegalStateException("no account whose name or IMAP host contains \"$match\"; accounts: $seen")
        android.util.Log.i("LiveIMAP", "proxy: account ${target.user}@${target.host}:${target.port} ${target.tls}")
        val server = ServerSocket(arg("labProxyPort").toIntOrNull() ?: 18143, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 1000
        val end = System.currentTimeMillis() + (arg("labProxySeconds").toLongOrNull() ?: 3600) * 1000
        while (System.currentTimeMillis() < end) {
            val client = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            Thread { serve(client, target.host, target.port, target.tls, target.user, target.password) }.start()
        }
        server.close()
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) throw java.io.EOFException()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun serve(client: Socket, host: String, port: Int, tls: String, user: String, password: String) {
        try {
            var up: Socket = if (tls == "Implicit") SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
            var i = up.getInputStream(); var o = up.getOutputStream()
            readLine(i)
            if (tls == "StartTls") {
                o.write("s STARTTLS\r\n".toByteArray()); o.flush()
                while (!readLine(i).startsWith("s ")) Unit
                up = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(up, host, port, true) as SSLSocket
                (up as SSLSocket).startHandshake()
                i = up.getInputStream(); o = up.getOutputStream()
            }
            o.write("l LOGIN ${quote(user)} ${quote(password)}\r\n".toByteArray()); o.flush()
            var line: String
            do { line = readLine(i) } while (!line.startsWith("l "))
            val co = client.getOutputStream()
            if (!line.startsWith("l OK")) {
                co.write("* BYE proxy login failed\r\n".toByteArray()); co.flush(); client.close(); up.close(); return
            }
            co.write("* PREAUTH proxy (logged in by the app)\r\n".toByteArray()); co.flush()
            val upFinal = up
            val t = Thread { pump(client.getInputStream(), upFinal.getOutputStream(), client, upFinal) }
            t.start()
            pump(i, co, client, upFinal)
        } catch (e: Exception) {
            android.util.Log.w("LiveIMAP", "proxy connection ended: ${e.javaClass.simpleName}")
            runCatching { client.close() }
        }
    }

    private fun pump(src: InputStream, dst: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { a.close() }; runCatching { b.close() }
        }
    }
}
