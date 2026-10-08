package org.dlang.liveimap.jmap

const val JMAP_MAIL = "urn:ietf:params:jmap:mail"
const val JMAP_SUBMISSION = "urn:ietf:params:jmap:submission"

class JmapFailure(val text: String) : Exception(text)

data class JmapSession(
    val username: String,
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String,
    val state: String,
    val capabilityIds: Set<String>,
    val primaryMailAccountId: String?,
) {
    fun offersMail(): Boolean = JMAP_MAIL in capabilityIds

    fun offersSubmission(): Boolean = JMAP_SUBMISSION in capabilityIds
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
