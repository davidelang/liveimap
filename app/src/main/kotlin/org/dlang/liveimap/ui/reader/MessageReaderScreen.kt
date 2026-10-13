package org.dlang.liveimap.ui.reader

import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.horizontalScroll
import org.dlang.liveimap.settings.openBound
import androidx.compose.foundation.layout.heightIn
import org.dlang.liveimap.session.LibetpanTooDeepSubtype
import org.dlang.liveimap.ui.compose.nestedLeafParts
import org.dlang.liveimap.session.TooDeepSubtype
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import org.dlang.liveimap.ui.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import org.dlang.liveimap.ui.help.HelpIconButton
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.ui.compose.armForwardOnce
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.sameMailbox
import org.dlang.liveimap.session.FolderAt
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.ui.index.refUndo
import org.dlang.liveimap.ui.compose.mailboxLeaf
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.clampTextZoom
import org.dlang.liveimap.settings.effectiveTextZoom
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.TextLayout
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
import org.dlang.liveimap.ui.DebugStatusIcon
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

internal enum class AttachmentAction { Open, Save, Share }

internal data class TypeWarning(val row: AttachmentRow, val mismatch: TypeMismatch, val action: AttachmentAction)

internal data class AttachmentRow(
    val section: String,
    val label: String,
    val size: Int,
    val fetched: Int,
    val done: Boolean,
    val type: String,
    val subtype: String,
    val busy: Boolean = false,
    val filename: String? = null,
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
    val headerListIdState = mutableStateOf("")
    val headerDateState = mutableStateOf("")
    val headerSubjectState = mutableStateOf("")
    val headerReadyState = mutableStateOf(false)
    val rowFlagsState = mutableStateOf(emptySet<String>())
    val showHtmlButtonState = mutableStateOf(false)
    val allowImagesState = mutableStateOf(false)
    val originalColorsState = mutableStateOf(false)
    val pinchZoomState = mutableStateOf(1f)
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
        headerListIdState.value = ""
        headerDateState.value = ""
        headerSubjectState.value = ""
        headerReadyState.value = false
        rowFlagsState.value = emptySet()
        showHtmlButtonState.value = false
        allowImagesState.value = false
        originalColorsState.value = false
        pinchZoomState.value = 1f
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
    uidValidity: Long = 0L,
    onCompose: (ComposeSeed) -> Unit,
    onAdvance: (Long, Int) -> Unit,
    onBack: () -> Unit,
    onFolderViewSaved: () -> Unit = {},
    onCustomize: () -> Unit = {},
    onFilterLike: (String, String, String, String) -> Unit = { _, _, _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (accountId == null) accountId = store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    MessageReaderLoaded(
        accountId = id,
        store = remember(id) { store.forAccount(id) },
        mailbox = mailbox,
        uid = uid,
        sequence = sequence,
        uidValidity = uidValidity,
        onCompose = { seed -> onCompose(seed.copy(accountId = id)) }, // F121: compose in this account
        onAdvance = onAdvance,
        onBack = onBack,
        onFolderViewSaved = onFolderViewSaved,
        onCustomize = onCustomize,
        onFilterLike = onFilterLike,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageReaderLoaded(
    accountId: String,
    store: DataStoreSettingsStore,
    mailbox: String,
    uid: Long,
    sequence: Int,
    uidValidity: Long = 0L,
    onCompose: (ComposeSeed) -> Unit,
    onAdvance: (Long, Int) -> Unit,
    onBack: () -> Unit,
    onFolderViewSaved: () -> Unit = {},
    onCustomize: () -> Unit = {},
    onFilterLike: (String, String, String, String) -> Unit = { _, _, _, _ -> },
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
    val session = remember(accountId) { mailSession(accountId) }
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
    var textLayout by remember(uid) { mutableStateOf(TextLayout.Text) }
    // RFC 3676 parameters of the plain part shown (format=flowed, DelSp=yes)
    var plainFlowed by remember(uid) { mutableStateOf(false) }
    var plainDelSp by remember(uid) { mutableStateOf(false) }
    var rawTruncated by remember { mutableStateOf(false) }
    var allHeadersOpen by remember(uid) { mutableStateOf(false) }
    var allHeaderText by remember(uid) { mutableStateOf<String?>(null) }
    var viewerExtra by remember(uid) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allHeadersTruncated by remember(uid) { mutableStateOf(false) }
    var decodeHeaderView by remember { mutableStateOf(false) }
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
    var headerListId by held.headerListIdState
    var imageTrust by remember(accountId) { mutableStateOf(loadImageTrust(context.filesDir, accountId)) }
    var headerDate by held.headerDateState
    var headerSubject by held.headerSubjectState
    var headerReady by held.headerReadyState
    var rowFlags by held.rowFlagsState
    var showHtmlButton by held.showHtmlButtonState
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var typeWarning by remember { mutableStateOf<TypeWarning?>(null) }
    var allowImages by held.allowImagesState
    var originalColors by held.originalColorsState
    var pinchZoom by held.pinchZoomState
    val linkBridge = remember { LinkBridge() }
    val saveTarget = remember { SaveTarget() }
    var noTextPart by held.noTextPartState
    var connected by held.connectedState
    var seenStored by held.seenStoredState
    var selectedMailbox by held.selectedMailboxState
    // F105: the folder epoch this message's UID belongs to (from its listed row; else the first one seen here)
    var readerEpoch by remember(mailbox) { mutableStateOf(uidValidity) }
    /** Pass 5: one lowest-layer call selects/verifies this message's folder and epoch and runs [op] there. */
    suspend fun <T> inRef(op: suspend (epoch: Long, s: MailSession) -> T): T =
        session.inFolder(FolderAt(mailbox, readerEpoch)) { sel, s ->
            if (readerEpoch == 0L) readerEpoch = sel.uidValidity
            selectedMailbox = mailbox
            op(readerEpoch, s)
        }
    /** Reader body/attachment reads: always in this message's folder and epoch (select is free when current). */
    suspend fun peekRef(section: String, offset: Int, length: Int): ByteArray {
        return inRef { _, s ->
            s.peekPart(uid, section, offset, length)
        }
    }
    var choosingMove by remember { mutableStateOf(false) }
    var choosingSave by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var saveOffer by remember { mutableStateOf("") }
    var confirmTake by remember { mutableStateOf(false) }
    var takeChoices by remember { mutableStateOf<List<TakeAddress>>(emptyList()) }
    var headerExpanded by rememberSaveable { mutableStateOf(true) }
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

    // Per-open cache token: the same UID and section in another folder or account never shares a file (F06).
    val cacheToken = remember(uid) { readerCacheToken() }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val row = saveTarget.row
        val messageUid = saveTarget.uid
        saveTarget.row = null
        if (uri == null || row == null) return@rememberLauncherForActivityResult
        val file = File(appContext.cacheDir, attachmentCacheName(cacheToken, messageUid, row.section))
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
            deleteReaderAttachmentCache(appContext.cacheDir, cacheToken)
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
                inRef { _, s -> // F43: act on this folder's UID
                    s.storeFlags(listOf(uid), setOf("\\Seen"), emptySet())
                }
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
            peekRef(part.section, bodyOffset, length)
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
        val built = StringBuilder()
        rawTruncated = false
        while (true) {
            if (bodyOffset >= RawViewCapBytes) {
                rawTruncated = true
                break
            }
            val length = 4096
            val chunk = try {
                peekRef(section, bodyOffset, length)
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
            built.append(text)
            if (chunk.size < length) break
        }
        if (carry.pending.isNotEmpty()) {
            built.append(carry.pending.toString(Charsets.UTF_8))
            carry.pending = ByteArray(0)
        }
        bodyText = built.toString()
    }

    suspend fun readPart(part: MimePart, markSeen: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        var offset = 0
        var marked = false
        suspend fun take(length: Int): ByteArray? {
            val chunk = try {
                peekRef(part.section, offset, length)
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
            val chunk = peekRef(section, offset, 4096)
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
                plainFlowed = plain.format.equals("flowed", ignoreCase = true)
                plainDelSp = plain.delSp
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

    /** A "nested part" entry (past MaxMimeDepth): fetch the structure below it and list its parts in its place. */
    fun openNestedPart(row: AttachmentRow) {
        if (row.busy) return
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                markAttachment(row.section, 0, done = false, busy = true)
                try {
                    val subtree = inRef { _, s ->
                        s.fetchStructureAt(uid, row.section)
                    }
                    val rows = nestedLeafParts(subtree).map { part -> attachmentRowOf(part) }
                    attachments = attachments.flatMap { if (it.section == row.section) rows else listOf(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    markAttachment(row.section, 0, done = false, busy = false)
                    postSnack(error.text)
                }
            }
        }
    }

    fun fetchAttachment(row: AttachmentRow) {
        if (isNestedPart(row)) {
            openNestedPart(row)
            return
        }
        if (row.done || row.busy) return
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                val current = attachments.firstOrNull { it.section == row.section } ?: return@withLock
                if (current.done || current.busy) return@withLock
                markAttachment(current.section, current.fetched, done = false, busy = true)
                try {
                    val offset = try {
                        streamAttachment(
                            File(appContext.cacheDir, attachmentCacheName(cacheToken, uid, current.section)),
                            current.size,
                            65536,
                            fetch = { at, length -> peekRef(current.section, at, length) },
                            progress = { bytes -> markAttachment(current.section, bytes, done = false, busy = true) },
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: java.io.IOException) {
                        markAttachment(current.section, current.fetched, done = false, busy = false)
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

    // Before open or save: sniff the first 4 KB (the cached file, else a BODY.PEEK partial fetch, which neither
    // downloads the part nor sets \Seen) and compare it with the claimed type and the extension.
    fun checkTypeThen(row: AttachmentRow, action: AttachmentAction, proceed: () -> Unit) {
        scope.launch {
            val cached = File(appContext.cacheDir, attachmentCacheName(cacheToken, uid, row.section))
            var tail = ByteArray(0)
            val head = try {
                if (cached.isFile && cached.length() > 0) {
                    // F54: the cached copy is decoded, so the ZIP central directory at its end can be read too.
                    java.io.RandomAccessFile(cached, "r").use { f ->
                        val len = f.length()
                        val from = maxOf(0L, len - SniffTailBytes)
                        tail = ByteArray((len - from).toInt()).also { f.seek(from); f.readFully(it) }
                    }
                    cached.inputStream().use { input ->
                        val buf = ByteArray(SniffBytes)
                        val n = input.read(buf)
                        if (n > 0) buf.copyOf(n) else ByteArray(0)
                    }
                } else {
                    gate.withLock {
                        peekRef(row.section, 0, SniffBytes)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ByteArray(0)
            }
            val mismatch = typeMismatch("${row.type}/${row.subtype}", row.filename, head, tail)
            if (mismatch == null) proceed() else typeWarning = TypeWarning(row, mismatch, action)
        }
    }

    fun openAttachmentAs(row: AttachmentRow, mime: String) {
        val uri = attachmentUri(attachmentCacheName(cacheToken, uid, row.section))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(row.label, uri)
        }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            postSnack(noAppFound)
        }
    }

    fun shareAttachmentAs(row: AttachmentRow, mime: String) {
        val uri = attachmentUri(attachmentCacheName(cacheToken, uid, row.section))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
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

    fun saveAttachmentNow(row: AttachmentRow) {
        saveTarget.row = row
        saveTarget.uid = uid
        saveDocument.launch(row.label)
    }

    fun openAttachment(row: AttachmentRow) = checkTypeThen(row, AttachmentAction.Open) { openAttachmentAs(row, attachmentMime(row)) }

    fun saveAttachment(row: AttachmentRow) = checkTypeThen(row, AttachmentAction.Save) { saveAttachmentNow(row) }

    fun shareAttachment(row: AttachmentRow) = checkTypeThen(row, AttachmentAction.Share) { shareAttachmentAs(row, attachmentMime(row)) }

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
            textLayout = settings.readerTextLayout
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@withLock
            }
            val opened = try {
                session.openBound(store, settings)
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
                val tree = inRef { _, s ->
                    s.fetchStructure(uid)
                }
                structure = tree
                attachments = attachmentParts(tree).map { part -> attachmentRowOf(part) }
                val row = try {
                    inRef { _, s -> s.fetchIndex(
                        IndexRequest(
                            mailbox = mailbox,
                            mode = IndexMode.ByUid,
                            uids = listOf(uid),
                            limit = 1,
                            prefetch = 0,
                            includePreview = false,
                        ),
                    ).firstOrNull { it.uid == uid } }
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
                    val headerText = readSectionText(section = "HEADER")
                    val fields = headerFields(headerText)
                    viewerExtra = viewerHeaderLines(headerText, settings.viewerHeaders)
                    if (fields.from.contains('@')) headerFrom = fields.from
                    headerTo = fields.to
                    headerCc = fields.cc
                    headerSender = fields.sender
                    headerResentTo = fields.resentTo
                    headerListId = fields.listId
                    // "Always show images from this sender / list": open with remote images allowed.
                    if (imagesTrusted(imageTrust, headerFrom, headerListId)) allowImages = true
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

    fun nextAfterDeleteOrMove(useFollowingAfterDelete: Boolean = false): Pair<Long, Int>? {
        if (!OpenMessageOrder.matches(accountId, mailbox, readerEpoch)) return null // F93
        val next = if (useFollowingAfterDelete) {
            OpenMessageOrder.followingAfterDelete(uid)
        } else {
            followingUid(OpenMessageOrder.uids, uid)
        } ?: return null
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
                helper.undo(offer)
                // F98: the undo selects other folders; never trust the reader's remembered selection after it.
                selectedMailbox = null
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
                    inRef { _, s -> // F43: expunge these UIDs in this folder, not wherever the session is
                        s.uidExpunge(uids)
                    }
                    selectedMailbox = mailbox
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
        if (linkSafety(url) == LinkSafety.Blocked) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    inRef { _, s -> // F43: act on this folder's UID
                        s.storeFlags(listOf(uid), emptySet(), setOf("\\Deleted"))
                    }
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
        val target = nextAfterDeleteOrMove(useFollowingAfterDelete = true)
        try {
            when (policy) {
                DeletePolicy.MarkDeleted -> {
                    val epoch = inRef { e, s -> // F43: act on this folder's UID
                        s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        e
                    }
                    showActionUndo(refUndo(account, mailbox, epoch, uid), target)
                }
                DeletePolicy.MoveToTrash -> {
                    val trash = session.knownTrash()
                    knownTrashName = trash
                    if (trash.isEmpty()) return
                    // F116: select, act and read the COPYUID result as one unit
                    val (epoch, targetEpoch, dest) = inRef { e, s -> // F43: act on this folder's UID
                        s.copyThenDelete(listOf(uid), trash)
                        Triple(e, s.copiedEpoch(), s.takeCopiedUids())
                    }
                    val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                    showActionUndo(refUndo(account, mailbox, epoch, uid, trash, dest, targetEpoch, usedMove), target)
                }
                DeletePolicy.DeletePermanently -> {
                    if (!session.featureCaps.uidPlus) return
                    inRef { _, s -> // F43: act on this folder's UID
                        s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        s.uidExpunge(listOf(uid))
                    }
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
        if (sameMailbox(spamMailbox, mailbox)) {
            postSnack("Already in ${mailboxLeaf(spamMailbox)}") // F103
            return
        }
        scope.launch {
            gate.withLock {
                val target = nextAfterDeleteOrMove()
                try {
                    // F116: select, act and read the COPYUID result as one unit
                    val (epoch, targetEpoch, dest) = inRef { e, s -> // F43: act on this folder's UID
                        s.copyThenDelete(listOf(uid), spamMailbox)
                        Triple(e, s.copiedEpoch(), s.takeCopiedUids())
                    }
                    val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                    showActionUndo(
                        refUndo(account, mailbox, epoch, uid, spamMailbox, dest, targetEpoch, usedMove),
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

    /** Opens/closes all headers in the header card; the first open fetches BODY.PEEK[HEADER], capped. */
    fun toggleAllHeaders() {
        allHeadersOpen = !allHeadersOpen
        if (!allHeadersOpen || allHeaderText != null) return
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                try {
                    val (text, truncated) = readCappedText(RawViewCapBytes) { offset, length ->
                        peekRef("HEADER", offset, length)
                    }
                    allHeaderText = text
                    allHeadersTruncated = truncated
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
            }
        }
    }

    fun runReaderAction(action: ReaderAction) {
        when (action) {
            ReaderAction.Reply ->
                onCompose(ComposeSeed(ComposeKind.Reply, mailbox, listOf(uid), readerEpoch))
            ReaderAction.ReplyAll ->
                onCompose(ComposeSeed(ComposeKind.ReplyAll, mailbox, listOf(uid), readerEpoch))
            ReaderAction.Forward ->
                onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid), readerEpoch))
            ReaderAction.Delete -> runDelete()
            ReaderAction.Move -> choosingMove = true
            ReaderAction.Spam -> runSpam()
            ReaderAction.Bounce ->
                onCompose(ComposeSeed(ComposeKind.Bounce, mailbox, listOf(uid), readerEpoch))
            ReaderAction.Headers -> toggleAllHeaders()
        }
    }

    fun saveMailboxView(transform: (FolderView) -> FolderView) {
        scope.launch {
            try {
                saveMutex.withLock {
                    val updated = store.update { loaded ->
                        val current = loaded.folderViews[mailbox] ?: loaded.defaultView
                        loaded.copy(folderViews = loaded.folderViews + (mailbox to transform(current)))
                    }
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
    val plainFont = if (textLayout == TextLayout.Monospaced) FontFamily.Monospace else null
    val noWrap = textLayout == TextLayout.Monospaced
    val textZoom = effectiveTextZoom(account.readerTextZoom, pinchZoom)
    linkBridge.onLink = { url -> pendingLink = url }
    val plainLines = remember(bodyText, quoteTint, linkColor, linkBridge, textLayout, plainFlowed, plainDelSp) {
        displayLines(readerLines(bodyText), textLayout, plainFlowed, plainDelSp).map { line ->
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
        modifier = Modifier.fillMaxSize().testTag("smoke-reader"),
        topBar = {
            TopAppBar(
                title = { MailboxTitleLines(heading.leaf, heading.parent) },
                navigationIcon = {
                    HelpIconButton(
                        label = stringResource(R.string.reader_back),
                        help = stringResource(R.string.help_reader_back),
                        page = "reading.html#back",
                        onClick = onBack,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                actions = {
                    HelpIconButton(
                        label = stringResource(
                            if (headerExpanded) R.string.reader_collapse_header
                            else R.string.reader_expand_header,
                        ),
                        help = stringResource(
                            if (headerExpanded) R.string.help_reader_collapse_header
                            else R.string.help_reader_expand_header,
                        ),
                        page = "reading.html#header",
                        onClick = { headerExpanded = !headerExpanded },
                    ) {
                        Icon(
                            imageVector = if (headerExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (headerExpanded) R.string.reader_collapse_header
                                else R.string.reader_expand_header,
                            ),
                        )
                    }
                    DebugStatusIcon(debugStatus)
                    if (renderedHtml) {
                        HelpIconButton(
                            label = stringResource(
                                if (allowImages) R.string.reader_hide_images
                                else R.string.reader_show_images,
                            ),
                            help = stringResource(
                                if (allowImages) R.string.help_reader_hide_images
                                else R.string.help_reader_show_images,
                            ),
                            page = "reading.html#images",
                            onClick = { allowImages = !allowImages },
                        ) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = stringResource(
                                        if (allowImages) R.string.reader_hide_images
                                        else R.string.reader_show_images,
                                    ),
                                    modifier = Modifier.size(24.dp),
                                )
                                if (allowImages) {
                                    Canvas(Modifier.matchParentSize()) {
                                        val stroke = 2.dp.toPx()
                                        val inset = stroke / 2f
                                        drawCircle(
                                            color = Color.Red,
                                            radius = (size.minDimension / 2f) - inset,
                                            style = Stroke(width = stroke),
                                        )
                                        drawLine(
                                            color = Color.Red,
                                            start = Offset(inset, size.height - inset),
                                            end = Offset(size.width - inset, inset),
                                            strokeWidth = stroke,
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                                    val label = if (reader == ReaderAction.Delete) {
                                        deletePolicyLabel(effectivePolicy)
                                    } else {
                                        readerActionName(reader)
                                    }
                                    val helpRes = when (reader) {
                                        ReaderAction.Reply -> R.string.help_reader_reply
                                        ReaderAction.ReplyAll -> R.string.help_reader_reply_all
                                        ReaderAction.Forward -> R.string.help_reader_forward
                                        ReaderAction.Delete -> R.string.help_reader_delete
                                        else -> null
                                    }
                                    val helpPage = when (reader) {
                                        ReaderAction.Reply,
                                        ReaderAction.ReplyAll,
                                        ReaderAction.Forward,
                                        -> "reading.html#reply"
                                        ReaderAction.Delete -> "reading.html#delete"
                                        else -> null
                                    }
                                    if (helpRes != null && helpPage != null) {
                                        HelpIconButton(
                                            label = label,
                                            help = stringResource(helpRes),
                                            page = helpPage,
                                            onClick = { runReaderAction(reader) },
                                        ) {
                                            Icon(
                                                imageVector = readerActionImage(reader),
                                                contentDescription = label,
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = { runReaderAction(reader) }) {
                                            Icon(
                                                imageVector = readerActionImage(reader),
                                                contentDescription = label,
                                            )
                                        }
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
                HelpIconButton(
                    label = stringResource(R.string.reader_more),
                    help = stringResource(R.string.help_reader_more),
                    page = "reading.html#more",
                    onClick = { moreMenu = true },
                ) {
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
                        scope.launch {
                            // F60: the personal namespace's delimiter, so a sender's "a.b" stays one folder name.
                            val delimiter = try {
                                session.namespaces().firstOrNull { it.kind == org.dlang.liveimap.session.NamespaceKind.Personal }?.delimiter ?: '.'
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                '.' // unknown: be conservative
                            }
                            val offered = saveFolderName(
                                account.saveNameRule,
                                account.savedMailbox,
                                account.lastSaveMailbox,
                                headerFrom,
                                headerSender,
                                headerTo,
                                headerResentTo,
                                delimiter,
                            )
                            if (offered.isNotEmpty()) {
                                saveOffer = offered
                                confirmSave = true
                            } else {
                                choosingSave = true
                            }
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
                    text = { Text(stringResource(R.string.reader_filter_like)) },
                    onClick = {
                        moreMenu = false
                        onFilterLike(headerFrom, headerTo, listIdKey(headerListId), headerSubject)
                    },
                )
                if (renderedHtml && dark) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (originalColors) R.string.reader_dark_colors else R.string.reader_original_colors,
                                ),
                            )
                        },
                        onClick = {
                            moreMenu = false
                            originalColors = !originalColors
                        },
                        modifier = Modifier.testTag("reader-original-colors"),
                    )
                }
                if (renderedHtml && trustSenderKey(headerFrom).isNotEmpty()) {
                    val on = trustSenderKey(headerFrom) in imageTrust.senders
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (on) R.string.reader_images_sender_off else R.string.reader_images_sender_on,
                                    trustSenderKey(headerFrom),
                                ),
                            )
                        },
                        onClick = {
                            moreMenu = false
                            imageTrust = withSender(imageTrust, headerFrom, !on)
                            saveImageTrust(context.filesDir, accountId, imageTrust)
                            if (!on) allowImages = true
                        },
                        modifier = Modifier.testTag("reader-images-sender"),
                    )
                }
                if (renderedHtml && listIdKey(headerListId).isNotEmpty()) {
                    val on = listIdKey(headerListId) in imageTrust.lists
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (on) R.string.reader_images_list_off else R.string.reader_images_list_on,
                                    listIdKey(headerListId),
                                ),
                            )
                        },
                        onClick = {
                            moreMenu = false
                            imageTrust = withList(imageTrust, headerListId, !on)
                            saveImageTrust(context.filesDir, accountId, imageTrust)
                            if (!on) allowImages = true
                        },
                        modifier = Modifier.testTag("reader-images-list"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(forwardStyleName(account.forwardAsAttachment)) },
                    onClick = {
                        moreMenu = false
                        armForwardOnce(!account.forwardAsAttachment)
                        onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid), readerEpoch))
                    },
                )
                // HTML and plain text are positions of the display selector; the menu keeps the other views.
                BodyView.entries.filter { it != BodyView.PlainOrHtml && it != BodyView.PlainOrError }.forEach { view ->
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
        val absent = missing
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (headerExpanded && headerReady) {
                ReaderHeaderCard(
                    from = headerFrom,
                    toLine = recipientLine(headerTo, headerCc),
                    date = headerDate,
                    subject = headerSubject,
                    deleted = "\\Deleted" in rowFlags,
                    onUndelete = { undeleteMessage() },
                    allHeaders = allHeadersOpen,
                    allHeaderText = allHeaderText,
                    allHeadersTruncated = allHeadersTruncated,
                    decode = decodeHeaderView,
                    onDecode = { decodeHeaderView = it },
                    onToggleAllHeaders = { toggleAllHeaders() },
                    extraHeaders = viewerExtra,
                )
            }
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
                if (selectedView != BodyView.Headers && selectedView != BodyView.Raw) {
                    ReaderDisplaySelector(
                        selected = readerDisplayOf(selectedView, textLayout),
                        onSelect = { display ->
                            textLayout = textLayoutOf(display, textLayout)
                            val view = bodyViewOf(display, selectedView)
                            if (view != selectedView) requestView(view)
                        },
                    )
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
                        AndroidView(
                            factory = { webContext ->
                                object : WebView(webContext) {
                                    private var postedWidth = 0
                                    private var postedHeight = 0

                                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                                        super.onSizeChanged(w, h, oldw, oldh)
                                        if (w > 0 && h > 0 && (w != postedWidth || h != postedHeight)) {
                                            postedWidth = w
                                            postedHeight = h
                                            post {
                                                requestLayout()
                                                invalidate()
                                            }
                                        }
                                    }
                                }.apply {
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

                                        // Network loads are only on while "show images" is; even then, only images.
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): android.webkit.WebResourceResponse? {
                                            if (request == null) return null
                                            val accept = request.requestHeaders?.entries
                                                ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
                                            if (remoteRequestAllowed(request.url?.scheme, accept, request.isForMainFrame)) return null
                                            return android.webkit.WebResourceResponse(
                                                "text/plain",
                                                "utf-8",
                                                java.io.ByteArrayInputStream(ByteArray(0)),
                                            )
                                        }

                                        // minSdk 26: only the request overload is called. Never prompt from it.
                                        @Deprecated("Deprecated in API 24")
                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            // Every navigation is cancelled; only a real tap on a main-frame link
                                            // (not a meta refresh or other redirect) may prompt.
                                            if (request != null &&
                                                navigationMayPrompt(request.hasGesture(), request.isRedirect, request.isForMainFrame)
                                            ) {
                                                confirm(request.url?.toString())
                                            }
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
                                // Text size setting; pinch-zoom is the WebView's own (no on-screen zoom buttons).
                                view.settings.textZoom = clampTextZoom(account.readerTextZoom)
                                view.settings.setSupportZoom(true)
                                view.settings.builtInZoomControls = true
                                view.settings.displayZoomControls = false
                                view.settings.blockNetworkLoads = !allowImages
                                // Original colors (dark theme only): the sender's own colors on a white page.
                                val recolor = dark && !originalColors
                                view.setBackgroundColor(if (dark && originalColors) android.graphics.Color.WHITE else htmlBackground)
                                applyHtmlDark(view.settings)
                                val page = themedHtml(bodyText, recolor, htmlBackground, htmlForeground)
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
                    absent == null && (selectedView == BodyView.Headers || selectedView == BodyView.Raw) -> {
                        RawTextView(
                            text = bodyText,
                            headers = selectedView == BodyView.Headers,
                            truncated = rawTruncated,
                            decode = decodeHeaderView,
                            onDecode = { decodeHeaderView = it },
                            scroll = scroll,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    absent == null -> {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .pinchToZoom { factor ->
                                    pinchZoom = effectiveTextZoom(account.readerTextZoom, pinchZoom * factor) /
                                        clampTextZoom(account.readerTextZoom).toFloat()
                                }
                                .verticalScroll(scroll)
                                .padding(8.dp)
                                .testTag("reader-plain"),
                        ) {
                          val base = LocalTextStyle.current
                          CompositionLocalProvider(LocalTextStyle provides base.copy(fontSize = base.fontSize * (textZoom / 100f))) {
                            val note = charsetNote
                            if (note != null) Text(note)
                            // Monospaced: original breaks, no wrapping; the whole body scrolls sideways together.
                            val hscroll = rememberScrollState()
                            Column(if (noWrap) Modifier.horizontalScroll(hscroll).testTag("reader-nowrap") else Modifier) {
                                for ((quoted, line) in plainLines) {
                                    if (quoted && !noWrap) {
                                        QuotedReaderLine(line, quoteTint, quoteBorder, plainFont)
                                    } else if (noWrap) {
                                        Text(text = line, fontFamily = plainFont, softWrap = false)
                                    } else {
                                        Text(text = line, fontFamily = plainFont, modifier = Modifier.fillMaxWidth())
                                    }
                                }
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
    val warning = typeWarning
    if (warning != null) {
        val m = warning.mismatch
        val openAs = safeAttachmentMime(m.detected.mime.substringBefore('/'), m.detected.mime.substringAfter('/'))
        TypeMismatchDialog(
            name = warning.row.filename ?: warning.row.label,
            mismatch = m,
            openAs = openAs,
            share = warning.action == AttachmentAction.Share,
            onDismiss = { typeWarning = null },
            onSave = {
                typeWarning = null
                saveAttachmentNow(warning.row)
            },
            onOpenAs = {
                typeWarning = null
                val row = warning.row
                when {
                    !row.done -> postSnack(notConnected)
                    warning.action == AttachmentAction.Share -> shareAttachmentAs(row, openAs)
                    else -> openAttachmentAs(row, openAs)
                }
            },
        )
    }
    val confirmedLink = pendingLink
    if (confirmedLink != null) {
        val safety = linkSafety(confirmedLink)
        val check = remember(confirmedLink, bodyText, renderedHtml) {
            checkLink(confirmedLink, if (renderedHtml) bodyText else null)
        }
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = if (check.host.isNotEmpty()) {
                { Text(check.host, modifier = Modifier.testTag("link-host")) }
            } else null,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (check.claimedHost.isNotEmpty()) {
                        Text(
                            stringResource(R.string.reader_link_mismatch, check.claimedHost, check.host),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("link-mismatch"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (check.asciiHost.isNotEmpty()) {
                        Text(
                            stringResource(R.string.reader_link_idn, check.asciiHost),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("link-idn"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    SelectionContainer { Text(confirmedLink.take(2000), style = MaterialTheme.typography.bodySmall) }
                    when (safety) {
                        LinkSafety.Safe -> {}
                        LinkSafety.Warn -> Text("\n" + stringResource(R.string.reader_link_warn))
                        LinkSafety.Blocked -> Text("\n" + stringResource(R.string.reader_link_blocked))
                    }
                }
            },
            confirmButton = {
                if (safety != LinkSafety.Blocked) {
                    TextButton(onClick = {
                        pendingLink = null
                        openConfirmedLink(confirmedLink)
                    }) {
                        Text(
                            stringResource(R.string.reader_open),
                            color = if (safety == LinkSafety.Warn || check.claimedHost.isNotEmpty() || check.asciiHost.isNotEmpty()) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    }
                }
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
                if (picked.isNotEmpty() && sameMailbox(picked, mailbox)) {
                    postSnack("Already in ${mailboxLeaf(picked)}") // F103
                } else if (picked.isNotEmpty()) {
                    scope.launch {
                        gate.withLock {
                            val target = nextAfterDeleteOrMove()
                            try {
                                // F116: select, act and read the COPYUID result as one unit
                                val (epoch, targetEpoch, dest) = inRef { e, s -> // F43: this folder's UID
                                    s.copyThenDelete(listOf(uid), picked)
                                    Triple(e, s.copiedEpoch(), s.takeCopiedUids())
                                }
                                val usedMove = moveCommandKind(account.moveMethod, session.featureCaps.move) == "Move"
                                showActionUndo(
                                    refUndo(account, mailbox, epoch, uid, picked, dest, targetEpoch, usedMove),
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
            exclude = mailbox,
        )
    }
    fun copySavedMessage(destination: String) {
        if (destination.isEmpty()) return
        scope.launch {
            var copied = false
            gate.withLock {
                try {
                    inRef { _, s -> // F126: copy this folder's UID, not the same number wherever the session is
                        s.copyUids(listOf(uid), destination)
                    }
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
                    val updated = store.update { it.copy(lastSaveMailbox = destination) }
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
        ReaderAction.Headers -> R.string.reader_all_headers
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
    ReaderToolbarAction.Headers -> ReaderAction.Headers
}

private fun readerActionImage(action: ReaderAction) = when (action) {
    ReaderAction.Reply -> Icons.AutoMirrored.Filled.Reply
    ReaderAction.ReplyAll -> Icons.AutoMirrored.Filled.ReplyAll
    ReaderAction.Forward -> Icons.AutoMirrored.Filled.Forward
    ReaderAction.Delete -> Icons.Filled.Delete
    ReaderAction.Move -> moveImage
    ReaderAction.Spam -> spamImage
    ReaderAction.Bounce -> Icons.AutoMirrored.Filled.Redo
    ReaderAction.Headers -> Icons.Filled.Code
}

@Composable
private fun ReaderHeaderCard(
    from: String,
    toLine: String?,
    date: String,
    subject: String,
    deleted: Boolean,
    onUndelete: () -> Unit,
    allHeaders: Boolean = false,
    allHeaderText: String? = null,
    allHeadersTruncated: Boolean = false,
    decode: Boolean = false,
    onDecode: (Boolean) -> Unit = {},
    onToggleAllHeaders: () -> Unit = {},
    extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    var recipientsOpen by remember(toLine) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(org.dlang.liveimap.ui.nonAscii(stringResource(R.string.reader_from, from)), modifier = Modifier.weight(1f))
                // Short header <-> all headers, in place (Pine's H).
                IconButton(onClick = onToggleAllHeaders, modifier = Modifier.testTag("reader-all-headers")) {
                    Icon(
                        if (allHeaders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (allHeaders) R.string.reader_short_headers else R.string.reader_all_headers,
                        ),
                    )
                }
            }
            if (allHeaders) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = decode,
                        onCheckedChange = onDecode,
                        modifier = Modifier.testTag("reader-decode-headers"),
                    )
                    Text(stringResource(R.string.reader_decode_headers), modifier = Modifier.padding(start = 8.dp))
                }
                if (allHeadersTruncated) Text(stringResource(R.string.reader_raw_truncated, RawViewCapBytes / 1024))
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = allHeaderText?.let { headerViewText(it, decode) } ?: "…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().testTag("reader-all-headers-text"),
                        )
                    }
                }
            }
            for ((name, value) in extraHeaders) {
                Text(
                    org.dlang.liveimap.ui.nonAscii("$name: $value"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("reader-viewer-header"),
                )
            }
            if (toLine != null) {
                // The tap target is a full-width row at least 48dp high, so Compose does not grow the touch bounds of
                // a one-line Text past its own line over the From and Date lines.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { recipientsOpen = !recipientsOpen }
                        .testTag("reader-recipients"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = toLine,
                        maxLines = if (recipientsOpen) Int.MAX_VALUE else 1,
                        overflow = if (recipientsOpen) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }
            Text(date)
            Text(org.dlang.liveimap.ui.nonAscii(subject), style = MaterialTheme.typography.titleMedium)
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

/** [dark] recolors the page to the app theme; "Original colors" passes dark = false (sender's colors, white page). */
internal fun themedHtml(page: String, dark: Boolean, background: Int, foreground: Int): String {
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
    return "<meta charset=\"utf-8\">" + stripMetaRefresh(styled)
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

internal fun attachmentRowOf(part: MimePart): AttachmentRow = AttachmentRow(
    section = part.section,
    label = if (part.subtype.equals(LibetpanTooDeepSubtype, ignoreCase = true)) {
        "structure too complex (raw part)"
    } else {
        part.filename?.takeIf { it.isNotEmpty() } ?: "${part.type}/${part.subtype}"
    },
    size = part.size,
    fetched = 0,
    done = false,
    type = part.type,
    subtype = part.subtype,
    filename = part.filename?.takeIf { it.isNotEmpty() },
)

/** Rows that stand for a deeper subtree rather than content: tapping opens the next level. */
internal fun isNestedPart(row: AttachmentRow): Boolean =
    row.subtype.equals(TooDeepSubtype, ignoreCase = true) || row.type.equals("multipart", ignoreCase = true) ||
        (row.type.equals("message", ignoreCase = true) && row.size == 0)

private fun attachmentMime(row: AttachmentRow): String = safeAttachmentMime(row.type, row.subtype)

internal fun readerCacheToken(): String {
    val bytes = ByteArray(8)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

internal fun attachmentCacheName(token: String, uid: Long, section: String): String {
    val safe = section.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "liveimap-$token-$uid-$safe"
}

private fun attachmentUri(name: String): Uri = Uri.parse("content://org.dlang.liveimap.attachments/$name")

private val staleCacheCleared = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Once per process, before any reader opens: remove decoded attachments a crash or kill left behind (F16).
 * Every open uses a fresh token, so no file from an earlier process can still be in use.
 */
fun clearStaleAttachmentCache(dir: File) {
    if (!staleCacheCleared.compareAndSet(false, true)) return
    deleteStaleAttachments(dir)
}

internal fun deleteStaleAttachments(dir: File) {
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (file.isFile && file.name.startsWith("liveimap-")) file.delete()
    }
}

private fun deleteReaderAttachmentCache(dir: File, token: String) {
    val prefix = "liveimap-$token-"
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

internal fun readerLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    // CRLF line ends: drop the CR so lines carry no stray control character.
    val parts = text.split('\n').map { it.removeSuffix("\r") }
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

/** Header and raw-source views stop after this many bytes, so a huge or hostile message cannot exhaust memory. */
const val RawViewCapBytes = 1 shl 20

/**
 * Reads a section in 4 KB partial fetches, stopping at [cap] bytes; returns the UTF-8 text (malformed bytes replaced)
 * and whether it stopped at the cap.
 */
suspend fun readCappedText(cap: Int, peek: suspend (offset: Int, length: Int) -> ByteArray): Pair<String, Boolean> {
    val out = java.io.ByteArrayOutputStream()
    while (out.size() < cap) {
        val length = minOf(4096, cap - out.size())
        val chunk = peek(out.size(), length)
        out.write(chunk)
        if (chunk.size < length) return out.toByteArray().toString(Charsets.UTF_8) to false
    }
    // At the cap: one more byte tells whether anything was cut.
    val more = peek(out.size(), 1).isNotEmpty()
    return out.toByteArray().toString(Charsets.UTF_8) to more
}

/** The header block as shown: raw bytes as text, or with RFC 2047 encoded-words decoded line by line on request. */
fun headerViewText(raw: String, decode: Boolean): String =
    if (!decode) raw else raw.lineSequence().joinToString("\n") { org.dlang.liveimap.ui.compose.decodeHeaderWordsForView(it) }

/** Unparsed, selectable text in a monospace font: no links, no HTML, nothing rendered. */
@Composable
private fun RawTextView(
    text: String,
    headers: Boolean,
    truncated: Boolean,
    decode: Boolean,
    onDecode: (Boolean) -> Unit,
    scroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(scroll).padding(8.dp)) {
        if (headers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = decode,
                    onCheckedChange = onDecode,
                    modifier = Modifier.testTag("reader-decode-headers"),
                )
                Text(stringResource(R.string.reader_decode_headers), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (truncated) Text(stringResource(R.string.reader_raw_truncated, RawViewCapBytes / 1024))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = if (headers) headerViewText(text, decode) else text,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().testTag("reader-raw-text"),
            )
        }
    }
}

/** Two-finger pinch only: reports each zoom step and consumes it; one-finger drags stay with scrolling. */
internal fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z != 1f && !z.isNaN()) {
                    onZoom(z)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Warning shown when an attachment's content does not match its claimed type or extension. */
@Composable
internal fun TypeMismatchDialog(
    name: String,
    mismatch: TypeMismatch,
    openAs: String,
    share: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onOpenAs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_type_mismatch_title)) },
        text = {
            Text(
                stringResource(
                    R.string.reader_type_mismatch_body,
                    name,
                    mismatch.claimed,
                    mismatch.extension ?: "-",
                    "${mismatch.detected.label} (${mismatch.detected.mime})",
                ) + if (mismatch.detected.dangerous) "\n\n" + stringResource(R.string.reader_type_mismatch_danger) else "",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSave) { Text(stringResource(R.string.reader_type_mismatch_save)) }
                TextButton(onClick = onOpenAs) {
                    Text(
                        stringResource(
                            if (share) R.string.reader_type_mismatch_share else R.string.reader_type_mismatch_open,
                            openAs,
                        ),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderDisplaySelector(selected: ReaderDisplay, onSelect: (ReaderDisplay) -> Unit) {
    val entries = ReaderDisplay.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("reader-display")) {
        entries.forEachIndexed { index, display ->
            SegmentedButton(
                selected = display == selected,
                onClick = { onSelect(display) },
                shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                modifier = Modifier.testTag("reader-display-${display.name}"),
            ) { Text(readerDisplayName(display)) }
        }
    }
}

@Composable
internal fun readerDisplayName(display: ReaderDisplay): String = stringResource(
    when (display) {
        ReaderDisplay.Html -> R.string.display_html
        ReaderDisplay.Text -> R.string.display_text
        ReaderDisplay.Flowed -> R.string.display_flowed
        ReaderDisplay.Monospaced -> R.string.display_monospaced
    },
)
