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
        check(capability.uppercase().contains("SASL-IR")) { "server advertises OAuth but not SASL-IR" }
        val probe = org.dlang.liveimap.session.oauthBearerInitial("user", server.listenHost, server.listenPort, "probe")
        val reply = authenticateBearer(reader, writer, "A2", probe, challenge = true)
        check(reply.startsWith("A2 ")) { reply }
        val logged = server.commandLog()
        check(logged.any { it.contains("AUTHENTICATE") && it.contains("OAUTHBEARER") && it.contains(probe) }) {
            "server did not record SASL-IR OAUTHBEARER"
        }

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
        check(found.authorization.isNotBlank() && found.token.isNotBlank()) { "discovery returned no endpoints" }
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
        check(url.contains("code_challenge=") && url.contains("code_challenge_method=S256"))
        check(pending != null)
        val code = Regex("""(?:^|[?&\s])code=([^\s&]+)""").find(capability)?.groupValues?.get(1)
            ?: error("server advertises OAuth but exposes no authorization code")
        val ready = settings.copy(
            oauthAuthorizationEndpoint = found.authorization,
            oauthTokenEndpoint = found.token,
            oauthRevocationEndpoint = found.revocation.orEmpty(),
        )
        val publicBody = authorizationCodeBody(ready, code, pending!!.verifier, null)
        check(!publicBody.contains("client_secret"))
        val publicGrant = postGrant(found.token, publicBody, transport, "token exchange")
        check(publicGrant.error == null && publicGrant.access.isNotEmpty()) {
            "public client was not issued a token (${publicGrant.error})"
        }
        val confidentialBody = authorizationCodeBody(ready, code, pending.verifier, "secret")
        check(confidentialBody.contains("client_secret=secret"))
        val confidentialGrant = postGrant(found.token, confidentialBody, transport, "token exchange")
        check(confidentialGrant.error == null && confidentialGrant.access.isNotEmpty()) {
            "confidential client was not issued a token (${confidentialGrant.error})"
        }
        val issued = exposedBearer(capability) ?: exposedBearer(reply) ?: publicGrant.access
        check(issued.isNotEmpty()) { "server advertises OAuth but issued no bearer without a browser" }
        val refresh = publicGrant.refresh ?: confidentialGrant.refresh
            ?: error("server advertises OAuth but issued no refresh token")
        val state = OauthTokenState(access = issued, refresh = refresh, expiresAtMs = 0)
        val expiredInitial = org.dlang.liveimap.session.oauthBearerInitial(
            "user",
            server.listenHost,
            server.listenPort,
            state.access,
        )
        authenticateBearer(reader, writer, "A3", expiredInitial, challenge = false)
        check(server.commandLog().any { it.contains(expiredInitial) }) {
            "expired access token was not presented"
        }
        var saved: OauthTokenState? = null
        val renewed = accessForLogin("pin", state, ready, "secret", 0, transport) { saved = it }
        check(renewed.isNotEmpty() && renewed != state.access) { "expired access token was not refreshed" }
        check(saved != null && saved!!.access == renewed && !saved!!.accessUnusable)

        val rotatedGrant = postGrant(found.token, refreshBody(ready, refresh, null), transport, "refresh")
        check(rotatedGrant.error == null && rotatedGrant.access.isNotEmpty()) { "refresh failed (${rotatedGrant.error})" }
        check(rotatedGrant.refresh != null && rotatedGrant.refresh != refresh) {
            "refresh did not replace the refresh token"
        }
        val rotated = applyGrant(state, rotatedGrant, 0)
        val keptGrant = postGrant(found.token, refreshBody(ready, rotated.refresh, "secret"), transport, "refresh")
        check(keptGrant.error == null && keptGrant.access.isNotEmpty()) { "refresh without a new refresh token failed" }
        check(keptGrant.refresh == null) { "refresh was required to omit refresh_token" }
        val kept = applyGrant(rotated, keptGrant, 0)
        check(kept.refresh == rotated.refresh && !kept.refreshUnusable && !kept.accessUnusable)

        check(ready.oauthRevocationEndpoint.isNotBlank()) {
            "server advertises OAuth but exposes no revocation endpoint"
        }
        oauthCall(
            OauthHttpRequest(
                ready.oauthRevocationEndpoint,
                "POST",
                "application/x-www-form-urlencoded",
                revocationBody(ready, kept.refresh, "secret"),
            ),
            transport,
            "revocation",
        )
        var refreshPosts = 0
        val counting = OauthTransport { request ->
            if (request.body.orEmpty().contains("grant_type=refresh_token")) refreshPosts++
            transport.exchange(request)
        }
        val denied = onAuthFailure("pin", kept, ready, "secret", 0, counting) {}
        check(refreshPosts == 1) { "invalid_grant refreshed $refreshPosts times" }
        check(denied.accessUnusable && denied.refreshUnusable) {
            "invalid_grant left the tokens usable"
        }
        val signIn = if (denied.access.isEmpty() || denied.accessUnusable) "Sign in again" else denied.access
        check(signIn == "Sign in again")

        val smtp = server.bound["smtp"] as? String ?: error("server advertises OAuth but exposes no SMTP listener")
        speakSmtp(smtp, renewed, sameAsImap = true)
        speakSmtp(smtp, renewed, sameAsImap = false)
        val jmap = (server.bound["jmap"] as? String) ?: (server.bound["jmap_tls"] as? String)
            ?: error("server advertises OAuth but exposes no JMAP listener")
        check(!ready.copy(smtpSameAsImap = false).smtpSameAsImap)
        submitJmap(jmap, renewed)
    }

    /** Sends AUTHENTICATE OAUTHBEARER with SASL-IR. [challenge] requires an error challenge answered by AQ==. */
    private fun authenticateBearer(
        reader: BufferedReader,
        writer: BufferedWriter,
        tag: String,
        initial: String,
        challenge: Boolean,
    ): String {
        writer.write("$tag AUTHENTICATE OAUTHBEARER $initial\r\n")
        writer.flush()
        var reply = reader.readLine() ?: error("server closed during OAUTHBEARER")
        if (challenge) {
            check(reply.startsWith("+")) { "server did not challenge OAUTHBEARER; AQ== was not sent ($reply)" }
        }
        if (reply.startsWith("+")) {
            check(OAUTH_BEARER_ERROR_REPLY == "AQ==")
            writer.write("AQ==\r\n")
            writer.flush()
            reply = reader.readLine() ?: error("server closed after AQ==")
        }
        return reply
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
            check(offered.uppercase().contains("AUTH")) { "smtp did not advertise AUTH" }
            if (sameAsImap) {
                check(offered.uppercase().contains("OAUTHBEARER")) { "smtp does not advertise OAUTHBEARER" }
                val initial = org.dlang.liveimap.session.oauthBearerInitial("user", host, port, bearer)
                val saslIr = offered.uppercase().contains("SASL-IR")
                val sent = if (saslIr) "AUTH OAUTHBEARER $initial" else "AUTH OAUTHBEARER"
                writer.write("$sent\r\n")
                writer.flush()
                var line = reader.readLine() ?: error("smtp closed during AUTH")
                var sentBearer = sent.contains(initial)
                if (!saslIr) {
                    check(line.startsWith("334")) { line }
                    writer.write("$initial\r\n")
                    writer.flush()
                    sentBearer = true
                    line = reader.readLine() ?: error("smtp closed after the bearer")
                }
                if (line.startsWith("334")) {
                    writer.write("AQ==\r\n")
                    writer.flush()
                    line = reader.readLine() ?: error("smtp closed after AQ==")
                }
                check(sentBearer) { "smtpSameAsImap did not send the bearer" }
                check(line.startsWith("235")) { "smtpSameAsImap did not accept the bearer ($line)" }
            } else {
                val plain = java.util.Base64.getEncoder().encodeToString("\u0000user\u0000secret".toByteArray(StandardCharsets.UTF_8))
                check(!plain.contains(bearer))
                writer.write("AUTH PLAIN $plain\r\n")
                writer.flush()
                var line = reader.readLine() ?: error("smtp closed during password AUTH")
                if (line.startsWith("334")) {
                    writer.write("$plain\r\n")
                    writer.flush()
                    line = reader.readLine() ?: error("smtp closed after the password")
                }
                check(!line.contains(bearer) && !line.contains("OAUTHBEARER"))
                check(line.startsWith("235")) { "smtp password login was not accepted ($line)" }
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
            check(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200")) {
                "EmailSubmission was not accepted (${response.take(80)})"
            }
            check(response.contains("EmailSubmission/set") && response.contains("\"created\"")) {
                "EmailSubmission/set did not create a submission"
            }
            check(!response.contains("\"notCreated\"")) { "EmailSubmission/set rejected the message" }
        }
    }
}
