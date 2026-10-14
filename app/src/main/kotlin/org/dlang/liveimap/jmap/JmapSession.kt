package org.dlang.liveimap.jmap

const val JMAP_MAIL = "urn:ietf:params:jmap:mail"
const val JMAP_SUBMISSION = "urn:ietf:params:jmap:submission"

open class JmapFailure(val text: String) : Exception(text)

/**
 * The server's certificate failed with a reason the user may override ("certificate untrusted" or "certificate
 * changed"); [ders] is the chain it presented, so the leaf can be shown and pinned (as IMAP does, F64).
 */
class JmapPinOffer(text: String, val host: String, val ders: List<ByteArray>) : JmapFailure(text)

internal fun jmapPinnable(verdict: String): Boolean =
    verdict == "certificate untrusted" || verdict == "certificate changed"

data class JmapSession(
    val username: String,
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String,
    val state: String,
    val capabilityIds: Set<String>,
    val primaryMailAccountId: String?,
    /** accountCapabilities of the primary mail account; null when the session does not list it. */
    val mailAccountCapabilityIds: Set<String>? = null,
) {
    fun offersMail(): Boolean = JMAP_MAIL in capabilityIds

    /** Session-level and, when listed, on the account too: Cyrus offers it per account only with a working SMTP. */
    fun offersSubmission(): Boolean =
        JMAP_SUBMISSION in capabilityIds && (mailAccountCapabilityIds?.contains(JMAP_SUBMISSION) ?: true)
}

fun parseJmapSession(text: String): JmapSession {
    if (text.isBlank()) throw JmapFailure("jmap session is empty")
    val parsed = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap session is not an object")
    }
    if (parsed !is Json.Obj) throw JmapFailure("jmap session is not an object")
    val root = parsed.fields
    val capabilities = requireObject(root, "capabilities")
    requireObject(root, "accounts")
    val primary = requireObject(root, "primaryAccounts")
    val mailAccount = primary[JMAP_MAIL]
    val primaryMailAccountId = when (mailAccount) {
        null -> null
        is Json.Str -> mailAccount.text
        else -> throw JmapFailure("jmap session primary mail account is not text")
    }
    return JmapSession(
        username = requireText(root, "username"),
        apiUrl = requireText(root, "apiUrl"),
        downloadUrl = requireText(root, "downloadUrl"),
        uploadUrl = requireText(root, "uploadUrl"),
        eventSourceUrl = requireText(root, "eventSourceUrl"),
        state = requireText(root, "state"),
        capabilityIds = capabilities.keys.toSet(),
        primaryMailAccountId = primaryMailAccountId,
        mailAccountCapabilityIds = primaryMailAccountId?.let { id ->
            ((root["accounts"] as? Json.Obj)?.fields?.get(id) as? Json.Obj)?.fields?.get("accountCapabilities")
                ?.let { (it as? Json.Obj)?.fields?.keys?.toSet() }
        },
    )
}

private fun requireText(fields: Map<String, Json>, name: String): String {
    val value = fields[name] ?: throw JmapFailure("jmap session lacks $name")
    if (value !is Json.Str) throw JmapFailure("jmap session $name is not text")
    return value.text
}

private fun requireObject(fields: Map<String, Json>, name: String): Map<String, Json> {
    val value = fields[name] ?: throw JmapFailure("jmap session lacks $name")
    if (value !is Json.Obj) throw JmapFailure("jmap session $name is not an object")
    return value.fields
}
