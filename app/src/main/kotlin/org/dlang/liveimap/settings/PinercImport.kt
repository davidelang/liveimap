package org.dlang.liveimap.settings

import java.util.Locale

data class PinercPreview(
    val next: AccountSettings,
    val rows: List<String>,
    val skipped: List<String>,
    val omittedCount: Int,
    val offerAutoExpunge: Boolean,
)

data class PinercPhrases(
    val tls: String,
    val smtpUser: String,
    val local: String,
    val history: String,
    val sort: String,
    val rule: String,
    val expunge: String,
    val passwords: String,
    val folders: String,
    val signature: String,
    val perFolder: String,
    val inboxDefault: String,
    val change: String,
    val imapHost: String,
    val imapPort: String,
    val smtpHost: String,
    val smtpPort: String,
    val username: String,
    val displayName: String,
    val altAddresses: String,
    val email: String,
    val sentMailbox: String,
    val postponedMailbox: String,
    val addressBookMailbox: String,
    val historyLabel: String,
    val defaultView: String,
    val newest: String,
    val askExpunge: String,
    val inboxOpens: String,
    val otherHost: String,
    val inboxBraces: String,
)

fun parsePinerc(text: String): Map<String, String?> =
    parseEntries(text).mapValues { it.value.decoded }

fun pinercPreview(text: String, current: AccountSettings, phrases: PinercPhrases): PinercPreview {
    val entries = parseEntries(text)
    var next = current
    val skipped = mutableListOf<String>()
    var omitted = 0
    var inboxUserApplied = false

    val inbox = entries["inbox-path"]?.decoded
    if (inbox != null) {
        if (!inbox.startsWith("{")) {
            skipped.add(phrases.inboxBraces)
        } else {
            val spec = parseRemoteSpec(inbox)
            if (spec != null) {
                if (spec.tls) {
                    skipped.add(phrases.tls.format(spec.host))
                } else {
                    next = next.copy(imapHost = spec.host, imapPort = spec.port ?: 143)
                    if (spec.user != null) {
                        next = next.copy(username = spec.user)
                        inboxUserApplied = true
                    }
                }
            }
        }
    }
    val comparisonHost = next.imapHost
    val collectionRaw = entries["folder-collections"]?.raw

    val smtp = entries["smtp-server"]
    if (smtp != null && smtp.raw.isNotEmpty()) {
        val first = splitList(smtp.raw).firstOrNull { it.isNotEmpty() }
        if (first != null) {
            val spec = parseRemoteSpec(first)
            if (spec != null) {
                if (spec.tls) {
                    skipped.add(phrases.tls.format(spec.host))
                } else {
                    val port = spec.port ?: if (spec.submit) 587 else 25
                    next = next.copy(smtpHost = spec.host, smtpPort = port)
                }
                val imapUser = if (inboxUserApplied) next.username else current.username
                if (spec.user != null && spec.user != imapUser) {
                    skipped.add(phrases.smtpUser)
                }
            }
        }
    }

    val userId = entries["user-id"]?.decoded
    val domain = entries["user-domain"]?.decoded
    if (!inboxUserApplied && userId != null) {
        next = next.copy(username = userId)
    }
    val local = if (userId != null) {
        userId
    } else if (inboxUserApplied) {
        next.username
    } else {
        null
    }
    if (local != null) {
        val email = if ('@' in local) {
            local
        } else if (!domain.isNullOrEmpty()) {
            "$local@$domain"
        } else {
            null
        }
        if (email != null) next = next.copy(email = email)
    }

    val personal = entries["personal-name"]?.decoded
    if (personal != null) next = next.copy(displayName = personal)

    val alt = entries["alt-addresses"]
    if (alt != null && alt.raw.isNotEmpty()) {
        val specs = splitList(alt.raw).map { it.trim() }.filter { it.isNotEmpty() }
        next = next.copy(altAddresses = specs)
    }

    val sent = entries["default-fcc"]
    if (sent?.decoded != null) {
        when (val folder = classifyFolder(sent.decoded, allowPlain = true, comparisonHost, collectionRaw)) {
            FolderKind.Local -> skipped.add(phrases.local)
            is FolderKind.Mailbox -> next = next.copy(sentMailbox = folder.name)
            is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
            FolderKind.Empty -> Unit
        }
    }

    val postponed = entries["postponed-folder"]
    if (postponed?.decoded != null) {
        when (val folder = classifyFolder(postponed.decoded, allowPlain = true, comparisonHost, collectionRaw)) {
            FolderKind.Local -> skipped.add(phrases.local)
            is FolderKind.Mailbox -> next = next.copy(postponedMailbox = folder.name)
            is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
            FolderKind.Empty -> Unit
        }
    }

    val book = entries["address-book"]
    if (book != null && book.raw.isNotEmpty()) {
        var chosen: String? = null
        for (item in splitList(book.raw)) {
            if (item.isEmpty()) continue
            when (val folder = classifyFolder(item, allowPlain = false, comparisonHost, collectionRaw)) {
                FolderKind.Local -> skipped.add(phrases.local)
                is FolderKind.Mailbox -> if (chosen == null) chosen = folder.name
                is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
                FolderKind.Empty -> Unit
            }
        }
        if (chosen != null) next = next.copy(addressBookMailbox = chosen)
    }

    val history = entries["remote-abook-history"]
    if (history != null) {
        val raw = history.decoded?.trim().orEmpty()
        val number = if (raw.isNotEmpty() && raw.all { it.isDigit() }) raw.toIntOrNull() else null
        if (number == null) skipped.add(phrases.history)
        else next = next.copy(addressBookHistory = number)
    }

    val sort = entries["sort-key"]?.decoded
    if (sort != null) {
        val view = parseSort(sort)
        if (view == null) {
            skipped.add(phrases.sort)
        } else {
            next = next.copy(defaultView = view)
        }
    }

    var appliedAlpineDefault = false
    if ("incoming-startup-rule" in entries) {
        val raw = entries["incoming-startup-rule"]?.decoded?.trim().orEmpty()
        val mapped = parseStartupRule(raw)
        if (mapped == null) {
            skipped.add(phrases.rule)
        } else {
            next = next.copy(inboxStart = mapped)
        }
    } else if (current.pinercStartDefault == PinercStartDefault.AlpineDefault) {
        next = next.copy(inboxStart = StartRule.FirstUnseen)
        appliedAlpineDefault = true
    }

    var offerAutoExpunge = false
    val features = entries["feature-list"]
    if (features?.decoded != null) {
        var ask: Boolean? = null
        for (item in splitList(features.raw)) {
            if (item.isEmpty()) continue
            when (item) {
                "expunge-without-confirm",
                "expunge-without-confirm-everywhere",
                -> ask = false
                "no-expunge-without-confirm",
                "no-expunge-without-confirm-everywhere",
                -> ask = true
                "expunge-only-manually" -> skipped.add(phrases.expunge)
                else -> omitted += 1
            }
        }
        if (ask != null) next = next.copy(askBeforeExpunge = ask)
        offerAutoExpunge = ask == false
    }

    for (name in entries.keys) {
        if ("pass" in name) {
            skipped.add(phrases.passwords)
            continue
        }
        when (name) {
            "incoming-folders", "stay-open-folders", "folder-collections" ->
                skipped.add(phrases.folders)
            "signature-file", "literal-signature" ->
                skipped.add(phrases.signature)
            "patterns-other" -> {
                val raw = entries[name]?.raw.orEmpty()
                if (raw.contains("/START=", ignoreCase = true)) {
                    skipped.add(phrases.perFolder)
                } else {
                    omitted += 1
                }
            }
            else -> if (name !in appliedNames) omitted += 1
        }
    }

    val rows = diffRows(current, next, phrases).toMutableList()
    if (appliedAlpineDefault) {
        val inboxPrefix = "${phrases.inboxOpens}:"
        rows.removeAll { it.startsWith(inboxPrefix) }
        rows.add(phrases.inboxDefault)
    }
    return PinercPreview(
        next = next,
        rows = rows,
        skipped = skipped,
        omittedCount = omitted,
        offerAutoExpunge = offerAutoExpunge,
    )
}

fun pinercApplied(preview: PinercPreview, turnOnAutoExpunge: Boolean): AccountSettings =
    if (turnOnAutoExpunge && preview.offerAutoExpunge) {
        preview.next.copy(autoExpunge = true)
    } else {
        preview.next
    }

private val appliedNames = setOf(
    "inbox-path",
    "smtp-server",
    "user-id",
    "user-domain",
    "personal-name",
    "alt-addresses",
    "default-fcc",
    "postponed-folder",
    "address-book",
    "remote-abook-history",
    "sort-key",
    "feature-list",
    "incoming-startup-rule",
)

private val variableName = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

private data class PinercValue(val decoded: String?, val raw: String)

private data class RemoteSpec(
    val host: String,
    val port: Int?,
    val user: String?,
    val tls: Boolean,
    val submit: Boolean,
)

private sealed class FolderKind {
    data object Local : FolderKind()
    data object Empty : FolderKind()
    data class Mailbox(val name: String) : FolderKind()
    data class OtherHost(val host: String) : FolderKind()
}

private fun parseEntries(text: String): Map<String, PinercValue> {
    val out = linkedMapOf<String, PinercValue>()
    var name: String? = null
    val raw = StringBuilder()

    fun finish() {
        val key = name ?: return
        out[key] = interpretValue(raw.toString())
        name = null
        raw.setLength(0)
    }

    for (line in text.lines()) {
        if (line.isEmpty() || line.startsWith("#")) continue
        // A leading space or tab continues the previous value with no added separator.
        if (line.startsWith(" ") || line.startsWith("\t")) {
            if (name != null) raw.append(line.trimStart(' ', '\t'))
            continue
        }
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq)
        if (!variableName.matches(key)) continue
        finish()
        name = key.lowercase(Locale.ROOT)
        raw.append(line.substring(eq + 1))
    }
    finish()
    return out
}

private fun interpretValue(assembled: String): PinercValue {
    if (assembled.isEmpty()) return PinercValue(decoded = null, raw = "")
    val trimmed = trimOneSpace(assembled)
    if (trimmed.isEmpty()) return PinercValue(decoded = "", raw = "")
    return PinercValue(decoded = decodeScalar(trimmed), raw = trimmed)
}

private fun trimOneSpace(value: String): String {
    var s = value
    if (s.startsWith(" ")) s = s.substring(1)
    if (s.endsWith(" ")) s = s.dropLast(1)
    return s
}

private fun decodeScalar(trimmed: String): String {
    if (trimmed[0] != '"') return trimmed
    val quoted = takeQuoted(trimmed) ?: return unescapeLoose(trimmed.substring(1))
    if (trimmed.substring(quoted.end).isBlank()) return quoted.text
    return trimmed
}

private class Quoted(val text: String, val end: Int)

private fun takeQuoted(raw: String): Quoted? {
    val sb = StringBuilder()
    var i = 1
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            val n = raw[i + 1]
            if (n == '\\' || n == '"') {
                sb.append(n)
                i += 2
                continue
            }
        }
        if (c == '"') return Quoted(sb.toString(), i + 1)
        sb.append(c)
        i++
    }
    return null
}

private fun unescapeLoose(body: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            val n = body[i + 1]
            if (n == '\\' || n == '"') {
                sb.append(n)
                i += 2
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun splitList(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    val items = mutableListOf<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (quoted) {
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                if (n == '\\' || n == '"') {
                    sb.append(n)
                    i += 2
                    continue
                }
            }
            if (c == '"') {
                quoted = false
                i++
                continue
            }
            sb.append(c)
            i++
            continue
        }
        if (c == '"') {
            quoted = true
            i++
            continue
        }
        if (c == ',') {
            items.add(sb.toString().trim())
            sb.clear()
            i++
            continue
        }
        sb.append(c)
        i++
    }
    items.add(sb.toString().trim())
    return items
}

private fun parseRemoteSpec(value: String): RemoteSpec? {
    val body = if (value.startsWith("{")) {
        val end = value.indexOf('}')
        if (end < 1) return null
        value.substring(1, end)
    } else {
        value
    }
    if (body.isEmpty()) return null
    val slash = body.indexOf('/')
    val hostPort = if (slash < 0) body else body.substring(0, slash)
    val flags = if (slash < 0) emptyList() else body.substring(slash + 1).split('/')
    if (hostPort.isEmpty()) return null
    val colon = hostPort.lastIndexOf(':')
    var host = hostPort
    var port: Int? = null
    if (colon > 0 && colon < hostPort.lastIndex) {
        val portText = hostPort.substring(colon + 1)
        if (portText.isNotEmpty() && portText.all { it.isDigit() }) {
            host = hostPort.substring(0, colon)
            port = portText.toIntOrNull() ?: return null
        }
    }
    if (host.isEmpty()) return null
    var tls = false
    var submit = false
    var user: String? = null
    for (flag in flags) {
        if (flag.isEmpty()) continue
        when {
            flag.equals("ssl", ignoreCase = true) ||
                flag.equals("tls", ignoreCase = true) ||
                flag.equals("secure", ignoreCase = true) -> tls = true
            flag.equals("submit", ignoreCase = true) -> submit = true
            flag.startsWith("user=", ignoreCase = true) -> user = flag.substring(5)
        }
    }
    return RemoteSpec(host, port, user, tls, submit)
}

private fun classifyFolder(
    value: String,
    allowPlain: Boolean,
    comparisonHost: String,
    collectionRaw: String?,
): FolderKind {
    if (value.contains('}')) return classifyBraced(value, comparisonHost)
    if (!allowPlain) return FolderKind.Local
    if (value.isEmpty()) return FolderKind.Empty
    if (value.startsWith("/") || value.startsWith("~") || '/' in value) return FolderKind.Local
    return applyCollectionPrefix(value, comparisonHost, collectionRaw)
}

private fun classifyBraced(value: String, comparisonHost: String): FolderKind {
    val brace = value.indexOf('{')
    val spec = if (brace >= 0) parseRemoteSpec(value.substring(brace)) else null
    if (spec != null &&
        comparisonHost.isNotEmpty() &&
        !comparisonHost.equals(spec.host, ignoreCase = true)
    ) {
        return FolderKind.OtherHost(spec.host)
    }
    val mailbox = value.substringAfter('}')
    if (mailbox.isEmpty()) return FolderKind.Empty
    return FolderKind.Mailbox(mailbox)
}

private fun applyCollectionPrefix(
    plainName: String,
    comparisonHost: String,
    collectionRaw: String?,
): FolderKind {
    if (collectionRaw.isNullOrEmpty()) return FolderKind.Mailbox(plainName)
    val item = splitList(collectionRaw).firstOrNull { it.isNotEmpty() }
        ?: return FolderKind.Mailbox(plainName)
    val brace = item.indexOf('{')
    if (brace < 0) return FolderKind.Mailbox(plainName)
    val specText = item.substring(brace)
    val spec = parseRemoteSpec(specText) ?: return FolderKind.Mailbox(plainName)
    if (comparisonHost.isEmpty()) return FolderKind.Mailbox(plainName)
    if (!comparisonHost.equals(spec.host, ignoreCase = true)) return FolderKind.OtherHost(spec.host)
    val rest = specText.substringAfter('}')
    val bracket = rest.indexOf("[]")
    val prefix = if (bracket < 0) "" else rest.substring(0, bracket)
    val name = if (prefix.isNotEmpty() && !plainName.startsWith(prefix)) prefix + plainName else plainName
    return FolderKind.Mailbox(name)
}

private fun parseStartupRule(value: String): StartRule? = when (value.lowercase(Locale.ROOT)) {
    "first-unseen" -> StartRule.FirstUnseen
    "first-recent" -> StartRule.FirstRecent
    "first-important" -> StartRule.FirstImportant
    "first-important-or-unseen" -> StartRule.FirstImportantOrUnseen
    "first-important-or-recent" -> StartRule.FirstImportantOrRecent
    "first" -> StartRule.First
    "last" -> StartRule.Last
    else -> null
}

private fun parseSort(value: String): FolderView? {
    val lower = value.lowercase(Locale.ROOT)
    val newest = lower.endsWith("/reverse")
    val keyText = if (newest) value.substring(0, value.length - "/reverse".length) else value
    val key = when (keyText.lowercase(Locale.ROOT)) {
        "arrival" -> SortKey.Arrival
        "date" -> SortKey.Date
        "from" -> SortKey.From
        "subject" -> SortKey.Subject
        "to" -> SortKey.To
        "cc" -> SortKey.Cc
        "size" -> SortKey.Size
        "thread" -> SortKey.ThreadReferences
        "orderedsubj" -> SortKey.ThreadOrderedSubject
        else -> return null
    }
    return FolderView(key, newestFirst = newest)
}

private fun diffRows(
    current: AccountSettings,
    next: AccountSettings,
    phrases: PinercPhrases,
): List<String> {
    val rows = mutableListOf<String>()
    fun add(label: String, old: Any?, new: Any?) {
        if (old != new) rows.add(phrases.change.format(label, old, new))
    }
    add(phrases.imapHost, current.imapHost, next.imapHost)
    add(phrases.imapPort, current.imapPort, next.imapPort)
    add(phrases.smtpHost, current.smtpHost, next.smtpHost)
    add(phrases.smtpPort, current.smtpPort, next.smtpPort)
    add(phrases.username, current.username, next.username)
    add(phrases.displayName, current.displayName, next.displayName)
    add(
        phrases.altAddresses,
        current.altAddresses.joinToString(", "),
        next.altAddresses.joinToString(", "),
    )
    add(phrases.email, current.email, next.email)
    add(phrases.sentMailbox, current.sentMailbox, next.sentMailbox)
    add(phrases.postponedMailbox, current.postponedMailbox, next.postponedMailbox)
    add(phrases.addressBookMailbox, current.addressBookMailbox, next.addressBookMailbox)
    add(phrases.historyLabel, current.addressBookHistory, next.addressBookHistory)
    add(
        phrases.defaultView,
        sortKeyLabel(current.defaultView.key),
        sortKeyLabel(next.defaultView.key),
    )
    add(phrases.newest, current.defaultView.newestFirst, next.defaultView.newestFirst)
    add(phrases.askExpunge, current.askBeforeExpunge, next.askBeforeExpunge)
    add(phrases.inboxOpens, startRuleLabel(current.inboxStart), startRuleLabel(next.inboxStart))
    return rows
}
