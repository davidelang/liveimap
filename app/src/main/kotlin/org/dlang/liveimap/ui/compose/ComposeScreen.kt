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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import java.util.UUID
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
import org.dlang.liveimap.ui.reader.htmlAsText
import org.dlang.liveimap.ui.contacts.AddressBookPicker
import org.dlang.liveimap.ui.contacts.AlpineEntry
import org.dlang.liveimap.ui.contacts.addressMarkedPlaintext
import org.dlang.liveimap.ui.contacts.loadAlpineBook
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets

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
    trailing: @Composable (() -> Unit)? = null,
) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        for (address in splitAddresses(stored)) {
            InputChip(
                selected = false,
                onClick = { onStored(removeChipAddress(stored, address)) },
                label = { Text(address) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = "Remove")
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    seed: ComposeSeed,
    onDone: () -> Unit,
    unsentId: String? = null,
    retryOnOpen: Boolean = false,
    onOpenUnsent: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
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
    var offerReplyTo by rememberSaveable { mutableStateOf(false) }
    var useReplyTo by rememberSaveable { mutableStateOf(false) }
    var replyToLine by rememberSaveable { mutableStateOf("") }
    var replyCcLine by rememberSaveable { mutableStateOf("") }
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
        val restore = selectedMailbox
        val entries = try {
            val loaded = loadAlpineBook(session, mailbox)
            if (loaded.notice != null) emptyList() else loaded.entries
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            emptyList()
        }
        if (restore != null && restore != mailbox) {
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

    suspend fun quotedBody(tree: MimePart, view: BodyView, uid: Long): String {
        val plain = textPart(tree, "plain")
        if (plain != null) {
            return peekWireBytes(plain.size, 4096, false) { offset, length ->
                session.peekPart(uid, plain.section, offset, length)
            }.toString(Charsets.UTF_8)
        }
        if (view == BodyView.PlainOrHtml || view == BodyView.PlainOrText) {
            val html = textPart(tree, "html")
            if (html != null) {
                val bytes = peekWireBytes(html.size, 4096, false) { offset, length ->
                    session.peekPart(uid, html.section, offset, length)
                }
                return htmlAsText(bytes.toString(Charsets.UTF_8))
            }
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
            val shown = "\n" + forwardHeaderBody(
                headerValues(parsed, "From").firstOrNull().orEmpty(),
                headerValues(parsed, "Date").firstOrNull().orEmpty(),
                headerValues(parsed, "Subject").firstOrNull().orEmpty(),
                quote,
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
                if (uid == null) throw MailFailure("fetch failed")
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
            notice = "Postponed copy is still in $box"
        }
    }

    suspend fun retryCopy(copy: DeviceCopy): Boolean {
        if (copy.appendOnly) {
            if (copy.mailbox.isEmpty()) {
                notice = "Sent mailbox is not set"
                return false
            }
            try {
                session.append(copy.mailbox, copy.bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                status = "Accepted but not saved"
                return false
            }
            deleteCopy(appContext, copy.id)
            if (held?.id == copy.id) held = null
            notice = null
            deliveryDone = true
            status = "Sent · saved to ${mailboxLeaf(copy.mailbox)}"
            removePostponedSource()
            return true
        }
        try {
            session.smtpSend(copy.bytes, copy.recipients)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
            status = "Not sent"
            return false
        }
        storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
        if (copy.mailbox.isNotEmpty()) {
            try {
                session.append(copy.mailbox, copy.bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                val saved = DeviceCopy(copy.id, true, copy.mailbox, copy.recipients, copy.bytes)
                writeCopy(appContext, saved)
                if (held?.id == copy.id) held = saved
                notice = error.text
                status = "Accepted but not saved"
                return false
            }
        }
        deleteCopy(appContext, copy.id)
        if (held?.id == copy.id) held = null
        notice = null
        deliveryDone = true
        status = if (copy.mailbox.isNotEmpty()) "Sent · saved to ${mailboxLeaf(copy.mailbox)}" else null
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
                notice = "Postponed mailbox is not set"
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
            status = "Saved to ${mailboxLeaf(account.postponedMailbox)}"
            removePostponedSource()
            true
        }
    }

    fun loadStoredCopy(id: String): DeviceCopy? {
        val copy = readCopies(appContext).firstOrNull { it.id == id }
        if (copy == null) {
            notice = "That unsent copy is no longer on this device."
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
                notice = error.message ?: "not connected"
                return@withLock
            }
            account = settings
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
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
                        status = "Sent · saved to ${mailboxLeaf(account.sentMailbox)}"
                        return@launchLocked true
                    }
                    if (account.email.isEmpty()) {
                        notice = "From address is not set"
                        return@launchLocked false
                    }
                    if (account.sentMailbox.isEmpty()) {
                        notice = "Sent mailbox is not set"
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
                    try {
                        session.smtpSend(built.rfc822, built.recipients)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val copy = DeviceCopy(id, false, account.sentMailbox, built.recipients, built.rfc822)
                        writeCopy(appContext, copy)
                        held = copy
                        notice = error.text
                        status = "Not sent"
                        return@launchLocked false
                    }
                    storeAcceptedFlags(seed.uids.ifEmpty { listOfNotNull(sourceUid) })
                    try {
                        session.append(account.sentMailbox, built.rfc822)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        val copy = DeviceCopy(id, true, account.sentMailbox, built.recipients, built.rfc822)
                        writeCopy(appContext, copy)
                        held = copy
                        notice = error.text
                        status = "Accepted but not saved"
                        return@launchLocked false
                    }
                    deleteCopy(appContext, id)
                    held = null
                    deliveryDone = true
                    notice = null
                    status = "Sent · saved to ${mailboxLeaf(account.sentMailbox)}"
                    removePostponedSource()
                    true
                }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(composeTitle(seed.kind)) },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (seed.kind != ComposeKind.Bounce) {
                        IconButton(onClick = { sendMessage() }) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = if (held?.appendOnly == true) "Retry" else "Send",
                            )
                        }
                        if (!deliveryDone) {
                            Box {
                                IconButton(onClick = { overflow = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "More",
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflow,
                                    onDismissRequest = { overflow = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Postpone") },
                                        onClick = {
                                            overflow = false
                                            postponeDraft()
                                        },
                                        enabled = account.postponedMailbox.isNotEmpty(),
                                    )
                                }
                            }
                        }
                    }
                },
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
            failure == "Accepted but not saved" ||
            failure == "Not sent" ||
            stateText == "Accepted but not saved" ||
            stateText == "Not sent"
        ) {
            TextButton(onClick = onOpenUnsent) { Text("Unsent") }
        }
        Text("From: ${formatMailbox(account.displayName, account.email)}")
        if (seed.kind == ComposeKind.Bounce) {
            OutlinedTextField(
                value = bounceTo,
                onValueChange = { bounceTo = it },
                label = { Text("Resent-To") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                pickerTarget = AddressTarget.Bounce
                pickerOpen = true
            }) { Text("Address book") }
            if (pickerOpen) {
                AddressBookPicker(
                    onPicked = { picked ->
                        bounceTo = formatMailbox(picked.name, picked.email)
                        pickerOpen = false
                    },
                    onDismiss = { pickerOpen = false },
                )
                TextButton(onClick = { pickerOpen = false }) { Text("Close") }
            }
            TextButton(onClick = {
                launchLocked {
                    if (account.email.isEmpty()) {
                        notice = "From address is not set"
                        return@launchLocked false
                    }
                    if (account.bounceFcc && account.sentMailbox.isEmpty()) {
                        notice = "Sent mailbox is not set"
                        return@launchLocked false
                    }
                    val resentTo = bounceTo.trim()
                    if (resentTo.isEmpty()) {
                        notice = "Resent-To is not set"
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
                        try {
                            session.smtpSend(bounced, listOf(recipient))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: MailFailure) {
                            val copy = DeviceCopy(id, false, savedMailbox, listOf(recipient), bounced)
                            writeCopy(appContext, copy)
                            held = copy
                            notice = error.text
                            status = "Not sent"
                            return@launchLocked false
                        }
                        storeAcceptedFlags(listOf(uid))
                        if (!account.bounceFcc) continue
                        try {
                            session.append(account.sentMailbox, bounced)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: MailFailure) {
                            val copy = DeviceCopy(id, true, account.sentMailbox, listOf(recipient), bounced)
                            writeCopy(appContext, copy)
                            held = copy
                            notice = error.text
                            status = "Accepted but not saved"
                            return@launchLocked false
                        }
                    }
                    notice = null
                    deliveryDone = true
                    status = if (account.bounceFcc) "Sent · saved to ${mailboxLeaf(account.sentMailbox)}" else null
                    true
                }
            }) { Text("Bounce") }
        } else {
            val showCc = copiesOpen || ccText.isNotBlank() || ccBuffer.isNotBlank()
            val showBcc = copiesOpen || bccText.isNotBlank() || bccBuffer.isNotBlank()
            AddressChips(
                label = "To",
                stored = toText,
                buffer = toBuffer,
                onStored = { toText = it },
                onBuffer = { toBuffer = it },
                trailing = {
                    TextButton(onClick = { copiesOpen = !copiesOpen }) {
                        Text(if (copiesOpen) "Hide Cc/Bcc" else "Cc/Bcc")
                    }
                },
            )
            if (showCc) {
                AddressChips(
                    label = "Cc",
                    stored = ccText,
                    buffer = ccBuffer,
                    onStored = { ccText = it },
                    onBuffer = { ccBuffer = it },
                )
            }
            if (showBcc) {
                AddressChips(
                    label = "Bcc",
                    stored = bccText,
                    buffer = bccBuffer,
                    onStored = { bccText = it },
                    onBuffer = { bccBuffer = it },
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
                    label = { Text(if (useReplyTo) "Using Reply-To" else "Use Reply-To?") },
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
                    label = { Text("Plain text only") },
                )
            }
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                pickerTarget = AddressTarget.To
                pickerOpen = true
            }) { Text("To address book") }
            if (showCc) {
                TextButton(onClick = {
                    pickerTarget = AddressTarget.Cc
                    pickerOpen = true
                }) { Text("Cc address book") }
            }
            if (showBcc) {
                TextButton(onClick = {
                    pickerTarget = AddressTarget.Bcc
                    pickerOpen = true
                }) { Text("Bcc address book") }
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
                TextButton(onClick = { pickerOpen = false }) { Text("Close") }
            }
            for (row in forwardRows) {
                FilterChip(
                    selected = row.included,
                    onClick = {
                        forwardRows = forwardRows.map { item ->
                            if (item.key == row.key) item.toggle() else item
                        }
                    },
                    label = { Text("${row.filename} ${row.size}") },
                    trailingIcon = {
                        IconButton(onClick = {
                            forwardRows = forwardRows.filter { it.key != row.key }
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    },
                )
            }
            OutlinedTextField(
                value = bodyField,
                onValueChange = { bodyField = it },
                label = { Text("Body") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text("Discard draft?") },
            confirmButton = {
                TextButton(onClick = { onDone() }) { Text("Discard") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { discardOpen = false }) { Text("Keep editing") }
                    TextButton(
                        onClick = {
                            discardOpen = false
                            postponeDraft()
                        },
                        enabled = account.postponedMailbox.isNotEmpty(),
                    ) { Text("Postpone") }
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

internal fun forwardHeaderBody(from: String, date: String, subject: String, peeked: String): String {
    val header = "From: ${from.trim()}\nDate: ${date.trim()}\nSubject: ${subject.trim()}"
    return quotePart(header, peeked)
}

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

internal fun composeTitle(kind: ComposeKind): String = when (kind) {
    ComposeKind.Reply -> "Reply"
    ComposeKind.ReplyAll -> "Reply all"
    ComposeKind.Forward -> "Forward"
    ComposeKind.New, ComposeKind.Bounce, ComposeKind.ResumePostpone -> "Compose"
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

private fun appendAddress(current: String, next: String): String {
    val trimmed = current.trim()
    if (trimmed.isEmpty()) return next
    return "$trimmed, $next"
}
