package org.dlang.liveimap.oauth

import org.dlang.liveimap.settings.Settings
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val OAUTH_REDIRECT_URI: String = "com.davidlang.liveimap:/oauth2redirect"

const val OAUTH_BEARER_ERROR_REPLY: String = "AQ=="

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private const val REFRESH_SKEW_MS = 60_000L

class OauthFailure(message: String) : Exception(message)

data class OauthHttpRequest(
    val url: String,
    val method: String = "GET",
    val contentType: String? = null,
    val body: String? = null,
)

data class OauthHttpResponse(val status: Int, val body: String)

fun interface OauthTransport {
    fun exchange(request: OauthHttpRequest): OauthHttpResponse
}

/** Loopback http origins a test has started, such as http://127.0.0.1:9 or http://[::1]:9. */
object OauthLoopback {
    private val allowed = java.util.Collections.synchronizedSet(linkedSetOf<String>())

    fun allow(origin: String) {
        allowed.add(origin.trim())
    }

    fun clear() {
        allowed.clear()
    }

    fun permits(origin: String): Boolean = origin in allowed
}

fun oauthUrlAllowed(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host ?: return false
    if (scheme == "https") return true
    if (scheme != "http") return false
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare != "127.0.0.1" && bare != "::1") return false
    val port = if (uri.port == -1) 80 else uri.port
    val shown = if (bare == "::1") "[::1]" else "127.0.0.1"
    return OauthLoopback.permits("http://$shown:$port")
}

fun formBody(fields: List<Pair<String, String>>): String =
    fields.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
    }

fun oauthCall(request: OauthHttpRequest, transport: OauthTransport, step: String): OauthHttpResponse {
    if (!oauthUrlAllowed(request.url)) throw OauthFailure("$step: URL is not https")
    val response = try {
        transport.exchange(request)
    } catch (error: OauthFailure) {
        throw error
    } catch (error: Exception) {
        throw OauthFailure("$step: ${error.message ?: "request failed"}")
    }
    if (response.status in 300..399) throw OauthFailure("$step: redirect")
    return response
}

object PlatformOauthTransport : OauthTransport {
    override fun exchange(request: OauthHttpRequest): OauthHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.instanceFollowRedirects = false
        }
        connection.instanceFollowRedirects = false
        connection.requestMethod = request.method
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        if (request.body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", request.contentType ?: "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        connection.disconnect()
        return OauthHttpResponse(status, body)
    }
}

private fun hostPart(uri: URI): String {
    val host = uri.host ?: throw OauthFailure("discovery: issuer has no host")
    return if (host.contains(':')) "[$host]" else host
}

private fun originOf(uri: URI): String {
    val scheme = uri.scheme ?: throw OauthFailure("discovery: issuer has no scheme")
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://${hostPart(uri)}$port"
}

/** RFC 8414: insert the well-known segment before the issuer path. */
fun rfc8414DiscoveryUrl(issuer: String): String {
    val uri = URI(issuer.trim())
    var path = uri.rawPath ?: ""
    if (path.endsWith("/")) path = path.dropLast(1)
    return originOf(uri) + "/.well-known/oauth-authorization-server" + path
}

fun oidcDiscoveryUrl(issuer: String): String {
    val trimmed = issuer.trim().trimEnd('/')
    return "$trimmed/.well-known/openid-configuration"
}

data class OauthEndpoints(
    val authorization: String,
    val token: String,
    val revocation: String?,
)

sealed class DiscoveryDocument {
    data object None : DiscoveryDocument()
    data class Missing(val message: String) : DiscoveryDocument()
    data class Found(val endpoints: OauthEndpoints) : DiscoveryDocument()
}

fun parseDiscoveryDocument(body: String): DiscoveryDocument {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return DiscoveryDocument.None
    if (trimmed[0] == '[') return DiscoveryDocument.None
    val fields = jsonStringFields(trimmed) ?: return DiscoveryDocument.None
    val authorization = fields["authorization_endpoint"].orEmpty()
    val token = fields["token_endpoint"].orEmpty()
    if (authorization.isBlank() || token.isBlank()) {
        return DiscoveryDocument.Missing("discovery: authorization or token endpoint is missing")
    }
    val revocation = fields["revocation_endpoint"]?.takeIf { it.isNotBlank() }
    return DiscoveryDocument.Found(OauthEndpoints(authorization, token, revocation))
}

/** Flat JSON object of string values. A non-object is null. */
internal fun jsonStringFields(text: String): Map<String, String>? {
    val body = text.trim()
    if (body.length < 2 || body.first() != '{' || body.last() != '}') return null
    val out = linkedMapOf<String, String>()
    var i = 1
    fun skip() {
        while (i < body.length && body[i].isWhitespace()) i++
    }
    fun readString(): String? {
        skip()
        if (i >= body.length || body[i] != '"') return null
        i++
        val start = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                if (i + 1 >= body.length) return null
                val n = body[i + 1]
                start.append(
                    when (n) {
                        '"', '\\', '/' -> n
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> n
                    },
                )
                i += 2
                continue
            }
            if (c == '"') {
                i++
                return start.toString()
            }
            start.append(c)
            i++
        }
        return null
    }
    while (i < body.length - 1) {
        skip()
        if (i < body.length && body[i] == ',') {
            i++
            continue
        }
        if (body[i] == '}') break
        val key = readString() ?: return null
        skip()
        if (i >= body.length || body[i] != ':') return null
        i++
        skip()
        if (i >= body.length) return null
        if (body[i] == '"') {
            val value = readString() ?: return null
            out[key] = value
        } else if (body[i] == '{' || body[i] == '[') {
            val open = body[i]
            val close = if (open == '{') '}' else ']'
            var depth = 0
            while (i < body.length) {
                if (body[i] == '"') {
                    readString() ?: return null
                    continue
                }
                if (body[i] == open) depth++
                if (body[i] == close) {
                    depth--
                    if (depth == 0) {
                        i++
                        break
                    }
                }
                i++
            }
        } else {
            val start = i
            while (i < body.length && body[i] != ',' && body[i] != '}') i++
            out[key] = body.substring(start, i).trim()
        }
    }
    return out
}

private fun fetchDiscovery(url: String, transport: OauthTransport): DiscoveryDocument {
    val response = try {
        oauthCall(OauthHttpRequest(url), transport, "discovery")
    } catch (_: OauthFailure) {
        return DiscoveryDocument.None
    }
    if (response.status !in 200..299) return DiscoveryDocument.None
    return parseDiscoveryDocument(response.body)
}

fun discoverOauth(issuer: String, transport: OauthTransport): OauthEndpoints {
    if (issuer.isBlank()) throw OauthFailure("discovery: issuer is empty")
    when (val first = fetchDiscovery(rfc8414DiscoveryUrl(issuer), transport)) {
        is DiscoveryDocument.Found -> return first.endpoints
        is DiscoveryDocument.Missing -> throw OauthFailure(first.message)
        DiscoveryDocument.None -> Unit
    }
    return when (val second = fetchDiscovery(oidcDiscoveryUrl(issuer), transport)) {
        is DiscoveryDocument.Found -> second.endpoints
        is DiscoveryDocument.Missing -> throw OauthFailure(second.message)
        DiscoveryDocument.None -> throw OauthFailure("discovery: no authorization server document")
    }
}

fun pkceVerifier(random: SecureRandom = SecureRandom(), length: Int = 64): String {
    val n = length.coerceIn(43, 128)
    val bytes = ByteArray(n)
    random.nextBytes(bytes)
    return buildString(n) {
        for (b in bytes) append(UNRESERVED[(b.toInt() and 0xff) % UNRESERVED.length])
    }
}

fun pkceS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

fun authorizationUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    challenge: String,
    state: String,
    scopes: String,
): String {
    val fields = mutableListOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "state" to state,
    )
    if (scopes.isNotBlank()) fields += "scope" to scopes.trim()
    val join = if (endpoint.contains('?')) "&" else "?"
    return endpoint + join + formBody(fields)
}

data class OauthPending(val verifier: String, val state: String)

data class OauthTokenState(
    val access: String = "",
    val refresh: String = "",
    val expiresAtMs: Long? = null,
    val accessUnusable: Boolean = false,
    val refreshUnusable: Boolean = false,
    val streak: Int = 0,
)

fun OauthTokenState.signedIn(now: Long): Boolean {
    if (refresh.isNotEmpty() && !refreshUnusable) return true
    if (access.isEmpty() || accessUnusable) return false
    val expiry = expiresAtMs ?: return true
    return expiry - now > REFRESH_SKEW_MS
}

fun OauthTokenState.needsProactiveRefresh(now: Long): Boolean {
    val expiry = expiresAtMs ?: return false
    if (access.isEmpty() || accessUnusable) return false
    return expiry - now <= REFRESH_SKEW_MS
}

fun encodeTokenState(state: OauthTokenState): String {
    fun esc(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    val lines = mutableListOf(
        "access=${esc(state.access)}",
        "refresh=${esc(state.refresh)}",
        "accessUnusable=${if (state.accessUnusable) 1 else 0}",
        "refreshUnusable=${if (state.refreshUnusable) 1 else 0}",
        "streak=${state.streak}",
    )
    if (state.expiresAtMs != null) lines += "expiresAtMs=${state.expiresAtMs}"
    return lines.joinToString("\n")
}

fun decodeTokenState(text: String): OauthTokenState? {
    if (text.isBlank()) return null
    val values = linkedMapOf<String, String>()
    for (line in text.lineSequence()) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        values[line.substring(0, eq)] = line.substring(eq + 1).replace("\\n", "\n").replace("\\\\", "\\")
    }
    if ("access" !in values && "refresh" !in values) return null
    return OauthTokenState(
        access = values["access"].orEmpty(),
        refresh = values["refresh"].orEmpty(),
        expiresAtMs = values["expiresAtMs"]?.toLongOrNull(),
        accessUnusable = values["accessUnusable"] == "1",
        refreshUnusable = values["refreshUnusable"] == "1",
        streak = values["streak"]?.toIntOrNull() ?: 0,
    )
}

fun encodePending(pending: OauthPending): String =
    "verifier=${pending.verifier.replace("\n", "")}\nstate=${pending.state.replace("\n", "")}"

fun decodePending(text: String): OauthPending? {
    val values = linkedMapOf<String, String>()
    for (line in text.lineSequence()) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        values[line.substring(0, eq)] = line.substring(eq + 1)
    }
    val verifier = values["verifier"] ?: return null
    val state = values["state"] ?: return null
    if (verifier.isEmpty() || state.isEmpty()) return null
    return OauthPending(verifier, state)
}

data class TokenGrant(
    val access: String = "",
    val refresh: String? = null,
    val expiresIn: Long? = null,
    val error: String? = null,
)

fun parseTokenGrant(body: String): TokenGrant {
    val fields = jsonStringFields(body.trim()) ?: return TokenGrant(error = "token exchange: response is not json")
    val error = fields["error"]?.takeIf { it.isNotBlank() }
    val access = fields["access_token"].orEmpty()
    val refresh = fields["refresh_token"]?.takeIf { it.isNotEmpty() }
    val expires = fields["expires_in"]?.toLongOrNull()
    return TokenGrant(access, refresh, expires, error)
}

fun applyGrant(state: OauthTokenState, grant: TokenGrant, now: Long): OauthTokenState {
    if (grant.error == "invalid_grant" || grant.access.isEmpty()) {
        return state.copy(accessUnusable = true, refreshUnusable = true)
    }
    return state.copy(
        access = grant.access,
        refresh = grant.refresh ?: state.refresh,
        expiresAtMs = grant.expiresIn?.let { now + it * 1000 },
        accessUnusable = false,
        refreshUnusable = if (grant.refresh != null) false else state.refreshUnusable,
    )
}

private fun tokenFields(settings: Settings, secret: String?, extra: List<Pair<String, String>>): List<Pair<String, String>> {
    val fields = extra.toMutableList()
    fields += "client_id" to settings.oauthClientId
    if (!secret.isNullOrEmpty()) fields += "client_secret" to secret
    return fields
}

fun authorizationCodeBody(settings: Settings, code: String, verifier: String, secret: String?): String {
    val fields = mutableListOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to OAUTH_REDIRECT_URI,
        "client_id" to settings.oauthClientId,
        "code_verifier" to verifier,
    )
    if (!secret.isNullOrEmpty()) fields += "client_secret" to secret
    if (settings.oauthScopes.isNotBlank()) fields += "scope" to settings.oauthScopes.trim()
    return formBody(fields)
}

fun refreshBody(settings: Settings, refresh: String, secret: String?): String =
    formBody(
        tokenFields(
            settings,
            secret,
            listOf("grant_type" to "refresh_token", "refresh_token" to refresh),
        ),
    )

fun revocationBody(settings: Settings, refresh: String, secret: String?): String =
    formBody(
        tokenFields(
            settings,
            secret,
            listOf("token" to refresh, "token_type_hint" to "refresh_token"),
        ),
    )

object OauthRefreshLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun lock(accountId: String): Any = locks.computeIfAbsent(accountId) { Any() }
}

fun <T> withRefreshLock(accountId: String, block: () -> T): T =
    synchronized(OauthRefreshLocks.lock(accountId)) { block() }

fun postGrant(url: String, body: String, transport: OauthTransport, step: String): TokenGrant {
    val response = oauthCall(
        OauthHttpRequest(url, "POST", "application/x-www-form-urlencoded", body),
        transport,
        step,
    )
    if (response.status !in 200..299) {
        val grant = parseTokenGrant(response.body)
        if (grant.error != null) return grant
        throw OauthFailure("$step: HTTP ${response.status}")
    }
    return parseTokenGrant(response.body)
}

fun refreshLocked(
    state: OauthTokenState,
    settings: Settings,
    secret: String?,
    now: Long,
    transport: OauthTransport,
): OauthTokenState {
    if (state.refresh.isEmpty() || state.refreshUnusable) {
        return state.copy(accessUnusable = true, refreshUnusable = true)
    }
    if (settings.oauthTokenEndpoint.isBlank()) throw OauthFailure("refresh: token endpoint is empty")
    val grant = try {
        postGrant(settings.oauthTokenEndpoint, refreshBody(settings, state.refresh, secret), transport, "refresh")
    } catch (error: OauthFailure) {
        return state.copy(accessUnusable = true, refreshUnusable = true)
    }
    if (grant.error != null || grant.access.isEmpty()) {
        return state.copy(accessUnusable = true, refreshUnusable = true)
    }
    return applyGrant(state, grant, now)
}

/** Access token for a login. Refreshes when expiry is within 60 seconds. */
fun accessForLogin(
    accountId: String,
    state: OauthTokenState?,
    settings: Settings,
    secret: String?,
    now: Long,
    transport: OauthTransport,
    save: (OauthTokenState) -> Unit,
): String = withRefreshLock(accountId) {
    var current = state ?: throw OauthFailure("Sign in again")
    if (!current.signedIn(now)) throw OauthFailure("Sign in again")
    if (current.needsProactiveRefresh(now)) {
        current = refreshLocked(current, settings, secret, now, transport)
        save(current)
        if (current.access.isEmpty() || current.accessUnusable) throw OauthFailure("Sign in again")
    }
    if (current.access.isEmpty() || current.accessUnusable) throw OauthFailure("Sign in again")
    current.access
}

/**
 * One auth failure refreshes once and sets the streak. A second failure, a failed refresh, or
 * invalid_grant marks both tokens unusable.
 */
fun onAuthFailure(
    accountId: String,
    state: OauthTokenState?,
    settings: Settings,
    secret: String?,
    now: Long,
    transport: OauthTransport,
    save: (OauthTokenState) -> Unit,
): OauthTokenState = withRefreshLock(accountId) {
    val current = state ?: OauthTokenState(accessUnusable = true, refreshUnusable = true)
    if (current.streak >= 1 || current.refresh.isEmpty() || current.refreshUnusable) {
        val marked = current.copy(accessUnusable = true, refreshUnusable = true)
        save(marked)
        return@withRefreshLock marked
    }
    val next = refreshLocked(current, settings, secret, now, transport)
    val stored = if (next.accessUnusable || next.refreshUnusable) {
        next.copy(accessUnusable = true, refreshUnusable = true)
    } else {
        next.copy(streak = 1)
    }
    save(stored)
    stored
}

fun onAuthSuccess(state: OauthTokenState): OauthTokenState =
    state.copy(streak = 0, accessUnusable = false)

data class SignOutTrace(val steps: List<String>, val stillSignedOut: Boolean)

fun signOutOrder(
    state: OauthTokenState?,
    settings: Settings,
    secret: String?,
    transport: OauthTransport,
    dropSession: () -> Unit,
    clearToken: () -> Unit,
    clearPending: () -> Unit,
): SignOutTrace {
    val refresh = state?.refresh?.takeIf { it.isNotEmpty() }
    val steps = ArrayList<String>()
    steps += "copy-refresh"
    dropSession()
    steps += "drop-session"
    clearToken()
    clearPending()
    steps += "clear"
    val endpoint = settings.oauthRevocationEndpoint
    if (!refresh.isNullOrEmpty() && endpoint.isNotBlank()) {
        try {
            oauthCall(
                OauthHttpRequest(endpoint, "POST", "application/x-www-form-urlencoded", revocationBody(settings, refresh, secret)),
                transport,
                "revocation",
            )
            steps += "revoke"
        } catch (_: OauthFailure) {
            steps += "revoke-failed"
        }
    }
    return SignOutTrace(steps, stillSignedOut = true)
}

fun prepareBrowserSignIn(
    settings: Settings,
    writePending: (OauthPending) -> Unit,
    readToken: () -> OauthTokenState?,
    random: SecureRandom = SecureRandom(),
): String {
    val before = readToken()
    val verifier = pkceVerifier(random)
    val state = pkceVerifier(random, 43)
    writePending(OauthPending(verifier, state))
    val after = readToken()
    if (after != before) throw OauthFailure("browser sign-in: token blob changed")
    if (settings.oauthAuthorizationEndpoint.isBlank()) throw OauthFailure("browser sign-in: authorization endpoint is empty")
    if (settings.oauthClientId.isBlank()) throw OauthFailure("browser sign-in: client ID is empty")
    return authorizationUrl(
        settings.oauthAuthorizationEndpoint,
        settings.oauthClientId,
        OAUTH_REDIRECT_URI,
        pkceS256(verifier),
        state,
        settings.oauthScopes,
    )
}

data class RedirectResult(val accountId: String?, val wroteToken: Boolean, val clearedPending: Boolean, val error: String?)

fun completeRedirect(
    accounts: List<String>,
    query: Map<String, String>,
    pendingOf: (String) -> OauthPending?,
    tokenOf: (String) -> OauthTokenState?,
    writePending: (String, OauthPending?) -> Unit,
    writeToken: (String, OauthTokenState?) -> Unit,
    settingsOf: (String) -> Settings,
    secretOf: (String) -> String?,
    now: Long,
    transport: OauthTransport,
): RedirectResult {
    val state = query["state"].orEmpty()
    val code = query["code"].orEmpty()
    if (state.isEmpty()) return RedirectResult(null, wroteToken = false, clearedPending = false, error = "browser sign-in: state is missing")
    val matched = accounts.firstOrNull { pendingOf(it)?.state == state }
    if (matched == null) {
        val only = accounts.filter { pendingOf(it) != null }
        if (only.size == 1) {
            writePending(only[0], null)
            return RedirectResult(only[0], wroteToken = false, clearedPending = true, error = "browser sign-in: state does not match")
        }
        return RedirectResult(null, wroteToken = false, clearedPending = false, error = "browser sign-in: state does not match")
    }
    val pending = pendingOf(matched) ?: return RedirectResult(matched, false, false, "browser sign-in: state does not match")
    val previous = tokenOf(matched)
    if (code.isEmpty()) {
        writePending(matched, null)
        return RedirectResult(matched, wroteToken = false, clearedPending = true, error = "browser sign-in: code is missing")
    }
    val settings = settingsOf(matched)
    val grant = try {
        postGrant(
            settings.oauthTokenEndpoint,
            authorizationCodeBody(settings, code, pending.verifier, secretOf(matched)),
            transport,
            "token exchange",
        )
    } catch (error: OauthFailure) {
        writePending(matched, null)
        return RedirectResult(matched, wroteToken = false, clearedPending = true, error = error.message)
    }
    if (grant.error != null || grant.access.isEmpty()) {
        writePending(matched, null)
        return RedirectResult(matched, wroteToken = false, clearedPending = true, error = "token exchange: ${grant.error ?: "no access token"}")
    }
    writeToken(matched, applyGrant(previous ?: OauthTokenState(), grant.copy(refresh = grant.refresh ?: previous?.refresh), now))
    writePending(matched, null)
    return RedirectResult(matched, wroteToken = true, clearedPending = true, error = null)
}

/** Custom Tabs when a package is known. Otherwise the web-page fallback. */
fun browserOpenKind(customTabsPackage: String?): String =
    if (customTabsPackage.isNullOrBlank()) "openWebPage" else "customTabs"

fun oauthWireMode(settings: Settings): String {
    val preference = settings.oauthMechanism.uppercase()
    val mode = when (preference) {
        "OAUTHBEARER" -> "OAUTHBEARER"
        "XOAUTH2" -> "XOAUTH2"
        else -> "AUTO"
    }
    return "OAUTH:$mode"
}

fun Settings.usesOauth(): Boolean = accountAuth == "oauth2"
