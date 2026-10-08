package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.settings.DeletePolicy

data class JmapMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val receivedAt: String,
    val preview: String,
    val unread: Boolean,
    val size: Long,
)

data class JmapMessagePage(
    val total: Long?,
    val messages: List<JmapMessage>,
)

fun jmapMessageLine(message: JmapMessage): String {
    val text = if (message.subject.isBlank()) "No subject" else message.subject
    if (message.from.isBlank()) return text
    return message.from + "  " + text
}

fun jmapMessageWindowRequest(
    accountId: String,
    mailboxId: String,
    position: Int,
    limit: Int,
): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (mailboxId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
    if (position < 0) throw JmapFailure("jmap message position is invalid")
    if (limit !in 1..120) throw JmapFailure("jmap message limit is invalid")
    val account = quoted(accountId)
    val mailbox = quoted(mailboxId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/query",{"accountId":$account,"filter":{"inMailbox":$mailbox},"sort":[{"property":"receivedAt","isAscending":false}],"position":$position,"limit":$limit,"calculateTotal":true},"0"],["Email/get",{"accountId":$account,"#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":["id","threadId","keywords","size","receivedAt","subject","from","preview"]},"1"]]}"""
}

fun jmapMessageSearchRequest(
    accountId: String,
    mailboxId: String,
    text: String,
    position: Int,
    limit: Int,
): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (mailboxId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
    if (text.isBlank()) throw JmapFailure("jmap search text is empty")
    if (position < 0) throw JmapFailure("jmap message position is invalid")
    if (limit !in 1..120) throw JmapFailure("jmap message limit is invalid")
    val account = quoted(accountId)
    val mailbox = quoted(mailboxId)
    val term = quoted(text)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/query",{"accountId":$account,"filter":{"operator":"AND","conditions":[{"inMailbox":$mailbox},{"text":$term}]},"sort":[{"property":"receivedAt","isAscending":false}],"position":$position,"limit":$limit,"calculateTotal":true},"0"],["Email/get",{"accountId":$account,"#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":["id","threadId","keywords","size","receivedAt","subject","from","preview"]},"1"]]}"""
}

fun jmapMessageBodyRequest(accountId: String, emailId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val account = quoted(accountId)
    val id = quoted(emailId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/get",{"accountId":$account,"ids":[$id],"properties":["textBody","bodyValues"],"fetchTextBodyValues":true,"maxBodyValueBytes":32768},"0"]]}"""
}

fun jmapSeenRequest(accountId: String, emailId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val account = quoted(accountId)
    val id = quoted(emailId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/set",{"accountId":$account,"update":{$id:{"keywords/${'$'}seen":true}}},"0"]]}"""
}

fun jmapSeenWasSet(text: String, emailId: String): Boolean {
    return try {
        val root = JsonParser(text).parseDocument()
        if (root !is Json.Obj) return false
        val args = methodArgs(root.fields["methodResponses"], "Email/set") ?: return false
        val updated = args["updated"] ?: return false
        updated is Json.Obj && updated.fields.containsKey(emailId)
    } catch (_: Exception) {
        false
    }
}

fun jmapMarkSeen(
    session: JmapSession,
    emailId: String,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val request = jmapSeenRequest(accountId, emailId)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    if (!jmapSeenWasSet(result.body, emailId)) throw JmapFailure("jmap seen was not set")
}

fun jmapDestroyRequest(accountId: String, emailId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val account = quoted(accountId)
    val id = quoted(emailId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/set",{"accountId":$account,"destroy":[$id]},"0"]]}"""
}

fun jmapMoveRequest(
    accountId: String,
    emailId: String,
    fromMailboxId: String,
    trashId: String,
): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    if (fromMailboxId.isBlank() || trashId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
    val account = quoted(accountId)
    val id = quoted(emailId)
    val fromKey = quoted("mailboxIds/$fromMailboxId")
    val trashKey = quoted("mailboxIds/$trashId")
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/set",{"accountId":$account,"update":{$id:{$fromKey:null,$trashKey:true}}},"0"]]}"""
}

fun jmapDeleteWasDone(text: String, emailId: String): Boolean {
    return try {
        val root = JsonParser(text).parseDocument()
        if (root !is Json.Obj) return false
        val args = methodArgs(root.fields["methodResponses"], "Email/set") ?: return false
        val destroyed = args["destroyed"]
        if (destroyed is Json.Arr && destroyed.values.any { it is Json.Str && it.text == emailId }) {
            return true
        }
        val updated = args["updated"]
        updated is Json.Obj && updated.fields.containsKey(emailId)
    } catch (_: Exception) {
        false
    }
}

fun jmapDelete(
    session: JmapSession,
    emailId: String,
    fromMailboxId: String,
    trashId: String,
    policy: DeletePolicy,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val request = when (policy) {
        DeletePolicy.MarkDeleted -> throw JmapFailure("jmap delete marks nothing")
        DeletePolicy.DeletePermanently -> jmapDestroyRequest(accountId, emailId)
        DeletePolicy.MoveToTrash -> {
            if (trashId.isBlank()) throw JmapFailure("jmap trash is not set")
            if (fromMailboxId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
            if (trashId == fromMailboxId) {
                jmapDestroyRequest(accountId, emailId)
            } else {
                jmapMoveRequest(accountId, emailId, fromMailboxId, trashId)
            }
        }
    }
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    if (!jmapDeleteWasDone(result.body, emailId)) throw JmapFailure("jmap delete was not done")
}

fun jmapMessageBody(
    session: JmapSession,
    emailId: String,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val request = jmapMessageBodyRequest(accountId, emailId)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return parseJmapMessageBody(result.body)
}

fun parseJmapMessageBody(text: String): String {
    if (text.isBlank()) throw JmapFailure("jmap message response is empty")
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap message response is not an object")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap message response is not an object")
    val list = emailGetList(root.fields["methodResponses"])
        ?: throw JmapFailure("jmap message response lacks get")
    if (list.isEmpty()) return ""
    val out = StringBuilder()
    for (item in list) {
        if (item !is Json.Obj) continue
        out.append(plainText(item.fields))
    }
    return out.toString()
}

fun jmapMessageWindow(
    session: JmapSession,
    mailboxId: String,
    position: Int,
    limit: Int,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapMessagePage {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMessageWindowRequest(accountId, mailboxId, position, limit)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return parseJmapMessages(result.body)
}

fun jmapMessageSearch(
    session: JmapSession,
    mailboxId: String,
    text: String,
    position: Int,
    limit: Int,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapMessagePage {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMessageSearchRequest(accountId, mailboxId, text, position, limit)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return parseJmapMessages(result.body)
}

fun parseJmapMessages(text: String): JmapMessagePage {
    if (text.isBlank()) throw JmapFailure("jmap message response is empty")
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap message response is not an object")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap message response is not an object")
    val responses = root.fields["methodResponses"]
    val total = queryTotal(responses)
    val list = emailGetList(responses) ?: throw JmapFailure("jmap message response lacks get")
    return JmapMessagePage(total, list.map { parseMessage(it) })
}

private fun queryTotal(responses: Json?): Long? {
    val args = methodArgs(responses, "Email/query") ?: return null
    val total = args["total"] ?: return null
    return messageLong(total)
}

private fun emailGetList(responses: Json?): List<Json>? {
    val args = methodArgs(responses, "Email/get") ?: return null
    val list = args["list"] ?: return null
    if (list !is Json.Arr) return null
    return list.values
}

private fun methodArgs(responses: Json?, name: String): Map<String, Json>? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val method = item.values[0]
        if (method !is Json.Str || method.text != name) continue
        val args = item.values[1]
        if (args !is Json.Obj) return null
        return args.fields
    }
    return null
}

private fun parseMessage(value: Json): JmapMessage {
    if (value !is Json.Obj) throw JmapFailure("jmap message lacks id")
    val fields = value.fields
    return JmapMessage(
        id = messageId(fields),
        threadId = messageText(fields, "threadId"),
        subject = messageText(fields, "subject"),
        from = messageFrom(fields),
        receivedAt = messageText(fields, "receivedAt"),
        preview = messageText(fields, "preview"),
        unread = messageUnread(fields),
        size = messageSize(fields),
    )
}

private fun messageId(fields: Map<String, Json>): String {
    val value = fields["id"] ?: throw JmapFailure("jmap message lacks id")
    if (value !is Json.Str) throw JmapFailure("jmap message id is not text")
    return value.text
}

private fun messageText(fields: Map<String, Json>, name: String): String {
    val value = fields[name] ?: return ""
    if (value !is Json.Str) throw JmapFailure("jmap message $name is not text")
    return value.text
}

private fun messageSize(fields: Map<String, Json>): Long {
    val value = fields["size"] ?: return 0L
    return messageLong(value)
}

private fun messageLong(value: Json): Long {
    if (value !is Json.Num || value.text.isEmpty() || value.text.any { it !in '0'..'9' }) {
        throw JmapFailure("jmap message number is not a number")
    }
    return try {
        value.text.toLong()
    } catch (_: NumberFormatException) {
        throw JmapFailure("jmap message number is not a number")
    }
}

private fun messageUnread(fields: Map<String, Json>): Boolean {
    val keywords = fields["keywords"] ?: return true
    if (keywords !is Json.Obj) return true
    return !keywords.fields.containsKey("\$seen")
}

private fun plainText(fields: Map<String, Json>): String {
    val textBody = fields["textBody"] ?: return ""
    if (textBody !is Json.Arr) throw JmapFailure("jmap message text is not a list")
    if (textBody.values.isEmpty()) return ""
    val bodyValues = fields["bodyValues"]
    if (bodyValues !is Json.Obj) return ""
    val out = StringBuilder()
    for (part in textBody.values) {
        if (part !is Json.Obj) continue
        val partId = part.fields["partId"]
        if (partId !is Json.Str) continue
        val stored = bodyValues.fields[partId.text] ?: continue
        if (stored !is Json.Obj) continue
        val value = stored.fields["value"]
        if (value !is Json.Str) continue
        out.append(value.text)
    }
    return out.toString()
}

private fun messageFrom(fields: Map<String, Json>): String {
    val from = fields["from"] ?: return ""
    if (from !is Json.Arr) throw JmapFailure("jmap message from is not a list")
    val first = from.values.firstOrNull() ?: return ""
    if (first !is Json.Obj) return ""
    val name = first.fields["name"]
    if (name is Json.Str && name.text.isNotBlank()) return name.text
    val email = first.fields["email"]
    if (email is Json.Str) return email.text
    return ""
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
