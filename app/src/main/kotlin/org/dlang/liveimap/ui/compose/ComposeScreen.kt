package org.dlang.liveimap.ui.compose

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import java.util.UUID
import org.dlang.liveimap.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.pineSourceId
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
    )
}

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
    onCustomize: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val acceptedNotice = stringResource(R.string.compose_accepted)
    val notSentNotice = stringResource(R.string.unsent_not_sent)
    val sentMailboxMissing = stringResource(R.string.compose_sent_mailbox)
    val postponedMailboxMissing = stringResource(R.string.compose_postponed_mailbox)
    val fromMissing = stringResource(R.string.compose_from_missing)
    val resentMissing = stringResource(R.string.compose_resent_missing)
    val draftNotSaved = stringResource(R.string.compose_draft_not_saved)
    val unsentGone = stringResource(R.string.compose_unsent_gone)
    val notConnected = stringResource(R.string.reader_not_connected)
    val fetchFailed = stringResource(R.string.compose_fetch_failed)
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    var account by remember { mutableStateOf(AccountSettings()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selectedMailbox by remember { mutableStateOf<String?>(null) }
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
    var draftLoaded by rememberSaveable { mutableStateOf(false) }
    var retryArmedFor by rememberSaveable { mutableStateOf("") }
    var forwardRows by remember { mutableStateOf<List<ForwardRow>>(emptyList()) }
    var sourceUid by remember { mutableStateOf<Long?>(null) }
    var deliveryDone by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<DeviceCopy?>(null) }
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
    var savedDraftUid by rememberSaveable { mutableLongStateOf(0L) }
    val forwardOnce = rememberSaveable {
        val code = ForwardOnce.code
        ForwardOnce.code = -1
        code
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

    suspend fun ensureMailbox(box: String?) {
        if (box == null) return
        if (selectedMailbox != box) {
            session.select(box)
            selectedMailbox = box
        }
    }

    suspend fun readPlaintextBook(mailbox: String): List<AlpineEntry> {
        if (mailbox.isEmpty()) return emptyList()
        val cached = pineBookIsLoaded()
        val restore = selectedMailbox
        val entries = try {
            pineEntriesOnce(session, mailbox)
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            emptyList()
        }
        if (!cached && restore != null && restore != mailbox) {
            try {
                session.select(restore)
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
                selectedMailbox = null
            }
        }
        return entries
    }

    suspend fun sourcesForCompose(settings: AccountSettings): List<CompletionSource> {
        val ids = settings.completionSources
        val pine = if (pineSourceId !in ids) {
            emptyList()
        } else {
            try {
                pineEntriesOnce(session, settings.addressBookMailbox)
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
                emptyList()
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
                session.peekPart(uid, part.section, offset, length)
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

    suspend fun loadReply(settings: AccountSettings, replyAll: Boolean) {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        ensureMailbox(box)
        val parsed = parseRfc822(session.fetchRfc822(uid))
        val quote = quotedBody(session.fetchStructure(uid), settings.bodyView, uid)
        val draft = replyDraft(replyAll, parsed, settings.email, quote)
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
        val chosen = if (useReplyTo) replyRoute else fromRoute
        val shown = replyCursorBody(draft.body, settings.replyAboveQuote)
        applyDraft(
            ReplyDraft(chosen.first, chosen.second, draft.subject, draft.inReplyTo, draft.references, shown),
            cursor = if (settings.replyAboveQuote) 0 else shown.length,
        )
    }

    suspend fun loadForward(settings: AccountSettings) {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        ensureMailbox(box)
        sourceUid = uid
        if (draftLoaded && forwardRows.isNotEmpty()) return
        val asAttachment = when (forwardOnce) {
            1 -> true
            0 -> false
            else -> settings.forwardAsAttachment
        }
        if (asAttachment) {
            val original = session.fetchRfc822(uid)
            if (!draftLoaded) {
                val parsed = parseRfc822(original)
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
                ),
            )
            return
        }
        val tree = if (draftLoaded) {
            session.fetchStructure(uid)
        } else {
            val parsed = parseRfc822(session.fetchRfc822(uid))
            val fetched = session.fetchStructure(uid)
            val quote = quotedBody(fetched, settings.bodyView, uid)
            val draft = forwardDraft(parsed, quote)
            val header = appContext.getString(
                R.string.compose_quote_header,
                headerValues(parsed, "From").firstOrNull().orEmpty().trim(),
                headerValues(parsed, "Date").firstOrNull().orEmpty().trim(),
                headerValues(parsed, "Subject").firstOrNull().orEmpty().trim(),
            )
            val shown = "\n" + forwardHeaderBody(header, quote)
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
            )
        }
    }

    suspend fun loadResume() {
        val box = seed.mailbox ?: return
        val uid = seed.uids.firstOrNull() ?: return
        ensureMailbox(box)
        sourceUid = uid
        if (draftLoaded && forwardRows.isNotEmpty()) return
        applyEditor(loadEditor(session.fetchRfc822(uid)))
    }

    suspend fun assemble(settings: AccountSettings): BuiltMail {
        val parts = ArrayList<OutgoingPart>()
        val uid = sourceUid
        for (row in forwardRows) {
            if (!row.included) continue
            val payload = if (row.bytes != null) {
                row.bytes to row.wireBase64
            } else {
                if (uid == null) throw MailFailure(fetchFailed)
                val fetched = peekWireBytes(row.size, 65536, true) { offset, length ->
                    session.peekPart(uid, row.section, offset, length)
                }
                fetched to looksLikeBase64(fetched)
            }
            parts.add(OutgoingPart(row.filename, row.mediaType, payload.first, payload.second))
        }
        return buildPlain(
            PlainMessage(
                fromName = settings.displayName,
                fromEmail = settings.email,
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
            ),
        )
    }

    suspend fun storeAcceptedFlags(uids: List<Long>) {
        val flags = smtpAcceptFlags(seed.kind)
        val box = seed.mailbox
        if (flags.isEmpty() || box == null || uids.isEmpty()) return
        try {
            ensureMailbox(box)
            session.storeFlags(uids, flags, emptySet())
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
        }
    }

    suspend fun removePostponedSource() {
        val uid = postponedUidToRemove(seed.kind, true, sourceUid) ?: return
        val box = seed.mailbox ?: account.postponedMailbox
        try {
            ensureMailbox(box)
            session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
            session.uidExpunge(listOf(uid))
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            notice = appContext.getString(R.string.compose_postponed_remains, box)
        }
    }

    suspend fun sendSmtp(bytes: ByteArray, recipients: List<String>): ByteArray {
        try {
            session.smtpSend(bytes, recipients)
            return bytes
        } catch (error: MailFailure) {
            if (error.text != "8bitmime") throw error
        }
        val rewritten = withoutEightBit(bytes)
        session.smtpSend(rewritten, recipients)
        return rewritten
    }

    suspend fun retryCopy(copy: DeviceCopy): Boolean {
        if (copy.appendOnly) {
            if (copy.mailbox.isEmpty()) {
                notice = sentMailboxMissing
                return false
            }
            try {
                session.append(copy.mailbox, copy.bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                status = acceptedNotice
                return false
            }
            deleteCopy(appContext, copy.id)
            if (held?.id == copy.id) held = null
            notice = null
            deliveryDone = true
            status = appContext.getString(R.string.compose_sent_saved, mailboxLeaf(copy.mailbox))
            removePostponedSource()
            return true
        }
        val accepted = try {
            sendSmtp(copy.bytes, copy.recipients)
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
                session.append(copy.mailbox, accepted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                val saved = DeviceCopy(copy.id, true, copy.mailbox, copy.recipients, accepted)
                writeCopy(appContext, saved)
                if (held?.id == copy.id) held = saved
                notice = error.text
                status = acceptedNotice
                return false
            }
        }
        deleteCopy(appContext, copy.id)
        if (held?.id == copy.id) held = null
        notice = null
        deliveryDone = true
        status = if (copy.mailbox.isNotEmpty()) {
            appContext.getString(R.string.compose_sent_saved, mailboxLeaf(copy.mailbox))
        } else {
            null
        }
        removePostponedSource()
        return true
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
            if (leave) onDone()
        }
    }

    fun captureBaseline() {
        baseTo = toText
        baseCc = ccText
        baseBcc = bccText
        baseSubject = subject
        baseBody = bodyField.text
        baseRowKeyText = forwardRows.joinToString("\n") { it.key }
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
                deleteCopy(appContext, current.id)
                held = null
            }
            notice = null
            status = appContext.getString(R.string.compose_saved_to, mailboxLeaf(account.postponedMailbox))
            removePostponedSource()
            true
        }
    }

    suspend fun saveDraftInBackground() {
        if (account.postponedMailbox.isEmpty()) return
        if (toText.isBlank() && subject.isBlank() && bodyField.text.isBlank()) return
        try {
            gate.withLock {
                val built = try {
                    assemble(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    notice = draftNotSaved
                    return@withLock
                }
                val uid = try {
                    session.appendReturningUid(
                        account.postponedMailbox,
                        built.rfc822,
                        setOf("\\Draft"),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    notice = draftNotSaved
                    return@withLock
                }
                val previous = when {
                    savedDraftUid != 0L -> savedDraftUid
                    seed.kind == ComposeKind.ResumePostpone -> sourceUid ?: 0L
                    else -> 0L
                }
                if (previous != 0L && previous != uid) {
                    try {
                        ensureMailbox(account.postponedMailbox)
                        session.storeFlags(listOf(previous), setOf("\\Deleted"), emptySet())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: MailFailure) {
                    }
                }
                if (uid != 0L) savedDraftUid = uid
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            notice = draftNotSaved
        }
    }

    SideEffect {
        ComposeBackgroundSave.hook = { saveDraftInBackground() }
    }
    DisposableEffect(Unit) {
        onDispose { ComposeBackgroundSave.hook = null }
    }

    fun loadStoredCopy(id: String): DeviceCopy? {
        val copy = readCopies(appContext).firstOrNull { it.id == id }
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
                session.open(settings)
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
                        ComposeKind.New -> Unit
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
                    val saved = held
                    if (saved != null && saved.appendOnly) {
                        return@launchLocked retryCopy(saved)
                    }
                    if (deliveryDone) {
                        notice = null
                        status = appContext.getString(R.string.compose_sent_saved, mailboxLeaf(account.sentMailbox))
                        return@launchLocked true
                    }
                    if (account.email.isEmpty()) {
                        notice = fromMissing
                        return@launchLocked false
                    }
                    if (account.sentMailbox.isEmpty()) {
                        notice = sentMailboxMissing
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
                    val id = saved?.id ?: UUID.randomUUID().toString()
                    val accepted = try {
                        sendSmtp(built.rfc822, built.recipients)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val copy = DeviceCopy(id, false, account.sentMailbox, built.recipients, built.rfc822)
                        writeCopy(appContext, copy)
                        held = copy
                        notice = error.text
                        status = notSentNotice
                        return@launchLocked false
                    }
                    storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                    try {
                        session.append(account.sentMailbox, accepted)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val copy = DeviceCopy(id, true, account.sentMailbox, built.recipients, accepted)
                        writeCopy(appContext, copy)
                        held = copy
                        notice = error.text
                        status = acceptedNotice
                        return@launchLocked false
                    }
                    deleteCopy(appContext, id)
                    held = null
                    deliveryDone = true
                    notice = null
                    status = appContext.getString(R.string.compose_sent_saved, mailboxLeaf(account.sentMailbox))
                    removePostponedSource()
                    true
                }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                    IconButton(onClick = { requestClose() }) {
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
                                        IconButton(
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
                        IconButton(onClick = { sendMessage() }) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = stringResource(
                                    if (held?.appendOnly == true) {
                                        R.string.reader_retry
                                    } else {
                                        R.string.compose_send
                                    },
                                ),
                            )
                        }
                        if (!deliveryDone) {
                            Box {
                                IconButton(onClick = { overflow = true }) {
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
                                                    onCustomize()
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
        if (
            failure == acceptedNotice ||
            failure == notSentNotice ||
            stateText == acceptedNotice ||
            stateText == notSentNotice
        ) {
            TextButton(onClick = onOpenUnsent) { Text(stringResource(R.string.unsent_title)) }
        }
        Text(stringResource(R.string.reader_from, formatMailbox(account.displayName, account.email)))
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
                    val resentTo = bounceTo.trim()
                    if (resentTo.isEmpty()) {
                        notice = resentMissing
                        return@launchLocked false
                    }
                    if (seed.uids.isEmpty()) return@launchLocked false
                    val box = seed.mailbox ?: return@launchLocked false
                    ensureMailbox(box)
                    val recipient = addrSpec(resentTo).ifEmpty { resentTo }
                    val resentFrom = formatMailbox(account.displayName, account.email)
                    for (uid in seed.uids) {
                        val original = session.fetchRfc822(uid)
                        val bounced = buildBounce(
                            original,
                            resentFrom = resentFrom,
                            resentTo = resentTo,
                            resentDate = rfc822Date(),
                            resentMessageId = newMessageId(account.email),
                        )
                        val id = UUID.randomUUID().toString()
                        val savedMailbox = if (account.bounceFcc) account.sentMailbox else ""
                        val accepted = try {
                            sendSmtp(bounced, listOf(recipient))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: MailFailure) {
                            val copy = DeviceCopy(id, false, savedMailbox, listOf(recipient), bounced)
                            writeCopy(appContext, copy)
                            held = copy
                            notice = error.text
                            status = notSentNotice
                            return@launchLocked false
                        }
                        storeAcceptedFlags(listOf(uid))
                        if (!account.bounceFcc) continue
                        try {
                            session.append(account.sentMailbox, accepted)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: MailFailure) {
                            val copy = DeviceCopy(id, true, account.sentMailbox, listOf(recipient), accepted)
                            writeCopy(appContext, copy)
                            held = copy
                            notice = error.text
                            status = acceptedNotice
                            return@launchLocked false
                        }
                    }
                    notice = null
                    deliveryDone = true
                    status = if (account.bounceFcc) {
                        appContext.getString(R.string.compose_sent_saved, mailboxLeaf(account.sentMailbox))
                    } else {
                        null
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
                TextButton(onClick = { onDone() }) { Text(stringResource(R.string.unsent_discard)) }
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

/** Next compose only. -1 uses the setting, 0 is inline, 1 is message/rfc822. */
internal object ForwardOnce {
    var code: Int = -1
}

internal fun armForwardOnce(asAttachment: Boolean) {
    ForwardOnce.code = if (asAttachment) 1 else 0
}

internal fun oppositeForwardLabel(settingOn: Boolean): String =
    if (settingOn) "Forward inline" else "Forward as attachment"

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
): Boolean {
    if (rowRemoved) return true
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

internal fun mailboxLeaf(mailbox: String): String {
    val slash = mailbox.lastIndexOf('/')
    if (slash >= 0) return mailbox.substring(slash + 1)
    val dot = mailbox.lastIndexOf('.')
    if (dot >= 0) return mailbox.substring(dot + 1)
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

internal object ComposeBackgroundSave {
    var hook: (suspend () -> Unit)? = null
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
