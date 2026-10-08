package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust

fun jmapEmailSubmissionRequest(
    accountId: String,
    emailId: String,
    mailFrom: String,
    rcptTo: List<String>,
): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    if (mailFrom.isBlank()) throw JmapFailure("jmap submission from is empty")
    if (rcptTo.isEmpty() || rcptTo.any { it.isBlank() }) {
        throw JmapFailure("jmap submission recipient is empty")
    }
    val account = quoted(accountId)
    val id = quoted(emailId)
    val from = quoted(mailFrom)
    val recipients = rcptTo.joinToString(",") { "{\"email\":${quoted(it)}}" }
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL","$JMAP_SUBMISSION"],"methodCalls":[["EmailSubmission/set",{"accountId":$account,"create":{"k1":{"emailId":$id,"envelope":{"mailFrom":{"email":$from},"rcptTo":[$recipients]}}}},"0"]]}"""
}

fun jmapSubmissionId(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap submission was not created")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap submission was not created")
    val args = submissionArgs(root.fields["methodResponses"])
        ?: throw JmapFailure("jmap submission was not created")
    val createdId = createdId(args["created"])
    if (createdId != null) {
        if (createdId.isBlank()) throw JmapFailure("jmap submission id is empty")
        return createdId
    }
    val description = notCreatedDescription(args["notCreated"])
    if (description != null) throw JmapFailure(description)
    throw JmapFailure("jmap submission was not created")
}

fun jmapSubmit(
    session: JmapSession,
    emailId: String,
    mailFrom: String,
    rcptTo: List<String>,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapEmailSubmissionRequest(accountId, emailId, mailFrom, rcptTo)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return jmapSubmissionId(result.body)
}

fun jmapDeliver(
    session: JmapSession,
    bytes: ByteArray,
    mailboxId: String,
    mailFrom: String,
    rcptTo: List<String>,
    username: String,
    password: String,
    pin: String,
    upload: (String, ByteArray, String, String) -> JmapHttpExchange,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    if (mailboxId.isBlank()) throw JmapFailure("jmap mailbox id is empty")
    if (mailFrom.isBlank()) throw JmapFailure("jmap submission from is empty")
    if (rcptTo.isEmpty() || rcptTo.any { it.isBlank() }) {
        throw JmapFailure("jmap submission recipient is empty")
    }
    val blobId = jmapUpload(session, bytes, username, password, pin, upload, trust)
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapEmailImportRequest(accountId, blobId, mailboxId)
    val authorization = jmapBasicAuthorization(username, password)
    val imported = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (imported.status != 200) throw JmapFailure("jmap api status ${imported.status}")
    val emailId = jmapImportedEmailId(imported.body)
    return jmapSubmit(
        session,
        emailId,
        mailFrom,
        rcptTo,
        username,
        password,
        pin,
        post,
        trust,
    )
}

sealed class JmapSendChoice {
    data object Smtp : JmapSendChoice()
    data class Submitted(val submissionId: String) : JmapSendChoice()
}

fun jmapSendChosen(
    chosen: Boolean,
    offer: JmapOffer,
    bytes: ByteArray,
    mailFrom: String,
    rcptTo: List<String>,
    username: String,
    password: String,
    pin: String,
    upload: (String, ByteArray, String, String) -> JmapHttpExchange,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapSendChoice {
    if (!chosen) return JmapSendChoice.Smtp
    val session = when (offer) {
        JmapOffer.None -> throw JmapFailure("jmap submission is not offered")
        is JmapOffer.Mail -> offer.session
    }
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    val mailboxId = jmapFindSentMailbox(session, username, password, pin, post, trust)
    val submissionId = jmapDeliver(
        session,
        bytes,
        mailboxId,
        mailFrom,
        rcptTo,
        username,
        password,
        pin,
        upload,
        post,
        trust,
    )
    return JmapSendChoice.Submitted(submissionId)
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

private fun submissionArgs(responses: Json?): Map<String, Json>? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val method = item.values[0]
        if (method !is Json.Str || method.text != "EmailSubmission/set") continue
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
