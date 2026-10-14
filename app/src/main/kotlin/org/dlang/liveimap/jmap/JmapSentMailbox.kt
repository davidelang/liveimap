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

/** David's Sent rule: a configured Sent folder that cannot be found is an error the user resolves (send without
 *  the copy, or fix the setting); the app never picks another folder. */
class JmapSentFolderMissing(val name: String) : JmapFailure("jmap sent folder \"$name\" was not found")

/**
 * Where the sent copy goes: only the Sent folder configured in settings, matched exactly at top level, whatever
 * the server's role metadata says (roles only prefill the settings default). Blank: null, send without a copy.
 */
fun jmapFindSentMailbox(
    session: JmapSession,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    sentName: String = "",
): String? {
    if (sentName.isBlank()) return null
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val named = jmapCall(session.apiUrl, jmapMailboxNamesRequest(accountId), jmapBasicAuthorization(username, password), pin, post, trust)
    if (named.status != 200) throw JmapFailure("jmap api status ${named.status}")
    return jmapTopLevelMailboxId(named.body, sentName) ?: throw JmapSentFolderMissing(sentName)
}

fun jmapMailboxNamesRequest(accountId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Mailbox/get",{"accountId":${quoted(accountId)},"ids":null,"properties":["id","name","parentId"]},"0"]]}"""
}

/** The id of the top-level mailbox named exactly [name] (no role needed), or null. */
fun jmapTopLevelMailboxId(text: String, name: String): String? {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return null }
    val responses = (root as? Json.Obj)?.fields?.get("methodResponses") as? Json.Arr ?: return null
    for (item in responses.values) {
        val call = item as? Json.Arr ?: continue
        if ((call.values.getOrNull(0) as? Json.Str)?.text != "Mailbox/get") continue
        val list = ((call.values.getOrNull(1) as? Json.Obj)?.fields?.get("list") as? Json.Arr) ?: return null
        for (box in list.values) {
            val f = (box as? Json.Obj)?.fields ?: continue
            val parent = f["parentId"]
            if (parent != null && parent !is Json.Null) continue
            if ((f["name"] as? Json.Str)?.text == name) return (f["id"] as? Json.Str)?.text?.takeIf { it.isNotBlank() }
        }
    }
    return null
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
