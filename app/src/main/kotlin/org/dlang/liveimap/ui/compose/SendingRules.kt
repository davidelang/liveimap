package org.dlang.liveimap.ui.compose

/**
 * Sending rules (design-decisions.md "Sending rules and roles"). Pure Kotlin.
 * First match wins per field; the account default (no conditions) ends the list.
 */
enum class ComposeKindTag(val code: String) { New("new"), Reply("reply"), ReplyAll("replyall"), Forward("forward") }

enum class DsnMode(val code: String) { Off("off"), Failure("failure"), FailureDelay("delay"), All("all") }

data class RuleConditions(
    val kinds: Set<ComposeKindTag> = emptySet(),
    val recipient: List<String> = emptyList(),
    val sentTo: List<String> = emptyList(),
    val listId: String = "",
    val folder: String = "",
    val originalFrom: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = kinds.isEmpty() && recipient.isEmpty() && sentTo.isEmpty() &&
        listId.isEmpty() && folder.isEmpty() && originalFrom.isEmpty()
}

data class RuleActions(
    val fromName: String? = null,
    val fromAddress: String? = null,
    val replyTo: String? = null,
    /** "" = no signature. */
    val signature: String? = null,
    val sigAtBottom: Boolean? = null,
    val sigDashes: Boolean? = null,
    val quotePrefix: String? = null,
    val leadIn: String? = null,
    val fcc: String? = null,
    val dsn: DsnMode? = null,
    val replyToList: Boolean? = null,
    val role: String? = null,
    /** Account id whose SMTP sends and whose IMAP takes the Fcc APPEND. */
    val sendAccount: String? = null,
)

data class SendingRule(
    val name: String,
    val conditions: RuleConditions = RuleConditions(),
    val actions: RuleActions = RuleActions(),
    /** Unknown keys from a newer version, kept as is. */
    val extra: Map<String, String> = emptyMap(),
)

data class ComposeContext(
    val kind: ComposeKindTag,
    val recipients: List<String> = emptyList(),
    /** To/Cc/Delivered-To/X-Original-To of the message replied to or forwarded. */
    val originalTo: List<String> = emptyList(),
    val listId: String = "",
    val folder: String = "",
    val originalFrom: List<String> = emptyList(),
    /** From pinned by the user in the picker; rules then only fill the other fields. */
    val pinnedFrom: String? = null,
)

data class Resolved(
    val fromName: String,
    val fromAddress: String,
    val replyTo: String,
    val signature: String,
    val sigAtBottom: Boolean,
    val sigDashes: Boolean,
    val quotePrefix: String,
    val leadIn: String,
    val fcc: String,
    val dsn: DsnMode,
    val replyToList: Boolean,
    val role: String,
    val sendAccount: String,
    /** Name of the rule that chose the From address. */
    val fromRule: String,
)

const val DefaultQuotePrefix = "> "
const val DefaultLeadIn = "On {date}, {from} wrote:"
const val AccountDefaultRule = "Account default"

// ---------- matching ----------

/** Lower-cased addr-spec without a +tag in the local part. */
fun baseAddress(address: String): String {
    val a = addrSpec(address).trim().lowercase()
    val at = a.lastIndexOf('@')
    if (at <= 0) return a
    val local = a.substring(0, at).substringBefore('+')
    return local + a.substring(at)
}

/** Pattern: full address (plus tags match their base), `@domain` (and subdomains), or a glob with `*`. */
fun addressMatches(pattern: String, address: String): Boolean {
    val p = pattern.trim().lowercase()
    if (p.isEmpty()) return false
    val a = addrSpec(address).trim().lowercase()
    if (p.startsWith("@")) {
        val domain = a.substringAfterLast('@', "")
        val want = p.substring(1)
        return domain == want || domain.endsWith(".$want")
    }
    if ('*' in p) return globMatches(p, a)
    if ('+' in p.substringBefore('@')) return a == p
    return baseAddress(a) == baseAddress(p)
}

/** `*` any run, `%` any run without `/` or `.` (IMAP LIST style), else literal; case-insensitive. */
fun globMatches(pattern: String, text: String): Boolean {
    val rx = buildString {
        for (c in pattern.lowercase()) when (c) {
            '*' -> append(".*")
            '%' -> append("[^/.]*")
            else -> append(Regex.escape(c.toString()))
        }
    }
    return Regex(rx).matches(text.lowercase())
}

fun RuleConditions.matches(ctx: ComposeContext): Boolean {
    if (kinds.isNotEmpty() && ctx.kind !in kinds) return false
    if (recipient.isNotEmpty() && ctx.recipients.none { r -> recipient.any { addressMatches(it, r) } }) return false
    if (sentTo.isNotEmpty() && ctx.originalTo.none { r -> sentTo.any { addressMatches(it, r) } }) return false
    if (listId.isNotEmpty() && !globMatches(listId, ctx.listId.trim().removePrefix("<").removeSuffix(">")) &&
        !ctx.listId.contains("<$listId>", ignoreCase = true)) return false
    if (folder.isNotEmpty() && !globMatches(folder, ctx.folder)) return false
    if (originalFrom.isNotEmpty() && ctx.originalFrom.none { r -> originalFrom.any { addressMatches(it, r) } }) return false
    return true
}

/**
 * Field-wise first match: each field comes from the first matching rule that sets it; [accountDefault]
 * (conditions ignored) fills the rest. With a pinned From, rules setting a different From are skipped.
 */
fun resolveSending(rules: List<SendingRule>, accountDefault: SendingRule, accountId: String, ctx: ComposeContext): Resolved {
    val pinned = ctx.pinnedFrom?.let { baseAddress(it) }
    val matching = rules.filter { it.conditions.matches(ctx) }.filter { rule ->
        pinned == null || rule.actions.fromAddress == null || baseAddress(rule.actions.fromAddress) == pinned
    } + accountDefault
    fun <T> pick(get: (RuleActions) -> T?): Pair<T?, String> {
        for (r in matching) get(r.actions)?.let { return it to r.name }
        return null to AccountDefaultRule
    }
    val (fromAddr, fromRule) = pick { it.fromAddress }
    val chosenFrom = ctx.pinnedFrom ?: fromAddr.orEmpty()
    // Reply from the exact address it was sent to, keeping a +tag, when a sentTo rule picked its base.
    val tagged = ctx.originalTo.firstOrNull { it.contains('+') && baseAddress(it) == baseAddress(chosenFrom) && ctx.pinnedFrom == null }
    return Resolved(
        fromName = pick { it.fromName }.first.orEmpty(),
        fromAddress = tagged?.let { addrSpec(it) } ?: chosenFrom,
        replyTo = pick { it.replyTo }.first.orEmpty(),
        signature = pick { it.signature }.first.orEmpty(),
        sigAtBottom = pick { it.sigAtBottom }.first ?: false,
        sigDashes = pick { it.sigDashes }.first ?: true,
        quotePrefix = pick { it.quotePrefix }.first ?: DefaultQuotePrefix,
        leadIn = pick { it.leadIn }.first ?: DefaultLeadIn,
        fcc = pick { it.fcc }.first.orEmpty(),
        dsn = pick { it.dsn }.first ?: DsnMode.Off,
        replyToList = pick { it.replyToList }.first ?: false,
        role = pick { it.role }.first.orEmpty(),
        sendAccount = pick { it.sendAccount }.first ?: accountId,
        fromRule = if (ctx.pinnedFrom != null) "" else fromRule,
    )
}

/** Every From the rules know, for the compose picker (account's first). */
fun knownFroms(rules: List<SendingRule>, accountDefault: SendingRule): List<String> =
    (listOfNotNull(accountDefault.actions.fromAddress) + rules.mapNotNull { it.actions.fromAddress })
        .filter { it.isNotBlank() }.distinctBy { baseAddress(it) }

data class FromMismatch(val expectedFrom: String, val expectedAccount: String, val rule: String)

/**
 * Safety net: what the rules would pick for these recipients with no pinned From, compared with what will be
 * sent. Also flags a From that doesn't belong to the sending account ([accountFroms]: its address,
 * alt-addresses, and Froms its rules pair with it).
 */
fun fromMismatch(
    rules: List<SendingRule>,
    accountDefault: SendingRule,
    accountId: String,
    ctx: ComposeContext,
    from: String,
    sendAccount: String,
    accountFroms: (String) -> List<String>,
): FromMismatch? {
    val expected = resolveSending(rules, accountDefault, accountId, ctx.copy(pinnedFrom = null))
    val fromOk = baseAddress(expected.fromAddress) == baseAddress(from) || expected.fromRule == AccountDefaultRule
    val accountOk = accountFroms(sendAccount).any { baseAddress(it) == baseAddress(from) }
    val serverOk = expected.sendAccount == sendAccount || expected.fromRule == AccountDefaultRule
    return if (fromOk && accountOk && serverOk) null else FromMismatch(expected.fromAddress, expected.sendAccount, expected.fromRule)
}

// ---------- storage ----------

private fun enc(v: String): String = buildString {
    for (b in v.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.@*+ ") append(c.toChar()) else append("%%%02X".format(c))
    }
}

private fun dec(v: String): String {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < v.length) {
        val c = v[i]
        if (c == '%' && i + 2 < v.length && v.substring(i + 1, i + 3).all { it.isLetterOrDigit() }) {
            out.write(v.substring(i + 1, i + 3).toInt(16)); i += 3
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i += 1
        }
    }
    return out.toString("UTF-8")
}

private fun list(v: List<String>) = v.joinToString(",") { enc(it).replace(",", "%2C") }
private fun unlist(v: String) = if (v.isEmpty()) emptyList() else v.split(',').map { dec(it) }

/** One rule per line: `key=value` pairs separated by `&`, values percent-encoded. */
fun encodeRules(rules: List<SendingRule>): String = rules.joinToString("\n") { r ->
    val c = r.conditions
    val a = r.actions
    val kv = linkedMapOf("name" to enc(r.name))
    if (c.kinds.isNotEmpty()) kv["kinds"] = c.kinds.joinToString(",") { it.code }
    if (c.recipient.isNotEmpty()) kv["recipient"] = list(c.recipient)
    if (c.sentTo.isNotEmpty()) kv["sentTo"] = list(c.sentTo)
    if (c.listId.isNotEmpty()) kv["listId"] = enc(c.listId)
    if (c.folder.isNotEmpty()) kv["folder"] = enc(c.folder)
    if (c.originalFrom.isNotEmpty()) kv["origFrom"] = list(c.originalFrom)
    a.fromName?.let { kv["fromName"] = enc(it) }
    a.fromAddress?.let { kv["from"] = enc(it) }
    a.replyTo?.let { kv["replyTo"] = enc(it) }
    a.signature?.let { kv["sig"] = enc(it) }
    a.sigAtBottom?.let { kv["sigBottom"] = it.toString() }
    a.sigDashes?.let { kv["sigDashes"] = it.toString() }
    a.quotePrefix?.let { kv["quote"] = enc(it) }
    a.leadIn?.let { kv["leadIn"] = enc(it) }
    a.fcc?.let { kv["fcc"] = enc(it) }
    a.dsn?.let { kv["dsn"] = it.code }
    a.replyToList?.let { kv["toList"] = it.toString() }
    a.role?.let { kv["role"] = enc(it) }
    a.sendAccount?.let { kv["account"] = enc(it) }
    for ((k, v) in r.extra) kv.putIfAbsent(k, v)
    kv.entries.joinToString("&") { "${it.key}=${it.value.replace("&", "%26").replace("=", "%3D").replace("\n", "%0A")}" }
}

fun decodeRules(text: String): List<SendingRule> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val kv = linkedMapOf<String, String>()
    for (part in line.split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        kv[part.substring(0, eq)] = part.substring(eq + 1)
    }
    fun s(k: String) = kv.remove(k)?.let { dec(it) }
    fun b(k: String) = kv.remove(k)?.toBooleanStrictOrNull()
    fun l(k: String) = kv.remove(k)?.let { unlist(it) } ?: emptyList()
    val name = s("name") ?: return@mapNotNull null
    val kinds = kv.remove("kinds")?.split(',')?.mapNotNull { c -> ComposeKindTag.entries.firstOrNull { it.code == c } }?.toSet() ?: emptySet()
    val cond = RuleConditions(kinds, l("recipient"), l("sentTo"), s("listId").orEmpty(), s("folder").orEmpty(), l("origFrom"))
    val act = RuleActions(
        fromName = s("fromName"), fromAddress = s("from"), replyTo = s("replyTo"), signature = s("sig"),
        sigAtBottom = b("sigBottom"), sigDashes = b("sigDashes"), quotePrefix = s("quote"), leadIn = s("leadIn"),
        fcc = s("fcc"), dsn = kv.remove("dsn")?.let { c -> DsnMode.entries.firstOrNull { it.code == c } },
        replyToList = b("toList"), role = s("role"), sendAccount = s("account"),
    )
    SendingRule(name, cond, act, kv.toMap())
}

// ---------- presets (each returns ordinary rules) ----------

object RulePresets {
    /** 2: reply (and forward, 9) from the address the original was sent to. */
    fun replyFromSentTo(addresses: List<String>, account: String? = null): List<SendingRule> = addresses.filter { it.isNotBlank() }.map {
        SendingRule(
            "Reply from ${addrSpec(it)}",
            RuleConditions(kinds = setOf(ComposeKindTag.Reply, ComposeKindTag.ReplyAll, ComposeKindTag.Forward), sentTo = listOf(addrSpec(it))),
            RuleActions(fromAddress = addrSpec(it), sendAccount = account),
        )
    }

    /** 3: per recipient or domain, new messages too. */
    fun perRecipient(pattern: String, from: String, signature: String? = null, account: String? = null) = SendingRule(
        "To $pattern", RuleConditions(recipient = listOf(pattern)),
        RuleActions(fromAddress = from, signature = signature, sendAccount = account),
    )

    /** 4: per folder: From, Fcc, signature. */
    fun perFolder(folder: String, from: String?, fcc: String?, signature: String?, account: String? = null) = SendingRule(
        "Folder $folder", RuleConditions(folder = folder),
        RuleActions(fromAddress = from, fcc = fcc, signature = signature, sendAccount = account),
    )

    /** 5: mailing list by List-Id. */
    fun mailingList(listId: String, subscribed: String, signature: String = "", account: String? = null) = SendingRule(
        "List $listId", RuleConditions(listId = listId),
        RuleActions(fromAddress = subscribed, replyToList = true, signature = signature, sendAccount = account),
    )

    /** 6: plus folders: me+tag@x -> From me+tag@x, Fcc tag. */
    fun plusFolder(base: String, tag: String, account: String? = null): SendingRule {
        val a = addrSpec(base)
        val tagged = a.substringBefore('@') + "+" + tag + "@" + a.substringAfter('@')
        return SendingRule("Plus $tag", RuleConditions(sentTo = listOf(tagged)), RuleActions(fromAddress = tagged, fcc = tag, sendAccount = account))
    }

    /** 7: signature by context. */
    fun signatureFor(kinds: Set<ComposeKindTag>, signature: String) = SendingRule(
        "Signature for ${kinds.joinToString("/") { it.code }}", RuleConditions(kinds = kinds), RuleActions(signature = signature),
    )

    /** 8: Fcc per role (From). */
    fun fccFor(from: String, fcc: String) = SendingRule("Fcc for ${addrSpec(from)}", RuleConditions(sentTo = listOf(addrSpec(from))), RuleActions(fcc = fcc))
}

// ---------- account glue ----------

/** The account's simple fields as the always-matching last rule. */
fun accountDefaultRule(settings: org.dlang.liveimap.settings.AccountSettings) = SendingRule(
    AccountDefaultRule,
    actions = RuleActions(
        fromName = settings.displayName,
        fromAddress = settings.email,
        signature = settings.signature,
        sigAtBottom = settings.sigAtBottom,
        sigDashes = settings.sigDashes,
        quotePrefix = settings.quotePrefix,
        leadIn = settings.replyLeadIn,
        fcc = settings.sentMailbox,
        dsn = settings.dsnMode,
    ),
)

fun accountRules(settings: org.dlang.liveimap.settings.AccountSettings): List<SendingRule> = decodeRules(settings.sendingRules)

/** Context from the message being answered or forwarded ([original] null for new messages). */
fun composeContext(kind: ComposeKindTag, original: ParsedRfc822?, recipients: List<String>, folder: String): ComposeContext {
    if (original == null) return ComposeContext(kind, recipients = recipients, folder = folder)
    val to = listOf("To", "Cc", "Delivered-To", "X-Original-To").flatMap { addresses(original, it) }
    return ComposeContext(
        kind = kind,
        recipients = recipients,
        originalTo = to,
        listId = headerValues(original, "List-Id").firstOrNull().orEmpty(),
        folder = folder,
        originalFrom = addresses(original, "From"),
    )
}

/** Settings as the composer sends with them: From, Fcc and the rest from [r]. */
fun org.dlang.liveimap.settings.AccountSettings.withSending(r: Resolved?): org.dlang.liveimap.settings.AccountSettings {
    if (r == null) return this
    return copy(
        displayName = r.fromName.ifEmpty { displayName },
        email = r.fromAddress.ifEmpty { email },
        sentMailbox = r.fcc.ifEmpty { sentMailbox },
    )
}

/** RFC 3461 NOTIFY mask for libetpan (MAILSMTP_DSN_NOTIFY_*); 0 = no DSN. */
fun dsnMask(mode: DsnMode): Int = when (mode) {
    DsnMode.Off -> 0
    DsnMode.Failure -> 2
    DsnMode.FailureDelay -> 2 or 4
    DsnMode.All -> 1 or 2 or 4
}

/** Composer header list: comma or space separated names, e.g. "Reply-To, X-Priority". */
fun headerNames(list: String): List<String> =
    list.split(',', ' ', '\n').map { it.trim().trimEnd(':') }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/** RFC 2369 List-Post mailto address, or null (absent, "NO", or not mailto). */
fun listPostAddress(message: ParsedRfc822): String? {
    val value = headerValues(message, "List-Post").firstOrNull()?.trim() ?: return null
    val m = Regex("<mailto:([^>?]+)", RegexOption.IGNORE_CASE).find(value) ?: return null
    return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").trim().ifEmpty { null }
}
