package org.dlang.liveimap.jmap

data class JmapMailbox(
    val id: String,
    val name: String,
    val parentId: String?,
    val role: String?,
    val sortOrder: Long,
    val totalEmails: Long,
    val unreadEmails: Long,
)

fun jmapMailboxLevelRequest(accountId: String, parentId: String?): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (parentId != null && parentId.isEmpty()) throw JmapFailure("jmap parent id is empty")
    val account = quoted(accountId)
    val parent = if (parentId == null) "null" else quoted(parentId)
    return levelRequest(account, parent)
}

fun jmapMailboxChildProbe(accountId: String, mailboxIds: List<String>): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (mailboxIds.isEmpty()) throw JmapFailure("jmap mailbox ids are empty")
    val account = quoted(accountId)
    val conditions = mailboxIds.joinToString(",") { """{"parentId":${quoted(it)}}""" }
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Mailbox/query",{"accountId":$account,"filter":{"operator":"OR","conditions":[$conditions]}},"0"],["Mailbox/get",{"accountId":$account,"#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["parentId"]},"1"]]}"""
}

fun parseJmapMailboxes(text: String): List<JmapMailbox> {
    if (text.isBlank()) throw JmapFailure("jmap mailbox response is empty")
    val root = parseMailboxDocument(text)
    if (root !is Json.Obj) throw JmapFailure("jmap mailbox response is not an object")
    val list = mailboxGetList(root.fields["methodResponses"])
        ?: throw JmapFailure("jmap mailbox response lacks get")
    return list.map { parseMailbox(it) }
}

fun jmapMailboxParentIds(listText: String): Set<String> {
    if (listText.isBlank()) throw JmapFailure("jmap mailbox list is not an array")
    val root = try {
        JsonParser(listText).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap mailbox list is not an array")
    }
    if (root !is Json.Arr) throw JmapFailure("jmap mailbox list is not an array")
    val ids = linkedSetOf<String>()
    for (item in root.values) {
        if (item !is Json.Obj) throw JmapFailure("jmap mailbox is not an object")
        when (val parent = item.fields["parentId"]) {
            null, Json.Null -> Unit
            is Json.Str -> ids.add(parent.text)
            else -> throw JmapFailure("jmap mailbox parentId is not text")
        }
    }
    return ids
}

private fun levelRequest(account: String, parent: String): String {
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Mailbox/query",{"accountId":$account,"filter":{"parentId":$parent},"sort":[{"property":"sortOrder","isAscending":true},{"property":"name","isAscending":true}]},"0"],["Mailbox/get",{"accountId":$account,"#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["id","name","parentId","role","sortOrder","totalEmails","unreadEmails"]},"1"]]}"""
}

private fun parseMailboxDocument(text: String): Json {
    return try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap mailbox response is not an object")
    }
}

private fun mailboxGetList(responses: Json?): List<Json>? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val name = item.values[0]
        if (name !is Json.Str || name.text != "Mailbox/get") continue
        val args = item.values[1]
        if (args !is Json.Obj) throw JmapFailure("jmap mailbox response lacks list")
        val list = args.fields["list"] ?: throw JmapFailure("jmap mailbox response lacks list")
        if (list !is Json.Arr) throw JmapFailure("jmap mailbox list is not an array")
        return list.values
    }
    return null
}

private fun parseMailbox(value: Json): JmapMailbox {
    if (value !is Json.Obj) throw JmapFailure("jmap mailbox is not an object")
    val fields = value.fields
    return JmapMailbox(
        id = requireMailboxText(fields, "id"),
        name = requireMailboxText(fields, "name"),
        parentId = optionalMailboxText(fields, "parentId"),
        role = optionalMailboxText(fields, "role"),
        sortOrder = mailboxLong(fields, "sortOrder"),
        totalEmails = mailboxLong(fields, "totalEmails"),
        unreadEmails = mailboxLong(fields, "unreadEmails"),
    )
}

private fun requireMailboxText(fields: Map<String, Json>, name: String): String {
    val value = fields[name] ?: throw JmapFailure("jmap mailbox lacks $name")
    if (value !is Json.Str) throw JmapFailure("jmap mailbox $name is not text")
    return value.text
}

private fun optionalMailboxText(fields: Map<String, Json>, name: String): String? {
    val value = fields[name] ?: return null
    if (value is Json.Null) return null
    if (value !is Json.Str) throw JmapFailure("jmap mailbox $name is not text")
    return value.text
}

private fun mailboxLong(fields: Map<String, Json>, name: String): Long {
    val value = fields[name] ?: return 0L
    if (value !is Json.Num || value.text.isEmpty() || value.text.any { it !in '0'..'9' }) {
        throw JmapFailure("jmap mailbox number is not a number")
    }
    return try {
        value.text.toLong()
    } catch (_: NumberFormatException) {
        throw JmapFailure("jmap mailbox number is not a number")
    }
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
