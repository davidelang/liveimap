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
        FakeimapServer.start("oauth-pin", personality, dataset).use { server ->
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
                exerciseProvider(capability)
            }
        }
    }

    /**
     * The pinned personalities do not advertise both mechanisms, so this stays unreachable.
     * A server that does must name an https issuer on the capability text; this change does not
     * invent AUTH lines or an OAuth provider.
     */
    private fun exerciseProvider(capability: String) {
        check(capability.contains("AUTH=OAUTHBEARER") && capability.contains("AUTH=XOAUTH2"))
        val issuer = Regex("""https://[^ ]+""").find(capability)?.value
            ?: error("server advertises OAuth but exposes no https provider")
        val settings = org.dlang.liveimap.settings.newAccountSettings().copy(
            accountAuth = "oauth2",
            oauthIssuer = issuer,
            oauthClientId = "public",
            oauthMechanism = "auto",
            smtpSameAsImap = false,
        )
        OauthLoopback.clear()
        val transport = OauthTransport { request ->
            when {
                request.url.contains("oauth-authorization-server") -> OauthHttpResponse(
                    200,
                    """{"authorization_endpoint":"https://example.com/a","token_endpoint":"https://example.com/t","revocation_endpoint":"https://example.com/r"}""",
                )
                request.body?.contains("grant_type=refresh_token") == true && request.body!!.contains("refresh_token=revoked") ->
                    OauthHttpResponse(400, """{"error":"invalid_grant"}""")
                request.body?.contains("grant_type=refresh_token") == true && request.body!!.contains("refresh_token=rotate") ->
                    OauthHttpResponse(200, """{"access_token":"next","refresh_token":"newer","expires_in":30}""")
                request.body?.contains("grant_type=refresh_token") == true ->
                    OauthHttpResponse(200, """{"access_token":"next","expires_in":30}""")
                else -> OauthHttpResponse(200, """{"access_token":"issued","refresh_token":"rotate","expires_in":30}""")
            }
        }
        val found = discoverOauth(settings.oauthIssuer, transport)
        check(found.token == "https://example.com/t")
        var pending: OauthPending? = null
        val url = prepareBrowserSignIn(
            settings.copy(oauthAuthorizationEndpoint = found.authorization, oauthTokenEndpoint = found.token),
            { pending = it },
            { null },
        )
        check(url.contains("code_challenge_method=S256"))
        val verifier = pending!!.verifier
        val exchanged = postGrant(
            found.token,
            authorizationCodeBody(settings.copy(oauthTokenEndpoint = found.token), "code", verifier, null),
            transport,
            "token exchange",
        )
        check(exchanged.access == "issued")
        check(authorizationCodeBody(settings, "code", verifier, "secret").contains("client_secret=secret"))
        var state = applyGrant(OauthTokenState(), exchanged, 0)
        state = refreshLocked(state, settings.copy(oauthTokenEndpoint = found.token), null, 0, transport)
        check(state.refresh == "newer")
        val kept = refreshLocked(
            OauthTokenState(access = "old", refresh = "keep", expiresAtMs = 1),
            settings.copy(oauthTokenEndpoint = found.token),
            null,
            0,
            OauthTransport { OauthHttpResponse(200, """{"access_token":"only"}""") },
        )
        check(kept.refresh == "keep" && kept.access == "only")
        check(OauthTokenState(access = "old", refresh = "keep", expiresAtMs = 0).needsProactiveRefresh(0))
        val revoked = onAuthFailure(
            "pin",
            OauthTokenState(access = "a", refresh = "revoked"),
            settings.copy(oauthTokenEndpoint = found.token),
            null,
            0,
            transport,
        ) {}
        check(revoked.refreshUnusable && revoked.accessUnusable)
        check(!settings.smtpSameAsImap)
        val (secret, bearer) = org.dlang.liveimap.jmap.jmapPresented(settings.accountAuth, "imap-password", "issued")
        check(bearer && secret == "issued")
        check(org.dlang.liveimap.jmap.jmapAuthorization(settings.username, secret, bearer) == "Bearer issued")
    }
}
