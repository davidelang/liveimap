package org.dlang.liveimap.jmap

fun jmapEmailImportRequest(accountId: String, blobId: String, mailboxId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (blobId.isBlank()) throw JmapFailure("jmap blob id is empty")
    if (mailboxId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
    val account = quoted(accountId)
    val blob = quoted(blobId)
    val mailbox = quoted(mailboxId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/import",{"accountId":$account,"emails":{"k1":{"blobId":$blob,"mailboxIds":{$mailbox:true}}}},"0"]]}"""
}

fun jmapImportedEmailId(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap import was not created")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap import was not created")
    val args = importArgs(root.fields["methodResponses"])
        ?: throw JmapFailure("jmap import was not created")
    val createdId = createdId(args["created"])
    if (createdId != null) {
        if (createdId.isBlank()) throw JmapFailure("jmap import id is empty")
        return createdId
    }
    val description = notCreatedDescription(args["notCreated"])
    if (description != null) throw JmapFailure(description)
    throw JmapFailure("jmap import was not created")
}

private fun createdId(created: Json?): String? {
    if (created !is Json.Obj) return null
    val row = created.fields["k1"]
    if (row !is Json.Obj) return null
    val id = row.fields["id"] ?: return null
    if (id !is Json.Str) return null
    return id.text
}

private fun notCreatedDescription(notCreated: Json?): String? {
    if (notCreated !is Json.Obj) return null
    val row = notCreated.fields["k1"]
    if (row !is Json.Obj) return null
    val description = row.fields["description"]
    if (description !is Json.Str || description.text.isBlank()) return null
    return description.text
}

private fun importArgs(responses: Json?): Map<String, Json>? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val method = item.values[0]
        if (method !is Json.Str || method.text != "Email/import") continue
        val args = item.values[1]
        if (args !is Json.Obj) return null
        return args.fields
    }
    return null
}

private fun quoted(text: String): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    for (c in text) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) {
                out.append("\\u")
                out.append(c.code.toString(16).padStart(4, '0'))
            } else {
                out.append(c)
            }
        }
    }
    out.append('"')
    return out.toString()
}
