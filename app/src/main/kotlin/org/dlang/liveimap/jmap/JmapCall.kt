package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.TrafficWriter
import org.dlang.liveimap.engine.applyTlsPolicy
import java.net.URI
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

fun jmapBasicAuthorization(username: String, password: String): String {
    if (username.isBlank()) throw JmapFailure("jmap username is empty")
    val token = Base64.getEncoder().encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
    )
    return "Basic $token"
}

/**
 * Password mode keeps the IMAP password as Basic. oauth2 presents [accessToken] and does not use a null IMAP password.
 */
fun jmapPresented(accountAuth: String, imapPassword: String?, accessToken: String?): Pair<String, Boolean> {
    if (accountAuth == "oauth2") {
        if (accessToken.isNullOrEmpty()) throw org.dlang.liveimap.session.MailFailure("Sign in again")
        return accessToken to true
    }
    if (imapPassword == null) throw org.dlang.liveimap.session.MailFailure("password missing")
    return imapPassword to false
}

/** Password mode is Basic. oauth2 sends the access token as a bearer and does not read the IMAP password. */
fun jmapAuthorization(username: String, secret: String, bearer: Boolean = false): String {
    if (!bearer) return jmapBasicAuthorization(username, secret)
    if (secret.isBlank()) throw JmapFailure("Sign in again")
    return "Bearer $secret"
}

fun jmapCall(
    apiUrl: String,
    body: String,
    authorization: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
): JmapHttpResult {
    if (apiUrl.isBlank()) throw JmapFailure("jmap api url is empty")
    if (!jmapUrlAllowed(accountKey, apiUrl)) throw JmapFailure("jmap api url is not https")
    val host = apiHost(apiUrl)
    val exchange = jmapNet { post(apiUrl, body, authorization) }
    try {
        val verdict = applyTlsPolicy(jmapVerdict(accountKey, apiUrl) { jmapNet { trust(host, exchange.peerDer(), pin) } }, policy)
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = jmapNet { exchange.status }
        if (status in apiRedirectStatuses) throw JmapFailure("jmap api redirect")
        return JmapHttpResult(status, jmapNet { exchange.body() }, apiUrl)
    } finally {
        exchange.close()
    }
}

fun jmapMailboxLevel(
    session: JmapSession,
    parentId: String?,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
    bearer: Boolean = false,
): List<JmapMailbox> {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxLevelRequest(accountId, parentId)
    val authorization = jmapAuthorization(username, password, bearer)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust, policy, accountKey)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return parseJmapMailboxes(result.body)
}

fun jmapMailboxChildren(
    session: JmapSession,
    mailboxIds: List<String>,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
    bearer: Boolean = false,
): Set<String> {
    if (mailboxIds.isEmpty()) throw JmapFailure("jmap mailbox ids are empty")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxChildProbe(accountId, mailboxIds)
    val authorization = jmapAuthorization(username, password, bearer)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust, policy, accountKey)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    val root = try {
        JsonParser(result.body).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap mailbox response is not an object")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap mailbox response is not an object")
    val responses = root.fields["methodResponses"]
    if (responses !is Json.Arr) throw JmapFailure("jmap mailbox response lacks get")
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val name = item.values[0]
        if (name !is Json.Str || name.text != "Mailbox/get") continue
        val args = item.values[1]
        if (args !is Json.Obj) throw JmapFailure("jmap mailbox response lacks list")
        val list = args.fields["list"] ?: throw JmapFailure("jmap mailbox response lacks list")
        if (list !is Json.Arr) throw JmapFailure("jmap mailbox list is not an array")
        return parentIdsOf(list)
    }
    throw JmapFailure("jmap mailbox response lacks get")
}

internal fun logJmapRequest(
    traffic: TrafficWriter?,
    method: String,
    url: String,
    headers: List<Pair<String, String>>,
    authorization: String,
    body: ByteArray,
) {
    val writer = traffic ?: return
    writer.record("jmap", '>', "$method $url HTTP/1.1".toByteArray(Charsets.US_ASCII))
    for ((name, value) in headers) {
        if (name.equals("Authorization", ignoreCase = true)) {
            writer.record("jmap", '>', "Authorization: ".toByteArray(Charsets.US_ASCII))
            writer.record("jmap", '>', TrafficLog.privateMarker(authorization.toByteArray(Charsets.UTF_8).size))
        } else {
            writer.record("jmap", '>', "$name: $value".toByteArray(Charsets.UTF_8))
        }
    }
    writer.record("jmap", '>', body)
}

internal fun logJmapResponse(traffic: TrafficWriter?, connection: java.net.HttpURLConnection, body: ByteArray) {
    val writer = traffic ?: return
    writer.record("jmap", '<', "HTTP/1.1 ${connection.responseCode}".toByteArray(Charsets.US_ASCII))
    for ((name, values) in connection.headerFields) {
        if (name == null) continue
        for (value in values) writer.record("jmap", '<', "$name: $value".toByteArray(Charsets.UTF_8))
    }
    writer.record("jmap", '<', body)
}

internal fun jmapLoggedExchange(connection: java.net.HttpURLConnection, traffic: TrafficWriter?): JmapHttpExchange {
    return object : JmapHttpExchange {
        private var logged = false

        override val status: Int
            get() = connection.responseCode

        override fun peerDer(): List<ByteArray> =
            connection.connect().let { (connection as? HttpsURLConnection)?.serverCertificates?.map { it.encoded } ?: emptyList() }

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun body(): String {
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            if (stream == null) {
                if (!logged) {
                    logged = true
                    logJmapResponse(traffic, connection, ByteArray(0))
                }
                return ""
            }
            val text = stream.use { readJmapBody(it) }
            if (!logged) {
                logged = true
                logJmapResponse(traffic, connection, text.toByteArray(Charsets.UTF_8))
            }
            return text
        }

        override fun close() {
            connection.disconnect()
        }
    }
}

fun platformJmapPost(
    url: String,
    body: String,
    authorization: String,
    pin: String,
    accountKey: String,
    traffic: TrafficWriter?,
    policy: TlsPolicy = TlsPolicy(),
): JmapHttpExchange {
    val headers = listOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Authorization" to authorization,
    )
    val payload = body.toByteArray(Charsets.UTF_8)
    logJmapRequest(traffic, "POST", url, headers, authorization, payload)
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin, accountKey, policy)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "POST"
        doOutput = true
        for ((name, value) in headers) setRequestProperty(name, value)
    }
    connection.outputStream.use { stream -> stream.write(payload) }
    return jmapLoggedExchange(connection, traffic)
}

fun platformJmapUpload(
    url: String,
    bytes: ByteArray,
    contentType: String,
    authorization: String,
    pin: String,
    accountKey: String,
    traffic: TrafficWriter?,
    policy: TlsPolicy = TlsPolicy(),
): JmapHttpExchange {
    val headers = listOf(
        "Accept" to "application/json",
        "Content-Type" to contentType,
        "Authorization" to authorization,
    )
    logJmapRequest(traffic, "POST", url, headers, authorization, bytes)
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin, accountKey, policy)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "POST"
        doOutput = true
        for ((name, value) in headers) setRequestProperty(name, value)
    }
    connection.outputStream.use { stream -> stream.write(bytes) }
    return jmapLoggedExchange(connection, traffic)
}

fun platformJmapUploadFile(
    url: String,
    file: java.io.File,
    contentType: String,
    authorization: String,
    pin: String,
    accountKey: String,
    traffic: TrafficWriter?,
    policy: TlsPolicy = TlsPolicy(),
): JmapHttpExchange {
    val headers = listOf(
        "Accept" to "application/json",
        "Content-Type" to contentType,
        "Authorization" to authorization,
    )
    logJmapRequest(traffic, "POST", url, headers, authorization, ByteArray(0))
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin, accountKey, policy)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "POST"
        doOutput = true
        setFixedLengthStreamingMode(file.length())
        for ((name, value) in headers) setRequestProperty(name, value)
    }
    file.inputStream().use { input ->
        connection.outputStream.use { stream -> input.copyTo(stream) }
    }
    return jmapLoggedExchange(connection, traffic)
}

private val apiRedirectStatuses = setOf(301, 302, 303, 307, 308)

private fun apiHost(url: String): String {
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    } ?: throw JmapFailure("jmap api url is not https")
    if (host.startsWith("[") && host.endsWith("]") && host.length >= 2) {
        return host.substring(1, host.length - 1)
    }
    return host
}
