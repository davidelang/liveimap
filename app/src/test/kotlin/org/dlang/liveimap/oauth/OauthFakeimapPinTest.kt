package org.dlang.liveimap.oauth

import org.dlang.liveimap.fakeimap.FakeimapPin
import org.dlang.liveimap.fakeimap.FakeimapServer
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

/**
 * Discovery, code exchange, refresh, and revocation against the pinned stalwart-oauth listener.
 * The issuer is the bound oauth address. The test does not open an IMAP, SMTP, or JMAP socket.
 */
class OauthFakeimapPinTest {
    @Test
    fun discoverAndExchangeAgainstOauthListener() {
        FakeimapPin.requireReady()
        val personality = FakeimapPin.personalities().resolve("stalwart-oauth.json")
        val dataset = FakeimapPin.personalities().parent.resolve("datasets/demo")
        FakeimapServer.start(
            "oauth-pin",
            personality,
            dataset,
            extra = mapOf("oauth_listen" to "127.0.0.1:0"),
        ).use { server ->
            val bound = server.bound["oauth"] as? String
                ?: error("stalwart-oauth bound no oauth listener")
            val issuer = "http://$bound"
            OauthLoopback.clear()
            allowLoopback(issuer)
            server.control(
                mapOf(
                    "op" to "register-client",
                    "client_id" to "liveimap-public",
                    "secret" to "",
                    "redirect_uris" to listOf(OAUTH_REDIRECT_URI),
                ),
            )
            val transport = PlatformOauthTransport
            val found = discoverOauth(issuer, transport)
            check(found.authorization.isNotBlank() && found.token.isNotBlank()) {
                "discovery returned no endpoints"
            }
            check(found.revocation.orEmpty().isNotBlank()) { "discovery returned no revocation endpoint" }
            val settings = org.dlang.liveimap.settings.newAccountSettings().copy(
                accountAuth = "oauth2",
                oauthIssuer = issuer,
                oauthClientId = "liveimap-public",
                oauthMechanism = "auto",
                oauthAuthorizationEndpoint = found.authorization,
                oauthTokenEndpoint = found.token,
                oauthRevocationEndpoint = found.revocation.orEmpty(),
            )
            var pending: OauthPending? = null
            val url = prepareBrowserSignIn(settings, { pending = it }, { null })
            check(url.contains("code_challenge=") && url.contains("code_challenge_method=S256"))
            val held = pending ?: error("browser sign-in wrote no pending verifier")
            val location = authorize(url + "&login_hint=demo")
            val code = queryParam(location, "code") ?: error("authorize redirect has no code")
            val state = queryParam(location, "state")
            check(state == held.state) { "authorize redirect state does not match" }
            val body = authorizationCodeBody(settings, code, held.verifier, null)
            check(!body.contains("client_secret"))
            val grant = postGrant(found.token, body, transport, "token exchange")
            check(grant.error == null && grant.access.isNotEmpty() && !grant.refresh.isNullOrEmpty()) {
                "public client was not issued a token (${grant.error})"
            }
            val firstRefresh = grant.refresh!!
            val rotated = postGrant(found.token, refreshBody(settings, firstRefresh, null), transport, "refresh")
            check(rotated.error == null && rotated.access.isNotEmpty()) { "refresh failed (${rotated.error})" }
            check(!rotated.refresh.isNullOrEmpty() && rotated.refresh != firstRefresh) {
                "refresh did not replace the refresh token"
            }
            oauthCall(
                OauthHttpRequest(
                    settings.oauthRevocationEndpoint,
                    "POST",
                    "application/x-www-form-urlencoded",
                    revocationBody(settings, rotated.refresh!!, null),
                ),
                transport,
                "revocation",
            )
            val denied = postGrant(
                found.token,
                refreshBody(settings, rotated.refresh, null),
                transport,
                "refresh",
            )
            check(denied.error == "invalid_grant") { "revoked refresh returned ${denied.error}" }
            val marked = onAuthFailure(
                "pin",
                OauthTokenState(access = rotated.access, refresh = rotated.refresh, expiresAtMs = 0),
                settings,
                null,
                0,
                transport,
            ) {}
            check(marked.accessUnusable && marked.refreshUnusable) {
                "revoked refresh left the tokens usable"
            }
        }
    }

    private fun authorize(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.connect()
        val location = conn.getHeaderField("Location")
        val status = conn.responseCode
        conn.disconnect()
        check(status == 302 && !location.isNullOrBlank()) { "authorize status $status location $location" }
        return location
    }

    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter("?", "")
        for (part in query.split("&")) {
            val key = part.substringBefore("=")
            if (key == name) {
                return URLDecoder.decode(part.substringAfter("="), "UTF-8")
            }
        }
        return null
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
}
