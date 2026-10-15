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

/** F147a: shown after a send whose staging copy stayed in Drafts. */
const val JMAP_STAGING_LEFT = "Sent, but its temporary copy could not be removed from the Drafts folder"

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
): String = jmapSubmitAnswer(session, emailId, mailFrom, rcptTo, username, password, pin, post, trust, destroyEmail, identityId).first

/** The EmailSubmission id and the whole answer (which carries the implicit Email/set of onSuccessDestroyEmail). */
fun jmapSubmitAnswer(
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
): Pair<String, String> {
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapEmailSubmissionRequest(accountId, emailId, mailFrom, rcptTo, destroyEmail, identityId)
    val authorization = jmapBasicAuthorization(username, password)
    // F146: once the EmailSubmission request may have reached the server, a lost answer is not "not sent".
    val result = try {
        jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    } catch (failure: JmapFailure) {
        throw JmapMaybeSent(failure.text)
    }
    if (result.status >= 500) throw JmapMaybeSent("jmap api status ${result.status}")
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return try {
        jmapSubmissionId(result.body) to result.body
    } catch (failure: JmapFailure) {
        // A server refusal (notCreated or a method error) is definite; an unreadable answer is not.
        if (jmapAnswerReadable(result.body)) throw failure
        throw JmapMaybeSent(failure.text)
    }
}

/**
 * F146: the submission request went out but its outcome is unknown (timeout, reset, 5xx, unreadable answer), and
 * a follow-up query could not settle it. The message may have been sent: never delete its copies, never say "not sent".
 */
class JmapMaybeSent(cause: String) : JmapFailure("the server may have sent this message ($cause); check Sent before sending again")

private fun jmapAnswerReadable(text: String): Boolean {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return false }
    return (root as? Json.Obj)?.fields?.get("methodResponses") is Json.Arr
}

fun jmapSubmissionQueryRequest(accountId: String, emailId: String): String =
    """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL","$JMAP_SUBMISSION"],"methodCalls":[["EmailSubmission/query",{"accountId":${quoted(accountId)},"filter":{"emailIds":[${quoted(emailId)}]}},"0"]]}"""

/** The ids an EmailSubmission/query answered, or null when the answer does not say (then nothing is known). */
fun jmapSubmissionQueryIds(text: String): List<String>? {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return null }
    val responses = (root as? Json.Obj)?.fields?.get("methodResponses") as? Json.Arr ?: return null
    for (item in responses.values) {
        val call = item as? Json.Arr ?: continue
        if ((call.values.getOrNull(0) as? Json.Str)?.text != "EmailSubmission/query") continue
        val ids = (call.values.getOrNull(1) as? Json.Obj)?.fields?.get("ids") as? Json.Arr ?: return null
        return ids.values.map { (it as? Json.Str)?.text ?: return null }
    }
    return null
}

/**
 * F147a: whether the implicit Email/set of onSuccessDestroyEmail destroyed [emailId]: true/false when the answer
 * has that Email/set, null when it has none (a server that ignored onSuccessDestroyEmail).
 */
fun jmapStagingDestroyed(text: String, emailId: String): Boolean? {
    val root = try { JsonParser(text).parseDocument() } catch (_: JsonBroken) { return null }
    val responses = (root as? Json.Obj)?.fields?.get("methodResponses") as? Json.Arr ?: return null
    for (item in responses.values) {
        val call = item as? Json.Arr ?: continue
        if ((call.values.getOrNull(0) as? Json.Str)?.text != "Email/set") continue
        val destroyed = (call.values.getOrNull(1) as? Json.Obj)?.fields?.get("destroyed") as? Json.Arr ?: return false
        return destroyed.values.any { (it as? Json.Str)?.text == emailId }
    }
    return null
}

fun jmapDestroyRequest(accountId: String, emailId: String): String {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    if (emailId.isBlank()) throw JmapFailure("jmap message id is empty")
    val account = quoted(accountId)
    val id = quoted(emailId)
    return """{"using":["urn:ietf:params:jmap:core","$JMAP_MAIL"],"methodCalls":[["Email/set",{"accountId":$account,"destroy":[$id]},"0"]]}"""
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
    /** F147a: told when a sent message's staging copy could not be removed from Drafts. */
    onNotice: (String) -> Unit = {},
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
    val request = jmapEmailImportRequest(accountId, blobId, mailboxId, if (destroyEmail) JMAP_STAGING_KEYWORDS else emptyList())
    val authorization = jmapBasicAuthorization(username, password)
    val imported = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (imported.status != 200) throw JmapFailure("jmap api status ${imported.status}")
    val emailId = jmapImportedEmailId(imported.body)
    fun destroyCopy(): Boolean = try {
        jmapStagingDestroyed(jmapCall(session.apiUrl, jmapDestroyRequest(accountId, emailId), authorization, pin, post, trust).body, emailId) == true
    } catch (_: JmapFailure) {
        false
    }
    return try {
        val submitted = jmapSubmitAnswer(
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
        // F147a: a staging copy the server did not destroy is removed now, or the user is told it is left over.
        if (destroyEmail && jmapStagingDestroyed(submitted.second, emailId) != true && !destroyCopy()) {
            onNotice(JMAP_STAGING_LEFT)
        }
        submitted.first
    } catch (maybe: JmapMaybeSent) {
        // F146: settle it from the server before anything is deleted or reported.
        val known = try {
            jmapSubmissionQueryIds(jmapCall(session.apiUrl, jmapSubmissionQueryRequest(accountId, emailId), authorization, pin, post, trust).body)
        } catch (_: JmapFailure) {
            null
        }
        when {
            known == null -> throw maybe
            known.isNotEmpty() -> {
                if (destroyEmail && !destroyCopy()) onNotice(JMAP_STAGING_LEFT)
                known.first()
            }
            else -> {
                destroyCopy()
                throw JmapFailure("jmap submission was not created")
            }
        }
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
    /** The account's Postponed/Drafts folder: where a message with no Sent copy is staged for submission. */
    draftsName: String = "",
    onNotice: (String) -> Unit = {},
): String {
    val session = when (offer) {
        JmapOffer.None -> throw JmapFailure("jmap submission is not offered")
        is JmapOffer.Mail -> offer.session
    }
    if (!session.offersSubmission()) throw JmapFailure("jmap submission is not offered")
    // F145: the identity (and the envelope sender) is the message's own From (Resent-From for a bounce), not the
    // account address; [mailFrom] is only the fallback for a message without one.
    val author = org.dlang.liveimap.ui.compose.headerFromAddress(bytes).ifBlank { mailFrom.trim() }
    val identityId = jmapIdentityFor(session, author, username, password, pin, post, trust)
    val sentId = if (saveCopy) jmapFindSentMailbox(session, username, password, pin, post, trust, sentName) else null
    // No Sent folder (none configured, or the user dropped the copy): EmailSubmission still needs an Email, so it
    // is staged in the configured Drafts folder and destroyed by onSuccessDestroyEmail. No copy is kept.
    val mailboxId = sentId ?: jmapStagingMailbox(session, draftsName, username, password, pin, post, trust)
    val submissionId = jmapDeliver(
        session,
        bytes,
        mailboxId,
        author,
        rcptTo,
        username,
        password,
        pin,
        upload,
        post,
        trust,
        destroyEmail = sentId == null,
        identityId = identityId,
        onNotice = onNotice,
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

/** No Sent folder and no configured Drafts: JMAP needs somewhere to hold the message while it is submitted. */
class JmapNoDraftsFolder : JmapFailure("a Drafts (Postponed) folder must be set to send over JMAP without a Sent folder")

/** The configured Postponed/Drafts folder, matched by its exact full path (F148); never a role or another folder. */
fun jmapStagingMailbox(
    session: JmapSession,
    draftsName: String,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    if (draftsName.isBlank()) throw JmapNoDraftsFolder()
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val named = jmapCall(session.apiUrl, jmapMailboxNamesRequest(accountId), jmapBasicAuthorization(username, password), pin, post, trust)
    if (named.status != 200) throw JmapFailure("jmap api status ${named.status}")
    return jmapMailboxIdByPath(named.body, draftsName) ?: throw JmapFailure("jmap drafts folder \"$draftsName\" was not found")
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
