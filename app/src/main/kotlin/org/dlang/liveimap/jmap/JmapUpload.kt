package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI

fun jmapUploadUrl(uploadUrl: String, accountId: String): String {
    if (uploadUrl.isBlank()) throw JmapFailure("jmap upload url is empty")
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    return uploadUrl.replace("{accountId}", encodeUploadAccount(accountId))
}

fun jmapBlobId(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap blob id is missing")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap blob id is missing")
    val blobId = root.fields["blobId"] ?: throw JmapFailure("jmap blob id is missing")
    if (blobId !is Json.Str) throw JmapFailure("jmap blob id is missing")
    if (blobId.text.isBlank()) throw JmapFailure("jmap blob id is empty")
    return blobId.text
}

fun jmapUpload(
    session: JmapSession,
    bytes: ByteArray,
    username: String,
    password: String,
    pin: String,
    post: (String, ByteArray, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    if (session.uploadUrl.isBlank()) throw JmapFailure("jmap upload url is empty")
    if (bytes.isEmpty()) throw JmapFailure("jmap upload is empty")
    val url = jmapUploadUrl(session.uploadUrl, accountId)
    if (!uploadHttps(url)) throw JmapFailure("jmap upload url is not https")
    val authorization = jmapBasicAuthorization(username, password)
    val exchange = post(url, bytes, "message/rfc822", authorization)
    try {
        val host = uploadHost(url)
        val verdict = trust(host, exchange.peerDer(), pin)
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val code = exchange.status
        if (code in uploadRedirectStatuses) throw JmapFailure("jmap upload redirect")
        if (code == 201) return jmapBlobId(exchange.body())
        throw JmapFailure("jmap upload status $code")
    } finally {
        exchange.close()
    }
}

private const val UPLOAD_HEX = "0123456789ABCDEF"

private val uploadRedirectStatuses = setOf(301, 302, 303, 307, 308)

private fun encodeUploadAccount(accountId: String): String {
    val bytes = accountId.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size * 3)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        val plain = value in 'A'.code..'Z'.code ||
            value in 'a'.code..'z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code ||
            value == '.'.code ||
            value == '_'.code ||
            value == '~'.code
        if (plain) {
            out.append(value.toChar())
        } else {
            out.append('%')
            out.append(UPLOAD_HEX[value ushr 4])
            out.append(UPLOAD_HEX[value and 0x0f])
        }
    }
    return out.toString()
}

private fun uploadHttps(url: String): Boolean {
    val scheme = try {
        URI(url).scheme
    } catch (_: Exception) {
        null
    }
    return scheme != null && scheme.equals("https", ignoreCase = true)
}

private fun uploadHost(url: String): String {
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    } ?: throw JmapFailure("jmap upload url is not https")
    if (host.startsWith("[") && host.endsWith("]") && host.length >= 2) {
        return host.substring(1, host.length - 1)
    }
    return host
}
