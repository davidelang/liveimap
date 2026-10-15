package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy

/** David's Sent rule: a configured Sent folder that cannot be found is an error the user resolves (send without
 *  the copy, or fix the setting); the app never picks another folder. */
class JmapSentFolderMissing(val name: String) : JmapFailure("jmap sent folder \"$name\" was not found")

/**
 * Where the sent copy goes: only the Sent folder configured in settings, matched exactly at top level, whatever
 * the server's role metadata says (roles only prefill the settings default). Blank: null, send without a copy.
 * F148: [sentName] is the full path ("Archive/Sent"), resolved the way JmapMailSession names folders.
 */
fun jmapFindSentMailbox(
    session: JmapSession,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    sentName: String = "",
    policy: TlsPolicy = TlsPolicy(),
): String? {
    if (sentName.isBlank()) return null
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val named = jmapCall(session.apiUrl, jmapMailboxNamesRequest(accountId), jmapBasicAuthorization(username, password), pin, post, trust, policy)
    if (named.status != 200) throw JmapFailure("jmap api status ${named.status}")
    return jmapMailboxIdByPath(named.body, sentName) ?: throw JmapSentFolderMissing(sentName)
}

fun jmapMailboxNamesRequest(accountId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Mailbox/get",{"accountId":${quoted(accountId)},"ids":null,"properties":["id","name","parentId"]},"0"]]}"""
}

/**
 * F148: the id of the mailbox whose full path is exactly [path]: names joined by [delimiter] from the top, the same
 * paths JmapMailSession lists (so "Sent" is top level and "Archive/Sent" is Sent under Archive). Null when none.
 */
fun jmapMailboxIdByPath(text: String, path: String, delimiter: Char = JmapMailSession.DELIMITER): String? {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return null }
    val responses = (root as? Json.Obj)?.fields?.get("methodResponses") as? Json.Arr ?: return null
    for (item in responses.values) {
        val call = item as? Json.Arr ?: continue
        if ((call.values.getOrNull(0) as? Json.Str)?.text != "Mailbox/get") continue
        val list = ((call.values.getOrNull(1) as? Json.Obj)?.fields?.get("list") as? Json.Arr) ?: return null
        class Box(val name: String, val parent: String?)
        val boxes = HashMap<String, Box>()
        for (box in list.values) {
            val f = (box as? Json.Obj)?.fields ?: continue
            val id = (f["id"] as? Json.Str)?.text?.takeIf { it.isNotBlank() } ?: continue
            val name = (f["name"] as? Json.Str)?.text ?: continue
            boxes[id] = Box(name, (f["parentId"] as? Json.Str)?.text)
        }
        fun fullPath(id: String): String? {
            val names = ArrayList<String>()
            var at: String? = id
            while (at != null) {
                val box = boxes[at] ?: return null
                names += box.name
                if (names.size > boxes.size) return null // a parent cycle: no path
                at = box.parent
            }
            return names.asReversed().joinToString(delimiter.toString())
        }
        return boxes.keys.firstOrNull { fullPath(it) == path }
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
