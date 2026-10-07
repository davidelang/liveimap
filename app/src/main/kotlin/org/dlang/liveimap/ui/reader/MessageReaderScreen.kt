package org.dlang.liveimap.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.ui.compose.armForwardOnce
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.ReaderAction
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.settings.moveCommandKind
import org.dlang.liveimap.ui.contacts.AlpineEntry
import org.dlang.liveimap.ui.contacts.PineRead
import org.dlang.liveimap.ui.contacts.PineWriteResult
import org.dlang.liveimap.ui.contacts.bookHasAddress
import org.dlang.liveimap.ui.contacts.readPineBook
import org.dlang.liveimap.ui.contacts.writePineBook
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.ui.DebugConnectionStatus
import org.dlang.liveimap.ui.folder.MailboxChooser
import org.dlang.liveimap.ui.index.IndexModel
import org.dlang.liveimap.ui.index.MailboxTitle
import org.dlang.liveimap.ui.index.alternateDeletePolicies
import org.dlang.liveimap.ui.index.effectiveDeletePolicy
import org.dlang.liveimap.ui.index.formatIndexDate
import org.dlang.liveimap.ui.index.MailboxTitleLines
import org.dlang.liveimap.ui.index.mailboxTitleFor
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets
import org.dlang.liveimap.ui.index.MailUndo
import org.dlang.liveimap.ui.index.OpenMessageOrder
import org.dlang.liveimap.ui.index.followingUid
import org.dlang.liveimap.ui.index.mailUndoText
import org.dlang.liveimap.ui.compose.attachmentParts
import org.dlang.liveimap.ui.compose.nextWireCount
import org.dlang.liveimap.ui.compose.textPart
import org.dlang.liveimap.ui.toolbar.ReaderToolbarAction
import org.dlang.liveimap.ui.toolbar.ToolbarIconRows
import org.dlang.liveimap.ui.toolbar.effectiveReaderToolbar
import org.dlang.liveimap.ui.toolbar.toolbarExpandedHeight
import org.dlang.liveimap.ui.toolbar.visibleReaderActions

internal class Utf8Carry {
    var pending: ByteArray = ByteArray(0)
    var decoder: WireTextDecoder? = null
}

private class ScrollBridge {
    var onNearEnd: () -> Unit = {}
}

private class LaterRetry {
    var block: () -> Unit = {}
}

private class LinkBridge {
    var onLink: (String) -> Unit = {}
}

private class SaveTarget {
    var row: AttachmentRow? = null
    var uid: Long = 0
}

internal data class AttachmentRow(
    val section: String,
    val label: String,
    val size: Int,
    val fetched: Int,
    val done: Boolean,
    val type: String,
    val subtype: String,
    val busy: Boolean = false,
)

internal class ReaderHeld : ViewModel() {
    var openUid: Long = -1L
    var openMailbox: String = ""
    var ready: Boolean = false
    var recordScroll: Boolean = true
    val carry = Utf8Carry()
    val scrollOffsetState = mutableIntStateOf(0)
    var webScroll: Int = 0
    val accountState = mutableStateOf(AccountSettings())
    val structureState = mutableStateOf<MimePart?>(null)
    val selectedViewState = mutableStateOf(BodyView.PlainOrError)
    val renderedHtmlState = mutableStateOf(false)
    val bodyTextState = mutableStateOf("")
    val charsetNoteState = mutableStateOf<String?>(null)
    val bodyOffsetState = mutableIntStateOf(0)
    val bodySizeState = mutableIntStateOf(0)
    val bodySectionState = mutableStateOf<String?>(null)
    val missingState = mutableStateOf<String?>(null)
    val attachmentsState = mutableStateOf<List<AttachmentRow>>(emptyList())
    val headerFromState = mutableStateOf("")
    val headerToState = mutableStateOf("")
    val headerCcState = mutableStateOf("")
    val headerSenderState = mutableStateOf("")
    val headerResentToState = mutableStateOf("")
    val headerDateState = mutableStateOf("")
    val headerSubjectState = mutableStateOf("")
    val headerReadyState = mutableStateOf(false)
    val rowFlagsState = mutableStateOf(emptySet<String>())
    val showHtmlButtonState = mutableStateOf(false)
    val allowImagesState = mutableStateOf(false)
    val noTextPartState = mutableStateOf(false)
    val connectedState = mutableStateOf(false)
    val seenStoredState = mutableStateOf(false)
    val selectedMailboxState = mutableStateOf<String?>(null)
    val headingState = mutableStateOf(MailboxTitle("", ""))

    fun dropLoaded() {
        openUid = -1L
        openMailbox = ""
        ready = false
        recordScroll = true
        carry.pending = ByteArray(0)
        carry.decoder = null
        scrollOffsetState.intValue = 0
        webScroll = 0
        accountState.value = AccountSettings()
        structureState.value = null
        selectedViewState.value = BodyView.PlainOrError
        renderedHtmlState.value = false
        bodyTextState.value = ""
        charsetNoteState.value = null
        bodyOffsetState.intValue = 0
        bodySizeState.intValue = 0
        bodySectionState.value = null
        missingState.value = null
        attachmentsState.value = emptyList()
        headerFromState.value = ""
        headerToState.value = ""
        headerCcState.value = ""
        headerSenderState.value = ""
        headerResentToState.value = ""
        headerDateState.value = ""
        headerSubjectState.value = ""
        headerReadyState.value = false
        rowFlagsState.value = emptySet()
        showHtmlButtonState.value = false
        allowImagesState.value = false
        noTextPartState.value = false
        connectedState.value = false
        seenStoredState.value = false
        selectedMailboxState.value = null
        headingState.value = MailboxTitle("", "")
    }
}

private fun Context.hostActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageReaderScreen(
    mailbox: String,
    uid: Long,
    sequence: Int,
    onCompose: (ComposeSeed) -> Unit,
    onAdvance: (Long, Int) -> Unit,
    onBack: () -> Unit,
    onFolderViewSaved: () -> Unit = {},
    onCustomize: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val noAppFound = stringResource(R.string.reader_no_app)
    val notConnected = stringResource(R.string.reader_not_connected)
    val savedText = stringResource(R.string.reader_saved)
    val noAddressBookText = stringResource(R.string.reader_no_address_book)
    val noAddressesText = stringResource(R.string.reader_no_addresses)
    val addressAddedText = stringResource(R.string.reader_address_added)
    val addressExistsText = stringResource(R.string.reader_address_exists)
    val copyChangedText = stringResource(R.string.copy_changed)
    val retryLabel = stringResource(R.string.reader_retry)
    val noTextPartText = stringResource(R.string.reader_no_text)
    val noHtmlPartText = stringResource(R.string.reader_no_html)
    val shareTitle = stringResource(R.string.reader_share)
    val indexNow = stringResource(R.string.index_now)
    val indexMin = stringResource(R.string.index_min)
    val indexHour = stringResource(R.string.index_hour)
    val indexHours = stringResource(R.string.index_hours)
    val indexDay = stringResource(R.string.index_day)
    val indexDays = stringResource(R.string.index_days)
    val indexAgo = stringResource(R.string.index_ago)
    val indexAhead = stringResource(R.string.index_ahead)
    val indexBadPattern = stringResource(R.string.index_bad_pattern)
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val connectionState by session.connectionState.collectAsState()
    val debugStatus by TrafficLog.debugStatus.collectAsState()
    val held = viewModel<ReaderHeld>(key = "reader:$mailbox:$uid")
    val reuseBody = held.ready && held.openMailbox == mailbox && held.openUid == uid
    val host = context.hostActivity()
    DisposableEffect(held) {
        onDispose {
            if (host?.isChangingConfigurations != true) held.dropLoaded()
        }
    }
    remember {
        if (reuseBody) held.recordScroll = false
        true
    }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val carry = held.carry
    val bridge = remember { ScrollBridge() }
    val scroll = rememberScrollState(held.scrollOffsetState.intValue)
    var loading by remember { mutableStateOf(!reuseBody) }
    var banner by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var undoOffer by remember { mutableStateOf<MailUndo?>(null) }
    var undoToken by remember { mutableIntStateOf(0) }
    var snackMode by remember { mutableStateOf("retry") }
    var advanceAfterUndo by remember { mutableStateOf(false) }
    var advanceTarget by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var confirmExpunge by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    var expungeUids by remember { mutableStateOf<List<Long>>(emptyList()) }
    val laterRetry = remember { LaterRetry() }
    var account by held.accountState
    var knownTrashName by remember(mailbox) { mutableStateOf(account.trashMailbox) }
    var structure by held.structureState
    var selectedView by held.selectedViewState
    var renderedHtml by held.renderedHtmlState
    var bodyText by held.bodyTextState
    var charsetNote by held.charsetNoteState
    var bodyOffset by held.bodyOffsetState
    var bodySize by held.bodySizeState
    var bodySection by held.bodySectionState
    var missing by held.missingState
    var attachments by held.attachmentsState
    var headerFrom by held.headerFromState
    var headerTo by held.headerToState
    var headerCc by held.headerCcState
    var headerSender by held.headerSenderState
    var headerResentTo by held.headerResentToState
    var headerDate by held.headerDateState
    var headerSubject by held.headerSubjectState
    var headerReady by held.headerReadyState
    var rowFlags by held.rowFlagsState
    var showHtmlButton by held.showHtmlButtonState
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var allowImages by held.allowImagesState
    val linkBridge = remember { LinkBridge() }
    val saveTarget = remember { SaveTarget() }
    var noTextPart by held.noTextPartState
    var connected by held.connectedState
    var seenStored by held.seenStoredState
    var selectedMailbox by held.selectedMailboxState
    var choosingMove by remember { mutableStateOf(false) }
    var choosingSave by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var saveOffer by remember { mutableStateOf("") }
    var confirmTake by remember { mutableStateOf(false) }
    var takeChoices by remember { mutableStateOf<List<TakeAddress>>(emptyList()) }
    var moreMenu by remember { mutableStateOf(false) }
    var iconRows by remember { mutableIntStateOf(1) }
    var heading by held.headingState
    val saveMutex = remember { Mutex() }

    BackHandler(enabled = (choosingMove || choosingSave || confirmSave || confirmTake) && !moreMenu) {
        choosingMove = false
        choosingSave = false
        confirmSave = false
        confirmTake = false
    }
    BackHandler(enabled = moreMenu) {
        moreMenu = false
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

    LaunchedEffect(mailbox, account.trashMailbox, connected) {
        if (!connected) return@LaunchedEffect
        knownTrashName = try {
            gate.withLock { session.knownTrash() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            ""
        }
    }

    fun postSnack(text: String) {
        snackMessage = text
        snackMode = "retry"
        snackEvent += 1
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val row = saveTarget.row
        val messageUid = saveTarget.uid
        saveTarget.row = null
        if (uri == null || row == null) return@rememberLauncherForActivityResult
        val file = File(appContext.cacheDir, attachmentCacheName(messageUid, row.section))
        try {
            val output = context.contentResolver.openOutputStream(uri)
            if (output == null) {
                postSnack(noAppFound)
                return@rememberLauncherForActivityResult
            }
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (error: Exception) {
            postSnack(error.message ?: notConnected)
        }
    }

    DisposableEffect(uid) {
        onDispose {
            deleteReaderAttachmentCache(appContext.cacheDir, uid)
        }
    }

    LaunchedEffect(snackEvent) {
        if (snackEvent == 0) return@LaunchedEffect
        snackMode = "retry"
        val result = snackbarHostState.showSnackbar(
            message = snackMessage,
            actionLabel = retryLabel,
        )
        if (result == SnackbarResult.ActionPerformed) {
            laterRetry.block()
        }
    }

    // peekPart is BODY.PEEK. \Seen is a separate STORE after the first successful peek.
    suspend fun noteSeen(markSeen: Boolean) {
        if (markSeen && !seenStored && account.markSeenOnOpen) {
            seenStored = true
            try {
                session.storeFlags(listOf(uid), setOf("\\Seen"), emptySet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                postSnack(error.text)
            }
        }
    }

    suspend fun pullBody(part: MimePart, reset: Boolean, markSeen: Boolean) {
        if (reset) {
            bodyText = ""
            bodyOffset = 0
            bodySize = part.size
            bodySection = part.section
            carry.pending = ByteArray(0)
            val decoder = WireTextDecoder(part.charset, part.encoding)
            carry.decoder = decoder
            charsetNote = if (decoder.unknownCharset) unknownCharsetNote else null
        }
        if (bodySection != part.section) return
        if (part.size > 0 && bodyOffset >= part.size) return
        val length = if (part.size > 0) {
            nextWireCount(bodyOffset, part.size, 4096, part.encoding.equals("base64", ignoreCase = true))
        } else if (bodyOffset == 0) {
            4096
        } else {
            return
        }
        if (length <= 0) return
        val chunk = try {
            session.peekPart(uid, part.section, bodyOffset, length)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            postSnack(error.text)
            return
        }
        noteSeen(markSeen)
        val decoder = carry.decoder ?: WireTextDecoder(part.charset, part.encoding).also { carry.decoder = it }
        if (chunk.isEmpty()) {
            bodyOffset = if (part.size > 0) part.size else bodyOffset + length
            bodyText += decoder.finish()
            return
        }
        bodyOffset += chunk.size
        bodyText += decoder.take(chunk)
        if (part.size <= 0 || bodyOffset >= part.size) bodyText += decoder.finish()
    }

    suspend fun pullUnbounded(section: String, markSeen: Boolean) {
        missing = null
        noTextPart = false
        renderedHtml = false
        bodyText = ""
        bodyOffset = 0
        bodySize = 0
        bodySection = section
        carry.pending = ByteArray(0)
        var marked = false
        while (true) {
            val length = 4096
            val chunk = try {
                session.peekPart(uid, section, bodyOffset, length)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                postSnack(error.text)
                return
            }
            if (!marked) {
                marked = true
                noteSeen(markSeen)
            }
            if (chunk.isEmpty()) break
            bodyOffset += chunk.size
            val (text, rest) = appendUtf8(carry.pending, chunk)
            carry.pending = rest
            bodyText += text
            if (chunk.size < length) break
        }
        if (carry.pending.isNotEmpty()) {
            bodyText += carry.pending.toString(Charsets.UTF_8)
            carry.pending = ByteArray(0)
        }
    }

    suspend fun readPart(part: MimePart, markSeen: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        var offset = 0
        var marked = false
        suspend fun take(length: Int): ByteArray? {
            val chunk = try {
                session.peekPart(uid, part.section, offset, length)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                postSnack(error.text)
                return null
            }
            if (!marked) {
                marked = true
                noteSeen(markSeen)
            }
            return chunk
        }
        if (part.size > 0) {
            while (offset < part.size) {
                val length = nextWireCount(offset, part.size, 4096, false)
                if (length <= 0) break
                val chunk = take(length) ?: return null
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
            }
        } else {
            while (true) {
                val chunk = take(4096) ?: return null
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
                if (chunk.size < 4096) break
            }
        }
        return out.toByteArray()
    }

    fun showMissing(text: String, noText: Boolean = false, offerHtml: Boolean = false) {
        missing = text
        noTextPart = noText
        showHtmlButton = offerHtml && noText
        renderedHtml = false
        bodyText = ""
        bodySection = null
        bodyOffset = 0
        bodySize = 0
        charsetNote = null
        carry.pending = ByteArray(0)
        carry.decoder = null
    }

    suspend fun readSectionText(section: String): String {
        val local = Utf8Carry()
        val out = StringBuilder()
        var offset = 0
        while (true) {
            val chunk = session.peekPart(uid, section, offset, 4096)
            if (chunk.isEmpty()) break
            offset += chunk.size
            val (text, rest) = appendUtf8(local.pending, chunk)
            local.pending = rest
            out.append(text)
            if (chunk.size < 4096) break
        }
        if (local.pending.isNotEmpty()) {
            out.append(local.pending.toString(Charsets.UTF_8))
        }
        return out.toString()
    }

    suspend fun loadPreferred(view: BodyView) {
        val root = structure ?: return
        val plain = textPart(root, "plain")
        val html = textPart(root, "html")
        when (view) {
            BodyView.PlainOrError -> {
                if (plain == null) {
                    showMissing(noTextPartText, noText = true, offerHtml = html != null)
                    return
                }
                showHtmlButton = false
                noTextPart = false
                missing = null
                renderedHtml = false
                pullBody(plain, reset = true, markSeen = !seenStored)
            }
            BodyView.PlainOrHtml -> {
                if (html == null) {
                    showMissing(noHtmlPartText)
                } else {
                    showHtmlButton = false
                    noTextPart = false
                    missing = null
                    renderedHtml = true
                    bodyText = ""
                    bodySection = html.section
                    bodyOffset = 0
                    bodySize = html.size
                    charsetNote = null
                    carry.pending = ByteArray(0)
                    carry.decoder = null
                    val bytes = readPart(html, markSeen = !seenStored) ?: return
                    val decoded = decodePart(bytes, html.charset, html.encoding)
                    bodyOffset = bytes.size
                    charsetNote = if (decoded.unknownCharset) unknownCharsetNote else null
                    bodyText = decoded.text
                }
            }
            BodyView.PlainOrText -> {
                if (html == null) {
                    showMissing(noHtmlPartText)
                } else {
                    renderedHtml = false
                    showHtmlButton = false
                    noTextPart = false
                    missing = null
                    bodyText = ""
                    bodySection = html.section
                    bodyOffset = 0
                    bodySize = html.size
                    charsetNote = null
                    carry.pending = ByteArray(0)
                    carry.decoder = null
                    val bytes = readPart(html, markSeen = !seenStored) ?: return
                    val decoded = decodePart(bytes, html.charset, html.encoding)
                    bodyOffset = bytes.size
                    charsetNote = if (decoded.unknownCharset) unknownCharsetNote else null
                    bodyText = htmlAsText(decoded.text)
                }
            }
            BodyView.Headers, BodyView.Raw -> Unit
        }
    }

    fun requestView(view: BodyView) {
        selectedView = view
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                when (view) {
                    BodyView.Headers -> {
                        charsetNote = null
                        pullUnbounded("HEADER", markSeen = !seenStored)
                    }
                    BodyView.Raw -> {
                        charsetNote = null
                        pullUnbounded("*", markSeen = !seenStored)
                    }
                    else -> loadPreferred(view)
                }
            }
        }
    }

    fun markAttachment(section: String, fetched: Int, done: Boolean, busy: Boolean) {
        attachments = attachments.map { item ->
            if (item.section == section) item.copy(fetched = fetched, done = done, busy = busy) else item
        }
    }

    fun fetchAttachment(row: AttachmentRow) {
        if (row.done || row.busy) return
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                val current = attachments.firstOrNull { it.section == row.section } ?: return@withLock
                if (current.done || current.busy) return@withLock
                markAttachment(current.section, current.fetched, done = false, busy = true)
                try {
                    if (selectedMailbox != mailbox) {
                        session.select(mailbox)
                        selectedMailbox = mailbox
                    }
                    val out = ByteArrayOutputStream()
                    var offset = 0
                    if (current.size <= 0) {
                        val chunk = session.peekPart(uid, current.section, 0, 65536)
                        out.write(chunk)
                        offset = chunk.size
                        markAttachment(current.section, offset, done = false, busy = true)
                    } else {
                        while (offset < current.size) {
                            val length = nextWireCount(offset, current.size, 65536, true)
                            if (length <= 0) break
                            val chunk = session.peekPart(uid, current.section, offset, length)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            offset += chunk.size
                            markAttachment(current.section, offset, done = false, busy = true)
                        }
                    }
                    try {
                        File(appContext.cacheDir, attachmentCacheName(uid, current.section))
                            .writeBytes(out.toByteArray())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        markAttachment(current.section, offset, done = false, busy = false)
                        postSnack(error.message ?: notConnected)
                        return@withLock
                    }
                    markAttachment(current.section, offset, done = true, busy = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    markAttachment(current.section, current.fetched, done = false, busy = false)
                    postSnack(error.text)
                }
            }
        }
    }

    fun openAttachment(row: AttachmentRow) {
        val uri = attachmentUri(attachmentCacheName(uid, row.section))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachmentMime(row))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(row.label, uri)
        }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            postSnack(noAppFound)
        }
    }

    fun shareAttachment(row: AttachmentRow) {
        val uri = attachmentUri(attachmentCacheName(uid, row.section))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = attachmentMime(row)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(row.label, uri)
        }
        val chooser = Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (error: ActivityNotFoundException) {
            postSnack(noAppFound)
        }
    }

    fun saveAttachment(row: AttachmentRow) {
        saveTarget.row = row
        saveTarget.uid = uid
        saveDocument.launch(row.label)
    }

    laterRetry.block = { requestView(selectedView) }

    LaunchedEffect(session, mailbox, uid, loadToken) {
        val reuse = held.ready && held.openMailbox == mailbox && held.openUid == uid && loadToken == 0
        if (reuse) {
            loading = false
            connected = true
            banner = null
            if (scroll.value != held.scrollOffsetState.intValue) {
                scroll.scrollTo(held.scrollOffsetState.intValue)
            }
            held.recordScroll = true
            return@LaunchedEffect
        }
        held.ready = false
        heading = MailboxTitle(mailbox, "")
        loading = true
        banner = null
        headerFrom = ""
        headerTo = ""
        headerCc = ""
        headerSender = ""
        headerResentTo = ""
        headerDate = ""
        headerSubject = ""
        headerReady = false
        rowFlags = emptySet()
        showHtmlButton = false
        noTextPart = false
        var initialView = BodyView.PlainOrError
        var openOk = false
        gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@withLock
            }
            account = settings
            selectedView = settings.bodyView
            initialView = settings.bodyView
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@withLock
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    return@withLock
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    return@withLock
                }
                OpenResult.Connected -> Unit
            }
            heading = mailboxTitleFor(session, mailbox)
            try {
                session.select(mailbox)
                selectedMailbox = mailbox
                val tree = session.fetchStructure(uid)
                structure = tree
                attachments = attachmentParts(tree).map { part ->
                    AttachmentRow(
                        section = part.section,
                        label = part.filename?.takeIf { it.isNotEmpty() }
                            ?: "${part.type}/${part.subtype}",
                        size = part.size,
                        fetched = 0,
                        done = false,
                        type = part.type,
                        subtype = part.subtype,
                    )
                }
                val row = try {
                    session.fetchIndex(
                        IndexRequest(
                            mailbox = mailbox,
                            mode = IndexMode.ByUid,
                            uids = listOf(uid),
                            limit = 1,
                            prefetch = 0,
                            includePreview = false,
                        ),
                    ).firstOrNull { it.uid == uid }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                    null
                }
                if (row != null) {
                    headerFrom = row.from
                    headerSubject = row.subject
                    rowFlags = row.flags
                    val formatted = formatIndexDate(
                        epochSeconds = row.internalDateEpoch,
                        format = account.dateFormat,
                        pattern = account.datePattern,
                        nowEpoch = Instant.now().epochSecond,
                        zone = ZoneId.systemDefault(),
                        nowWord = indexNow,
                        minWord = indexMin,
                        hourWord = indexHour,
                        hoursWord = indexHours,
                        dayWord = indexDay,
                        daysWord = indexDays,
                        agoPhrase = indexAgo,
                        aheadPhrase = indexAhead,
                        badPattern = indexBadPattern,
                    )
                    headerDate = if (row.envelopeDate.isNotBlank()) row.envelopeDate else formatted
                }
                try {
                    val fields = headerFields(readSectionText(section = "HEADER"))
                    if (fields.from.contains('@')) headerFrom = fields.from
                    headerTo = fields.to
                    headerCc = fields.cc
                    headerSender = fields.sender
                    headerResentTo = fields.resentTo
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
                headerReady = true
                connected = true
                openOk = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
            }
        }
        loading = false
        if (openOk) {
            gate.withLock {
                if (connected) {
                    when (initialView) {
                        BodyView.Headers -> {
                            charsetNote = null
                            pullUnbounded("HEADER", markSeen = !seenStored)
                        }
                        BodyView.Raw -> {
                            charsetNote = null
                            pullUnbounded("*", markSeen = !seenStored)
                        }
                        else -> loadPreferred(initialView)
                    }
                }
            }
            held.ready = true
            held.openMailbox = mailbox
            held.openUid = uid
        }
    }

    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collect { value ->
            if (!held.recordScroll) return@collect
            held.scrollOffsetState.intValue = value
        }
    }

    LaunchedEffect(connected, bodySection) {
        if (!connected) return@LaunchedEffect
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            if (value > 0 && max - value <= 64) {
                gate.withLock {
                    if (renderedHtml) return@withLock
                    val root = structure ?: return@withLock
                    val part = textPart(root, "plain") ?: return@withLock
                    if (part.section != bodySection) return@withLock
                    if (part.size > 0 && bodyOffset < part.size) {
                        pullBody(part, reset = false, markSeen = false)
                    }
                }
            }
        }
    }

    bridge.onNearEnd = {
        scope.launch {
            gate.withLock {
                if (!renderedHtml) return@withLock
                val root = structure ?: return@withLock
                val part = textPart(root, "html") ?: return@withLock
                if (part.section == bodySection && part.size > 0 && bodyOffset < part.size) {
                    pullBody(part, reset = false, markSeen = false)
                }
            }
        }
    }

    fun nextAfterDeleteOrMove(): Pair<Long, Int>? {
        if (OpenMessageOrder.mailbox != mailbox) return null
        val next = followingUid(OpenMessageOrder.uids, uid) ?: return null
        return next to OpenMessageOrder.sequence(next)
    }

    fun openNextOrIndex(target: Pair<Long, Int>?) {
        if (target == null) onBack() else onAdvance(target.first, target.second)
    }

    fun showActionUndo(undo: MailUndo, next: Pair<Long, Int>?) {
        undoOffer = undo
        advanceTarget = next
        advanceAfterUndo = true
        snackMode = "undo"
        undoToken += 1
    }

    fun runReaderUndo(offer: MailUndo) {
        advanceAfterUndo = false
        undoOffer = null
        scope.launch {
            gate.withLock {
                val helper = IndexModel(session, store, mailbox)
                if (offer.delete) helper.undoDelete(offer.uids, offer.allMailbox)
                else helper.undoMove(
                    offer.uids,
                    offer.targetMailbox,
                    offer.destUids,
                    offer.usedMove,
                    offer.allMailbox,
                )
                val reported = helper.notice
                if (reported != null) postSnack(reported)
            }
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    fun runReaderExpunge(uids: List<Long>) {
        if (uids.isEmpty()) return
        scope.launch {
            var failed = false
            gate.withLock {
                try {
                    session.uidExpunge(uids)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    failed = true
                    postSnack(error.text)
                }
            }
            if (!failed) {
                advanceAfterUndo = true
                undoOffer = null
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
    }

    fun askOrExpunge(uids: List<Long>) {
        if (account.askBeforeExpunge) {
            expungeUids = uids
            confirmExpunge = true
        } else {
            runReaderExpunge(uids)
        }
    }

    LaunchedEffect(undoToken) {
        val token = undoToken
        if (token == 0) return@LaunchedEffect
        val offer = undoOffer ?: return@LaunchedEffect
        snackMode = "undo"
        snackbarHostState.showSnackbar(
            message = mailUndoText(offer),
            duration = SnackbarDuration.Indefinite,
        )
        if (undoToken != token) return@LaunchedEffect
        val go = advanceAfterUndo
        val next = advanceTarget
        undoOffer = null
        advanceAfterUndo = false
        snackMode = "retry"
        if (go) openNextOrIndex(next)
    }

    fun openConfirmedLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            postSnack(noAppFound)
        }
    }

    fun undeleteMessage() {
        scope.launch {
            gate.withLock {
                try {
                    session.storeFlags(listOf(uid), emptySet(), setOf("\\Deleted"))
                    rowFlags = rowFlags - "\\Deleted"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
            }
        }
    }

    suspend fun runReaderPolicyLocked(policy: DeletePolicy) {
        val target = nextAfterDeleteOrMove()
        try {
            when (policy) {
                DeletePolicy.MarkDeleted -> {
                    session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                    showActionUndo(MailUndo(delete = true, uids = listOf(uid)), target)
                }
                DeletePolicy.MoveToTrash -> {
                    val trash = session.knownTrash()
                    knownTrashName = trash
                    if (trash.isEmpty()) return
                    session.copyThenDelete(listOf(uid), trash)
                    val dest = session.takeCopiedUids()
                    val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                    showActionUndo(
                        MailUndo(
                            delete = false,
                            uids = listOf(uid),
                            targetMailbox = trash,
                            destUids = dest,
                            usedMove = usedMove,
                        ),
                        target,
                    )
                }
                DeletePolicy.DeletePermanently -> {
                    if (!session.featureCaps.uidPlus) return
                    session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                    session.uidExpunge(listOf(uid))
                    openNextOrIndex(target)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            postSnack(error.text)
        }
    }

    fun runChosenPolicy(policy: DeletePolicy) {
        scope.launch {
            if (policy == DeletePolicy.DeletePermanently && account.askBeforeExpunge) {
                confirmPermanent = true
                return@launch
            }
            gate.withLock { runReaderPolicyLocked(policy) }
        }
    }

    fun runDelete() {
        scope.launch {
            val trash = try {
                gate.withLock { session.knownTrash() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
                ""
            }
            knownTrashName = trash
            val policy = effectiveDeletePolicy(
                account.deletePolicy,
                trash.isNotEmpty() && mailbox == trash,
                session.featureCaps.uidPlus,
                trash.isNotEmpty(),
            )
            if (policy == DeletePolicy.DeletePermanently && account.askBeforeExpunge) {
                confirmPermanent = true
                return@launch
            }
            gate.withLock { runReaderPolicyLocked(policy) }
        }
    }

    fun runSpam() {
        val spamMailbox = account.spamMailbox
        if (spamMailbox.isEmpty()) return
        scope.launch {
            gate.withLock {
                val target = nextAfterDeleteOrMove()
                try {
                    session.copyThenDelete(listOf(uid), spamMailbox)
                    val dest = session.takeCopiedUids()
                    val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                    showActionUndo(
                        MailUndo(
                            delete = false,
                            uids = listOf(uid),
                            targetMailbox = spamMailbox,
                            destUids = dest,
                            usedMove = usedMove,
                        ),
                        target,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
            }
        }
    }

    fun runReaderAction(action: ReaderAction) {
        when (action) {
            ReaderAction.Reply ->
                onCompose(ComposeSeed(ComposeKind.Reply, mailbox, listOf(uid)))
            ReaderAction.ReplyAll ->
                onCompose(ComposeSeed(ComposeKind.ReplyAll, mailbox, listOf(uid)))
            ReaderAction.Forward ->
                onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid)))
            ReaderAction.Delete -> runDelete()
            ReaderAction.Move -> choosingMove = true
            ReaderAction.Spam -> runSpam()
            ReaderAction.Bounce ->
                onCompose(ComposeSeed(ComposeKind.Bounce, mailbox, listOf(uid)))
        }
    }

    fun saveMailboxView(transform: (FolderView) -> FolderView) {
        scope.launch {
            try {
                saveMutex.withLock {
                    val loaded = store.load()
                    val current = loaded.folderViews[mailbox] ?: loaded.defaultView
                    val updated = loaded.copy(
                        folderViews = loaded.folderViews + (mailbox to transform(current)),
                    )
                    store.save(updated)
                    account = updated
                }
                onFolderViewSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                postSnack(error.message ?: notConnected)
            }
        }
    }

    val systemDark = isSystemInDarkTheme()
    val dark = when (account.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.FollowSystem -> systemDark
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteTint = MaterialTheme.colorScheme.onSurfaceVariant
    val quoteBorder = MaterialTheme.colorScheme.outlineVariant
    val htmlBackground = MaterialTheme.colorScheme.background.toArgb()
    val htmlForeground = MaterialTheme.colorScheme.onBackground.toArgb()
    val plainFont = if (account.plainTextMonospace) FontFamily.Monospace else null
    linkBridge.onLink = { url -> pendingLink = url }
    val plainLines = remember(bodyText, quoteTint, linkColor, linkBridge) {
        readerLines(bodyText).map { line ->
            val quoted = quotedReaderLine(line)
            quoted to plainLineText(line, if (quoted) quoteTint else null, linkColor, linkBridge)
        }
    }
    val readerLayout = effectiveReaderToolbar(account)
    val barActions = visibleReaderActions(readerLayout.toolbar, account.spamMailbox)
    val menuActions = visibleReaderActions(readerLayout.overflow, account.spamMailbox)
    val trashKnown = knownTrashName.isNotEmpty()
    val readerUidPlus = session.featureCaps.uidPlus
    val effectivePolicy = effectiveDeletePolicy(
        account.deletePolicy,
        trashKnown && mailbox == knownTrashName,
        readerUidPlus,
        trashKnown,
    )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { MailboxTitleLines(heading.leaf, heading.parent) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                actions = {
                    val readerIcons = ArrayList<@Composable () -> Unit>(barActions.size)
                    for (action in barActions) {
                        if (action == ReaderToolbarAction.Refresh) {
                            readerIcons.add {
                                IconButton(onClick = { if (!loading) loadToken += 1 }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.reader_refresh),
                                    )
                                }
                            }
                        } else {
                            val reader = action.readerAction()
                            if (reader != null) {
                                readerIcons.add {
                                    IconButton(onClick = { runReaderAction(reader) }) {
                                        Icon(
                                            imageVector = readerActionImage(reader),
                                            contentDescription = if (reader == ReaderAction.Delete) {
                                                deletePolicyLabel(effectivePolicy)
                                            } else {
                                                readerActionName(reader)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ToolbarIconRows(
                        maxRows = account.toolbarRows,
                        onRows = { count -> iconRows = count },
                        icons = readerIcons,
                    )
                    Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.reader_more),
                    )
                }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                for (policy in alternateDeletePolicies(effectivePolicy, readerUidPlus, trashKnown)) {
                    DropdownMenuItem(
                        text = { Text(deletePolicyLabel(policy)) },
                        onClick = {
                            moreMenu = false
                            runChosenPolicy(policy)
                        },
                    )
                }
                for (action in menuActions) {
                    if (action == ReaderToolbarAction.Refresh) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_refresh)) },
                            onClick = {
                                moreMenu = false
                                if (!loading) loadToken += 1
                            },
                        )
                    } else {
                        val reader = action.readerAction()
                        if (reader != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (reader == ReaderAction.Delete) deletePolicyLabel(effectivePolicy)
                                        else readerActionName(reader),
                                    )
                                },
                                onClick = {
                                    moreMenu = false
                                    runReaderAction(reader)
                                },
                            )
                        }
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_save)) },
                    onClick = {
                        moreMenu = false
                        val offered = saveFolderName(
                            account.saveNameRule,
                            account.savedMailbox,
                            account.lastSaveMailbox,
                            headerFrom,
                            headerSender,
                            headerTo,
                            headerResentTo,
                        )
                        if (offered.isNotEmpty()) {
                            saveOffer = offered
                            confirmSave = true
                        } else {
                            choosingSave = true
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_take_address)) },
                    onClick = {
                        moreMenu = false
                        if (account.addressBookMailbox.isEmpty()) {
                            postSnack(noAddressBookText)
                        } else {
                            val found = takeAddresses(
                                headerFrom,
                                headerSender,
                                headerTo,
                                headerCc,
                                headerResentTo,
                            )
                            if (found.isEmpty()) {
                                postSnack(noAddressesText)
                            } else {
                                takeChoices = found
                                confirmTake = true
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(forwardStyleName(account.forwardAsAttachment)) },
                    onClick = {
                        moreMenu = false
                        armForwardOnce(!account.forwardAsAttachment)
                        onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid)))
                    },
                )
                BodyView.entries.forEach { view ->
                    DropdownMenuItem(
                        text = { Text(bodyViewName(view)) },
                        onClick = {
                            moreMenu = false
                            requestView(view)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.toolbar_customize)) },
                    onClick = {
                        moreMenu = false
                        onCustomize()
                    },
                )
            }
                    }
                },
                expandedHeight = toolbarExpandedHeight(iconRows),
                windowInsets = mailBarInsets(),
            )
        },
        contentWindowInsets = mailScreenInsets(),
    ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
    Column(Modifier.fillMaxSize()) {
        ConnectionStatusStrip(connectionState) {
            scope.launch {
                try {
                    session.resume()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
                loadToken += 1
            }
        }
        DebugConnectionStatus(debugStatus)
        if (loading && banner == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
        val shownBanner = banner
        if (shownBanner != null) {
            FailureBanner(message = shownBanner) {
                banner = null
                loading = true
                loadToken += 1
            }
        }
        if (headerReady) {
            ReaderHeaderCard(
                from = headerFrom,
                toLine = recipientLine(headerTo, headerCc),
                date = headerDate,
                subject = headerSubject,
                deleted = "\\Deleted" in rowFlags,
                onUndelete = { undeleteMessage() },
            )
        }
        val absent = missing
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { if (!loading) loadToken += 1 },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (attachments.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (row in attachments) {
                            AttachmentChip(
                                row = row,
                                onFetch = { fetchAttachment(row) },
                                onOpen = { openAttachment(row) },
                                onShare = { shareAttachment(row) },
                                onSave = { saveAttachment(row) },
                            )
                        }
                    }
                }
                when {
                    noTextPart -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.reader_no_text))
                                if (showHtmlButton) {
                                    TextButton(onClick = { requestView(BodyView.PlainOrHtml) }) {
                                        Text(stringResource(R.string.reader_show_html))
                                    }
                                }
                            }
                        }
                    }
                    absent == null && renderedHtml -> {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                        val note = charsetNote
                        if (note != null) Text(note, modifier = Modifier.padding(horizontal = 8.dp))
                        TextButton(onClick = { allowImages = true }) { Text(stringResource(R.string.reader_show_images)) }
                        AndroidView(
                            factory = { webContext ->
                                WebView(webContext).apply {
                                    settings.javaScriptEnabled = false
                                    settings.javaScriptCanOpenWindowsAutomatically = false
                                    settings.blockNetworkLoads = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    setBackgroundColor(htmlBackground)
                                    applyHtmlDark(settings)
                                    webViewClient = object : WebViewClient() {
                                        private fun confirm(url: String?) {
                                            if (!url.isNullOrEmpty()) linkBridge.onLink(url)
                                        }

                                        @Deprecated("Deprecated in API 24")
                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                            confirm(url)
                                            return true
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            confirm(request?.url?.toString())
                                            return true
                                        }
                                    }
                                    setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                        val extent = (contentHeight * scale) - height
                                        if (held.recordScroll &&
                                            !(scrollY == 0 && held.webScroll > 0 && contentHeight == 0)
                                        ) {
                                            held.webScroll = scrollY
                                        }
                                        if (scrollY > 0 && extent - scrollY < 48f) bridge.onNearEnd()
                                    }
                                }
                            },
                            update = { view ->
                                view.settings.javaScriptEnabled = false
                                view.settings.blockNetworkLoads = !allowImages
                                view.setBackgroundColor(htmlBackground)
                                applyHtmlDark(view.settings)
                                val page = themedHtml(bodyText, dark, htmlBackground, htmlForeground)
                                val token = page to allowImages
                                if (view.tag != token) {
                                    view.tag = token
                                    view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                                    val y = held.webScroll
                                    if (y > 0) view.post { view.scrollTo(0, y) }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        }
                    }
                    absent == null -> {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .padding(8.dp),
                        ) {
                            val note = charsetNote
                            if (note != null) Text(note)
                            for ((quoted, line) in plainLines) {
                                if (quoted) {
                                    QuotedReaderLine(line, quoteTint, quoteBorder, plainFont)
                                } else {
                                    Text(text = line, fontFamily = plainFont, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    else -> {
                        Text(text = absent, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data ->
            val offer = undoOffer
            val uidPlus = session.featureCaps.uidPlus
            val showExpunge = snackMode == "undo" && offer != null && offer.delete &&
                offer.uids.isNotEmpty() && uidPlus
            if (snackMode == "undo" && offer != null) {
                Snackbar(
                    action = {
                        TextButton(onClick = { runReaderUndo(offer) }) { Text(stringResource(R.string.reader_undo)) }
                        if (showExpunge) {
                            TextButton(onClick = { askOrExpunge(offer.uids) }) { Text(stringResource(R.string.reader_expunge)) }
                        }
                    },
                ) { Text(data.visuals.message) }
            } else {
                Snackbar(data)
            }
        }
    }
    }
    val confirmedLink = pendingLink
    if (confirmedLink != null) {
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            text = { Text(confirmedLink) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    openConfirmedLink(confirmedLink)
                }) { Text(stringResource(R.string.reader_open)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.reader_cancel)) }
            },
        )
    }
    if (confirmExpunge) {
        val uidPlus = session.featureCaps.uidPlus
        val expungeBody = stringResource(R.string.reader_expunge_body)
        val expungeOthers = stringResource(R.string.reader_expunge_others)
        val body = if (uidPlus) expungeBody else "$expungeBody $expungeOthers"
        AlertDialog(
            onDismissRequest = { confirmExpunge = false },
            title = { Text(stringResource(R.string.reader_expunge_title)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    val uids = expungeUids
                    confirmExpunge = false
                    runReaderExpunge(uids)
                }) { Text(stringResource(R.string.reader_expunge), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExpunge = false }) { Text(stringResource(R.string.reader_cancel)) }
            },
        )
    }
    if (confirmPermanent) {
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            text = { Text(stringResource(R.string.index_permanent_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanent = false
                    scope.launch {
                        gate.withLock { runReaderPolicyLocked(DeletePolicy.DeletePermanently) }
                    }
                }) {
                    Text(
                        stringResource(R.string.settings_delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanent = false }) {
                    Text(stringResource(R.string.reader_cancel))
                }
            },
        )
    }
    if (choosingMove) {
        MailboxChooser(
            store = store,
            saveMutex = saveMutex,
            onStored = { loaded ->
                account = account.copy(expandedFolders = loaded.expandedFolders)
            },
            onPick = { picked ->
                choosingMove = false
                if (picked.isNotEmpty()) {
                    scope.launch {
                        gate.withLock {
                            val target = nextAfterDeleteOrMove()
                            try {
                                session.copyThenDelete(listOf(uid), picked)
                                val dest = session.takeCopiedUids()
                                val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                                showActionUndo(
                                    MailUndo(
                                        delete = false,
                                        uids = listOf(uid),
                                        targetMailbox = picked,
                                        destUids = dest,
                                        usedMove = usedMove,
                                    ),
                                    target,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                postSnack(error.text)
                            }
                        }
                    }
                }
            },
            onDismiss = { choosingMove = false },
        )
    }
    fun copySavedMessage(destination: String) {
        if (destination.isEmpty()) return
        scope.launch {
            var copied = false
            gate.withLock {
                try {
                    session.copyUids(listOf(uid), destination)
                    postSnack(savedText)
                    copied = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
            }
            if (!copied) return@launch
            try {
                saveMutex.withLock {
                    val loaded = store.load()
                    val updated = loaded.copy(lastSaveMailbox = destination)
                    store.save(updated)
                    account = updated
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed store write leaves the Saved snack and the copy.
            }
        }
    }

    if (confirmSave) {
        val mailbox = saveOffer
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            text = { Text(stringResource(R.string.reader_save_to, mailbox)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    copySavedMessage(mailbox)
                }) { Text(stringResource(R.string.reader_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmSave = false
                        choosingSave = true
                    }) { Text(stringResource(R.string.reader_save_choose)) }
                    TextButton(onClick = { confirmSave = false }) {
                        Text(stringResource(R.string.reader_cancel))
                    }
                }
            },
        )
    }
    if (choosingSave) {
        MailboxChooser(
            store = store,
            saveMutex = saveMutex,
            onStored = { loaded ->
                account = account.copy(expandedFolders = loaded.expandedFolders)
            },
            onPick = { picked ->
                choosingSave = false
                copySavedMessage(picked)
            },
            onDismiss = { choosingSave = false },
        )
    }
    fun takeChosenAddress(choice: TakeAddress) {
        scope.launch {
            gate.withLock {
                try {
                    val book = account.addressBookMailbox
                    when (val read = readPineBook(session, book)) {
                        is PineRead.NotBook -> postSnack(read.notice)
                        is PineRead.Ready -> {
                            if (bookHasAddress(read.state.entries, choice.email)) {
                                postSnack(addressExistsText)
                            } else {
                                val added = read.state.entries + AlpineEntry(
                                    nickname = choice.email.substringBefore('@'),
                                    fullname = choice.name,
                                    address = choice.email,
                                    fcc = "",
                                    comments = "",
                                )
                                when (val wrote = writePineBook(
                                    session,
                                    book,
                                    read.state.lastUid,
                                    added,
                                    account.addressBookHistory,
                                    account.addressBookNeverTrim,
                                )) {
                                    is PineWriteResult.Wrote -> postSnack(addressAddedText)
                                    is PineWriteResult.Stale -> postSnack(copyChangedText)
                                    is PineWriteResult.NotBook -> postSnack(wrote.notice)
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
                try {
                    session.select(mailbox)
                    selectedMailbox = mailbox
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
            }
        }
    }
    if (confirmTake) {
        AlertDialog(
            onDismissRequest = { confirmTake = false },
            text = { Text(stringResource(R.string.reader_take_prompt)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    for (choice in takeChoices) {
                        val label = if (choice.name.isEmpty()) {
                            choice.email
                        } else {
                            "${choice.name} <${choice.email}>"
                        }
                        TextButton(onClick = {
                            confirmTake = false
                            takeChosenAddress(choice)
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTake = false }) {
                    Text(stringResource(R.string.reader_cancel))
                }
            },
        )
    }
}

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.reader_retry)) }
        }
    }
}

private fun applyHtmlDark(settings: WebSettings) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        settings.isAlgorithmicDarkeningAllowed = false
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        settings.forceDark = WebSettings.FORCE_DARK_OFF
    }
}

internal fun readerBarActions(saved: List<ReaderAction>, spamMailbox: String): List<ReaderAction> {
    return saved.filter { action ->
        action != ReaderAction.Spam || spamMailbox.isNotEmpty()
    }.take(4)
}

internal fun readerMenuActions(saved: List<ReaderAction>, spamMailbox: String): List<ReaderAction> {
    val onBar = readerBarActions(saved, spamMailbox).toSet()
    val overflow = saved.filter { action ->
        action !in onBar && (action != ReaderAction.Spam || spamMailbox.isNotEmpty())
    }
    val off = ReaderAction.entries.filter { action -> action !in saved }
    return overflow + off
}

@Composable
private fun deletePolicyLabel(policy: DeletePolicy): String = stringResource(
    when (policy) {
        DeletePolicy.MarkDeleted -> R.string.settings_mark_deleted
        DeletePolicy.MoveToTrash -> R.string.settings_move_to_trash
        DeletePolicy.DeletePermanently -> R.string.settings_delete_permanently
    },
)

@Composable
private fun readerActionName(action: ReaderAction): String = stringResource(
    when (action) {
        ReaderAction.Reply -> R.string.compose_reply
        ReaderAction.ReplyAll -> R.string.compose_reply_all
        ReaderAction.Forward -> R.string.compose_forward
        ReaderAction.Delete -> R.string.drawer_delete
        ReaderAction.Move -> R.string.label_move
        ReaderAction.Spam -> R.string.label_spam
        ReaderAction.Bounce -> R.string.compose_bounce
    },
)

@Composable
private fun bodyViewName(view: BodyView): String = stringResource(
    when (view) {
        BodyView.PlainOrError -> R.string.label_plain
        BodyView.PlainOrHtml -> R.string.label_html
        BodyView.PlainOrText -> R.string.label_html_text
        BodyView.Headers -> R.string.label_headers
        BodyView.Raw -> R.string.label_raw
    },
)

@Composable
private fun forwardStyleName(asAttachment: Boolean): String = stringResource(
    if (asAttachment) R.string.label_forward_inline else R.string.settings_forward_attachment,
)

private fun ReaderToolbarAction.readerAction(): ReaderAction? = when (this) {
    ReaderToolbarAction.Refresh -> null
    ReaderToolbarAction.Reply -> ReaderAction.Reply
    ReaderToolbarAction.ReplyAll -> ReaderAction.ReplyAll
    ReaderToolbarAction.Forward -> ReaderAction.Forward
    ReaderToolbarAction.Delete -> ReaderAction.Delete
    ReaderToolbarAction.Move -> ReaderAction.Move
    ReaderToolbarAction.Spam -> ReaderAction.Spam
    ReaderToolbarAction.Bounce -> ReaderAction.Bounce
}

private fun readerActionImage(action: ReaderAction) = when (action) {
    ReaderAction.Reply -> Icons.AutoMirrored.Filled.Reply
    ReaderAction.ReplyAll -> Icons.AutoMirrored.Filled.ReplyAll
    ReaderAction.Forward -> Icons.AutoMirrored.Filled.Forward
    ReaderAction.Delete -> Icons.Filled.Delete
    ReaderAction.Move -> moveImage
    ReaderAction.Spam -> spamImage
    ReaderAction.Bounce -> Icons.AutoMirrored.Filled.Redo
}

@Composable
private fun ReaderHeaderCard(
    from: String,
    toLine: String?,
    date: String,
    subject: String,
    deleted: Boolean,
    onUndelete: () -> Unit,
) {
    var recipientsOpen by remember(toLine) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.reader_from, from))
            if (toLine != null) {
                Text(
                    text = toLine,
                    maxLines = if (recipientsOpen) Int.MAX_VALUE else 1,
                    overflow = if (recipientsOpen) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { recipientsOpen = !recipientsOpen },
                )
            }
            Text(date)
            Text(subject, style = MaterialTheme.typography.titleMedium)
            if (deleted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.reader_deleted)) })
                    TextButton(onClick = onUndelete) { Text(stringResource(R.string.reader_undelete)) }
                }
            }
        }
    }
}

@Composable
private fun QuotedReaderLine(
    text: AnnotatedString,
    color: Color,
    border: Color,
    fontFamily: FontFamily?,
) {
    Layout(
        content = {
            Box(Modifier.background(border))
            Text(text = text, color = color, fontFamily = fontFamily)
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val barWidth = 2.dp.roundToPx()
        val textMax = (constraints.maxWidth - barWidth - gap).coerceAtLeast(0)
        val textPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = textMax),
        )
        val barPlaceable = measurables[0].measure(
            Constraints.fixed(barWidth, textPlaceable.height.coerceAtLeast(1)),
        )
        layout(constraints.maxWidth, textPlaceable.height) {
            barPlaceable.placeRelative(0, 0)
            textPlaceable.placeRelative(barWidth + gap, 0)
        }
    }
}

private fun themedHtml(page: String, dark: Boolean, background: Int, foreground: Int): String {
    val styled = if (!dark) {
        page
    } else {
        val css = "<style>html,body,body *{background-color:${cssColor(background)} !important;color:${cssColor(foreground)} !important;}</style>"
        val lower = page.lowercase()
        val head = lower.indexOf("<head")
        if (head >= 0) {
            val close = page.indexOf('>', head)
            if (close >= 0) page.substring(0, close + 1) + css + page.substring(close + 1) else css + page
        } else {
            css + page
        }
    }
    return "<meta charset=\"utf-8\">$styled"
}

private fun cssColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private val httpLink = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val mailLink = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

private data class PlainLink(val start: Int, val end: Int, val url: String)

@Composable
private fun AttachmentChip(
    row: AttachmentRow,
    onFetch: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    val detail = if (row.busy && !row.done) row.fetched.toString() else attachmentSizeText(row.size)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(attachmentIcon(row.type, row.subtype), contentDescription = null)
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clickable(enabled = !row.done && !row.busy, onClick = onFetch),
        ) {
            Text(row.label)
            if (detail != null) Text(detail)
        }
        if (row.done) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.reader_open)) }
            TextButton(onClick = onShare) { Text(stringResource(R.string.reader_share)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.reader_save)) }
        }
    }
}

private fun attachmentIcon(type: String, subtype: String) = when {
    type.equals("image", ignoreCase = true) -> Icons.Filled.Image
    type.equals("application", ignoreCase = true) && subtype.equals("pdf", ignoreCase = true) ->
        Icons.Filled.PictureAsPdf
    else -> Icons.Filled.AttachFile
}

private fun attachmentSizeText(size: Int): String? = when {
    size <= 0 -> null
    size < 1024 -> "$size B"
    else -> "${size / 1024} KB"
}

private fun attachmentMime(row: AttachmentRow): String {
    val type = row.type.trim().ifEmpty { "application" }.lowercase()
    val subtype = row.subtype.trim().ifEmpty { "octet-stream" }.lowercase()
    return "$type/$subtype"
}

private fun attachmentCacheName(uid: Long, section: String): String {
    val safe = section.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "liveimap-$uid-$safe"
}

private fun attachmentUri(name: String): Uri = Uri.parse("content://org.dlang.liveimap.attachments/$name")

private fun deleteReaderAttachmentCache(dir: File, messageUid: Long) {
    val prefix = "liveimap-$messageUid-"
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (file.isFile && file.name.startsWith(prefix)) file.delete()
    }
}

@Composable
private fun recipientLine(to: String, cc: String): String? {
    val toText = to.trim()
    val ccText = cc.trim()
    val toLine = stringResource(R.string.reader_to, toText)
    val ccLine = stringResource(R.string.reader_cc, ccText)
    val bothLine = stringResource(R.string.reader_to_cc, toText, ccText)
    if (toText.isEmpty() && ccText.isEmpty()) return null
    if (ccText.isEmpty()) return toLine
    if (toText.isEmpty()) return ccLine
    return bothLine
}

private fun readerLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    if (parts.size > 1 && parts.last().isEmpty()) return parts.dropLast(1)
    return parts
}

private fun plainLineText(
    line: String,
    textColor: Color?,
    linkColor: Color,
    links: LinkBridge,
): AnnotatedString {
    return buildAnnotatedString {
        appendLinkedLine(line, textColor, linkColor, links)
    }
}

private fun AnnotatedString.Builder.appendLinkedLine(
    line: String,
    textColor: Color?,
    linkColor: Color,
    links: LinkBridge,
) {
    var cursor = 0
    for (link in findPlainLinks(line)) {
        if (link.start > cursor) appendStyled(line.substring(cursor, link.start), textColor)
        val url = link.url
        withLink(
            LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { links.onLink(url) },
            ),
        ) {
            append(line.substring(link.start, link.end))
        }
        cursor = link.end
    }
    if (cursor < line.length) appendStyled(line.substring(cursor), textColor)
}

private fun AnnotatedString.Builder.appendStyled(value: String, color: Color?) {
    if (color == null) {
        append(value)
        return
    }
    val from = length
    append(value)
    addStyle(SpanStyle(color = color), from, length)
}

private fun findPlainLinks(line: String): List<PlainLink> {
    val found = ArrayList<PlainLink>()
    for (match in httpLink.findAll(line)) {
        val trimmed = trimLinkEnd(match.value)
        if (trimmed.isEmpty()) continue
        found.add(PlainLink(match.range.first, match.range.first + trimmed.length, trimmed))
    }
    for (match in mailLink.findAll(line)) {
        val start = match.range.first
        val rawEnd = match.range.last + 1
        if (found.any { start < it.end && rawEnd > it.start }) continue
        val trimmed = trimLinkEnd(match.value)
        if (trimmed.isEmpty() || !trimmed.contains('@')) continue
        found.add(PlainLink(start, start + trimmed.length, "mailto:$trimmed"))
    }
    found.sortBy { it.start }
    return found
}

private fun trimLinkEnd(raw: String): String {
    var end = raw.length
    while (end > 0 && raw[end - 1] in ".,;:!?)]") end -= 1
    return raw.substring(0, end)
}

private fun appendUtf8(pending: ByteArray, chunk: ByteArray): Pair<String, ByteArray> {
    val all = ByteArray(pending.size + chunk.size)
    pending.copyInto(all)
    chunk.copyInto(all, pending.size)
    var index = all.size
    var continuations = 0
    while (index > 0 && continuations < 3) {
        val b = all[index - 1].toInt() and 0xFF
        if (b and 0xC0 != 0x80) break
        continuations++
        index--
    }
    var keep = 0
    if (index > 0) {
        val lead = all[index - 1].toInt() and 0xFF
        val need = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 0
        }
        if (need > continuations + 1) keep = continuations + 1
    }
    val cut = (all.size - keep).coerceAtLeast(0)
    val text = if (cut == 0) "" else all.copyOfRange(0, cut).toString(Charsets.UTF_8)
    val rest = if (keep == 0) ByteArray(0) else all.copyOfRange(cut, all.size)
    return text to rest
}
