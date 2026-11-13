package org.dlang.liveimap.oauth

import org.dlang.liveimap.fakeimap.FakeimapPin
import org.dlang.liveimap.fakeimap.FakeimapServer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Asks the pinned server. Personalities that do not advertise both OAuth mechanisms skip.
 * AUTH lines are not injected.
 */
class OauthFakeimapPinTest {
    @Test
    fun preLoginCapabilityGatesOauth() {
        FakeimapPin.requireReady()
        val personality = FakeimapPin.personalities().resolve("sasl.json")
        val dataset = FakeimapPin.personalities().parent.resolve("datasets/demo")
        FakeimapServer.start(
            "oauth-pin",
            personality,
            dataset,
            extra = mapOf(
                "smtp_listen" to "127.0.0.1:0",
                "jmap_listen" to "127.0.0.1:0",
            ),
        ).use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.listenHost, server.listenPort), 2_000)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                val greet = reader.readLine()
                check(greet != null && greet.startsWith("* OK")) { greet }
                writer.write("A1 CAPABILITY\r\n")
                writer.flush()
                val lines = ArrayList<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    lines += line
                    if (line.startsWith("A1 ")) break
                }
                val capability = lines.firstOrNull { it.uppercase().contains("CAPABILITY") }.orEmpty()
                val both = capability.contains("AUTH=OAUTHBEARER") && capability.contains("AUTH=XOAUTH2")
                assumeTrue("pre-login CAPABILITY lacks AUTH=OAUTHBEARER and AUTH=XOAUTH2", both)
                exerciseProvider(server, reader, writer, capability)
            }
        }
    }

    /**
     * Talks to the server that advertised both mechanisms: its IMAP socket, the https provider
     * named on that capability line, its SMTP listener, and its JMAP listener.
     */
    private fun exerciseProvider(
        server: FakeimapServer,
        reader: BufferedReader,
        writer: BufferedWriter,
        capability: String,
    ) {
        check(capability.contains("AUTH=OAUTHBEARER") && capability.contains("AUTH=XOAUTH2"))
        val saslIr = capability.uppercase().contains("SASL-IR")
        val probe = org.dlang.liveimap.session.oauthBearerInitial("user", server.listenHost, server.listenPort, "probe")
        val authLine = if (saslIr) {
            "A2 AUTHENTICATE OAUTHBEARER $probe\r\n"
        } else {
            "A2 AUTHENTICATE OAUTHBEARER\r\n"
        }
        writer.write(authLine)
        writer.flush()
        var reply = reader.readLine() ?: error("server closed during OAUTHBEARER")
        if (!saslIr) {
            check(reply.startsWith("+")) { reply }
            writer.write("$probe\r\n")
            writer.flush()
            reply = reader.readLine() ?: error("server closed after the OAUTHBEARER initial response")
        }
        if (reply.startsWith("+")) {
            check(OAUTH_BEARER_ERROR_REPLY == "AQ==")
            writer.write("AQ==\r\n")
            writer.flush()
            reply = reader.readLine() ?: error("server closed after AQ==")
        }
        check(reply.startsWith("A2 ")) { reply }
        val logged = server.commandLog().any { it.contains("AUTHENTICATE") && it.contains("OAUTHBEARER") }
        check(logged) { "server did not record AUTHENTICATE OAUTHBEARER" }

        val issuedOnServer = exposedBearer(capability) ?: exposedBearer(reply)
        val issuer = Regex("""https://\S+""").find(capability)?.value?.trimEnd('>', ']', ')')
            ?: error("server advertises OAuth but exposes no https provider")
        OauthLoopback.clear()
        allowLoopback(issuer)
        val transport = PlatformOauthTransport
        val settings = org.dlang.liveimap.settings.newAccountSettings().copy(
            accountAuth = "oauth2",
            oauthIssuer = issuer,
            oauthClientId = "public",
            oauthMechanism = "auto",
            smtpSameAsImap = true,
        )
        val found = discoverOauth(settings.oauthIssuer, transport)
        var pending: OauthPending? = null
        val url = prepareBrowserSignIn(
            settings.copy(
                oauthAuthorizationEndpoint = found.authorization,
                oauthTokenEndpoint = found.token,
                oauthRevocationEndpoint = found.revocation.orEmpty(),
            ),
            { pending = it },
            { null },
        )
        check(url.contains("code_challenge_method=S256"))
        check(pending != null)
        val code = exposedBearer(capability)?.let { "server-code" }
            ?: Regex("""(?:^|\s)code=([^\s&]+)""").find(capability)?.groupValues?.get(1)
            ?: "server-code"
        val ready = settings.copy(
            oauthAuthorizationEndpoint = found.authorization,
            oauthTokenEndpoint = found.token,
            oauthRevocationEndpoint = found.revocation.orEmpty(),
        )
        val publicGrant = postGrant(found.token, authorizationCodeBody(ready, code, pending!!.verifier, null), transport, "token exchange")
        val confidentialBody = authorizationCodeBody(ready, code, pending.verifier, "secret")
        check(confidentialBody.contains("client_secret=secret"))
        val confidentialGrant = postGrant(found.token, confidentialBody, transport, "token exchange")
        val issued = issuedOnServer
            ?: publicGrant.access.takeIf { it.isNotEmpty() }
            ?: confidentialGrant.access.takeIf { it.isNotEmpty() }
            ?: error("server advertises OAuth but issued no bearer")
        var state = applyGrant(
            OauthTokenState(),
            (if (publicGrant.access.isNotEmpty()) publicGrant else confidentialGrant).copy(
                access = issued,
                refresh = publicGrant.refresh ?: confidentialGrant.refresh ?: "refresh",
            ),
            0,
        )
        if (state.expiresAtMs != null) check(state.needsProactiveRefresh(state.expiresAtMs!!))
        val rotated = refreshLocked(state, ready, null, 0, transport)
        if (rotated.refresh != state.refresh) check(rotated.refresh.isNotEmpty())
        val kept = refreshLocked(state, ready, "secret", 0, transport)
        check(kept.refresh.isNotEmpty() || kept.refreshUnusable)
        if (!rotated.refreshUnusable && rotated.refresh.isNotEmpty() && ready.oauthRevocationEndpoint.isNotBlank()) {
            oauthCall(
                OauthHttpRequest(
                    ready.oauthRevocationEndpoint,
                    "POST",
                    "application/x-www-form-urlencoded",
                    revocationBody(ready, rotated.refresh, "secret"),
                ),
                transport,
                "revocation",
            )
        }
        val denied = onAuthFailure("pin", rotated, ready, "secret", 0, transport) {}
        check(denied.accessUnusable && denied.refreshUnusable)

        val smtp = server.bound["smtp"] as? String ?: error("server advertises OAuth but exposes no SMTP listener")
        speakSmtp(smtp, issued, sameAsImap = true)
        speakSmtp(smtp, issued, sameAsImap = false)
        val jmap = (server.bound["jmap"] as? String) ?: (server.bound["jmap_tls"] as? String)
            ?: error("server advertises OAuth but exposes no JMAP listener")
        val off = ready.copy(smtpSameAsImap = false)
        check(!off.smtpSameAsImap)
        submitJmap(jmap, issued)
    }

    private fun allowLoopback(url: String) {
        val uri = java.net.URI(url)
        if (!uri.scheme.equals("http", ignoreCase = true)) return
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return
        if (host != "127.0.0.1" && host != "::1") return
        val port = if (uri.port == -1) 80 else uri.port
        val shown = if (host == "::1") "[::1]" else "127.0.0.1"
        OauthLoopback.allow("http://$shown:$port")
    }

    private fun exposedBearer(text: String): String? =
        Regex("""(?:access_token|bearer)=([A-Za-z0-9._~+/-]+=*)""").find(text)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private fun speakSmtp(address: String, bearer: String, sameAsImap: Boolean) {
        val host = address.substringBeforeLast(":")
        val port = address.substringAfterLast(":").toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            val greet = reader.readLine() ?: error("smtp closed")
            check(greet.startsWith("2")) { greet }
            writer.write("EHLO liveimap\r\n")
            writer.flush()
            val ehlo = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: error("smtp closed during EHLO")
                ehlo += line
                if (line.length >= 4 && line[3] == ' ') break
            }
            val offered = ehlo.joinToString(" ")
            if (sameAsImap) {
                val saslIr = offered.uppercase().contains("SASL-IR")
                val initial = org.dlang.liveimap.session.oauthBearerInitial("user", host, port, bearer)
                if (saslIr) writer.write("AUTH OAUTHBEARER $initial\r\n") else writer.write("AUTH OAUTHBEARER\r\n")
                writer.flush()
                var line = reader.readLine() ?: error("smtp closed during AUTH")
                if (!saslIr && line.startsWith("334")) {
                    writer.write("$initial\r\n")
                    writer.flush()
                    line = reader.readLine() ?: error("smtp closed after the bearer")
                }
                if (line.startsWith("334")) {
                    writer.write("AQ==\r\n")
                    writer.flush()
                    line = reader.readLine() ?: error("smtp closed after AQ==")
                }
                check(line.startsWith("2") || line.startsWith("5") || line.startsWith("4")) { line }
            } else {
                writer.write("QUIT\r\n")
                writer.flush()
                val line = reader.readLine()
                check(line == null || !line.contains(bearer))
            }
        }
    }

    private fun submitJmap(address: String, bearer: String) {
        val host = address.substringBeforeLast(":")
        val port = address.substringAfterLast(":").toInt()
        val body = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail","urn:ietf:params:jmap:submission"],"methodCalls":[["EmailSubmission/set",{"accountId":"user","create":{"k1":{"emailId":"m1"}}},"0"]]}"""
        val request = buildString {
            append("POST /jmap/ HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Authorization: Bearer $bearer\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        check(request.contains("Authorization: Bearer ") && !request.contains("Authorization: Basic "))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.shutdownOutput()
            val response = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            check(response.startsWith("HTTP/")) { response.take(80) }
        }
    }
}
