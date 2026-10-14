package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust

fun jmapEmailSubmissionRequest(
    accountId: String,
    emailId: String,
    mailFrom: String,
    rcptTo: List<String>,
    destroyEmail: Boolean = false,
    /** RFC 8621 §7: the Identity the user sends as (Stalwart requires it). */
    identityId: String = "",
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
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL","$JMAP_SUBMISSION"],"methodCalls":[["EmailSubmission/set",{"accountId":$account,"create":{"k1":{"emailId":$id,${if (identityId.isBlank()) "" else "\"identityId\":${quoted(identityId)},"}"envelope":{"mailFrom":{"email":$from},"rcptTo":[$recipients]}}}${if (destroyEmail) ",\"onSuccessDestroyEmail\":[\"#k1\"]" else ""}},"0"]]}"""
}

fun jmapSubmissionId(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap submission was not created")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap submission was not created")
    val args = submissionArgs(root.fields["methodResponses"])
        ?: throw JmapFailure(methodErrorType(root.fields["methodResponses"])?.let { "jmap submission refused: $it" }
            ?: "jmap submission was not created")
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
    destroyEmail: Boolean = false,
    identityId: String = "",
): String {
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapEmailSubmissionRequest(accountId, emailId, mailFrom, rcptTo, destroyEmail, identityId)
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
    /** No sent copy: the imported message is only a staging copy, destroyed once the submission succeeds. */
    destroyEmail: Boolean = false,
    identityId: String = "",
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
    return try {
        jmapSubmit(
            session,
            emailId,
            mailFrom,
            rcptTo,
            username,
            password,
            pin,
            post,
            trust,
            destroyEmail,
            identityId,
        )
    } catch (failure: JmapFailure) {
        // F63: the copy in Sent was imported for a send that did not happen; remove it (best effort) so it
        // does not look sent and a retry does not leave a duplicate.
        try {
            jmapCall(session.apiUrl, jmapDestroyRequest(accountId, emailId), authorization, pin, post, trust)
        } catch (_: JmapFailure) {
        }
        throw failure
    }
}

/** Submits through JMAP; returns the EmailSubmission id. The caller has already chosen JMAP (F92b: no SMTP branch). */
fun jmapSend(
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
    /** The account's Sent folder name: where the copy goes when the server marks no mailbox role "sent". */
    sentName: String = "",
    /** False when the user chose "send without saving a copy" after a Sent failure. */
    saveCopy: Boolean = true,
): String {
    val session = when (offer) {
        JmapOffer.None -> throw JmapFailure("jmap submission is not offered")
        is JmapOffer.Mail -> offer.session
    }
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    val identityId = jmapIdentityFor(session, mailFrom, username, password, pin, post, trust)
    val sentId = if (saveCopy) jmapFindSentMailbox(session, username, password, pin, post, trust, sentName) else null
    // No Sent folder (none configured, no role "sent", or the user dropped the copy): EmailSubmission still needs
    // an Email, so it is staged in Drafts (else Inbox) and destroyed by onSuccessDestroyEmail. No copy is kept.
    val mailboxId = sentId ?: jmapStagingMailbox(session, username, password, pin, post, trust)
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
        destroyEmail = sentId == null,
        identityId = identityId,
    )
    return submissionId
}

/** Thrown when no Identity has exactly the From address; David's rule: never guess an identity. */
class JmapNoIdentity(val from: String) : JmapFailure("no JMAP identity matches the From address $from")

fun jmapIdentityRequest(accountId: String): String =
    """{"using":["urn:ietf:params:jmap:core","$JMAP_SUBMISSION"],"methodCalls":[["Identity/get",{"accountId":${quoted(accountId)},"properties":["id","email"]},"0"]]}"""

/** The id of the identity whose email equals [from] (case-insensitive); null when none does. */
fun jmapMatchingIdentityId(text: String, from: String): String? {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return null }
    val responses = (root as? Json.Obj)?.fields?.get("methodResponses") as? Json.Arr ?: return null
    for (item in responses.values) {
        val call = item as? Json.Arr ?: continue
        if ((call.values.getOrNull(0) as? Json.Str)?.text != "Identity/get") continue
        val list = (call.values.getOrNull(1) as? Json.Obj)?.fields?.get("list") as? Json.Arr ?: return null
        for (row in list.values) {
            val f = (row as? Json.Obj)?.fields ?: continue
            val email = (f["email"] as? Json.Str)?.text ?: continue
            if (email.equals(from.trim(), ignoreCase = true)) return (f["id"] as? Json.Str)?.text?.takeIf { it.isNotBlank() }
        }
    }
    return null
}

fun jmapIdentityFor(
    session: JmapSession,
    from: String,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val result = jmapCall(session.apiUrl, jmapIdentityRequest(accountId), jmapBasicAuthorization(username, password), pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return jmapMatchingIdentityId(result.body, from) ?: throw JmapNoIdentity(from)
}

fun jmapStagingMailbox(
    session: JmapSession,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val authorization = jmapBasicAuthorization(username, password)
    for (role in listOf("drafts", "inbox")) {
        val result = jmapCall(session.apiUrl, jmapRoleMailboxRequest(accountId, role), authorization, pin, post, trust)
        if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
        try { return jmapSentMailboxId(result.body) } catch (_: JmapFailure) {}
    }
    throw JmapFailure("jmap has no drafts or inbox mailbox to stage the message")
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

/** The type of a method-level ["error", {"type": ...}] response (e.g. Cyrus "accountNotSupportedByMethod"). */
private fun methodErrorType(responses: Json?): String? {
    if (responses !is Json.Arr) return null
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        if ((item.values[0] as? Json.Str)?.text != "error") continue
        return ((item.values[1] as? Json.Obj)?.fields?.get("type") as? Json.Str)?.text?.takeIf { it.isNotBlank() }
    }
    return null
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
