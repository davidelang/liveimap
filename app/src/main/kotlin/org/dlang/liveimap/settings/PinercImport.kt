package org.dlang.liveimap.settings

import java.util.Locale
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.ui.toolbar.defaultComposeBar
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultSelectionBar

data class PinercPreview(
    val next: AccountSettings,
    val rows: List<String>,
    val skipped: List<String>,
    val omittedCount: Int,
    val offerAutoExpunge: Boolean,
)

data class PinercPhrases(
    val tls: String,
    val tlsMode: String,
    val tlsNone: String,
    val tlsStart: String,
    val tlsImplicit: String,
    val smtpUsername: String,
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
    val missingFolder: String,
    val folderCheck: String,
    val favorite: String,
    val signatureFile: String = "signature-file is not read; paste the text into the account's signature",
    val signatureLabel: String = "Signature",
    val quotePrefixLabel: String = "Reply quote prefix",
    val leadInLabel: String = "Reply lead-in",
    val sigDashesLabel: String = "Signature dashes",
    val sigBottomLabel: String = "Signature at bottom",
    val dsnLabel: String = "Delivery status notifications",
    val composerHeadersLabel: String = "Composer headers",
    val viewerHeadersLabel: String = "Viewer headers",
    val rolesLabel: String = "Sending rule from role",
    val roleSkipped: String = "Role %1\$s not imported (unsupported pattern)",
    val roleNoConditions: String = "Role %1\$s not imported: it has no conditions, so it would apply to every message",
)

fun parsePinerc(text: String): Map<String, String?> =
    parseEntries(text).mapValues { it.value.decoded }

fun pinercPreview(text: String, current: AccountSettings, phrases: PinercPhrases): PinercPreview {
    val entries = parseEntries(text)
    var next = current
    val skipped = mutableListOf<String>()
    val addedFavorites = mutableListOf<FolderFavorite>()
    var omitted = 0
    var inboxUserApplied = false
    var inboxVote: TlsMode? = null
    var smtpVote: TlsMode? = null

    val inbox = entries["inbox-path"]?.decoded
    if (inbox != null) {
        if (!inbox.startsWith("{")) {
            skipped.add(phrases.inboxBraces)
        } else {
            val spec = parseRemoteSpec(inbox)
            if (spec != null) {
                val port = spec.port ?: if (spec.mode == TlsMode.Implicit) 993 else 143
                next = next.copy(imapHost = spec.host, imapPort = port)
                if (spec.user != null) {
                    next = next.copy(username = spec.user)
                    inboxUserApplied = true
                }
                inboxVote = spec.mode
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
                val port = spec.port ?: when {
                    spec.mode == TlsMode.Implicit -> 465
                    spec.submit -> 587
                    else -> 25
                }
                next = next.copy(smtpHost = spec.host, smtpPort = port)
                smtpVote = spec.mode ?: if (spec.submit) TlsMode.StartTls else null
                val imapUser = if (inboxUserApplied) next.username else current.username
                if (spec.user != null && spec.user != imapUser) {
                    next = next.copy(smtpUsername = spec.user)
                }
            }
        }
    }
    val votes = listOfNotNull(inboxVote, smtpVote)
    if (votes.size == 2 && votes[0] != votes[1]) {
        skipped.add(phrases.tls)
    } else if (votes.isNotEmpty()) {
        next = next.copy(tlsMode = votes[0])
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

    val incoming = entries["incoming-folders"]
    if (incoming != null && incoming.raw.isNotEmpty()) {
        val built = next.favorites.toMutableList()
        for (piece in splitOutsideQuotes(incoming.raw)) {
            val (label, specText) = nicknameSplit(piece)
            val spec = if (specText.startsWith("=")) specText.substring(1) else specText
            when (val folder = classifyFolder(spec, allowPlain = true, comparisonHost, collectionRaw)) {
                FolderKind.Local -> skipped.add(phrases.local)
                is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
                FolderKind.Empty -> Unit
                is FolderKind.Mailbox -> {
                    if (folder.name.equals("INBOX", ignoreCase = true)) {
                        Unit
                    } else if (built.any { sameLeaf(it, folder.name) }) {
                        Unit
                    } else {
                        val favorite = FolderFavorite(
                            node = false,
                            mailbox = folder.name,
                            delimiter = '.',
                            label = label,
                        )
                        built.add(favorite)
                        addedFavorites.add(favorite)
                    }
                }
            }
        }
        next = next.copy(favorites = built)
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
                "enable-sigdashes" -> next = next.copy(sigDashes = true)
                "no-enable-sigdashes" -> next = next.copy(sigDashes = false)
                "signature-at-bottom" -> next = next.copy(sigAtBottom = true)
                "no-signature-at-bottom" -> next = next.copy(sigAtBottom = false)
                "enable-delivery-status-notification" ->
                    next = next.copy(dsnMode = org.dlang.liveimap.ui.compose.DsnMode.FailureDelay)
                "no-enable-delivery-status-notification" ->
                    next = next.copy(dsnMode = org.dlang.liveimap.ui.compose.DsnMode.Off)
                else -> omitted += 1
            }
        }
        if (ask != null) next = next.copy(askBeforeExpunge = ask)
        offerAutoExpunge = ask == false
    }

    entries["literal-signature"]?.decoded?.let { next = next.copy(signature = pineEscapes(it)) }
    entries["reply-indent-string"]?.decoded?.let { if (it.isNotEmpty()) next = next.copy(quotePrefix = it) }
    entries["reply-leadin"]?.decoded?.let { if (it.isNotEmpty()) next = next.copy(replyLeadIn = pineLeadIn(it)) }
    entries["customized-hdrs"]?.let { v ->
        if (v.raw.isNotEmpty()) next = next.copy(composerHeaders = headerList(splitList(v.raw)))
    }
    entries["default-composer-hdrs"]?.let { v ->
        if (v.raw.isNotEmpty() && entries["customized-hdrs"] == null) next = next.copy(composerHeaders = headerList(splitList(v.raw)))
    }
    entries["viewer-hdrs"]?.let { v ->
        if (v.raw.isNotEmpty()) next = next.copy(viewerHeaders = headerList(splitList(v.raw)))
    }
    entries["patterns-roles"]?.let { v ->
        if (v.raw.isNotEmpty()) {
            val imported = ArrayList<org.dlang.liveimap.ui.compose.SendingRule>()
            for (item in splitOutsideQuotes(v.raw)) {
                if (item.isBlank()) continue
                val rule = pineRole(item)
                when {
                    rule == null -> skipped.add(phrases.roleSkipped.format(item.take(40)))
                    // F106: an empty pattern matches every compose, so it would silently set From/Reply-To/Fcc
                    rule.conditions.isEmpty -> skipped.add(phrases.roleNoConditions.format(rule.name))
                    else -> imported.add(rule)
                }
            }
            if (imported.isNotEmpty()) {
                val existing = org.dlang.liveimap.ui.compose.decodeRules(next.sendingRules)
                val merged = existing + imported.filter { r -> existing.none { it.name == r.name } }
                next = next.copy(sendingRules = org.dlang.liveimap.ui.compose.encodeRules(merged))
            }
        }
    }

    for (name in entries.keys) {
        if ("pass" in name) {
            skipped.add(phrases.passwords)
            continue
        }
        when (name) {
            "stay-open-folders", "folder-collections" ->
                skipped.add(phrases.folders)
            "signature-file" -> skipped.add(phrases.signatureFile)
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
    for (favorite in addedFavorites) rows.add(favoriteRow(favorite, phrases))
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

fun encodePinerc(settings: AccountSettings): String {
    val lines = ArrayList<String>()
    if (settings.inboxStart == StartRule.Newest) lines.add("# Newest")
    if (settings.autoExpunge) lines.add("# auto-expunge")
    if (settings.addressBookNeverTrim) lines.add("# Never trim")
    if (settings.bodyView != BodyView.PlainOrError && settings.bodyView != BodyView.PlainOrHtml) {
        lines.add("# body view: ${bodyViewLabel(settings.bodyView)}")
    }
    if (settings.dateFormat != DateFormat.Local) {
        lines.add("# date format: ${dateFormatLabel(settings.dateFormat)}")
    }
    if (settings.readerBar != defaultReaderBar ||
        settings.indexBar != defaultIndexBar() ||
        settings.selectionBar != defaultSelectionBar() ||
        settings.folderBar != defaultFolderBar() ||
        settings.readerToolbar != null ||
        settings.composeBar != defaultComposeBar() ||
        settings.toolbarRows != 2
    ) {
        lines.add("# toolbar")
    }
    if ((settings.folderPane != LayoutChoice.Off && settings.folderPane != LayoutChoice.Auto) ||
        settings.foldPosture != LayoutChoice.Off ||
        settings.fullScreen
    ) {
        lines.add("# layout")
    }
    if (settings.multiPane != MultiPane.Off && settings.multiPane != MultiPane.Auto) lines.add("# multi-pane")
    if (settings.emailSubmission) lines.add("# JMAP")
    if (settings.sieveHost.isNotEmpty() ||
        settings.sievePort != 4190 ||
        settings.inboundRules.isNotEmpty()
    ) {
        lines.add("# Sieve")
    }
    if (settings.watchedFolders.isNotEmpty() || settings.extraIdleBudget != 2) {
        lines.add("# watched folders")
    }
    if (settings.savedSearches.isNotEmpty() || settings.savedAdvanced.isNotEmpty()) {
        lines.add("# saved searches")
    }
    lines.add(
        "# The importer does not read back default-saved-msg-folder, saved-msg-name-rule, " +
            "threading-index-style, composer-wrap-column, or feature-list tokens other than " +
            "the expunge confirm tokens.",
    )

    fun add(name: String, value: String) {
        if (value.isEmpty()) return
        lines.add("$name=$value")
    }

    val userId = if ('@' in settings.email) settings.email.substringBefore('@') else settings.username
    val domain = if ('@' in settings.email) settings.email.substringAfter('@') else ""
    add("user-id", pinercQuoted(userId))
    add("user-domain", pinercQuoted(domain))
    add("personal-name", pinercQuoted(settings.displayName))
    add("inbox-path", pinercQuoted(pinercInbox(settings)))
    add("smtp-server", pinercQuoted(pinercSmtp(settings)))
    add("alt-addresses", settings.altAddresses.joinToString(", ") { pinercQuoted(it) })
    add("default-fcc", pinercQuoted(settings.sentMailbox))
    add("postponed-folder", pinercQuoted(settings.postponedMailbox))
    if (settings.addressBookMailbox.isNotEmpty()) {
        add("address-book", pinercQuoted("{${settings.imapHost}}${settings.addressBookMailbox}"))
    }
    add("incoming-folders", pinercIncoming(settings))
    if (!settings.addressBookNeverTrim) {
        add("remote-abook-history", settings.addressBookHistory.toString())
    }
    lines.add("sort-key=${pinercSort(settings.defaultView)}")
    val startup = pinercStartup(settings.inboxStart)
    if (startup != null) lines.add("incoming-startup-rule=$startup")
    add("default-saved-msg-folder", pinercQuoted(settings.savedMailbox))
    lines.add("saved-msg-name-rule=${pinercSaveRule(settings.saveNameRule)}")
    lines.add("threading-index-style=${pinercThreadStyle(settings.threadIndexStyle)}")
    lines.add("composer-wrap-column=${settings.composerWrapColumn}")
    lines.add("feature-list=${pinercFeatures(settings)}")
    return lines.joinToString("\n") + "\n"
}

suspend fun pinercMailboxListed(session: MailSession, name: String): Boolean {
    if (name.isEmpty() || '%' in name || '*' in name) return false
    return session.mailboxListed(name)
}

fun withoutMissingMailboxes(
    preview: PinercPreview,
    current: AccountSettings,
    missing: Set<String>,
    phrases: PinercPhrases,
): PinercPreview {
    val drops = mutableListOf<String>()
    val notes = mutableListOf<String>()
    var next = preview.next
    fun consider(phrase: String, now: String, was: String, write: (String) -> Unit) {
        if (now == was || now !in missing) return
        drops.add("$phrase:")
        notes.add(phrases.missingFolder.format(now))
        write(was)
    }
    consider(phrases.sentMailbox, next.sentMailbox, current.sentMailbox) {
        next = next.copy(sentMailbox = it)
    }
    consider(phrases.postponedMailbox, next.postponedMailbox, current.postponedMailbox) {
        next = next.copy(postponedMailbox = it)
    }
    consider(phrases.addressBookMailbox, next.addressBookMailbox, current.addressBookMailbox) {
        next = next.copy(addressBookMailbox = it)
    }
    if (drops.isEmpty()) return preview
    val rows = preview.rows.filter { row -> drops.none { prefix -> row.startsWith(prefix) } }
    return preview.copy(next = next, rows = rows, skipped = preview.skipped + notes)
}

fun withoutCheckedMailboxes(
    preview: PinercPreview,
    current: AccountSettings,
    phrases: PinercPhrases,
): PinercPreview {
    val drops = mutableListOf<String>()
    var next = preview.next
    fun consider(phrase: String, now: String, was: String, write: (String) -> Unit) {
        if (now.isEmpty() || now == was) return
        drops.add("$phrase:")
        write(was)
    }
    consider(phrases.sentMailbox, next.sentMailbox, current.sentMailbox) {
        next = next.copy(sentMailbox = it)
    }
    consider(phrases.postponedMailbox, next.postponedMailbox, current.postponedMailbox) {
        next = next.copy(postponedMailbox = it)
    }
    consider(phrases.addressBookMailbox, next.addressBookMailbox, current.addressBookMailbox) {
        next = next.copy(addressBookMailbox = it)
    }
    if (drops.isEmpty()) return preview
    val rows = preview.rows.filter { row -> drops.none { prefix -> row.startsWith(prefix) } }
    return preview.copy(next = next, rows = rows, skipped = preview.skipped + phrases.folderCheck)
}

fun favoriteDelimiter(namespaces: List<Namespace>): Char {
    val personal = namespaces.firstOrNull { it.kind == NamespaceKind.Personal } ?: return '.'
    if (personal.delimiter == '\u0000') return '.'
    return personal.delimiter
}

fun withFavoriteDelimiter(
    preview: PinercPreview,
    current: AccountSettings,
    delimiter: Char,
): PinercPreview {
    val fresh = newLeafMailboxes(preview.next.favorites, current.favorites).toSet()
    if (fresh.isEmpty()) return preview
    var changed = false
    val favorites = preview.next.favorites.map { favorite ->
        if (!favorite.node && favorite.mailbox in fresh && favorite.delimiter != delimiter) {
            changed = true
            favorite.copy(delimiter = delimiter)
        } else {
            favorite
        }
    }
    if (!changed) return preview
    return preview.copy(next = preview.next.copy(favorites = favorites))
}

fun withoutMissingFavorites(
    preview: PinercPreview,
    current: AccountSettings,
    missing: Set<String>,
    phrases: PinercPhrases,
): PinercPreview {
    val fresh = newLeafMailboxes(preview.next.favorites, current.favorites).toSet()
    if (fresh.isEmpty() || missing.isEmpty()) return preview
    val dropped = preview.next.favorites.filter { favorite ->
        !favorite.node && favorite.mailbox in fresh && favorite.mailbox in missing
    }
    if (dropped.isEmpty()) return preview
    val dropMailboxes = dropped.map { it.mailbox }.toSet()
    val favorites = preview.next.favorites.filter { favorite ->
        favorite.node || favorite.mailbox !in fresh || favorite.mailbox !in dropMailboxes
    }
    val notes = dropped.map { phrases.missingFolder.format(it.mailbox) }
    return preview.copy(
        next = preview.next.copy(favorites = favorites),
        rows = removeExactRows(preview.rows, dropped.map { favoriteRow(it, phrases) }),
        skipped = preview.skipped + notes,
    )
}

fun withoutCheckedFavorites(
    preview: PinercPreview,
    current: AccountSettings,
    phrases: PinercPhrases,
): PinercPreview {
    val fresh = newLeafMailboxes(preview.next.favorites, current.favorites)
    if (fresh.isEmpty()) return preview
    val freshSet = fresh.toSet()
    val dropped = preview.next.favorites.filter { !it.node && it.mailbox in freshSet }
    val favorites = preview.next.favorites.filter { it.node || it.mailbox !in freshSet }
    val skipped = if (phrases.folderCheck in preview.skipped) {
        preview.skipped
    } else {
        preview.skipped + phrases.folderCheck
    }
    return preview.copy(
        next = preview.next.copy(favorites = favorites),
        rows = removeExactRows(preview.rows, dropped.map { favoriteRow(it, phrases) }),
        skipped = skipped,
    )
}

internal fun newLeafMailboxes(
    favorites: List<FolderFavorite>,
    current: List<FolderFavorite>,
): List<String> {
    val names = ArrayList<String>()
    for (favorite in favorites) {
        if (favorite.node) continue
        if (current.any { sameLeaf(it, favorite.mailbox) }) continue
        names.add(favorite.mailbox)
    }
    return names
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
    "incoming-folders",
    "remote-abook-history",
    "sort-key",
    "feature-list",
    "incoming-startup-rule",
    "literal-signature",
    "reply-indent-string",
    "reply-leadin",
    "customized-hdrs",
    "default-composer-hdrs",
    "viewer-hdrs",
    "patterns-roles",
)

/** Pine's literal-signature escapes: \n newline, \t tab, \\ backslash. */
internal fun pineEscapes(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            when (text[i + 1]) {
                'n' -> { append('\n'); i += 2; continue }
                't' -> { append('\t'); i += 2; continue }
                '\\' -> { append('\\'); i += 2; continue }
            }
        }
        append(c)
        i++
    }
}

/** Pine lead-in tokens (_FROM_, _DATE_, _SUBJECT_, _PERSONAL_ ...) to ours. */
internal fun pineLeadIn(text: String): String = text
    .replace("_FROM_", "{from}").replace("_FROMADDRESS_", "{from}").replace("_PERSONAL_", "{name}")
    .replace("_DATE_", "{date}").replace("_LONGDATE_", "{date}").replace("_SUBJECT_", "{subject}")

/** "Reply-To: x" (customized-hdrs may carry defaults) to the name list; header names only. */
private fun headerList(items: List<String>): String =
    items.map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", ")

/**
 * One patterns-roles entry: pattern="/NICK=n/TO=a,b/FROM=x/FLDTYPE=SPEC/FOLDER=f" action="/ROLE=1/FROM=me/REPLYTO=r/FCC=f/LSIG=s".
 * Only roles whose conditions we can express come back; anything else (scores, headers, ages) is refused.
 */
internal fun pineRole(entry: String): org.dlang.liveimap.ui.compose.SendingRule? {
    fun section(key: String): Map<String, String>? {
        val at = entry.indexOf("$key=\"")
        if (at < 0) return null
        val start = at + key.length + 2
        val sb = StringBuilder()
        var i = start
        while (i < entry.length && entry[i] != '"') {
            if (entry[i] == '\\' && i + 1 < entry.length) { sb.append(entry[i + 1]); i += 2; continue }
            sb.append(entry[i]); i++
        }
        val out = LinkedHashMap<String, String>()
        for (part in Regex("/(?=[A-Z]+=)").split(sb.toString())) {
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return out
    }
    val pattern = section("pattern") ?: return null
    val action = section("action") ?: return null
    if (action["ROLE"] != "1") return null
    val name = pattern["NICK"]?.takeIf { it.isNotBlank() } ?: return null
    val known = setOf("NICK", "COMM", "TO", "CC", "RECIP", "FROM", "FLDTYPE", "FOLDER", "STATS")
    if (pattern.keys.any { it !in known }) return null
    fun list(v: String?) = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val folder = if (pattern["FLDTYPE"] == "SPEC") list(pattern["FOLDER"]).firstOrNull().orEmpty() else ""
    val from = action["FROM"].orEmpty()
    val fromAddress = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim().ifEmpty { null }
    val fromName = if ('<' in from) from.substringBefore('<').trim().trim('"').ifEmpty { null } else null
    return org.dlang.liveimap.ui.compose.SendingRule(
        name = name,
        conditions = org.dlang.liveimap.ui.compose.RuleConditions(
            sentTo = list(pattern["TO"]) + list(pattern["CC"]) + list(pattern["RECIP"]),
            originalFrom = list(pattern["FROM"]),
            folder = folder,
        ),
        actions = org.dlang.liveimap.ui.compose.RuleActions(
            fromName = fromName,
            fromAddress = fromAddress,
            replyTo = action["REPLYTO"]?.ifBlank { null },
            fcc = action["FCC"]?.ifBlank { null },
            signature = action["LSIG"]?.let(::pineEscapes),
            role = name,
        ),
    )
}

private val variableName = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

private data class PinercValue(val decoded: String?, val raw: String)

private data class RemoteSpec(
    val host: String,
    val port: Int?,
    val user: String?,
    val mode: TlsMode?,
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

private fun favoriteRow(favorite: FolderFavorite, phrases: PinercPhrases): String =
    if (favorite.label.isEmpty()) {
        "${phrases.favorite}: ${favorite.mailbox}"
    } else {
        "${phrases.favorite}: ${favorite.mailbox} (${favorite.label})"
    }

private fun removeExactRows(rows: List<String>, drop: List<String>): List<String> {
    if (drop.isEmpty()) return rows
    val left = drop.toMutableList()
    val kept = ArrayList<String>(rows.size)
    for (row in rows) {
        val index = left.indexOf(row)
        if (index >= 0) left.removeAt(index) else kept.add(row)
    }
    return kept
}

private fun sameLeaf(favorite: FolderFavorite, mailbox: String): Boolean {
    if (favorite.node) return false
    return if (mailbox.equals("INBOX", ignoreCase = true)) {
        favorite.mailbox.equals("INBOX", ignoreCase = true)
    } else {
        favorite.mailbox == mailbox
    }
}

private fun isNicknameSpace(c: Char): Boolean = when (c) {
    ' ', '\t', '\r', '\n', '\u000B', '\u000C' -> true
    else -> false
}

private fun splitOutsideQuotes(value: String): List<String> {
    val items = mutableListOf<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length && value[i + 1] == '"') {
            sb.append(c)
            sb.append('"')
            i += 2
            continue
        }
        if (c == '"') {
            quoted = !quoted
            sb.append(c)
            i++
            continue
        }
        if (c == ',' && !quoted) {
            val piece = sb.toString().trim()
            if (piece.isNotEmpty()) items.add(piece)
            sb.clear()
            i++
            continue
        }
        sb.append(c)
        i++
    }
    val piece = sb.toString().trim()
    if (piece.isNotEmpty()) items.add(piece)
    return items
}

private fun nicknameSplit(piece: String): Pair<String, String> {
    var quoted = false
    var last = -1
    var i = 0
    while (i < piece.length) {
        val c = piece[i]
        if (c == '\\' && i + 1 < piece.length && piece[i + 1] == '"') {
            i += 2
            continue
        }
        if (c == '"') {
            quoted = !quoted
            i++
            continue
        }
        if (!quoted && isNicknameSpace(c)) last = i
        i++
    }
    if (last < 0) return "" to unquoteWrapped(piece.trim())
    var start = last
    while (start > 0 && isNicknameSpace(piece[start - 1])) start--
    var end = last + 1
    while (end < piece.length && isNicknameSpace(piece[end])) end++
    val label = unquoteWrapped(piece.substring(0, start).trim())
    val spec = unquoteWrapped(piece.substring(end).trim())
    return label to spec
}

private fun unquoteWrapped(side: String): String {
    if (side.length < 2 || side.first() != '"' || side.last() != '"') return side
    return unescapeLoose(side.substring(1, side.length - 1))
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

/** F57: index of the `}` closing a `{host/...}` spec, skipping quoted flag values. */
internal fun specEnd(value: String): Int {
    var quoted = false
    var i = 1
    while (i < value.length) {
        val c = value[i]
        when {
            quoted && c == '\\' -> i++
            c == '"' -> quoted = !quoted
            !quoted && c == '}' -> return i
        }
        i++
    }
    return -1
}

internal fun splitSpecFlags(text: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quoted && c == '\\' && i + 1 < text.length) {
            sb.append(c).append(text[i + 1]); i += 2; continue
        }
        if (c == '"') quoted = !quoted
        if (!quoted && c == '/') { out.add(sb.toString()); sb.clear() } else sb.append(c)
        i++
    }
    out.add(sb.toString())
    return out
}

internal fun unquoteSpecValue(v: String): String {
    if (v.length < 2 || !v.startsWith("\"") || !v.endsWith("\"")) return v
    val sb = StringBuilder()
    var i = 1
    while (i < v.length - 1) {
        if (v[i] == '\\' && i + 1 < v.length - 1) i++
        sb.append(v[i]); i++
    }
    return sb.toString()
}

/** c-client quoting for a /user= value that contains spec syntax. */
internal fun quoteSpecValue(v: String): String =
    if (v.none { it == '/' || it == '}' || it == '{' || it == '"' || it == '\\' || it.isWhitespace() }) v
    else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun parseRemoteSpec(value: String): RemoteSpec? {
    val body = if (value.startsWith("{")) {
        val end = specEnd(value)
        if (end < 1) return null
        value.substring(1, end)
    } else {
        value
    }
    if (body.isEmpty()) return null
    val slash = body.indexOf('/')
    val hostPort = if (slash < 0) body else body.substring(0, slash)
    val flags = if (slash < 0) emptyList() else splitSpecFlags(body.substring(slash + 1))
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
    var mode: TlsMode? = null
    var submit = false
    var user: String? = null
    for (flag in flags) {
        if (flag.isEmpty()) continue
        when {
            flag.equals("submit", ignoreCase = true) -> submit = true
            flag.startsWith("user=", ignoreCase = true) -> user = unquoteSpecValue(flag.substring(5))
            mode != null -> Unit
            flag.equals("notls", ignoreCase = true) ||
                flag.equals("nostarttls", ignoreCase = true) -> mode = TlsMode.None
            flag.equals("ssl", ignoreCase = true) -> mode = TlsMode.Implicit
            flag.equals("tls", ignoreCase = true) ||
                flag.equals("starttls", ignoreCase = true) -> mode = TlsMode.StartTls
        }
    }
    return RemoteSpec(host, port, user, mode, submit)
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

private fun pinercQuoted(value: String): String {
    val needs = value.startsWith(" ") ||
        value.endsWith(" ") ||
        ' ' in value ||
        '"' in value ||
        '\\' in value
    if (!needs) return value
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun pinercTls(mode: TlsMode): String = when (mode) {
    TlsMode.Implicit -> "ssl"
    TlsMode.StartTls -> "tls"
    TlsMode.None -> "notls"
}

private fun pinercInbox(settings: AccountSettings): String {
    if (settings.imapHost.isEmpty()) return ""
    val user = if (settings.username.isEmpty()) "" else "/user=${quoteSpecValue(settings.username)}"
    return "{${settings.imapHost}:${settings.imapPort}/${pinercTls(settings.tlsMode)}$user}INBOX"
}

private fun pinercSmtp(settings: AccountSettings): String {
    if (settings.smtpHost.isEmpty()) return ""
    val submit = if (settings.smtpPort == 587 && settings.tlsMode == TlsMode.StartTls) "/submit" else ""
    val user = if (settings.smtpUsername.isNotEmpty() && settings.smtpUsername != settings.username) {
        "/user=${quoteSpecValue(settings.smtpUsername)}"
    } else {
        ""
    }
    return "{${settings.smtpHost}:${settings.smtpPort}/${pinercTls(settings.tlsMode)}$submit$user}"
}

private fun pinercIncoming(settings: AccountSettings): String {
    val pieces = ArrayList<String>()
    for (favorite in settings.favorites) {
        if (favorite.node) continue
        if (favorite.mailbox.equals("INBOX", ignoreCase = true)) continue
        val mailbox = pinercQuoted(favorite.mailbox)
        val piece = if (favorite.label.isEmpty()) mailbox else pinercQuoted(favorite.label) + " " + mailbox
        if (piece.isNotEmpty()) pieces.add(piece)
    }
    return pieces.joinToString(", ")
}

private fun pinercSort(view: FolderView): String {
    val key = when (view.key) {
        SortKey.Arrival -> "arrival"
        SortKey.Date -> "date"
        SortKey.From -> "from"
        SortKey.Subject -> "subject"
        SortKey.To -> "to"
        SortKey.Cc -> "cc"
        SortKey.Size -> "size"
        SortKey.ThreadReferences -> "thread"
        SortKey.ThreadOrderedSubject -> "orderedsubj"
    }
    return if (view.newestFirst) "$key/reverse" else key
}

private fun pinercStartup(rule: StartRule): String? = when (rule) {
    StartRule.FirstUnseen -> "first-unseen"
    StartRule.FirstRecent -> "first-recent"
    StartRule.FirstImportant -> "first-important"
    StartRule.FirstImportantOrUnseen -> "first-important-or-unseen"
    StartRule.FirstImportantOrRecent -> "first-important-or-recent"
    StartRule.First -> "first"
    StartRule.Last -> "last"
    StartRule.Newest -> null
}

private fun pinercSaveRule(rule: SaveNameRule): String = when (rule) {
    SaveNameRule.DefaultFolder -> "default-folder"
    SaveNameRule.ByFrom -> "by-from"
    SaveNameRule.BySender -> "by-sender"
    SaveNameRule.ByRecipient -> "by-recipient"
    SaveNameRule.LastFolderUsed -> "last-folder-used"
}

private fun pinercThreadStyle(style: ThreadIndexStyle): String = when (style) {
    ThreadIndexStyle.Expanded -> "exp"
    ThreadIndexStyle.Collapsed -> "coll"
}

private fun pinercFeatures(settings: AccountSettings): String {
    val tokens = ArrayList<String>(6)
    tokens.add(if (settings.askBeforeExpunge) "no-expunge-without-confirm" else "expunge-without-confirm")
    if (settings.quellFlowed) tokens.add("quell-flowed-text")
    if (settings.forwardAsAttachment) tokens.add("forward-as-attachment")
    if (settings.bodyView == BodyView.PlainOrError) tokens.add("prefer-plain-text")
    if (settings.bodyView == BodyView.PlainOrHtml) tokens.add("render-html-internally")
    if (settings.dateFormat == DateFormat.Local) tokens.add("convert-dates-to-localtime")
    return tokens.joinToString(",")
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
    fun tlsLabel(mode: TlsMode): String = when (mode) {
        TlsMode.None -> phrases.tlsNone
        TlsMode.StartTls -> phrases.tlsStart
        TlsMode.Implicit -> phrases.tlsImplicit
    }
    add(phrases.imapHost, current.imapHost, next.imapHost)
    add(phrases.imapPort, current.imapPort, next.imapPort)
    add(phrases.smtpHost, current.smtpHost, next.smtpHost)
    add(phrases.smtpPort, current.smtpPort, next.smtpPort)
    add(phrases.tlsMode, tlsLabel(current.tlsMode), tlsLabel(next.tlsMode))
    add(phrases.username, current.username, next.username)
    add(phrases.smtpUsername, current.smtpUsername, next.smtpUsername)
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
    add(phrases.signatureLabel, current.signature, next.signature)
    add(phrases.quotePrefixLabel, current.quotePrefix, next.quotePrefix)
    add(phrases.leadInLabel, current.replyLeadIn, next.replyLeadIn)
    add(phrases.sigDashesLabel, current.sigDashes, next.sigDashes)
    add(phrases.sigBottomLabel, current.sigAtBottom, next.sigAtBottom)
    add(phrases.dsnLabel, current.dsnMode, next.dsnMode)
    add(phrases.composerHeadersLabel, current.composerHeaders, next.composerHeaders)
    add(phrases.viewerHeadersLabel, current.viewerHeaders, next.viewerHeaders)
    val before = org.dlang.liveimap.ui.compose.decodeRules(current.sendingRules).map { it.name }
    for (rule in org.dlang.liveimap.ui.compose.decodeRules(next.sendingRules)) {
        if (rule.name !in before) rows.add("${phrases.rolesLabel}: ${roleSummary(rule)}")
    }
    return rows
}

/** F106: what an imported role does and when, for the import preview (not just its name). */
internal fun roleSummary(rule: org.dlang.liveimap.ui.compose.SendingRule): String {
    val c = rule.conditions
    val a = rule.actions
    val whenParts = buildList {
        if (c.sentTo.isNotEmpty()) add("sent to ${c.sentTo.joinToString(", ")}")
        if (c.originalFrom.isNotEmpty()) add("from ${c.originalFrom.joinToString(", ")}")
        if (c.recipient.isNotEmpty()) add("to ${c.recipient.joinToString(", ")}")
        if (c.folder.isNotEmpty()) add("in ${c.folder}")
        if (c.listId.isNotEmpty()) add("list ${c.listId}")
    }
    val sets = buildList {
        val from = listOfNotNull(a.fromName, a.fromAddress?.let { "<$it>" }).joinToString(" ")
        if (from.isNotEmpty()) add("From $from")
        a.replyTo?.let { add("Reply-To $it") }
        a.fcc?.let { add("Fcc $it") }
        a.signature?.let { add(if (it.isEmpty()) "no signature" else "signature") }
    }
    return "${rule.name} (when ${whenParts.joinToString("; ").ifEmpty { "always" }}; sets ${sets.joinToString(", ").ifEmpty { "nothing" }})"
}
