package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust

fun jmapSentMailboxRequest(accountId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    val account = quoted(accountId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Mailbox/query",{"accountId":$account,"filter":{"role":"sent"}},"0"],["Mailbox/get",{"accountId":$account,"#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["id","role"]},"1"]]}"""
}

fun jmapSentMailboxId(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap sent mailbox is missing")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap sent mailbox is missing")
    val ids = queryIds(root.fields["methodResponses"])
        ?: throw JmapFailure("jmap sent mailbox is missing")
    if (ids.isEmpty()) throw JmapFailure("jmap sent mailbox is missing")
    val first = ids[0]
    if (first !is Json.Str || first.text.isBlank()) {
        throw JmapFailure("jmap sent mailbox is missing")
    }
    return first.text
}

fun jmapFindSentMailbox(
    session: JmapSession,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapSentMailboxRequest(accountId)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return jmapSentMailboxId(result.body)
}

private fun queryIds(responses: Json?): List<Json>? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val method = item.values[0]
        if (method !is Json.Str || method.text != "Mailbox/query") continue
        val args = item.values[1]
        if (args !is Json.Obj) return null
        val ids = args.fields["ids"] ?: return null
        if (ids !is Json.Arr) return null
        return ids.values
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
