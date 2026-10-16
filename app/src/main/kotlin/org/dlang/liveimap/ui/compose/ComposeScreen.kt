package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.settings.openBound
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import org.dlang.liveimap.ui.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import java.util.UUID
import org.dlang.liveimap.R
import org.dlang.liveimap.ui.help.HelpIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.jmap.JmapFailure
import org.dlang.liveimap.jmap.JmapSentFolderMissing
import org.dlang.liveimap.jmap.JmapMaybeSent
import org.dlang.liveimap.jmap.jmapSend
import org.dlang.liveimap.jmap.offerForAccount
import org.dlang.liveimap.jmap.jmapBasicAuthorization
import org.dlang.liveimap.jmap.platformJmapExchange
import org.dlang.liveimap.jmap.pinnedJmapPost
import org.dlang.liveimap.jmap.pinnedJmapUpload
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.FolderAt
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.favoriteDelimiter
import org.dlang.liveimap.settings.tlsPolicy
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.alpineBooks
import org.dlang.liveimap.settings.pineSourceId
import org.dlang.liveimap.ui.reader.RawViewCapBytes
import org.dlang.liveimap.ui.reader.decodePart
import org.dlang.liveimap.ui.reader.htmlAsText
import org.dlang.liveimap.ui.reader.unknownCharsetNote
import org.dlang.liveimap.ui.contacts.AddressBookPicker
import org.dlang.liveimap.ui.contacts.AddressSuggestion
import org.dlang.liveimap.ui.contacts.AlpineEntry
import org.dlang.liveimap.ui.contacts.CompletionSource
import org.dlang.liveimap.ui.contacts.addressMarkedPlaintext
import org.dlang.liveimap.ui.contacts.androidContactSetsOnce
import org.dlang.liveimap.ui.contacts.androidEntriesOnce
import org.dlang.liveimap.ui.contacts.completeAddress
import org.dlang.liveimap.ui.contacts.completionSourcesInOrder
import org.dlang.liveimap.ui.contacts.hasReadContacts
import org.dlang.liveimap.ui.contacts.pineBookIsLoaded
import org.dlang.liveimap.ui.contacts.pineEntriesOnce
import org.dlang.liveimap.ui.contacts.suggestionText
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets
import org.dlang.liveimap.ui.toolbar.ComposeBarAction
import org.dlang.liveimap.ui.toolbar.ComposeMenuEntry
import org.dlang.liveimap.ui.toolbar.ToolbarIconRows
import org.dlang.liveimap.ui.toolbar.composeMenu
import org.dlang.liveimap.ui.toolbar.toolbarExpandedHeight

private enum class AddressTarget {
    To,
    Cc,
    Bcc,
    Bounce,
}

private class ForwardRow(
    val key: String,
    val section: String,
    val filename: String,
    val mediaType: String,
    val size: Int,
    val included: Boolean,
    val bytes: ByteArray?,
    val wireBase64: Boolean,
    val encoding: String?,
) {
    fun toggle(): ForwardRow = ForwardRow(
        key,
        section,
        filename,
        mediaType,
        size,
        !included,
        bytes,
        wireBase64,
        encoding,
    )
}

private val headerValueSaver = mapSaver(
    save = { value: SnapshotStateMap<String, String> -> value.toMap() },
    restore = { saved ->
        val map = mutableStateMapOf<String, String>()
        for ((key, item) in saved) {
            if (item is String) map[key] = item
        }
        map
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddressChips(
    label: String,
    stored: String,
    buffer: String,
    onStored: (String) -> Unit,
    onBuffer: (String) -> Unit,
    suggestions: List<AddressSuggestion> = emptyList(),
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        for (address in splitAddresses(stored)) {
            InputChip(
                selected = false,
                onClick = { onStored(removeChipAddress(stored, address)) },
                label = { Text(address) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.compose_remove),
                    )
                },
            )
        }
        OutlinedTextField(
            value = buffer,
            onValueChange = { next ->
                val edited = commitChip(stored, next)
                onStored(edited.stored)
                onBuffer(edited.buffer)
            },
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val edited = commitChip(stored, "$buffer\n")
                    onStored(edited.stored)
                    onBuffer(edited.buffer)
                },
            ),
            modifier = Modifier
                .defaultMinSize(minWidth = 160.dp)
                .weight(1f),
        )
        if (trailing != null) trailing()
    }
        if (buffer.isNotEmpty()) {
            for (suggestion in suggestions) {
                TextButton(onClick = {
                    onStored(commitSuggestion(stored, suggestion))
                    onBuffer("")
                }) { Text(suggestionText(suggestion)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    seed: ComposeSeed,
    onDone: () -> Unit,
    unsentId: String? = null,
    retryOnOpen: Boolean = false,
    onOpenUnsent: () -> Unit = {},
    onCustomize: (String) -> Unit = { _ -> },
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (accountId == null) accountId = seed.accountId.ifEmpty { null } ?: store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    ComposeLoaded(
        accountId = id,
        store = remember(id) { store.forAccount(id) },
        seed = seed,
        onDone = onDone,
        unsentId = unsentId,
        retryOnOpen = retryOnOpen,
        onOpenUnsent = onOpenUnsent,
        onCustomize = onCustomize,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeLoaded(
    accountId: String,
    store: DataStoreSettingsStore,
    seed: ComposeSeed,
    onDone: () -> Unit,
    unsentId: String? = null,
    retryOnOpen: Boolean = false,
    onOpenUnsent: () -> Unit = {},
    onCustomize: (String) -> Unit = { _ -> },
) {
    val appContext = LocalContext.current.applicationContext
    val acceptedNotice = stringResource(R.string.compose_accepted)
    val notSentNotice = stringResource(R.string.unsent_not_sent)
    val sentMailboxMissing = stringResource(R.string.compose_sent_mailbox)
    val postponedMailboxMissing = stringResource(R.string.compose_postponed_mailbox)
    val fromMissing = stringResource(R.string.compose_from_missing)
    val resentMissing = stringResource(R.string.compose_resent_missing)
    val unsentGone = stringResource(R.string.compose_unsent_gone)
    val notConnected = stringResource(R.string.reader_not_connected)
    val fetchFailed = stringResource(R.string.compose_fetch_failed)
    val session = remember(accountId) { mailSession(accountId) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    var account by remember { mutableStateOf(AccountSettings()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var loadedDelimiter by remember { mutableStateOf<Char?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selectedMailbox by remember { mutableStateOf<String?>(null) }
    val boxEpochs = remember { HashMap<String, Long>() }
    var toText by rememberSaveable { mutableStateOf("") }
    var ccText by rememberSaveable { mutableStateOf("") }
    var bccText by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var bodyField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var inReplyTo by rememberSaveable { mutableStateOf("") }
    var referencesHeader by rememberSaveable { mutableStateOf("") }
    var bounceTo by rememberSaveable { mutableStateOf("") }
    var bounceAccepted by rememberSaveable(seed.mailbox, seed.uidValidity) { mutableStateOf("") }
    fun bounceAcceptedSet(): Set<Long> =
        bounceAccepted.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    fun addBounceAccepted(uid: Long) {
        bounceAccepted = (bounceAcceptedSet() + uid).joinToString(",")
    }
    var draftLoaded by rememberSaveable { mutableStateOf(false) }
    var retryArmedFor by rememberSaveable { mutableStateOf("") }
    var forwardRows by remember { mutableStateOf<List<ForwardRow>>(emptyList()) }
    // Sending rules (design-decisions.md "Sending rules and roles").
    var sending by remember { mutableStateOf<Resolved?>(null) }
    var pinnedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var originalParsed by remember { mutableStateOf<ParsedRfc822?>(null) }
    var mismatchArmed by remember { mutableStateOf(false) }
    var fromMenuOpen by remember { mutableStateOf(false) }
    val headerValuesTyped = rememberSaveable(saver = headerValueSaver) { mutableStateMapOf<String, String>() }
    var sourceUid by remember { mutableStateOf<Long?>(null) }
    var deliveryDone by remember { mutableStateOf(false) }
    // Set when the server took the message without the requested DSN; kept through the "Sent" status.
    var dsnRefused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<DeviceCopy?>(null) }
    // David's Sent rule (c): saving the copy failed, so offer "send without saving a copy" (Alpine's dropped Fcc).
    var offerNoCopy by remember { mutableStateOf(false) }
    var withoutCopy by remember { mutableStateOf(false) }
    /** F150: the "send without copy" offer came from retrying a stored copy, so it retries that copy. */
    var noCopyRetry by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf(AddressTarget.To) }
    var plaintextEntries by remember { mutableStateOf<List<AlpineEntry>>(emptyList()) }
    var addressSources by remember { mutableStateOf<List<CompletionSource>>(emptyList()) }
    var discardOpen by remember { mutableStateOf(false) }
    var baseTo by rememberSaveable { mutableStateOf("") }
    var baseCc by rememberSaveable { mutableStateOf("") }
    var baseBcc by rememberSaveable { mutableStateOf("") }
    var baseSubject by rememberSaveable { mutableStateOf("") }
    var baseBody by rememberSaveable { mutableStateOf("") }
    var baseRowKeyText by rememberSaveable { mutableStateOf("") }
    var baseHeader by rememberSaveable { mutableStateOf("") }
    var baseIncluded by rememberSaveable { mutableStateOf("") }
    var basePinnedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var baseUseReplyTo by rememberSaveable { mutableStateOf(false) }
    var baselineReady by rememberSaveable { mutableStateOf(false) }
    var copiesOpen by rememberSaveable { mutableStateOf(false) }
    var toBuffer by rememberSaveable { mutableStateOf("") }
    var ccBuffer by rememberSaveable { mutableStateOf("") }
    var bccBuffer by rememberSaveable { mutableStateOf("") }
    var overflow by remember { mutableStateOf(false) }
    var iconRows by remember { mutableIntStateOf(1) }
    var offerReplyTo by rememberSaveable { mutableStateOf(false) }
    var useReplyTo by rememberSaveable { mutableStateOf(false) }
    var replyToLine by rememberSaveable { mutableStateOf("") }
    var replyCcLine by rememberSaveable { mutableStateOf("") }

    fun statusLeaf(mailbox: String): String {
        val delimiter = loadedDelimiter
        return if (delimiter == null) mailboxLeaf(mailbox) else mailboxLeaf(mailbox, delimiter)
    }

    BackHandler(enabled = pickerOpen) {
        pickerOpen = false
    }

    fun applyDraft(draft: ReplyDraft, cursor: Int = draft.body.length) {
        toText = draft.to.joinToString(", ")
        ccText = draft.cc.joinToString(", ")
        bccText = ""
        toBuffer = ""
        ccBuffer = ""
        bccBuffer = ""
        subject = draft.subject
        val at = cursor.coerceIn(0, draft.body.length)
        bodyField = TextFieldValue(draft.body, TextRange(at))
        inReplyTo = draft.inReplyTo
        referencesHeader = draft.references
    }

    /**
     * F116 / pass 5: [box]'s UIDs are used only inside one lowest-layer [MailSession.inFolder] call, checked
     * against the UIDVALIDITY they came from (the seed's, or the first one seen here).
     */
    suspend fun <T> inBox(box: String, block: suspend (MailSession) -> T): T {
        val known = boxEpochs[box] ?: if (box == seed.mailbox && seed.uidValidity != 0L) seed.uidValidity else 0L
        return session.inFolder(FolderAt(box, known)) { sel, s ->
            boxEpochs[box] = sel.uidValidity
            block(s)
        }
    }

    suspend fun readPlaintextBook(mailbox: String): List<AlpineEntry> {
        if (mailbox.isEmpty()) return emptyList()
        val cached = pineBookIsLoaded(accountId, mailbox)
        val entries = try {
            pineEntriesOnce(session, accountId, mailbox)
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            emptyList()
        }
        return entries
    }

    suspend fun sourcesForCompose(settings: AccountSettings): List<CompletionSource> {
        val ids = settings.completionSources
        val pine = if (pineSourceId !in ids) {
            emptyList()
        } else {
            // The account's book, then its extra and global books (contacts plan phase 5).
            settings.alpineBooks().flatMap { (book, _) ->
                try {
                    pineEntriesOnce(session, accountId, book)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    emptyList()
                }
            }
        }
        val wantAndroid = ids.any { it.startsWith("android|") }
        if (!wantAndroid || !hasReadContacts(appContext)) {
            return completionSourcesInOrder(ids, pine, emptyMap(), emptyMap())
        }
        val entries = try {
            androidEntriesOnce(appContext.contentResolver)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        val labels = try {
            androidContactSetsOnce(appContext.contentResolver, appContext.packageManager)
                .associate { it.id to it.label }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        return completionSourcesInOrder(ids, pine, entries, labels)
    }

    suspend fun quotedBody(tree: MimePart, view: BodyView, uid: Long): String {
        suspend fun decodedQuote(part: MimePart, html: Boolean): String {
            val bytes = peekWireBytes(
                part.size,
                4096,
                part.encoding.equals("base64", ignoreCase = true),
            ) { offset, length ->
                val box = seed.mailbox
                if (box == null) throw MailFailure("message reference is incomplete; reload the list")
                else inBox(box) { s -> s.peekPart(uid, part.section, offset, length) } // F116
            }
            val decoded = decodePart(bytes, part.charset, part.encoding)
            if (decoded.unknownCharset && notice.isNullOrEmpty()) notice = unknownCharsetNote
            return if (html) htmlAsText(decoded.text) else decoded.text
        }
        val plain = textPart(tree, "plain")
        if (plain != null) return decodedQuote(plain, html = false)
        if (view == BodyView.PlainOrHtml || view == BodyView.PlainOrText) {
            val html = textPart(tree, "html")
            if (html != null) return decodedQuote(html, html = true)
            notice = missingPartText(true)
            return ""
        }
        notice = missingPartText(false)
        return ""
    }

    fun kindTag(): ComposeKindTag = when (seed.kind) {
        ComposeKind.Reply -> ComposeKindTag.Reply
        ComposeKind.ReplyAll -> ComposeKindTag.ReplyAll
        ComposeKind.Forward, ComposeKind.Bounce -> ComposeKindTag.Forward
        else -> ComposeKindTag.New
    }

    fun sendingContext(): ComposeContext = composeContext(
        kindTag(),
        originalParsed,
        // Includes an address still being typed, so the From line follows it before Enter or a comma.
        splitAddresses(chipProbe(toText, toBuffer)) + splitAddresses(chipProbe(ccText, ccBuffer)),
        seed.mailbox.orEmpty(),
    ).copy(pinnedFrom = pinnedFrom)

    fun resolveNow(settings: AccountSettings): Resolved {
        val r = resolveSending(accountRules(settings), accountDefaultRule(settings), accountId, sendingContext())
        sending = r
        return r
    }

    fun cursorBeforeSignature(body: String, r: Resolved): Int {
        val block = signatureBlock(r.signature, r.sigDashes)
        val at = if (block.isEmpty()) -1 else body.lastIndexOf("\n\n" + block)
        return if (at >= 0) at else body.length
    }

    suspend fun loadReply(settings: AccountSettings, replyAll: Boolean) {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        val parsed = parseRfc822(inBox(box) { s -> s.peekPart(uid, "HEADER", 0, RawViewCapBytes) })
        originalParsed = parsed
        val quote = quotedBody(inBox(box) { s -> s.fetchStructure(uid) }, settings.bodyView, uid)
        val rules0 = resolveNow(settings)
        val draft = replyDraft(replyAll, parsed, settings.email, quote, rules0.quotePrefix, rules0.leadIn)
        val replyToAddrs = addresses(parsed, "Reply-To")
        val fromAddrs = addresses(parsed, "From")
        fun route(use: Boolean) = replyRecipients(
            replyAll = replyAll,
            replyTo = replyToAddrs,
            from = fromAddrs,
            to = addresses(parsed, "To"),
            cc = addresses(parsed, "Cc"),
            accountEmail = settings.email,
            altAddresses = settings.altAddresses,
            useReplyTo = use,
        )
        val fromRoute = route(false)
        val replyRoute = route(true)
        replyToLine = replyRoute.first.joinToString(", ")
        replyCcLine = replyRoute.second.joinToString(", ")
        offerReplyTo = replyToDiffers(replyToAddrs, fromAddrs)
        val listPost = if (!replyAll && rules0.replyToList) listPostAddress(parsed) else null
        val chosen = when {
            listPost != null -> listOf(listPost) to emptyList()
            useReplyTo -> replyRoute
            else -> fromRoute
        }
        toText = chosen.first.joinToString(", ")
        ccText = chosen.second.joinToString(", ")
        val r = resolveNow(settings)
        val shown = withSignature(
            replyCursorBody(draft.body, settings.replyAboveQuote),
            signatureBlock(r.signature, r.sigDashes),
            r.sigAtBottom,
            settings.replyAboveQuote,
        )
        applyDraft(
            ReplyDraft(chosen.first, chosen.second, draft.subject, draft.inReplyTo, draft.references, shown),
            cursor = if (settings.replyAboveQuote) 0 else cursorBeforeSignature(shown, r),
        )
    }

    suspend fun loadForward(settings: AccountSettings) {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        sourceUid = uid
        if (draftLoaded && forwardRows.isNotEmpty()) return
        val asAttachment = seed.forwardAsAttachment ?: settings.forwardAsAttachment
        if (asAttachment) {
            val original = inBox(box) { s -> s.fetchRfc822(uid) }
            if (!draftLoaded) {
                val parsed = parseRfc822(original)
                originalParsed = parsed
                resolveNow(settings)
                val draft = forwardDraft(parsed, "")
                applyDraft(
                    ReplyDraft(draft.to, draft.cc, draft.subject, "", "", ""),
                    cursor = 0,
                )
            }
            forwardRows = listOf(
                ForwardRow(
                    key = "rfc822:$uid",
                    section = "",
                    filename = "forwarded.eml",
                    mediaType = "message/rfc822",
                    size = original.size,
                    included = true,
                    bytes = original,
                    wireBase64 = false,
                    encoding = null,
                ),
            )
            return
        }
        val tree = if (draftLoaded) {
            inBox(box) { s -> s.fetchStructure(uid) }
        } else {
            val parsed = parseRfc822(inBox(box) { s -> s.peekPart(uid, "HEADER", 0, RawViewCapBytes) })
            originalParsed = parsed
            val r = resolveNow(settings)
            val fetched = inBox(box) { s -> s.fetchStructure(uid) }
            val quote = quotedBody(fetched, settings.bodyView, uid)
            val draft = forwardDraft(parsed, quote, r.quotePrefix, r.leadIn)
            val header = appContext.getString(
                R.string.compose_quote_header,
                headerValues(parsed, "From").firstOrNull().orEmpty().trim(),
                headerValues(parsed, "Date").firstOrNull().orEmpty().trim(),
                headerValues(parsed, "Subject").firstOrNull().orEmpty().trim(),
            )
            val shown = withSignature(
                "\n" + forwardHeaderBody(header, quote),
                signatureBlock(r.signature, r.sigDashes),
                r.sigAtBottom,
                cursorAtTop = true,
            )
            applyDraft(
                ReplyDraft(draft.to, draft.cc, draft.subject, draft.inReplyTo, draft.references, shown),
                cursor = 0,
            )
            fetched
        }
        forwardRows = attachmentParts(tree).mapIndexed { index, item ->
            ForwardRow(
                key = "${item.section}:$index",
                section = item.section,
                filename = item.filename?.takeIf { it.isNotEmpty() } ?: "${item.type}/${item.subtype}",
                mediaType = "${item.type}/${item.subtype}",
                size = item.size,
                included = settings.includeForwardAttachments,
                bytes = null,
                wireBase64 = false,
                encoding = item.encoding,
            )
        }
    }

    fun applyEditor(loaded: EditorLoad) {
        if (!draftLoaded) {
            toText = loaded.to
            ccText = loaded.cc
            bccText = ""
            toBuffer = ""
            ccBuffer = ""
            bccBuffer = ""
            subject = loaded.subject
            bodyField = TextFieldValue(loaded.body, TextRange(loaded.body.length))
            inReplyTo = loaded.inReplyTo
            referencesHeader = loaded.references
        }
        forwardRows = loaded.attachments.mapIndexed { index, part ->
            ForwardRow(
                key = "resume:$index",
                section = "",
                filename = part.filename,
                mediaType = part.mediaType,
                size = part.bytes.size,
                included = true,
                bytes = part.bytes,
                wireBase64 = part.wireBase64,
                encoding = null,
            )
        }
    }

    suspend fun loadResume() {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        sourceUid = uid
        if (draftLoaded && forwardRows.isNotEmpty()) return
        applyEditor(loadEditor(inBox(box) { s -> s.fetchRfc822(uid) }))
    }

    fun composedHeaders(settings: AccountSettings): List<Pair<String, String>> {
        val replyTo = sending?.replyTo.orEmpty()
        val typed = headerNames(settings.composerHeaders).map { it to headerValuesTyped[it.lowercase()].orEmpty() }
        val typedReplyTo = typed.any { it.first.equals("Reply-To", true) && it.second.isNotBlank() }
        return (if (replyTo.isNotBlank() && !typedReplyTo) listOf("Reply-To" to replyTo) else emptyList()) + typed
    }

    suspend fun assemble(settings: AccountSettings): BuiltMail {
        val parts = ArrayList<OutgoingPart>()
        val uid = sourceUid
        for (row in forwardRows) {
            if (!row.included) continue
            val payload = if (row.bytes != null) {
                row.bytes
            } else {
                if (uid == null) throw MailFailure(fetchFailed)
                val source = seed.mailbox ?: account.postponedMailbox // F116: the source's folder, not wherever
                val base64 = row.encoding?.equals("base64", ignoreCase = true) == true
                peekWireBytes(row.size, 65536, base64) { offset, length ->
                    inBox(source) { s -> s.peekPart(uid, row.section, offset, length) }
                }
            }
            parts.add(OutgoingPart(row.filename, row.mediaType, payload, row.wireBase64, row.encoding))
        }
        return buildPlain(
            PlainMessage(
                fromName = settings.displayName,
                fromEmail = settings.email,
                extraHeaders = composedHeaders(settings),
                to = splitAddresses(toText),
                cc = splitAddresses(ccText),
                bcc = splitAddresses(bccText),
                subject = subject,
                body = bodyField.text,
                messageId = newMessageId(settings.email),
                date = rfc822Date(),
                inReplyTo = inReplyTo,
                references = referencesHeader,
                attachments = parts,
                wrapColumn = settings.composerWrapColumn,
                flowed = !settings.quellFlowed,
            ),
        )
    }

    suspend fun storeAcceptedFlags(uids: List<Long>) {
        if (seed.uidValidity == 0L) return
        val flags = smtpAcceptFlags(seed.kind)
        val box = seed.mailbox
        if (flags.isEmpty() || box == null || uids.isEmpty()) return
        try {
            inBox(box) { s -> s.storeFlags(uids, flags, emptySet()) }
        } catch (error: CancellationException) {
            throw error
        } catch (failure: MailFailure) {
            // F116: sent, but say the source was not marked (instead of swallowing it)
            notice = appContext.getString(R.string.compose_flags_not_stored, failure.text)
        }
    }

    /**
     * A JMAP send that did not complete. keepCopyUntilSubmitted already left the device copy (F146: still "may be
     * sent" when the server's answer was lost); the Unsent list shows it like any failed send.
     */
    fun jmapSendFailed(copy: DeviceCopy, error: Exception) {
        val maybe = jmapMaybeSent(error)
        held = copy.copyWith(uncertain = maybe)
        notice = when (error) {
            is JmapFailure -> error.text
            is MailFailure -> error.text
            else -> error.message ?: notConnected
        }
        status = if (maybe) appContext.getString(R.string.compose_send_uncertain) else notSentNotice
    }

    suspend fun removePostponedSource() {
        if (seed.uidValidity == 0L) return
        val uid = postponedUidToRemove(seed.kind, true, sourceUid) ?: return
        val box = seed.mailbox ?: account.postponedMailbox
        try {
            inBox(box) { s ->
                s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                s.uidExpunge(listOf(uid))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            notice = appContext.getString(R.string.compose_postponed_remains, box)
        }
    }

    fun sentStatus(savedLeaf: String?): String? = sentStatusText(
        savedLeaf?.takeIf { it.isNotEmpty() }?.let { appContext.getString(R.string.compose_sent_saved, it) },
        if (dsnRefused) appContext.getString(R.string.compose_dsn_unsupported) else null,
    )

    fun noteDsn(result: Int) {
        dsnRefused = result < 0
        if (dsnRefused) status = appContext.getString(R.string.compose_dsn_unsupported)
    }

    /** The session that sends (and files the Fcc of) mail routed through account [via]; empty = this account. */
    suspend fun outbound(via: String): MailSession {
        if (via.isEmpty() || via == accountId) return session
        val credentials = store.accountById(via)
            ?: throw MailFailure(appContext.getString(R.string.compose_send_account_missing))
        val other = mailSession(via)
        when (val opened = other.openWith(credentials)) {
            OpenResult.Connected -> Unit
            is OpenResult.Failed -> throw MailFailure(
                appContext.getString(R.string.compose_send_account_failed, org.dlang.liveimap.accounts.accountVisibleName(credentials.settings), opened.text),
            )
            is OpenResult.Rejected -> throw MailFailure(
                appContext.getString(R.string.compose_send_account_failed, org.dlang.liveimap.accounts.accountVisibleName(credentials.settings), opened.capabilities),
            )
        }
        return other
    }

    suspend fun sendSmtp(bytes: ByteArray, envelope: List<String>, via: String = ""): ByteArray {
        val out = outbound(via)
        // IDN domains go as punycode; non-ASCII local parts need SMTPUTF8 (refused natively as "smtputf8").
        val recipients = envelope.map(::asciiDomainAddress)
        val unsafe = recipients.filterNot(::envelopeAddressOk)
        if (unsafe.isNotEmpty()) {
            throw MailFailure(appContext.getString(R.string.compose_bad_envelope_address, unsafe.joinToString(", ") { it.map { c -> if (c < ' ' || c == '\u007f') '?' else c }.joinToString("") }))
        }
        val sender = (if (via.isEmpty() || via == accountId) account else store.accountById(via)?.settings ?: account)
            .let { envelopeSender(headerFromAddress(bytes), it.email, it.altAddresses + knownFroms(accountRules(it), accountDefaultRule(it))) }
        fun utf8Refused(error: MailFailure): Nothing =
            throw MailFailure(appContext.getString(R.string.compose_needs_smtputf8, needsSmtpUtf8(recipients).joinToString(", ")))
        try {
            noteDsn(out.smtpSendFrom(bytes, recipients, dsnMask(sending?.dsn ?: DsnMode.Off), sender))
            return bytes
        } catch (error: MailFailure) {
            if (error.text == "smtputf8") utf8Refused(error)
            if (error.text != "8bitmime") throw error
        }
        val rewritten = withoutEightBit(bytes)
        try {
            noteDsn(out.smtpSendFrom(rewritten, recipients, dsnMask(sending?.dsn ?: DsnMode.Off), sender))
        } catch (error: MailFailure) {
            if (error.text == "smtputf8") utf8Refused(error)
            throw error
        }
        return rewritten
    }

    suspend fun retryCopy(copy: DeviceCopy): Boolean {
        when (retryDelivery(account.emailSubmission && copy.via.isEmpty(), copy.appendOnly)) { // F92
            RetryDelivery.Append -> {
                if (copy.mailbox.isEmpty()) {
                    notice = sentMailboxMissing
                    return false
                }
                try {
                    outbound(copy.via).append(copy.mailbox, copy.bytes)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    notice = appContext.getString(R.string.compose_sent_copy_failed, copy.mailbox, error.text)
                    offerNoCopy = true
                    status = acceptedNotice
                    return false
                }
                deleteCopy(appContext, copy.id, accountId)
                if (held?.id == copy.id) held = null
                notice = null
                deliveryDone = true
                status = sentStatus(statusLeaf(copy.mailbox))
                removePostponedSource()
                return true
            }
            RetryDelivery.Smtp -> {
                val accepted = try {
                    sendSmtp(copy.bytes, copy.recipients, copy.via)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    notice = error.text
                    status = notSentNotice
                    return false
                }
                storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                if (copy.mailbox.isNotEmpty()) {
                    try {
                        outbound(copy.via).append(copy.mailbox, accepted)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val saved = DeviceCopy(copy.id, true, copy.mailbox, copy.recipients, accepted, via = copy.via)
                        writeCopy(appContext, saved, accountId)
                        if (held?.id == copy.id) held = saved
                        notice = error.text
                        status = acceptedNotice
                        return false
                    }
                }
                deleteCopy(appContext, copy.id, accountId)
                if (held?.id == copy.id) held = null
                notice = null
                deliveryDone = true
                status = if (copy.mailbox.isNotEmpty()) {
                    sentStatus(statusLeaf(copy.mailbox))
                } else {
                    sentStatus(null)
                }
                removePostponedSource()
                return true
            }
            RetryDelivery.Submission -> {
                val password = try {
                    store.password()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    notice = error.message ?: notConnected
                    status = notSentNotice
                    return false
                }
                var leftOver: String? = null
                try {
                    val offer = offerForAccount(account, platformJmapExchange(account.jmapCertPin, account.tlsPolicy()), authorization = jmapBasicAuthorization(account.username, password), accountKey = accountId)
                    keepCopyUntilSubmitted(copy, { writeCopy(appContext, it, accountId) }, { deleteCopy(appContext, it, accountId) }) { jmapSend(
                        offer,
                        copy.bytes,
                        account.email,
                        copy.recipients,
                        account.username,
                        password,
                        account.jmapCertPin,
                        pinnedJmapUpload(account.jmapCertPin, account.tlsPolicy()),
                        pinnedJmapPost(account.jmapCertPin, account.tlsPolicy()),
                        // F150: the copy's own Sent choice (blank after "send without copy" or a no-Fcc bounce).
                        sentName = copy.mailbox,
                        saveCopy = copy.mailbox.isNotEmpty(),
                        draftsName = account.postponedMailbox,
                        onNotice = { leftOver = it },
                        policy = account.tlsPolicy(), accountKey = accountId,
                    ) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: DeviceCopyNotSaved) {
                    notice = appContext.getString(R.string.compose_copy_not_saved)
                    status = notSentNotice
                    return false
                } catch (error: JmapSentFolderMissing) {
                    held = copy
                    notice = appContext.getString(R.string.compose_sent_copy_failed, error.name, error.text)
                    offerNoCopy = true
                    noCopyRetry = true
                    return false
                } catch (error: JmapMaybeSent) {
                    jmapSendFailed(copy, error)
                    return false
                } catch (error: JmapFailure) {
                    notice = error.text
                    status = notSentNotice
                    return false
                } catch (error: MailFailure) {
                    notice = error.text
                    status = notSentNotice
                    return false
                } catch (error: Exception) {
                    notice = error.message ?: notConnected
                    status = notSentNotice
                    return false
                }
                storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                if (held?.id == copy.id) held = null
                notice = leftOver
                deliveryDone = true
                status = if (copy.mailbox.isNotEmpty()) {
                    sentStatus(statusLeaf(copy.mailbox))
                } else {
                    sentStatus(null)
                }
                removePostponedSource()
                return true
            }
        }
    }

    fun launchLocked(block: suspend () -> Boolean) {
        if (busy || !connected) return
        busy = true
        scope.launch {
            var leave = false
            try {
                gate.withLock {
                    leave = try {
                        block()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        notice = error.text
                        false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                busy = false
            }
            if (leave) {
                // Compose closes on success, which would hide the status; this note must outlive the screen.
                if (deliveryDone && dsnRefused) {
                    android.widget.Toast.makeText(
                        appContext,
                        appContext.getString(R.string.compose_dsn_unsupported),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onDone()
            }
        }
    }

    fun headerSnapshot(): String =
        headerValuesTyped.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    fun includedSnapshot(): String =
        forwardRows.joinToString("\n") { "${it.key}=${it.included}" }

    fun captureBaseline() {
        baseTo = toText
        baseCc = ccText
        baseBcc = bccText
        baseSubject = subject
        baseBody = bodyField.text
        baseRowKeyText = forwardRows.joinToString("\n") { it.key }
        baseHeader = headerSnapshot()
        baseIncluded = includedSnapshot()
        basePinnedFrom = pinnedFrom
        baseUseReplyTo = useReplyTo
        baselineReady = true
    }

    fun attachmentRowRemoved(): Boolean {
        if (baseRowKeyText.isEmpty()) return false
        val present = forwardRows.map { it.key }.toSet()
        return baseRowKeyText.split('\n').any { it !in present }
    }

    fun requestClose() {
        val dirty = composeIsDirty(
            to = toText,
            cc = ccText,
            bcc = bccText,
            subject = subject,
            body = bodyField.text,
            baselineTo = baseTo,
            baselineCc = baseCc,
            baselineBcc = baseBcc,
            baselineSubject = baseSubject,
            baselineBody = baseBody,
            rowRemoved = attachmentRowRemoved(),
            header = headerSnapshot(),
            baselineHeader = baseHeader,
            included = includedSnapshot(),
            baselineIncluded = baseIncluded,
            pinnedFrom = pinnedFrom,
            baselinePinnedFrom = basePinnedFrom,
            useReplyTo = useReplyTo,
            baselineUseReplyTo = baseUseReplyTo,
        )
        if (dirty || bounceTo.isNotEmpty()) discardOpen = true else onDone()
    }

    fun postponeDraft() {
        launchLocked {
            if (account.postponedMailbox.isEmpty()) {
                notice = postponedMailboxMissing
                return@launchLocked false
            }
            val built = try {
                assemble(account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                return@launchLocked false
            }
            try {
                session.append(account.postponedMailbox, built.rfc822, setOf("\\Draft"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                return@launchLocked false
            }
            val current = held
            if (current != null && !current.appendOnly) {
                deleteCopy(appContext, current.id, accountId)
                held = null
            }
            notice = null
            status = appContext.getString(R.string.compose_saved_to, statusLeaf(account.postponedMailbox))
            removePostponedSource()
            true
        }
    }

    fun loadStoredCopy(id: String): DeviceCopy? {
        val copy = readCopies(appContext, accountId).firstOrNull { it.id == id }
        if (copy == null) {
            notice = unsentGone
            return null
        }
        held = copy
        if (!(draftLoaded && forwardRows.isNotEmpty())) {
            applyEditor(loadEditor(copy.bytes))
        }
        if (!baselineReady) captureBaseline()
        draftLoaded = true
        return copy
    }

    LifecycleStartEffect(store) {
        val job = scope.launch {
            val loaded = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (loaded != null) account = loaded
        }
        onStopOrDispose { job.cancel() }
    }

    // The From line follows the recipients as they are typed (rules match on To/Cc); a pinned From stays.
    LaunchedEffect(toText, ccText, toBuffer, ccBuffer) {
        if (sending == null) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        resolveNow(account)
    }
    LaunchedEffect(seed, unsentId, retryOnOpen) {
        val storedId = unsentId?.takeIf { it.isNotEmpty() }
        if (seed.kind == ComposeKind.New && storedId == null && !baselineReady) {
            captureBaseline()
        }
        val stored = if (storedId == null) null else loadStoredCopy(storedId)
        var leave = false
        gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: notConnected
                return@withLock
            }
            account = settings
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: notConnected
                return@withLock
            }
            val opened = try {
                session.openBound(store, settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    return@withLock
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    return@withLock
                }
                OpenResult.Connected -> Unit
            }
            connected = true
            try {
                if (storedId != null) {
                    if (stored != null && retryOnOpen && retryArmedFor != storedId) {
                        retryArmedFor = storedId
                        busy = true
                        try {
                            leave = retryCopy(stored)
                        } finally {
                            if (!leave) busy = false
                        }
                    }
                } else {
                    when (seed.kind) {
                        ComposeKind.New -> {
                            val r = resolveNow(settings)
                            if (!draftLoaded && bodyField.text.isEmpty()) {
                                val block = signatureBlock(r.signature, r.sigDashes)
                                if (block.isNotEmpty()) bodyField = TextFieldValue("\n\n" + block, TextRange(0))
                            }
                        }
                        ComposeKind.Reply -> if (!draftLoaded) loadReply(settings, replyAll = false)
                        ComposeKind.ReplyAll -> if (!draftLoaded) loadReply(settings, replyAll = true)
                        ComposeKind.Forward -> loadForward(settings)
                        ComposeKind.Bounce -> Unit
                        ComposeKind.ResumePostpone -> loadResume()
                    }
                    if (seed.kind != ComposeKind.New && !baselineReady) {
                        captureBaseline()
                    }
                    draftLoaded = true
                }
                if (seed.kind != ComposeKind.Bounce) {
                    plaintextEntries = readPlaintextBook(settings.addressBookMailbox)
                    addressSources = sourcesForCompose(settings)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
            }
            try {
                loadedDelimiter = favoriteDelimiter(session.namespaces())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        if (leave) onDone()
    }

    BackHandler(enabled = !pickerOpen && discardOpen) {
        discardOpen = false
    }
    BackHandler(enabled = !pickerOpen && !discardOpen) {
        requestClose()
    }

    fun sendMessage() {
                launchLocked {
                    // An address still being typed (no comma or Enter yet) is a recipient too.
                    toText = chipProbe(toText, toBuffer); toBuffer = ""
                    ccText = chipProbe(ccText, ccBuffer); ccBuffer = ""
                    bccText = chipProbe(bccText, bccBuffer); bccBuffer = ""
                    val base = account
                    val r = resolveNow(base)
                    // A rule may route this through another account's SMTP and Fcc; never fall back to this one's.
                    val via = r.sendAccount.takeIf { it.isNotEmpty() && it != accountId }.orEmpty()
                    val viaSettings = if (via.isEmpty()) null else store.accountById(via)?.settings
                    if (via.isNotEmpty() && viaSettings == null) {
                        notice = appContext.getString(R.string.compose_send_account_unavailable, r.fromRule)
                        return@launchLocked false
                    }
                    val account = base.withSending(r).let { s ->
                        if (viaSettings == null) s
                        else s.copy(sentMailbox = viaFccMailbox(base.sentMailbox, r.fcc, viaSettings.sentMailbox))
                    }.let { if (withoutCopy) it.copy(sentMailbox = "") else it }
                    val mismatch = fromMismatch(
                        accountRules(base), accountDefaultRule(base), accountId,
                        sendingContext(), account.email, via.ifEmpty { accountId },
                    ) { id ->
                        val owner = if (id == accountId) base else viaSettings ?: base
                        listOf(owner.email) + owner.altAddresses + knownFroms(accountRules(base), accountDefaultRule(base))
                    }
                    if (mismatch != null && pinnedFrom != null && !mismatchArmed) {
                        mismatchArmed = true
                        notice = appContext.getString(R.string.compose_from_mismatch, mismatch.expectedFrom, mismatch.rule)
                        return@launchLocked false
                    }
                    val saved = held
                    if (saved != null && saved.appendOnly) {
                        return@launchLocked retryCopy(saved)
                    }
                    if (deliveryDone) {
                        notice = null
                        status = sentStatus(statusLeaf(account.sentMailbox))
                        return@launchLocked true
                    }
                    if (account.email.isEmpty()) {
                        notice = fromMissing
                        return@launchLocked false
                    }
                    if (splitAddresses(toText).isEmpty() && splitAddresses(ccText).isEmpty() &&
                        splitAddresses(bccText).isEmpty()
                    ) {
                        // Without this, SMTP got MAIL and DATA with no RCPT (found on the lab sink).
                        notice = appContext.getString(R.string.compose_no_recipients)
                        return@launchLocked false
                    }
                    // David's Sent rule (b): no Sent folder configured means send without saving a copy.
                    val built = try {
                        assemble(account)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        notice = error.text
                        return@launchLocked false
                    }
                    val id = saved?.id ?: UUID.randomUUID().toString()
                    // F92: JMAP submission uses this account's credentials, so a rule that routes through another
                    // account (via) always takes that account's SMTP path instead.
                    if (account.emailSubmission && via.isEmpty()) {
                        val password = try {
                            store.password()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            val copy = DeviceCopy(id, false, account.sentMailbox, built.recipients, built.rfc822, via = via)
                            writeCopy(appContext, copy, accountId)
                            held = copy
                            notice = error.message ?: notConnected
                            status = notSentNotice
                            return@launchLocked false
                        }
                        // F50/David's JMAP rule: the device copy exists before the upload/submission starts, marked
                        // "may be sent", and goes only once the submission is confirmed (keepCopyUntilSubmitted).
                        val sending = DeviceCopy(id, false, if (withoutCopy) "" else account.sentMailbox, built.recipients, built.rfc822, via = via)
                        var leftOver: String? = null
                        try {
                            val offer = offerForAccount(account, platformJmapExchange(account.jmapCertPin, account.tlsPolicy()), authorization = jmapBasicAuthorization(account.username, password), accountKey = accountId)
                            keepCopyUntilSubmitted(sending, { writeCopy(appContext, it, accountId) }, { deleteCopy(appContext, it, accountId) }) { jmapSend(
                                offer,
                                built.rfc822,
                                account.email,
                                built.recipients,
                                account.username,
                                password,
                                account.jmapCertPin,
                                pinnedJmapUpload(account.jmapCertPin, account.tlsPolicy()),
                                pinnedJmapPost(account.jmapCertPin, account.tlsPolicy()),
                                sentName = account.sentMailbox,
                                draftsName = account.postponedMailbox,
                                saveCopy = !withoutCopy,
                                onNotice = { leftOver = it },
                                policy = account.tlsPolicy(), accountKey = accountId,
                            ) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: DeviceCopyNotSaved) {
                            notice = appContext.getString(R.string.compose_copy_not_saved)
                            status = notSentNotice
                            return@launchLocked false
                        } catch (error: JmapSentFolderMissing) {
                            // Nothing was sent; the user chooses: fix the setting or send without the copy. The local
                            // copy stays (kept by keepCopyUntilSubmitted) until a send is confirmed.
                            held = sending
                            notice = appContext.getString(R.string.compose_sent_copy_failed, error.name, error.text)
                            offerNoCopy = true
                            return@launchLocked false
                        } catch (error: Exception) {
                            jmapSendFailed(sending, error)
                            return@launchLocked false
                        }
                        storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                        held = null
                        deliveryDone = true
                        notice = leftOver
                        status = sentStatus(if (withoutCopy) null else statusLeaf(account.sentMailbox))
                        removePostponedSource()
                        return@launchLocked true
                    }
                    // F50: the device copy exists before SMTP starts, marked "may be sent", so a process death or
                    // cancel mid-send keeps the message and the Unsent list says to check Sent before retrying.
                    val sending = DeviceCopy(id, false, account.sentMailbox, built.recipients, built.rfc822, via = via, uncertain = true)
                    if (!writeCopy(appContext, sending, accountId)) {
                        notice = appContext.getString(R.string.compose_copy_not_saved)
                        status = notSentNotice
                        return@launchLocked false
                    }
                    val accepted = try {
                        sendSmtp(built.rfc822, built.recipients, via)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val uncertain = smtpUncertain(error.text)
                        val copy = sending.copyWith(uncertain = uncertain)
                        val saved = writeCopy(appContext, copy, accountId)
                        held = copy
                        notice = smtpFailureText(error.text) +
                            if (saved) "" else "\n" + appContext.getString(R.string.compose_copy_not_saved)
                        status = if (uncertain) appContext.getString(R.string.compose_send_uncertain) else notSentNotice
                        return@launchLocked false
                    }
                    storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                    if (account.sentMailbox.isNotEmpty()) try {
                        outbound(via).append(account.sentMailbox, accepted)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val copy = DeviceCopy(id, true, account.sentMailbox, built.recipients, accepted, via = via)
                        writeCopy(appContext, copy, accountId)
                        held = copy
                        notice = appContext.getString(R.string.compose_sent_copy_failed, account.sentMailbox, error.text)
                        offerNoCopy = true
                        status = acceptedNotice
                        return@launchLocked false
                    }
                    deleteCopy(appContext, id, accountId)
                    held = null
                    deliveryDone = true
                    notice = null
                    status = sentStatus(statusLeaf(account.sentMailbox))
                    removePostponedSource()
                    true
                }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("smoke-compose"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        composeTitle(
                            seed.kind,
                            stringResource(R.string.compose_reply),
                            stringResource(R.string.compose_reply_all),
                            stringResource(R.string.compose_forward),
                            stringResource(R.string.compose_new),
                        ),
                    )
                },
                navigationIcon = {
                    HelpIconButton(
                        label = stringResource(R.string.reader_back),
                        help = stringResource(R.string.help_compose_back),
                        page = "writing.html#back",
                        onClick = { requestClose() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                actions = {
                    if (seed.kind != ComposeKind.Bounce) {
                        if (!deliveryDone) {
                            val composeIcons = ArrayList<@Composable () -> Unit>(account.composeBar.toolbar.size)
                            for (action in account.composeBar.toolbar) {
                                when (action) {
                                    ComposeBarAction.Postpone -> composeIcons.add {
                                        HelpIconButton(
                                            label = stringResource(R.string.compose_postpone),
                                            help = stringResource(R.string.help_compose_postpone),
                                            page = "writing.html#postpone",
                                            onClick = { postponeDraft() },
                                            enabled = account.postponedMailbox.isNotEmpty(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Save,
                                                contentDescription = stringResource(R.string.compose_postpone),
                                            )
                                        }
                                    }
                                }
                            }
                            ToolbarIconRows(
                                maxRows = account.toolbarRows,
                                onRows = { count -> iconRows = count },
                                icons = composeIcons,
                            )
                        } else {
                            SideEffect { iconRows = 1 }
                        }
                        val sendDescription = if (held?.appendOnly == true) {
                            R.string.reader_retry
                        } else {
                            R.string.compose_send
                        }
                        if (sendDescription == R.string.compose_send) {
                            HelpIconButton(
                                label = stringResource(R.string.compose_send),
                                help = stringResource(R.string.help_compose_send),
                                page = "writing.html#send",
                                onClick = { sendMessage() },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = stringResource(sendDescription),
                                )
                            }
                        } else {
                            IconButton(onClick = { sendMessage() }) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = stringResource(sendDescription),
                                )
                            }
                        }
                        if (!deliveryDone) {
                            Box {
                                HelpIconButton(
                                    label = stringResource(R.string.reader_more),
                                    help = stringResource(R.string.help_compose_more),
                                    page = "writing.html#more",
                                    onClick = { overflow = true },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.reader_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflow,
                                    onDismissRequest = { overflow = false },
                                ) {
                                    for (entry in composeMenu(account.composeBar)) {
                                        when (entry) {
                                            is ComposeMenuEntry.Action -> when (entry.action) {
                                                ComposeBarAction.Postpone -> DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.compose_postpone)) },
                                                    onClick = {
                                                        overflow = false
                                                        postponeDraft()
                                                    },
                                                    enabled = account.postponedMailbox.isNotEmpty(),
                                                )
                                            }
                                            ComposeMenuEntry.Divider -> HorizontalDivider()
                                            ComposeMenuEntry.Customize -> DropdownMenuItem(
                                                text = { Text(stringResource(R.string.toolbar_customize)) },
                                                onClick = {
                                                    overflow = false
                                                    onCustomize(accountId)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        SideEffect { iconRows = 1 }
                    }
                },
                expandedHeight = toolbarExpandedHeight(iconRows),
                windowInsets = mailBarInsets(),
            )
        },
        contentWindowInsets = mailScreenInsets(),
    ) { padding ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        val stateText = status
        if (stateText != null) Text(stateText)
        val failure = notice
        if (failure != null) Text(failure)
        if (offerNoCopy && !deliveryDone) {
            TextButton(onClick = {
                offerNoCopy = false
                val kept = held
                if (kept != null && kept.appendOnly) {
                    // Already sent; only the copy is dropped.
                    deleteCopy(appContext, kept.id, accountId)
                    held = null
                    deliveryDone = true
                    notice = null
                    status = sentStatus(null)
                    scope.launch { removePostponedSource() }
                } else if (kept != null && noCopyRetry) {
                    noCopyRetry = false
                    val bare = kept.withoutSentCopy()
                    scope.launch { writeCopy(appContext, bare, accountId); held = bare; retryCopy(bare) }
                } else {
                    withoutCopy = true
                    sendMessage()
                }
            }) { Text(stringResource(R.string.compose_send_without_copy)) }
        }
        if (
            failure == acceptedNotice ||
            failure == notSentNotice ||
            stateText == acceptedNotice ||
            stateText == notSentNotice
        ) {
            TextButton(onClick = onOpenUnsent) { Text(stringResource(R.string.unsent_title)) }
        }
        val shownFrom = account.withSending(sending)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.reader_from, formatMailbox(shownFrom.displayName, shownFrom.email)),
                modifier = Modifier.weight(1f),
            )
            val rule = sending?.fromRule
            TextButton(onClick = { fromMenuOpen = true }) {
                Text(
                    if (pinnedFrom != null) stringResource(R.string.compose_from_pinned)
                    else if (rule.isNullOrEmpty() || rule == AccountDefaultRule) stringResource(R.string.compose_from_change)
                    else stringResource(R.string.compose_from_by_rule, rule),
                )
            }
            DropdownMenu(expanded = fromMenuOpen, onDismissRequest = { fromMenuOpen = false }) {
                val froms = (listOf(account.email) + account.altAddresses +
                    knownFroms(accountRules(account), accountDefaultRule(account))).distinctBy { it.trim().lowercase() }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.compose_from_by_rules)) },
                    onClick = { pinnedFrom = null; mismatchArmed = false; fromMenuOpen = false; resolveNow(account) },
                )
                for (from in froms) {
                    DropdownMenuItem(
                        text = { Text(from) },
                        onClick = { pinnedFrom = from; mismatchArmed = false; fromMenuOpen = false; resolveNow(account) },
                    )
                }
            }
        }
        if (seed.kind == ComposeKind.Bounce) {
            OutlinedTextField(
                value = bounceTo,
                onValueChange = { bounceTo = it },
                label = { Text(stringResource(R.string.compose_resent_to)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                pickerTarget = AddressTarget.Bounce
                pickerOpen = true
            }) { Text(stringResource(R.string.compose_address_book)) }
            if (pickerOpen) {
                AddressBookPicker(
                    onPicked = { picked ->
                        bounceTo = formatMailbox(picked.name, picked.email)
                        pickerOpen = false
                    },
                    onDismiss = { pickerOpen = false },
                )
                TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.compose_close)) }
            }
            TextButton(onClick = {
                launchLocked {
                    if (account.email.isEmpty()) {
                        notice = fromMissing
                        return@launchLocked false
                    }
                    if (account.bounceFcc && account.sentMailbox.isEmpty()) {
                        notice = sentMailboxMissing
                        return@launchLocked false
                    }
                    val recipients = bounceRecipientSpecs(bounceTo)
                    if (recipients.isEmpty()) {
                        notice = resentMissing
                        return@launchLocked false
                    }
                    if (seed.uids.isEmpty()) return@launchLocked false
                    val box = seed.mailbox ?: return@launchLocked false
                    val resentTo = recipients.joinToString(", ")
                    val resentFrom = formatMailbox(account.displayName, account.email)
                    val alreadyAccepted = bounceAcceptedSet()
                    when (bounceDelivery(account.emailSubmission)) {
                        BounceDelivery.Smtp -> {
                            for (uid in bounceUidsStillToSend(seed.uids, alreadyAccepted)) {
                                val original = inBox(box) { s -> s.fetchRfc822(uid) } // F116: pinned to the source folder
                                val bounced = buildBounce(
                                    original,
                                    resentFrom = resentFrom,
                                    resentTo = resentTo,
                                    resentDate = rfc822Date(),
                                    resentMessageId = newMessageId(account.email),
                                )
                                val id = UUID.randomUUID().toString()
                                val savedMailbox = if (account.bounceFcc) account.sentMailbox else ""
                                val sending = DeviceCopy(id, false, savedMailbox, recipients, bounced, uncertain = true)
                                if (!writeCopy(appContext, sending, accountId)) {
                                    notice = appContext.getString(R.string.compose_copy_not_saved)
                                    status = notSentNotice
                                    return@launchLocked false
                                }
                                val accepted = try {
                                    sendSmtp(bounced, recipients)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: MailFailure) {
                                    val copy = deviceCopyAfterSmtpFailure(sending, error.text)
                                    val saved = writeCopy(appContext, copy, accountId)
                                    held = copy
                                    notice = smtpFailureText(error.text) +
                                        if (saved) "" else "\n" + appContext.getString(R.string.compose_copy_not_saved)
                                    status = if (smtpUncertain(error.text)) appContext.getString(R.string.compose_send_uncertain) else notSentNotice
                                    return@launchLocked false
                                }
                                storeAcceptedFlags(listOf(uid))
                                if (!account.bounceFcc) {
                                    deleteCopy(appContext, id, accountId)
                                    addBounceAccepted(uid)
                                    continue
                                }
                                try {
                                    session.append(account.sentMailbox, accepted)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: MailFailure) {
                                    val copy = DeviceCopy(id, true, account.sentMailbox, recipients, accepted)
                                    writeCopy(appContext, copy, accountId)
                                    held = copy
                                    notice = error.text
                                    status = acceptedNotice
                                    addBounceAccepted(uid)
                                    return@launchLocked false
                                }
                                deleteCopy(appContext, id, accountId)
                                addBounceAccepted(uid)
                            }
                        }
                        BounceDelivery.Submission -> {
                            val password = try {
                                store.password()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                notice = error.message ?: notConnected
                                return@launchLocked false
                            }
                            for (uid in bounceUidsStillToSend(seed.uids, alreadyAccepted)) {
                                val original = inBox(box) { s -> s.fetchRfc822(uid) } // F116: pinned to the source folder
                                val bounced = buildBounce(
                                    original,
                                    resentFrom = resentFrom,
                                    resentTo = resentTo,
                                    resentDate = rfc822Date(),
                                    resentMessageId = newMessageId(account.email),
                                )
                                val id = UUID.randomUUID().toString()
                                val savedMailbox = if (account.bounceFcc) account.sentMailbox else ""
                                val bouncing = DeviceCopy(id, false, savedMailbox, recipients, bounced)
                                try {
                                    val offer = offerForAccount(account, platformJmapExchange(account.jmapCertPin, account.tlsPolicy()), authorization = jmapBasicAuthorization(account.username, password), accountKey = accountId)
                                    keepCopyUntilSubmitted(
                                        bouncing,
                                        { writeCopy(appContext, it, accountId) },
                                        { deleteCopy(appContext, it, accountId) },
                                    ) { jmapSend(
                                        offer,
                                        bounced,
                                        account.email,
                                        recipients,
                                        account.username,
                                        password,
                                        account.jmapCertPin,
                                        pinnedJmapUpload(account.jmapCertPin, account.tlsPolicy()),
                                        pinnedJmapPost(account.jmapCertPin, account.tlsPolicy()),
                                        // F149: Fcc on bounce off means no Sent copy (staged in Drafts instead).
                                        sentName = savedMailbox,
                                        saveCopy = savedMailbox.isNotEmpty(),
                                        draftsName = account.postponedMailbox,
                                        onNotice = { notice = it },
                                        policy = account.tlsPolicy(), accountKey = accountId,
                                    ) }
                                    storeAcceptedFlags(listOf(uid))
                                    addBounceAccepted(uid)
                                    continue
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: DeviceCopyNotSaved) {
                                    notice = appContext.getString(R.string.compose_copy_not_saved)
                                    status = notSentNotice
                                    return@launchLocked false
                                } catch (error: Exception) {
                                    jmapSendFailed(bouncing, error)
                                    return@launchLocked false
                                }
                            }
                        }
                    }
                    notice = null
                    deliveryDone = true
                    status = if (account.bounceFcc) {
                        sentStatus(statusLeaf(account.sentMailbox))
                    } else {
                        sentStatus(null)
                    }
                    true
                }
            }) { Text(stringResource(R.string.compose_bounce)) }
        } else {
            val showCc = copiesOpen || ccText.isNotBlank() || ccBuffer.isNotBlank()
            val showBcc = copiesOpen || bccText.isNotBlank() || bccBuffer.isNotBlank()
            AddressChips(
                label = stringResource(R.string.compose_to),
                stored = toText,
                buffer = toBuffer,
                onStored = { toText = it },
                onBuffer = { toBuffer = it },
                suggestions = completeAddress(toBuffer, addressSources),
                trailing = {
                    TextButton(onClick = { copiesOpen = !copiesOpen }) {
                        Text(
                            stringResource(
                                if (copiesOpen) R.string.compose_hide_copies else R.string.compose_show_copies,
                            ),
                        )
                    }
                },
            )
            if (showCc) {
                AddressChips(
                    label = stringResource(R.string.compose_cc),
                    stored = ccText,
                    buffer = ccBuffer,
                    onStored = { ccText = it },
                    onBuffer = { ccBuffer = it },
                    suggestions = completeAddress(ccBuffer, addressSources),
                )
            }
            if (showBcc) {
                AddressChips(
                    label = stringResource(R.string.compose_bcc),
                    stored = bccText,
                    buffer = bccBuffer,
                    onStored = { bccText = it },
                    onBuffer = { bccBuffer = it },
                    suggestions = completeAddress(bccBuffer, addressSources),
                )
            }
            if (
                offerReplyTo &&
                (seed.kind == ComposeKind.Reply || seed.kind == ComposeKind.ReplyAll)
            ) {
                AssistChip(
                    onClick = {
                        if (!useReplyTo) {
                            useReplyTo = true
                            toText = replyToLine
                            ccText = replyCcLine
                            toBuffer = ""
                            ccBuffer = ""
                        }
                    },
                    label = {
                        Text(
                            stringResource(
                                if (useReplyTo) {
                                    R.string.compose_using_reply_to
                                } else {
                                    R.string.compose_use_reply_to
                                },
                            ),
                        )
                    },
                )
            }
            if (
                showsPlaintextChip(
                    plaintextEntries,
                    chipProbe(toText, toBuffer),
                    chipProbe(ccText, ccBuffer),
                    chipProbe(bccText, bccBuffer),
                )
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.compose_plain_only)) },
                )
            }
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text(stringResource(R.string.compose_subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            for (name in headerNames(account.composerHeaders)) {
                OutlinedTextField(
                    value = headerValuesTyped[name.lowercase()].orEmpty(),
                    onValueChange = { headerValuesTyped[name.lowercase()] = it },
                    label = { Text(name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = {
                pickerTarget = AddressTarget.To
                pickerOpen = true
            }) { Text(stringResource(R.string.compose_to_book)) }
            if (showCc) {
                TextButton(onClick = {
                    pickerTarget = AddressTarget.Cc
                    pickerOpen = true
                }) { Text(stringResource(R.string.compose_cc_book)) }
            }
            if (showBcc) {
                TextButton(onClick = {
                    pickerTarget = AddressTarget.Bcc
                    pickerOpen = true
                }) { Text(stringResource(R.string.compose_bcc_book)) }
            }
            if (pickerOpen) {
                AddressBookPicker(
                    onPicked = { picked ->
                        val line = formatMailbox(picked.name, picked.email)
                        when (pickerTarget) {
                            AddressTarget.To -> toText = appendAddress(toText, line)
                            AddressTarget.Cc -> ccText = appendAddress(ccText, line)
                            AddressTarget.Bcc -> bccText = appendAddress(bccText, line)
                            AddressTarget.Bounce -> bounceTo = line
                        }
                        pickerOpen = false
                    },
                    onDismiss = { pickerOpen = false },
                )
                TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.compose_close)) }
            }
            for (row in forwardRows) {
                FilterChip(
                    selected = row.included,
                    onClick = {
                        forwardRows = forwardRows.map { item ->
                            if (item.key == row.key) item.toggle() else item
                        }
                    },
                    label = { Text(row.filename + " " + row.size) },
                    trailingIcon = {
                        IconButton(onClick = {
                            forwardRows = forwardRows.filter { it.key != row.key }
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.compose_remove),
                            )
                        }
                    },
                )
            }
            OutlinedTextField(
                value = bodyField,
                onValueChange = { bodyField = it },
                label = { Text(stringResource(R.string.compose_body)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            confirmButton = {
                TextButton(
                    onClick = { onDone() },
                    modifier = Modifier.testTag("smoke-compose-discard"),
                ) { Text(stringResource(R.string.unsent_discard)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { discardOpen = false }) {
                        Text(stringResource(R.string.compose_keep_editing))
                    }
                    TextButton(
                        onClick = {
                            discardOpen = false
                            postponeDraft()
                        },
                        enabled = account.postponedMailbox.isNotEmpty(),
                    ) { Text(stringResource(R.string.compose_postpone)) }
                }
            },
        )
    }
}

internal fun oppositeForwardLabel(inlineLabel: String, attachmentLabel: String, settingOn: Boolean): String =
    if (settingOn) inlineLabel else attachmentLabel

internal fun replyCursorBody(quoted: String, above: Boolean): String {
    val block = quoted.trimEnd('\n')
    return if (above) "\n$block" else "$block\n"
}

internal fun forwardHeaderBody(header: String, peeked: String): String = quotePart(header, peeked)

internal fun composeIsDirty(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    baselineTo: String,
    baselineCc: String,
    baselineBcc: String,
    baselineSubject: String,
    baselineBody: String,
    rowRemoved: Boolean,
    header: String = "",
    baselineHeader: String = "",
    included: String = "",
    baselineIncluded: String = "",
    pinnedFrom: String? = null,
    baselinePinnedFrom: String? = null,
    useReplyTo: Boolean = false,
    baselineUseReplyTo: Boolean = false,
): Boolean {
    if (rowRemoved) return true
    if (header != baselineHeader) return true
    if (included != baselineIncluded) return true
    if (pinnedFrom != baselinePinnedFrom) return true
    if (useReplyTo != baselineUseReplyTo) return true
    return to != baselineTo ||
        cc != baselineCc ||
        bcc != baselineBcc ||
        subject != baselineSubject ||
        body != baselineBody
}

internal fun postponedUidToRemove(kind: ComposeKind, sent: Boolean, uid: Long?): Long? {
    if (kind == ComposeKind.ResumePostpone && sent && uid != null) return uid
    return null
}

internal fun smtpAcceptFlags(kind: ComposeKind): Set<String> = when (kind) {
    ComposeKind.Reply, ComposeKind.ReplyAll -> setOf("\\Answered")
    ComposeKind.Forward, ComposeKind.Bounce -> setOf("\$Forwarded")
    ComposeKind.New, ComposeKind.ResumePostpone -> emptySet()
}

internal fun composeTitle(
    kind: ComposeKind,
    reply: String,
    replyAll: String,
    forward: String,
    compose: String,
): String = when (kind) {
    ComposeKind.Reply -> reply
    ComposeKind.ReplyAll -> replyAll
    ComposeKind.Forward -> forward
    ComposeKind.New, ComposeKind.Bounce, ComposeKind.ResumePostpone -> compose
}

internal data class ChipCommit(val stored: String, val buffer: String)

internal fun commitChip(stored: String, buffer: String): ChipCommit {
    val cut = chipSeparatorAt(buffer)
    if (cut < 0) return ChipCommit(stored, buffer)
    val token = buffer.substring(0, cut).trim()
    var rest = buffer.substring(cut + 1)
    if (buffer[cut] == '\r' && rest.startsWith("\n")) rest = rest.substring(1)
    rest = rest.trimStart(' ', '\t')
    val next = if (token.isEmpty()) stored else appendAddress(stored, token)
    return commitChip(next, rest)
}

internal fun removeChipAddress(stored: String, address: String): String {
    val parts = splitAddresses(stored)
    val drop = parts.indexOfFirst { it == address }
    if (drop < 0) return stored
    return parts.filterIndexed { index, _ -> index != drop }.joinToString(", ")
}

private fun chipSeparatorAt(buffer: String): Int {
    var quoted = false
    var angle = 0
    var escaped = false
    for (i in buffer.indices) {
        val ch = buffer[i]
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\' && quoted) {
            escaped = true
            continue
        }
        if (ch == '"') {
            quoted = !quoted
            continue
        }
        if (!quoted && ch == '<') angle++
        if (!quoted && ch == '>' && angle > 0) angle--
        if (!quoted && angle == 0 && (ch == ',' || ch == '\n' || ch == '\r')) return i
    }
    return -1
}

private fun replyToDiffers(replyTo: List<String>, from: List<String>): Boolean {
    if (replyTo.isEmpty()) return false
    fun specs(list: List<String>): Set<String> =
        list.map { addrSpec(it).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return specs(replyTo) != specs(from)
}

private fun chipProbe(stored: String, buffer: String): String {
    val pending = buffer.trim()
    if (pending.isEmpty()) return stored
    return appendAddress(stored, pending)
}

internal fun bounceUidsStillToSend(uids: List<Long>, accepted: Set<Long>): List<Long> {
    val still = ArrayList<Long>()
    for (uid in uids) {
        if (uid !in accepted) still.add(uid)
    }
    return still
}

internal fun bounceRecipientSpecs(field: String): List<String> {
    val specs = ArrayList<String>()
    for (piece in splitAddresses(field)) {
        val spec = addrSpec(piece).trim()
        if (spec.isNotEmpty()) specs.add(spec)
    }
    return specs
}

internal fun mailboxLeaf(mailbox: String): String {
    val slash = mailbox.lastIndexOf('/')
    if (slash >= 0) return mailbox.substring(slash + 1)
    val dot = mailbox.lastIndexOf('.')
    if (dot >= 0) return mailbox.substring(dot + 1)
    return mailbox
}

internal fun mailboxLeaf(mailbox: String, delimiter: Char): String {
    val at = mailbox.lastIndexOf(delimiter)
    if (at >= 0) return mailbox.substring(at + 1)
    return mailbox
}

private fun showsPlaintextChip(
    entries: List<AlpineEntry>,
    to: String,
    cc: String,
    bcc: String,
): Boolean {
    for (line in listOf(to, cc, bcc)) {
        for (address in splitAddresses(line)) {
            if (addressMarkedPlaintext(entries, address)) return true
        }
    }
    return false
}

private fun appendAddress(current: String, next: String): String {
    val trimmed = current.trim()
    if (trimmed.isEmpty()) return next
    return "$trimmed, $next"
}

private fun commitSuggestion(stored: String, suggestion: AddressSuggestion): String {
    if (!suggestion.distribution) {
        val name = suggestion.displayName.ifEmpty { suggestion.nickname }
        return appendAddress(stored, formatMailbox(name, suggestion.email))
    }
    var next = stored
    for (member in suggestion.members) {
        next = appendAddress(next, formatMailbox(member.name, member.email))
    }
    return next
}

/**
 * F92(c)/F135: the Fcc folder for mail routed through another account. That account's server gets its own Sent
 * folder, never this account's Sent name; a folder the rule names explicitly (or none) is used as given.
 */
internal fun viaFccMailbox(ownSent: String, ruleFcc: String, viaSent: String): String =
    if (ruleFcc == ownSent) viaSent else ruleFcc
