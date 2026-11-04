package org.dlang.liveimap.settings

import java.util.Locale
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.ui.toolbar.defaultComposeBar
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultReaderToolbar
import org.dlang.liveimap.ui.toolbar.defaultSelectionBar

data class PinercPreview(
    val next: Settings,
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
    val extraAddressBooks: String = "Extra address books",
    val globalAddressBooks: String = "Global address books",
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

fun pinercPreview(text: String, current: Settings, phrases: PinercPhrases): PinercPreview =
    PinercRun(text, current, phrases).run()

private class PinercRun(
    val text: String,
    val current: Settings,
    val phrases: PinercPhrases,
) {
    val entries = parseEntries(text)
    var next = current
    val skipped = mutableListOf<String>()
    val addedFavorites = mutableListOf<FolderFavorite>()
    var omitted = 0
    var inboxUserApplied = false
    var inboxVote: TlsMode? = null
    var comparisonHost = ""
    var collectionRaw: String? = null
    var appliedAlpineDefault = false
    var offerAutoExpunge = false

    fun run(): PinercPreview {
        applyInbox()
        applySmtp()
        applyIdentity()
        val done = HashSet<String>()
        for (leaf in pinercLeaves()) {
            when (leaf.pinercFormat) {
                "remote", "novalidate", "smtp", "email" -> Unit
                "text" -> if (leaf.pinerc != "user-id") applyText(leaf)
                "list" -> applyList(leaf)
                "signature" -> applySignature(leaf)
                "leadin" -> applyLeadin(leaf)
                "headers" -> applyHeaders(leaf)
                "int" -> applyInt(leaf)
                "mailbox" -> applyMailbox(leaf)
                "sort" -> applySort(leaf)
                "threadStyle" -> applyThreadStyle(leaf)
                "startup" -> applyStartup(leaf)
                "saveRule" -> applySaveRule(leaf)
                "folder" -> applyFolder(leaf)
                "books", "booksExtra" -> if (done.add("books")) applyBooks()
                "incoming" -> applyIncoming()
                "roles" -> applyRoles(leaf)
                "feature" -> if (done.add("feature")) applyFeatures()
            }
        }
        countOmitted()
        return finish()
    }

    private fun applyInbox() {
        val inbox = entries["inbox-path"]?.decoded
        if (inbox != null) {
            if (!inbox.startsWith("{")) {
                skipped.add(phrases.inboxBraces)
            } else {
                val spec = parseRemoteSpec(inbox)
                if (spec != null) {
                    val port = spec.port ?: if (spec.mode == TlsMode.Implicit) 993 else 143
                    next = next.copy(
                        imapHost = spec.host,
                        imapPort = port,
                        acceptInvalidCert = spec.acceptInvalidCert,
                    )
                    if (spec.user != null) {
                        next = next.copy(username = spec.user)
                        inboxUserApplied = true
                    }
                    inboxVote = spec.mode
                }
            }
        }
        comparisonHost = next.imapHost
        collectionRaw = entries["folder-collections"]?.raw
    }

    private fun applySmtp() {
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
                    val smtpMode = spec.mode ?: if (spec.submit) TlsMode.StartTls else null
                    next = next.copy(
                        smtpHost = spec.host,
                        smtpPort = port,
                        smtpTlsMode = smtpMode ?: next.smtpTlsMode,
                    )
                    val imapUser = if (inboxUserApplied) next.username else current.username
                    if (spec.user != null && spec.user != imapUser) {
                        next = next.copy(smtpUsername = spec.user)
                    }
                }
            }
        }
        val vote = inboxVote
        if (vote != null) next = next.copy(tlsMode = vote)
    }

    private fun applyIdentity() {
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
    }

    private fun applyText(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val decoded = entries[key]?.decoded ?: return
        val defaultText = (leaf.default as? JsonValue.Str)?.value.orEmpty()
        if (decoded.isEmpty() && defaultText.isNotEmpty()) return
        next = next.withSetting(leaf.setting, SettingValue(decoded))
    }

    private fun applyList(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val alt = entries[key] ?: return
        if (alt.raw.isEmpty()) return
        val specs = splitList(alt.raw).map { it.trim() }.filter { it.isNotEmpty() }
        next = next.withSetting(leaf.setting, SettingValue(encodeAltAddresses(specs)))
    }

    private fun applySignature(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val decoded = entries[key]?.decoded ?: return
        next = next.withSetting(leaf.setting, SettingValue(pineEscapes(decoded)))
    }

    private fun applyLeadin(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val decoded = entries[key]?.decoded ?: return
        if (decoded.isEmpty()) return
        next = next.withSetting(leaf.setting, SettingValue(pineLeadIn(decoded)))
    }

    private fun applyHeaders(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val direct = entries[key]
        val raw = when {
            direct != null && direct.raw.isNotEmpty() -> direct.raw
            key == "customized-hdrs" && direct == null -> entries["default-composer-hdrs"]?.raw.orEmpty()
            else -> ""
        }
        if (raw.isEmpty()) return
        next = next.withSetting(leaf.setting, SettingValue(headerList(splitList(raw))))
    }

    private fun applyInt(leaf: TreeLeaf) {
        if (leaf.setting == "addressBookHistory") {
            applyHistory(leaf)
            return
        }
        val key = leaf.pinerc ?: return
        val raw = entries[key]?.decoded ?: return
        val number = raw.trim().toIntOrNull() ?: return
        val lo = (leaf.min ?: Int.MIN_VALUE.toLong()).toInt()
        val hi = (leaf.max ?: Int.MAX_VALUE.toLong()).toInt()
        next = next.withSetting(leaf.setting, SettingValue(number.coerceIn(lo, hi).toString()))
    }

    private fun applyMailbox(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val decoded = entries[key]?.decoded ?: return
        if (decoded.isNotEmpty()) next = next.withSetting(leaf.setting, SettingValue(decoded))
    }

    private fun applyFolder(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val decoded = entries[key]?.decoded ?: return
        when (val folder = classifyFolder(decoded, allowPlain = true, comparisonHost, collectionRaw)) {
            FolderKind.Local -> skipped.add(phrases.local)
            is FolderKind.Mailbox -> next = next.withSetting(leaf.setting, SettingValue(folder.name))
            is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
            FolderKind.Empty -> Unit
        }
    }

    private fun applyThreadStyle(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val raw = entries[key]?.decoded ?: return
        val style = parsePinercThreadStyle(raw) ?: return
        next = next.withSetting(leaf.setting, SettingValue(style.name))
    }

    private fun applySaveRule(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val raw = entries[key]?.decoded ?: return
        val rule = parsePinercSaveRule(raw) ?: return
        next = next.withSetting(leaf.setting, SettingValue(rule.name))
    }

    private fun applyBooks() {
        // The first address-book on this server is the account's book; the others become extra books, and
        // global-address-book entries become read-only books (with the contacts work).
        fun bookMailboxes(key: String): List<String>? {
            val raw = entries[key]?.raw ?: return null
            if (raw.isEmpty()) return null
            val out = ArrayList<String>()
            for (item in splitList(raw)) {
                if (item.isEmpty()) continue
                when (val folder = classifyFolder(item, allowPlain = false, comparisonHost, collectionRaw)) {
                    FolderKind.Local -> skipped.add(phrases.local)
                    is FolderKind.Mailbox -> if (folder.name !in out) out.add(folder.name)
                    is FolderKind.OtherHost -> skipped.add(phrases.otherHost.format(folder.host))
                    FolderKind.Empty -> Unit
                }
            }
            return out
        }
        val books = bookMailboxes("address-book")
        val globals = bookMailboxes("global-address-book")
        if (books != null || globals != null) {
            val items = ArrayList<AddressBookEntry>()
            for (name in books.orEmpty()) {
                items += AddressBookEntry(kind = "pine", mailbox = name, readOnly = false, saveTarget = false)
            }
            for (name in globals.orEmpty()) {
                if (items.none { it.mailbox == name }) {
                    items += AddressBookEntry(kind = "pine", mailbox = name, readOnly = true, saveTarget = false)
                }
            }
            next = next.copy(addressBooks = items)
        }
    }

    private fun applyIncoming() {
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
    }

    private fun applyHistory(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        if (key !in entries) return
        val raw = entries[key]?.decoded?.trim().orEmpty()
        val number = if (raw.isNotEmpty() && raw.all { it.isDigit() }) raw.toIntOrNull() else null
        if (number == null) skipped.add(phrases.history)
        else next = next.withSetting(leaf.setting, SettingValue(number.toString()))
    }

    private fun applySort(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val sort = entries[key]?.decoded ?: return
        val view = parseSort(sort)
        if (view == null) skipped.add(phrases.sort)
        else next = next.withSetting(leaf.setting, SettingValue(encodeView(view)))
    }

    private fun applyStartup(leaf: TreeLeaf) {
        appliedAlpineDefault = false
        val key = leaf.pinerc ?: return
        if (key in entries) {
            val raw = entries[key]?.decoded?.trim().orEmpty()
            val mapped = parseStartupRule(raw)
            if (mapped == null) skipped.add(phrases.rule)
            else next = next.withSetting(leaf.setting, SettingValue(mapped.name))
        } else if (current.pinercStartDefault == PinercStartDefault.AlpineDefault) {
            next = next.withSetting(leaf.setting, SettingValue(StartRule.FirstUnseen.name))
            appliedAlpineDefault = true
        }
    }

    private fun applyFeatures() {
        offerAutoExpunge = false
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
    }

    private fun applyRoles(leaf: TreeLeaf) {
        val key = leaf.pinerc ?: return
        val v = entries[key] ?: return
        if (v.raw.isEmpty()) return
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

    private fun countOmitted() {
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
                else -> if (name !in appliedPinercNames()) omitted += 1
            }
        }
    }

    private fun finish(): PinercPreview {
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
}

fun pinercApplied(preview: PinercPreview, turnOnAutoExpunge: Boolean): Settings =
    if (turnOnAutoExpunge && preview.offerAutoExpunge) {
        preview.next.copy(autoExpunge = true)
    } else {
        preview.next
    }

fun encodePinerc(settings: Settings): String {
    val lines = ArrayList<String>()
    if (settings.inboxStart == StartRule.Newest) lines.add("# Newest")
    if (settings.autoExpunge) lines.add("# auto-expunge")
    if (settings.addressBookNeverTrim) lines.add("# Never trim")
    if (settings.dateFormat != DateFormat.Local) {
        lines.add("# date format: ${dateFormatLabel(settings.dateFormat)}")
    }
    val bars = org.dlang.liveimap.ui.toolbar.ToolbarStore.record
    if ((bars.readerToolbar != null && bars.readerToolbar != defaultReaderToolbar()) ||
        bars.indexBar != defaultIndexBar() ||
        bars.selectionBar != defaultSelectionBar() ||
        bars.folderBar != defaultFolderBar() ||
        bars.composeBar != defaultComposeBar() ||
        bars.indexBar.rows != 2 ||
        bars.selectionBar.rows != 2 ||
        bars.folderBar.rows != 2 ||
        (bars.readerToolbar ?: defaultReaderToolbar()).rows != 2 ||
        bars.composeBar.rows != 2
    ) {
        lines.add("# toolbar")
    }
    if ((settings.folderPlace != FolderPlace.Off && settings.folderPlace != FolderPlace.Auto) ||
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
    fun add(name: String, value: String) {
        if (value.isEmpty()) return
        lines.add("$name=$value")
    }
    val done = HashSet<String>()
    for (leaf in pinercLeaves()) writePinercLeaf(settings, leaf, lines, done, ::add)
    return lines.joinToString("\n") + "\n"
}

private fun writePinercLeaf(
    settings: Settings,
    leaf: TreeLeaf,
    lines: ArrayList<String>,
    done: HashSet<String>,
    add: (String, String) -> Unit,
) {
    val key = leaf.pinerc ?: return
    when (leaf.pinercFormat) {
        "email" -> if (done.add("identity")) writePinercIdentity(settings, add)
        "text" -> if (key == "user-id") {
            if (done.add("identity")) writePinercIdentity(settings, add)
        } else if (key == "sieve-server") {
            if (!settings.sieveSameAsInbound && settings.sieveHost.isNotEmpty() && done.add(key)) {
                add(key, pinercQuoted(settings.sieveHost))
            }
        } else if (done.add(key)) {
            add(key, pinercQuoted(settings.getSetting(leaf.setting)))
        }
        "list" -> if (done.add(key)) {
            add(key, settings.altAddresses.joinToString(", ") { pinercQuoted(it) })
        }
        "remote", "novalidate" -> if (done.add("inbox-path")) add("inbox-path", pinercQuoted(pinercInbox(settings)))
        "smtp" -> if (done.add("smtp-server")) add("smtp-server", pinercQuoted(pinercSmtp(settings)))
        "folder", "mailbox" -> if (done.add(key)) add(key, pinercQuoted(settings.getSetting(leaf.setting)))
        "books", "booksExtra" -> if (done.add("books")) writePinercBooks(settings, add)
        "incoming" -> if (done.add(key)) add(key, pinercIncoming(settings))
        "signature" -> if (done.add(key)) add(key, pinercQuoted(pineEscape(settings.getSetting(leaf.setting))))
        "leadin" -> if (done.add(key)) add(key, pinercQuoted(settings.getSetting(leaf.setting)))
        "headers" -> if (done.add(key)) add(key, settings.getSetting(leaf.setting))
        "roles" -> if (done.add(key)) {
            add(key, pinercRoles(org.dlang.liveimap.ui.compose.decodeRules(settings.sendingRules)))
        }
        "sort" -> if (done.add(key)) lines.add("$key=${pinercSort(settings.defaultView)}")
        "startup" -> if (done.add(key)) {
            val startup = pinercStartup(StartRule.valueOf(settings.getSetting(leaf.setting)))
            if (startup != null) lines.add("$key=$startup")
        }
        "saveRule" -> if (done.add(key)) {
            lines.add("$key=${pinercSaveRule(SaveNameRule.valueOf(settings.getSetting(leaf.setting)))}")
        }
        "threadStyle" -> if (done.add(key)) {
            lines.add("$key=${pinercThreadStyle(ThreadIndexStyle.valueOf(settings.getSetting(leaf.setting)))}")
        }
        "int" -> if (done.add(key)) writePinercInt(settings, leaf, key, lines, add)
        "feature" -> if (done.add("feature-list")) lines.add("feature-list=${pinercFeatures(settings)}")
    }
}

private fun writePinercIdentity(settings: Settings, add: (String, String) -> Unit) {
    val userId = if ('@' in settings.email) settings.email.substringBefore('@') else settings.username
    val domain = if ('@' in settings.email) settings.email.substringAfter('@') else ""
    add("user-id", pinercQuoted(userId))
    add("user-domain", pinercQuoted(domain))
}

private fun writePinercBooks(settings: Settings, add: (String, String) -> Unit) {
    val writable = settings.addressBooks.filter { it.kind == "pine" && !it.readOnly && it.mailbox.isNotEmpty() }
    val readonly = settings.addressBooks.filter { it.kind == "pine" && it.readOnly && it.mailbox.isNotEmpty() }
    if (writable.isNotEmpty()) {
        add("address-book", writable.joinToString(", ") { pinercQuoted("{${settings.imapHost}}${it.mailbox}") })
    }
    if (readonly.isNotEmpty()) {
        add("global-address-book", readonly.joinToString(", ") { pinercQuoted("{${settings.imapHost}}${it.mailbox}") })
    }
}

private fun writePinercInt(
    settings: Settings,
    leaf: TreeLeaf,
    key: String,
    lines: ArrayList<String>,
    add: (String, String) -> Unit,
) {
    if (leaf.setting == "addressBookHistory") {
        if (!settings.addressBookNeverTrim) add(key, settings.getSetting(leaf.setting))
        return
    }
    lines.add("$key=${settings.getSetting(leaf.setting)}")
}

private fun pinercLeaves(): List<TreeLeaf> {
    if (settingsRegistry.isEmpty()) installGeneratedSettings()
    return settingsRegistry.values.map { it.leaf }.filter { it.pinerc != null }
}

private fun appliedPinercNames(): Set<String> {
    val names = HashSet<String>()
    for (leaf in pinercLeaves()) leaf.pinerc?.let { names.add(it) }
    names.add("default-composer-hdrs")
    return names
}

suspend fun pinercMailboxListed(session: MailSession, name: String): Boolean {
    if (name.isEmpty() || '%' in name || '*' in name) return false
    return session.mailboxListed(name)
}

fun withoutMissingMailboxes(
    preview: PinercPreview,
    current: Settings,
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
    consider(phrases.addressBookMailbox, next.addressBooks.firstOrNull { it.kind == "pine" }?.mailbox.orEmpty(), current.addressBooks.firstOrNull { it.kind == "pine" }?.mailbox.orEmpty()) {
        next = next.copy(addressBooks = current.addressBooks)
    }
    if (drops.isEmpty()) return preview
    val rows = preview.rows.filter { row -> drops.none { prefix -> row.startsWith(prefix) } }
    return preview.copy(next = next, rows = rows, skipped = preview.skipped + notes)
}

fun withoutCheckedMailboxes(
    preview: PinercPreview,
    current: Settings,
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
    consider(phrases.addressBookMailbox, next.addressBooks.firstOrNull { it.kind == "pine" }?.mailbox.orEmpty(), current.addressBooks.firstOrNull { it.kind == "pine" }?.mailbox.orEmpty()) {
        next = next.copy(addressBooks = current.addressBooks)
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
    current: Settings,
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
    current: Settings,
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
    current: Settings,
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
        for (part in roleField.split(sb.toString())) {
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return out
    }
    val pattern = section("pattern") ?: return null
    val action = section("action") ?: return null
    if (action["ROLE"] != "1") return null
    val name = pattern["NICK"]?.takeIf { it.isNotBlank() } ?: return null
    val known = setOf("NICK", "COMM", "TO", "CC", "RECIP", "FROM", "FLDTYPE", "FOLDER")
    if (pattern.keys.any { it !in known }) return null
    fun list(v: String?) = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val folder = if (pattern["FLDTYPE"] == "SPEC") list(pattern["FOLDER"]).firstOrNull().orEmpty() else ""
    val from = action["FROM"].orEmpty()
    val fromAddress = angleAddress.find(from)?.groupValues?.get(1) ?: from.trim().ifEmpty { null }
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

private val roleField = Regex("/(?=[A-Z]+=)")
private val angleAddress = Regex("<([^>]+)>")
private val variableName = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

private data class PinercValue(val decoded: String?, val raw: String)

private data class RemoteSpec(
    val host: String,
    val port: Int?,
    val user: String?,
    val mode: TlsMode?,
    val submit: Boolean,
    val acceptInvalidCert: Boolean = false,
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
    var acceptInvalid = false
    for (flag in flags) {
        if (flag.isEmpty()) continue
        when {
            flag.equals("submit", ignoreCase = true) -> submit = true
            flag.startsWith("user=", ignoreCase = true) -> user = unquoteSpecValue(flag.substring(5))
            flag.equals("novalidate-cert", ignoreCase = true) -> acceptInvalid = true
            mode != null -> Unit
            flag.equals("notls", ignoreCase = true) ||
                flag.equals("nostarttls", ignoreCase = true) -> mode = TlsMode.None
            flag.equals("ssl", ignoreCase = true) -> mode = TlsMode.Implicit
            flag.equals("tls", ignoreCase = true) ||
                flag.equals("starttls", ignoreCase = true) -> mode = TlsMode.StartTls
        }
    }
    return RemoteSpec(host, port, user, mode, submit, acceptInvalid)
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

internal fun pinercQuoted(value: String): String {
    val needs = value.startsWith(" ") ||
        value.endsWith(" ") ||
        ' ' in value ||
        '"' in value ||
        '\\' in value ||
        ',' in value
    if (!needs) return value
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** The inverse of [pineEscapes]: newline, tab, and backslash. */
private fun pineEscape(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

private fun pinercRoleEscape(value: String): String = buildString {
    for (c in value) {
        when (c) {
            '\\', '"' -> {
                append('\\')
                append(c)
            }
            else -> append(c)
        }
    }
}

/** Kinds, list id, and send-account are not pinerc role fields, so they are not written. */
private fun pinercRoles(rules: List<org.dlang.liveimap.ui.compose.SendingRule>): String {
    val pieces = ArrayList<String>()
    for (rule in rules) {
        if (rule.name.isBlank()) continue
        val pattern = StringBuilder()
        pattern.append("/NICK=").append(pinercRoleEscape(rule.name))
        if (rule.conditions.sentTo.isNotEmpty()) {
            pattern.append("/TO=").append(pinercRoleEscape(rule.conditions.sentTo.joinToString(",")))
        }
        if (rule.conditions.originalFrom.isNotEmpty()) {
            pattern.append("/FROM=").append(pinercRoleEscape(rule.conditions.originalFrom.joinToString(",")))
        }
        if (rule.conditions.folder.isNotEmpty()) {
            pattern.append("/FLDTYPE=SPEC/FOLDER=").append(pinercRoleEscape(rule.conditions.folder))
        }
        val action = StringBuilder("/ROLE=1")
        val fromName = rule.actions.fromName
        val fromAddress = rule.actions.fromAddress
        val from = when {
            !fromName.isNullOrBlank() && !fromAddress.isNullOrBlank() -> "$fromName <$fromAddress>"
            !fromAddress.isNullOrBlank() -> fromAddress
            !fromName.isNullOrBlank() -> fromName
            else -> ""
        }
        if (from.isNotEmpty()) action.append("/FROM=").append(pinercRoleEscape(from))
        rule.actions.replyTo?.let { action.append("/REPLYTO=").append(pinercRoleEscape(it)) }
        rule.actions.fcc?.let { action.append("/FCC=").append(pinercRoleEscape(it)) }
        rule.actions.signature?.let { action.append("/LSIG=").append(pinercRoleEscape(pineEscape(it))) }
        pieces.add("pattern=\"$pattern\" action=\"$action\"")
    }
    return pieces.joinToString(",")
}

private fun pinercTls(mode: TlsMode): String = when (mode) {
    TlsMode.Implicit -> "ssl"
    TlsMode.StartTls -> "tls"
    TlsMode.None -> "notls"
}

private fun pinercInbox(settings: Settings): String {
    if (settings.imapHost.isEmpty()) return ""
    val user = if (settings.username.isEmpty()) "" else "/user=${quoteSpecValue(settings.username)}"
    val noval = if (settings.acceptInvalidCert) "/novalidate-cert" else ""
    return "{${settings.imapHost}:${settings.imapPort}/${pinercTls(settings.tlsMode)}$noval$user}INBOX"
}

private fun pinercSmtp(settings: Settings): String {
    if (settings.smtpHost.isEmpty()) return ""
    val submit = if (settings.smtpPort == 587 && settings.smtpTlsMode == TlsMode.StartTls) "/submit" else ""
    val user = if (settings.smtpUsername.isNotEmpty() && settings.smtpUsername != settings.username) {
        "/user=${quoteSpecValue(settings.smtpUsername)}"
    } else {
        ""
    }
    return "{${settings.smtpHost}:${settings.smtpPort}/${pinercTls(settings.smtpTlsMode)}$submit$user}"
}

private fun pinercIncoming(settings: Settings): String {
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

private fun parsePinercSaveRule(raw: String): SaveNameRule? = when (raw.trim()) {
    "default-folder" -> SaveNameRule.DefaultFolder
    "by-from" -> SaveNameRule.ByFrom
    "by-sender" -> SaveNameRule.BySender
    "by-recipient" -> SaveNameRule.ByRecipient
    "last-folder-used" -> SaveNameRule.LastFolderUsed
    else -> null
}

private fun parsePinercThreadStyle(raw: String): ThreadIndexStyle? = when (raw.trim()) {
    "exp" -> ThreadIndexStyle.Expanded
    "coll" -> ThreadIndexStyle.Collapsed
    else -> null
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

private fun pinercFeatures(settings: Settings): String {
    val tokens = ArrayList<String>(6)
    tokens.add(if (settings.askBeforeExpunge) "no-expunge-without-confirm" else "expunge-without-confirm")
    tokens.add(if (settings.sigDashes) "enable-sigdashes" else "no-enable-sigdashes")
    tokens.add(if (settings.sigAtBottom) "signature-at-bottom" else "no-signature-at-bottom")
    tokens.add(
        if (settings.dsnMode == org.dlang.liveimap.ui.compose.DsnMode.Off) {
            "no-enable-delivery-status-notification"
        } else {
            "enable-delivery-status-notification"
        },
    )
    if (settings.quellFlowed) tokens.add("quell-flowed-text")
    if (settings.forwardAsAttachment) tokens.add("forward-as-attachment")
    if (settings.readerDisplay == org.dlang.liveimap.ui.reader.ReaderDisplay.Flowed ||
        settings.readerDisplay == org.dlang.liveimap.ui.reader.ReaderDisplay.Monospaced
    ) {
        tokens.add("prefer-plain-text")
    }
    if (settings.readerDisplay == org.dlang.liveimap.ui.reader.ReaderDisplay.Html) {
        tokens.add("render-html-internally")
    }
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

private fun pineBookList(settings: Settings, readOnly: Boolean): String =
    settings.addressBooks
        .filter { it.kind == "pine" && it.readOnly == readOnly && it.mailbox.isNotEmpty() }
        .joinToString(", ") { it.mailbox }

private fun diffRows(
    current: Settings,
    next: Settings,
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
    add(phrases.addressBookMailbox, pineBookList(current, readOnly = false), pineBookList(next, readOnly = false))
    add(phrases.globalAddressBooks, pineBookList(current, readOnly = true), pineBookList(next, readOnly = true))
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
