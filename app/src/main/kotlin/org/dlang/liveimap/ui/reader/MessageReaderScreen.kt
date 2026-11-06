package org.dlang.liveimap.ui.reader
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.settings.SettingsTreeGenerated

import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.usableTrashName
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import org.dlang.liveimap.session.LibetpanTooDeepSubtype
import org.dlang.liveimap.ui.compose.nestedLeafParts
import org.dlang.liveimap.session.TooDeepSubtype
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.ObscuredTouchFilter
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.FileOutputStream
import org.dlang.liveimap.ui.compose.MimeDecodeException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.ui.help.HelpDropdownMenuItem
import org.dlang.liveimap.ui.help.HelpIconButton
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.ui.compose.oppositeForwardLabel
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

import org.dlang.liveimap.settings.globalSettingsRecord
import org.dlang.liveimap.settings.clampTextZoom
import org.dlang.liveimap.settings.effectiveTextZoom
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.AddressBookEntry
import org.dlang.liveimap.settings.AddressSave
import org.dlang.liveimap.settings.addressSave
import org.dlang.liveimap.settings.androidSourceId
import org.dlang.liveimap.settings.saveBookChoices
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.SwipeBinding
import org.dlang.liveimap.settings.TextLayout
import org.dlang.liveimap.settings.DataStoreSettingsStore

import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.settings.moveCommandKind
import org.dlang.liveimap.ui.contacts.AlpineEntry
import org.dlang.liveimap.ui.contacts.AndroidContacts
import org.dlang.liveimap.ui.contacts.CopyContact
import org.dlang.liveimap.ui.contacts.PineRead
import org.dlang.liveimap.ui.contacts.PineWriteResult
import org.dlang.liveimap.ui.contacts.androidEntriesOnce
import org.dlang.liveimap.ui.contacts.bookHasAddress
import org.dlang.liveimap.ui.contacts.hasReadContacts
import org.dlang.liveimap.ui.contacts.readPineBook
import org.dlang.liveimap.ui.contacts.writeAndroidContacts
import org.dlang.liveimap.ui.contacts.writePineBook
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.ui.LocalScreenAccounts
import org.dlang.liveimap.ui.DebugStatusIcon
import org.dlang.liveimap.ui.folder.MailboxChooser
import org.dlang.liveimap.ui.index.IndexModel
import org.dlang.liveimap.ui.index.MailboxTitle
import org.dlang.liveimap.ui.index.alternateDeletePolicies
import org.dlang.liveimap.ui.index.deleteCapabilityWarn
import org.dlang.liveimap.ui.index.effectiveDeletePolicy
import org.dlang.liveimap.ui.index.formatIndexDate
import org.dlang.liveimap.ui.index.MailboxTitleLines
import org.dlang.liveimap.ui.index.mailboxTitleFor
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets
import org.dlang.liveimap.ui.index.MailUndo
import org.dlang.liveimap.ui.index.OpenMessageOrder
import org.dlang.liveimap.window.IndexRecord
import org.dlang.liveimap.window.MessageAfterDelete
import org.dlang.liveimap.window.afterDelete
import org.dlang.liveimap.window.next
import org.dlang.liveimap.window.previous
import org.dlang.liveimap.ui.index.swipeWidthPercent
import org.dlang.liveimap.ui.index.followingUid
import org.dlang.liveimap.ui.index.precedingUid
import org.dlang.liveimap.ui.index.swipeBindingForOffset
import org.dlang.liveimap.ui.index.swipeReached
import org.dlang.liveimap.ui.index.swipeVisual
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
    val committed = ArrayList<String>()
    var linePartial: String = ""
    private var htmlFile: java.io.File? = null
    private var htmlOut: java.io.OutputStream? = null

    fun closeDecoder() {
        decoder?.close()
        decoder = null
    }

    fun clearText() {
        committed.clear()
        linePartial = ""
        htmlOut?.close()
        htmlOut = null
        htmlFile?.delete()
        htmlFile = null
    }

    /** Appends only newly complete lines. HTML body text is read back from its file when the part is finished. */
    fun appendPiece(piece: String, lines: MutableList<String>, finished: Boolean, html: Boolean): String? {
        if (piece.isNotEmpty()) {
            if (html) {
                if (htmlOut == null) {
                    val file = java.io.File.createTempFile("liveimap-body-", ".html")
                    htmlFile = file
                    htmlOut = java.io.FileOutputStream(file)
                }
                htmlOut?.write(piece.toByteArray(Charsets.UTF_8))
            }
            val parts = (linePartial + piece).split('\n')
            linePartial = parts.last().removeSuffix("\r")
            val complete = parts.dropLast(1).map { it.removeSuffix("\r") }
            if (complete.isNotEmpty()) {
                committed.addAll(complete)
                lines.addAll(complete)
            }
        }
        if (finished && linePartial.isNotEmpty()) {
            committed.add(linePartial)
            lines.add(linePartial)
            linePartial = ""
        }
        if (finished && html) {
            htmlOut?.close()
            htmlOut = null
            val text = htmlFile?.readText() ?: ""
            htmlFile?.delete()
            htmlFile = null
            return text
        }
        return null
    }
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
    val path: String = "",
    val encoding: String = "",
)

internal class MessageReaderLoaded : ViewModel() {
    var openUid: Long = -1L
    var openMailbox: String = ""
    var openAccount: String = ""
    var openEpoch: Long = -1L
    var ready: Boolean = false
    var recordScroll: Boolean = true
    val carry = Utf8Carry()
    val scrollOffsetState = mutableIntStateOf(0)
    var webScroll: Int = 0
    val accountState = mutableStateOf(Settings())
    val structureState = mutableStateOf<MimePart?>(null)
    val selectedViewState = mutableStateOf(BodyView.PlainOrError)
    val messageDisplayState = mutableStateOf(ReaderDisplay.Monospaced)
    val textLayoutState = mutableStateOf(TextLayout.Monospaced)
    val plainFlowedState = mutableStateOf(false)
    val plainDelSpState = mutableStateOf(false)
    val renderedHtmlState = mutableStateOf(false)
    val bodyTextState = mutableStateOf("")
    val bodyLinesState = mutableStateListOf<String>()
    val bodyCompleteState = mutableStateOf(false)
    val bodyTruncatedState = mutableStateOf(false)
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

    override fun onCleared() {
        dropLoaded()
    }

    fun holds(ref: MessageRef): Boolean =
        ready && openAccount == ref.accountId && openMailbox == ref.mailbox &&
            openEpoch == ref.uidValidity && openUid == ref.uid

    fun dropLoaded() {
        openUid = -1L
        openMailbox = ""
        openAccount = ""
        openEpoch = -1L
        ready = false
        recordScroll = true
        carry.pending = ByteArray(0)
        carry.closeDecoder()
        carry.clearText()
        bodyLinesState.clear()
        bodyCompleteState.value = false
        scrollOffsetState.intValue = 0
        webScroll = 0
        accountState.value = Settings()
        structureState.value = null
        selectedViewState.value = BodyView.PlainOrError
        messageDisplayState.value = ReaderDisplay.Monospaced
        textLayoutState.value = TextLayout.Monospaced
        plainFlowedState.value = false
        plainDelSpState.value = false
        renderedHtmlState.value = false
        bodyTextState.value = ""
        bodyTruncatedState.value = false
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

    fun watchAccount(
        scope: kotlinx.coroutines.CoroutineScope,
        store: org.dlang.liveimap.settings.DataStoreSettingsStore,
        apply: (Settings) -> Unit,
    ) = scope.launch {
        val loaded = try {
            store.load()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (loaded != null) apply(loaded)
    }

    @Composable
    fun startWatch(
        scope: kotlinx.coroutines.CoroutineScope,
        store: org.dlang.liveimap.settings.DataStoreSettingsStore,
        apply: (Settings) -> Unit,
    ) {
        LifecycleStartEffect(store) {
            val job = watchAccount(scope, store, apply)
            onStopOrDispose { job.cancel() }
        }
    }
}

private class HtmlPull(val text: String, val note: String?, val truncated: Boolean, val wireRead: Int)

private class ReaderLabels(
    val noAppFound: String,
    val notConnected: String,
    val savedText: String,
    val noAddressBookText: String,
    val noAddressesText: String,
    val addressAddedText: String,
    val addressExistsText: String,
    val copyChangedText: String,
    val retryLabel: String,
    val noTextPartText: String,
    val shareTitle: String,
    val indexNow: String,
    val indexMin: String,
    val indexHour: String,
    val indexHours: String,
    val indexDay: String,
    val indexDays: String,
    val indexAgo: String,
    val indexAhead: String,
    val indexBadPattern: String,
)

@Composable
private fun readerLabels(): ReaderLabels = ReaderLabels(
    noAppFound = S("No app found"),
    notConnected = S("not connected"),
    savedText = S("Saved"),
    noAddressBookText = S("No writable address book. Nothing was saved."),
    noAddressesText = S("No addresses"),
    addressAddedText = S("Added to the address book"),
    addressExistsText = S("Already in the address book"),
    copyChangedText = S("The address book changed. Nothing was written."),
    retryLabel = S("Retry", name = "reader_retry"),
    noTextPartText = S("There is no text part"),
    shareTitle = S("Share"),
    indexNow = S("now"),
    indexMin = S("min"),
    indexHour = S("hour"),
    indexHours = S("hours"),
    indexDay = S("day"),
    indexDays = S("days"),
    indexAgo = S("%1\$d %2\$s ago"),
    indexAhead = S("in %1\$d %2\$s"),
    indexBadPattern = S("bad date pattern"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageReaderScreen(
    mailbox: String,
    uid: Long,
    sequence: Int,
    uidValidity: Long = 0L,
    onCompose: (ComposeSeed) -> Unit,
    onAdvance: (MessageRef, Int) -> Unit,
    onBack: () -> Unit,
    onFolderViewSaved: () -> Unit = {},
    onCustomize: () -> Unit = {},
    onFilterLike: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onMark: (Long?) -> Unit = {},
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
    ReaderScreen(
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
        onMark = onMark,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderScreen(
    accountId: String,
    store: DataStoreSettingsStore,
    mailbox: String,
    uid: Long,
    sequence: Int,
    uidValidity: Long = 0L,
    onCompose: (ComposeSeed) -> Unit,
    onAdvance: (MessageRef, Int) -> Unit,
    onBack: () -> Unit,
    onFolderViewSaved: () -> Unit = {},
    onCustomize: () -> Unit = {},
    onFilterLike: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onMark: (Long?) -> Unit = {},
) {
    val RawViewCapBytes = SettingsHub.uiNow().maxRawViewBytes.toInt()
    LocalScreenAccounts.current.Register(accountId)
    val context = LocalContext.current
    val appContext = context.applicationContext
    val labels = readerLabels()
    val noAppFound = labels.noAppFound
    val notConnected = labels.notConnected
    val savedText = labels.savedText
    val noAddressBookText = labels.noAddressBookText
    val noAddressesText = labels.noAddressesText
    val addressAddedText = labels.addressAddedText
    val addressExistsText = labels.addressExistsText
    val copyChangedText = labels.copyChangedText
    val retryLabel = labels.retryLabel
    val noTextPartText = labels.noTextPartText
    val shareTitle = labels.shareTitle
    val indexNow = labels.indexNow
    val indexMin = labels.indexMin
    val indexHour = labels.indexHour
    val indexHours = labels.indexHours
    val indexDay = labels.indexDay
    val indexDays = labels.indexDays
    val indexAgo = labels.indexAgo
    val indexAhead = labels.indexAhead
    val indexBadPattern = labels.indexBadPattern
    val session = remember(accountId) { mailSession(accountId) }
    val connectionState by session.connectionState.collectAsState()
    val debugStatus by TrafficLog.debugStatus.collectAsState()
    val messageRef = MessageRef(accountId, mailbox, uidValidity, uid)
    val held = viewModel<MessageReaderLoaded>(key = "pane")
    if (held.ready && !held.holds(messageRef)) held.dropLoaded()
    val reuseBody = held.holds(messageRef)
    remember {
        if (reuseBody) held.recordScroll = false
        true
    }
    val scope = rememberCoroutineScope()
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
    var deleteWarn by remember { mutableStateOf("") }
    var pendingReaderPolicy by remember { mutableStateOf(DeletePolicy.DeletePermanently) }
    var expungeUids by remember { mutableStateOf<List<Long>>(emptyList()) }
    val laterRetry = remember { LaterRetry() }
    var account by held.accountState
    var knownTrashName by remember(mailbox) { mutableStateOf(account.trashMailbox) }
    var structure by held.structureState
    var selectedView by held.selectedViewState
    var messageDisplay by held.messageDisplayState
    var textLayout by held.textLayoutState
    // RFC 3676 parameters of the plain part shown (format=flowed, DelSp=yes)
    var plainFlowed by held.plainFlowedState
    var plainDelSp by held.plainDelSpState
    var rawTruncated by remember { mutableStateOf(false) }
    var allHeadersOpen by remember(accountId, mailbox, uid, uidValidity) { mutableStateOf(false) }
    var allHeaderText by remember(accountId, mailbox, uid, uidValidity) { mutableStateOf<String?>(null) }
    var viewerExtra by remember(accountId, mailbox, uid, uidValidity) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allHeadersTruncated by remember(accountId, mailbox, uid, uidValidity) { mutableStateOf(false) }
    var decodeHeaderView by remember { mutableStateOf(false) }
    var renderedHtml by held.renderedHtmlState
    var bodyText by held.bodyTextState
    val bodyLines = held.bodyLinesState
    var bodyTruncated by held.bodyTruncatedState
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
    var imageTrust by remember(accountId) { mutableStateOf(ImageTrust()) }
    var trustCanSave by remember(accountId) { mutableStateOf(false) }
    var trustLoadFailed by remember(accountId) { mutableStateOf(false) }
    LaunchedEffect(accountId) {
        val result = store.onAccount {
            loadImageTrust(context.filesDir, accountId, SettingsHub.uiNow().maxImageTrustBytes.toInt().toLong())
        }
        if (result.error != null) {
            trustCanSave = false
            imageTrust = ImageTrust()
            trustLoadFailed = true
        } else {
            imageTrust = result.trust
            trustCanSave = true
        }
    }
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
    val appUi by org.dlang.liveimap.settings.SettingsHub.ui.collectAsState()
    var appTextZoom by remember { mutableIntStateOf(100) }
    LaunchedEffect(appUi.appTextZoom) {
        appTextZoom = clampTextZoom(appUi.appTextZoom)
    }
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
    var showFlags by remember { mutableStateOf(false) }
    var choosingSave by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var saveOffer by remember { mutableStateOf("") }
    var confirmTake by remember { mutableStateOf(false) }
    var takeChoices by remember { mutableStateOf<List<TakeAddress>>(emptyList()) }
    var pendingTake by remember { mutableStateOf<TakeAddress?>(null) }
    var pendingAndroidTake by remember { mutableStateOf<Pair<TakeAddress, AddressBookEntry>?>(null) }
    var headerExpanded by rememberSaveable { mutableStateOf(true) }
    var moreMenu by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }
    var iconRows by remember { mutableIntStateOf(1) }
    var bodyGeneration by remember { mutableIntStateOf(0) }
    var pageGeneration by remember { mutableIntStateOf(0) }
    var heading by held.headingState

    BackHandler(enabled = (choosingMove || choosingSave || confirmSave || confirmTake || pendingTake != null) && !moreMenu && !displayMenu) {
        choosingMove = false
        choosingSave = false
        confirmSave = false
        confirmTake = false
        pendingTake = null
    }
    BackHandler(enabled = moreMenu || displayMenu) {
        moreMenu = false
        displayMenu = false
    }

    held.startWatch(scope, store) { loaded -> account = loaded }

    LaunchedEffect(mailbox, account.trashMailbox, connected) {
        if (!connected) return@LaunchedEffect
        knownTrashName = try {
            session.knownTrash()
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

    var launchWriteContacts: (() -> Unit)? = null

    suspend fun writeAndroidContact(choice: TakeAddress, book: AddressBookEntry) {
        try {
            val exists = withContext(Dispatchers.IO) {
                if (!hasReadContacts(appContext)) return@withContext false
                when (val loaded = androidEntriesOnce(appContext.contentResolver)) {
                    AndroidContacts.PermissionNeeded -> false
                    is AndroidContacts.Ready -> {
                        val id = androidSourceId(book.accountType, book.accountName)
                        val entries = loaded.value[id].orEmpty().map {
                            AlpineEntry(it.nickname, it.displayName, it.email, "", "")
                        }
                        bookHasAddress(entries, choice.email)
                    }
                }
            }
            if (exists) {
                postSnack(addressExistsText)
                return
            }
            val entry = CopyContact(
                nickname = choice.email.substringBefore('@'),
                displayName = choice.name,
                emails = listOf(choice.email),
                changed = true,
            )
            withContext(Dispatchers.IO) {
                writeAndroidContacts(appContext.contentResolver, book.accountType, book.accountName, listOf(entry))
            }
            postSnack(addressAddedText)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            postSnack(error.message ?: S(context, "Could not write contacts."))
        }
    }

    LaunchedEffect(trustLoadFailed) {
        if (trustLoadFailed) postSnack(S(context, "Could not load image trust"))
    }

    // Per-open cache token: the same UID and section in another folder or account never shares a file (F06).
    val cacheToken = remember(accountId, mailbox, uid, uidValidity) { readerCacheToken() }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val row = saveTarget.row
        val messageUid = saveTarget.uid
        saveTarget.row = null
        if (uri == null || row == null) return@rememberLauncherForActivityResult
        val file = File(appContext.cacheDir, attachmentCacheName(accountId, cacheToken, readerEpoch, messageUid, row.section))
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

    DisposableEffect(accountId, mailbox, uid, uidValidity) {
        onDispose {
            deleteReaderAttachmentCache(appContext.cacheDir, accountId, cacheToken)
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

    fun nextBodyLoad(): Int {
        bodyGeneration += 1
        return bodyGeneration
    }

    var plainVersion by remember { mutableIntStateOf(0) }
    var bodyEpoch by remember { mutableIntStateOf(0) }
    var bodyComplete by held.bodyCompleteState
    fun clearBodyLines() {
        val lines = bodyLines
        lines.clear()
        bodyEpoch += 1
        bodyComplete = false
    }

    fun showMissing(text: String, noText: Boolean = false, offerHtml: Boolean = false) {
        missing = text
        noTextPart = noText
        showHtmlButton = offerHtml && noText
        renderedHtml = false
        bodyText = ""
        clearBodyLines()
        bodyTruncated = false
        bodySection = null
        bodyOffset = 0
        bodySize = 0
        charsetNote = null
        carry.pending = ByteArray(0)
        carry.closeDecoder()
        carry.clearText()
    }

    fun publishDecoded(piece: String, finished: Boolean = false) {
        val before = bodyLines.size
        val htmlBody = carry.appendPiece(piece, bodyLines, finished, renderedHtml)
        if (bodyLines.size > before) plainVersion += 1
        if (finished) bodyComplete = true
        if (htmlBody != null) bodyText = if (bodyText.isEmpty()) htmlBody else bodyText + htmlBody
        val decoder = carry.decoder
        charsetNote = if (decoder != null && decoder.unknownCharset) {
            S(appContext, "Unknown charset; shown as %1\$s.", decoder.charsetUsed)
        } else {
            null
        }
    }

    suspend fun pullBody(part: MimePart, reset: Boolean, markSeen: Boolean, token: Int) {
        if (token != bodyGeneration) return
        val transfer = fetchedTransferEncoding(session.featureCaps.fetchKind(), part.section, part.encoding)
        val stripped = serverStrippedTransferEncoding(session.featureCaps.fetchKind(), part.section)
        if (reset) {
            pageGeneration = token
            bodyText = ""
            clearBodyLines()
            bodyOffset = 0
            bodySize = part.size
            bodySection = part.section
            carry.pending = ByteArray(0)
            carry.closeDecoder()
            carry.clearText()
            val decoder = WireTextDecoder(part.charset, transfer, account.unlabeledCharset)
            carry.decoder = decoder
            charsetNote = if (decoder.unknownCharset) S(appContext, "Unknown charset; shown as %1\$s.", decoder.charsetUsed) else null
        }
        if (bodySection != part.section) return
        if (part.size > 0 && bodyOffset >= part.size) return
        val step = account.bodyFetchChunk
        val length = if (part.size > 0) {
            nextWireCount(bodyOffset, part.size, step, transfer.equals("base64", ignoreCase = true))
        } else if (bodyOffset == 0) {
            step
        } else {
            return
        }
        if (length <= 0) return
        val chunk = try {
            peekRef(part.section, bodyOffset, length)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            if (token != bodyGeneration) return
            postSnack(error.text)
            return
        }
        if (token != bodyGeneration) return
        noteSeen(markSeen)
        if (token != bodyGeneration) return
        val decoder = carry.decoder ?: WireTextDecoder(part.charset, transfer, account.unlabeledCharset).also { carry.decoder = it }
        try {
            if (chunk.isEmpty()) {
                bodyOffset = if (part.size > 0) part.size else bodyOffset + length
                publishDecoded(decoder.finish(), finished = true)
                return
            }
            bodyOffset += chunk.size
            // Decoded BINARY.PEEK is shorter than the BODYSTRUCTURE size. A short read is the end.
            if (stripped && chunk.size < length && part.size > 0) bodyOffset = part.size
            val finished = part.size <= 0 || bodyOffset >= part.size
            if (finished) {
                publishDecoded(decoder.take(chunk))
                publishDecoded(decoder.finish(), finished = true)
            } else {
                publishDecoded(decoder.take(chunk))
            }
        } catch (error: MimeDecodeException) {
            if (token != bodyGeneration) return
            showMissing(error.text)
        }
    }

    suspend fun pullUnbounded(section: String, markSeen: Boolean, token: Int) {
        if (token != bodyGeneration) return
        pageGeneration = token
        missing = null
        noTextPart = false
        renderedHtml = false
        bodyText = ""
        clearBodyLines()
        bodyOffset = 0
        bodySize = 0
        bodySection = section
        carry.pending = ByteArray(0)
        carry.closeDecoder()
        carry.clearText()
        var marked = false
        rawTruncated = false
        val bodyFile = java.io.File.createTempFile("liveimap-section-", ".txt", appContext.cacheDir)
        try {
        java.io.FileOutputStream(bodyFile).use { out ->
        while (true) {
            if (bodyOffset >= RawViewCapBytes) {
                rawTruncated = true
                break
            }
            val length = account.bodyFetchChunk
            val chunk = try {
                peekRef(section, bodyOffset, length)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                if (token != bodyGeneration) return
                postSnack(error.text)
                return
            }
            if (token != bodyGeneration) return
            if (!marked) {
                marked = true
                noteSeen(markSeen)
                if (token != bodyGeneration) return
            }
            if (chunk.isEmpty()) break
            bodyOffset += chunk.size
            out.write(chunk)
            if (chunk.size < length) break
        }
        }
        if (token != bodyGeneration) return
        bodyText = bodyFile.readText(Charsets.UTF_8)
        } finally {
            bodyFile.delete()
        }
        clearBodyLines()
        bodyLines.addAll(readerLines(bodyText))
        bodyComplete = true
    }

    suspend fun readPart(
        part: MimePart,
        markSeen: Boolean,
        cap: Int,
        onChunk: (ByteArray) -> Unit,
    ): Boolean? {
        val token = pageGeneration
        if (token != bodyGeneration) return null
        var wire = 0
        var offset = 0
        var marked = false
        suspend fun take(length: Int): ByteArray? {
            val chunk = try {
                peekRef(part.section, offset, length)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                if (token != bodyGeneration) return null
                postSnack(error.text)
                return null
            }
            if (token != bodyGeneration) return null
            if (!marked) {
                marked = true
                noteSeen(markSeen)
            }
            return chunk
        }
        if (part.size > 0) {
            val limit = minOf(part.size, cap)
            val stripped = serverStrippedTransferEncoding(session.featureCaps.fetchKind(), part.section)
            var endedEarly = false
            while (offset < limit && wire < cap) {
                val length = nextWireCount(offset, limit, account.bodyFetchChunk, false)
                if (length <= 0) break
                val chunk = take(length) ?: return null
                if (chunk.isEmpty()) {
                    endedEarly = true
                    break
                }
                val room = cap - wire
                if (chunk.size > room) {
                    onChunk(chunk.copyOf(room))
                    wire += room
                    break
                }
                onChunk(chunk)
                wire += chunk.size
                offset += chunk.size
            }
            // BODYSTRUCTURE size is the encoded size. A short BINARY.PEEK is the whole decoded part.
            if (stripped && endedEarly && wire < cap) return false
            return part.size > cap
        }
        while (wire < cap) {
            val length = minOf(account.bodyFetchChunk, cap - wire)
            val chunk = take(length) ?: return null
            if (chunk.isEmpty()) break
            val room = cap - wire
            if (chunk.size > room) {
                onChunk(chunk.copyOf(room))
                wire += room
                return true
            }
            onChunk(chunk)
            wire += chunk.size
            offset += chunk.size
            if (chunk.size < length) return false
        }
        if (wire < cap) return false
        val more = try {
            peekRef(part.section, wire, 1).isNotEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            if (token != bodyGeneration) return null
            postSnack(error.text)
            true
        }
        if (token != bodyGeneration) return null
        return more
    }

    suspend fun readSectionText(section: String): String {
        val step = account.bodyFetchChunk
        val bodyFile = java.io.File.createTempFile("liveimap-header-", ".txt", appContext.cacheDir)
        try {
            java.io.FileOutputStream(bodyFile).use { out ->
                var offset = 0
                while (true) {
                    val chunk = peekRef(section, offset, step)
                    if (chunk.isEmpty()) break
                    offset += chunk.size
                    out.write(chunk)
                    if (chunk.size < step) break
                }
            }
            return bodyFile.readText(Charsets.UTF_8)
        } finally {
            bodyFile.delete()
        }
    }

    suspend fun loadPreferred(view: BodyView, token: Int) {
        if (token != bodyGeneration) return
        pageGeneration = token
        val root = structure ?: return
        val plain = textPart(root, "plain")
        val html = textPart(root, "html")
        suspend fun pullHtml(part: MimePart, markSeen: Boolean): HtmlPull? {
            var wireRead = 0
            val stripped = serverStrippedTransferEncoding(session.featureCaps.fetchKind(), part.section)
            val transfer = fetchedTransferEncoding(session.featureCaps.fetchKind(), part.section, part.encoding)
            val decoder = WireTextDecoder(part.charset, transfer, account.unlabeledCharset)
            // The whole part. maxRawViewBytes caps Raw and Headers, not this view.
            val cap = if (part.size > 0) part.size else Int.MAX_VALUE
            try {
                val built = StringBuilder()
                val stopped = readPart(part, markSeen, cap) { chunk ->
                    built.append(decoder.take(chunk))
                    wireRead += chunk.size
                }
                if (stopped == null) return null
                val truncated = transferStrippedTruncated(stripped, part.size, cap, stopped)
                built.append(decoder.finish())
                val text = finishedHtmlBody(built.toString(), truncated) ?: return null
                val note = if (decoder.unknownCharset) {
                    S(appContext, "Unknown charset; shown as %1\$s.", decoder.charsetUsed)
                } else {
                    null
                }
                return HtmlPull(text, note, truncated, wireRead)
            } finally {
                decoder.close()
            }
        }
        suspend fun showPlain(part: MimePart) {
            showHtmlButton = false
            noTextPart = false
            missing = null
            renderedHtml = false
            bodyTruncated = false
            plainFlowed = part.format.equals("flowed", ignoreCase = true)
            plainDelSp = part.delSp
            pullBody(part, reset = true, markSeen = !seenStored, token)
        }
        suspend fun showHtml(part: MimePart, asText: Boolean) {
            showHtmlButton = false
            noTextPart = false
            missing = null
            renderedHtml = !asText
            bodyText = ""
            clearBodyLines()
            bodyTruncated = false
            bodySection = part.section
            bodyOffset = 0
            bodySize = part.size
            charsetNote = null
            if (asText) {
                plainFlowed = false
                plainDelSp = false
            }
            carry.pending = ByteArray(0)
            carry.closeDecoder()
            carry.clearText()
            val pulled = try {
                pullHtml(part, !seenStored)
            } catch (error: MimeDecodeException) {
                if (token != bodyGeneration) return
                showMissing(error.text)
                return
            }
            if (pulled == null || token != bodyGeneration) return
            val text = if (asText) htmlAsText(pulled.text) else pulled.text
            bodyOffset = pulled.wireRead
            charsetNote = pulled.note
            bodyText = text
            clearBodyLines()
            bodyLines.addAll(readerLines(text))
            bodyComplete = true
            bodyTruncated = pulled.truncated
            // No plain part: the card keeps Show HTML. The words "no text part" stay off while HTML is shown as text.
            if (asText && plain == null) showHtmlButton = true
        }
        suspend fun showPlainFlowed(part: MimePart) {
            messageDisplay = ReaderDisplay.Flowed
            textLayout = TextLayout.Flowed
            selectedView = BodyView.PlainOrError
            showPlain(part)
        }
        suspend fun showHtmlText(part: MimePart) {
            messageDisplay = ReaderDisplay.HtmlAsText
            textLayout = TextLayout.Text
            selectedView = BodyView.PlainOrText
            showHtml(part, asText = true)
        }
        when (view) {
            // HTML as text is the HTML part, even when a plain part exists.
            BodyView.PlainOrText -> when {
                html != null -> showHtml(html, asText = true)
                plain != null -> showPlainFlowed(plain)
                else -> showMissing(noTextPartText, noText = true, offerHtml = false)
            }
            BodyView.PlainOrHtml -> when {
                html != null -> showHtml(html, asText = false)
                plain != null -> showPlainFlowed(plain)
                else -> showMissing(noTextPartText, noText = true, offerHtml = false)
            }
            // Flowed and Monospaced are the plain part. No plain part is HTML as text.
            BodyView.PlainOrError -> when {
                plain != null -> showPlain(plain)
                html != null -> showHtmlText(html)
                else -> showMissing(noTextPartText, noText = true, offerHtml = false)
            }
            BodyView.Headers, BodyView.Raw -> Unit
        }
    }

    fun requestView(view: BodyView) {
        selectedView = view
        val token = nextBodyLoad()
        val defer = loading || !connected
        scope.launch {
                if (defer || token != bodyGeneration) return@launch
                when (view) {
                    BodyView.Headers -> {
                        charsetNote = null
                        pullUnbounded("HEADER", markSeen = !seenStored, token)
                    }
                    BodyView.Raw -> {
                        charsetNote = null
                        pullUnbounded("*", markSeen = !seenStored, token)
                    }
                    else -> loadPreferred(view, token)
                }
        }
    }

    fun useDisplay(display: ReaderDisplay) {
        messageDisplay = display
        textLayout = textLayoutOf(display, textLayout)
        val view = bodyViewOf(display, selectedView)
        if (view != selectedView) requestView(view)
    }

    fun quoteViewName(): String = if (held.ready) messageDisplay.name else ""

    fun markAttachment(section: String, fetched: Int, done: Boolean, busy: Boolean, path: String? = null) {
        attachments = attachments.map { item ->
            if (item.section == section) {
                item.copy(fetched = fetched, done = done, busy = busy, path = path ?: item.path)
            } else {
                item
            }
        }
    }

    /** A "nested part" entry (past MaxMimeDepth): fetch the structure below it and list its parts in its place. */
    fun openNestedPart(row: AttachmentRow) {
        if (row.busy) return
        scope.launch {
                if (!connected) return@launch
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

    fun deleteChecked(file: File) {
        if (!file.exists()) return
        try {
            java.nio.file.Files.delete(file.toPath())
        } catch (_: java.io.IOException) {
            postSnack(S(appContext, "Could not delete %1\$s", file.name))
        }
    }

    fun fetchAttachment(row: AttachmentRow) {
        if (isNestedPart(row)) {
            openNestedPart(row)
            return
        }
        if (row.done || row.busy) return
        scope.launch {
                if (!connected) return@launch
                val current = attachments.firstOrNull { it.section == row.section } ?: return@launch
                if (current.done || current.busy) return@launch
                markAttachment(current.section, current.fetched, done = false, busy = true)
                val dest = File(appContext.cacheDir, attachmentCacheName(accountId, cacheToken, readerEpoch, uid, current.section))
                val tmp = File(dest.parentFile, dest.name + ".part")
                val transfer = fetchedTransferEncoding(session.featureCaps.fetchKind(), current.section, current.encoding)
                val handle = MimeDecode.xferOpen(transfer)
                try {
                    var offset = 0
                    val base64 = transfer.equals("base64", ignoreCase = true)
                    FileOutputStream(tmp).use { out ->
                        while (true) {
                            val finished = current.size > 0 && offset >= current.size
                            if (finished) {
                                val tail = MimeDecode.xferTake(handle, ByteArray(0), true)
                                    ?: throw MimeDecodeException("could not decode this part")
                                if (tail.isNotEmpty()) out.write(tail)
                                break
                            }
                            val length = if (current.size > 0) {
                                nextWireCount(offset, current.size, account.bodyFetchChunk, base64)
                            } else {
                                account.bodyFetchChunk
                            }
                            if (length <= 0) break
                            val chunk = peekRef(current.section, offset, length)
                            if (chunk.isEmpty()) {
                                val tail = MimeDecode.xferTake(handle, ByteArray(0), true)
                                    ?: throw MimeDecodeException("could not decode this part")
                                if (tail.isNotEmpty()) out.write(tail)
                                break
                            }
                            val decoded = MimeDecode.xferTake(handle, chunk, false)
                                ?: throw MimeDecodeException("could not decode this part")
                            if (decoded.isNotEmpty()) out.write(decoded)
                            offset += chunk.size
                            markAttachment(current.section, offset, done = false, busy = true)
                            if (current.size <= 0 && chunk.size < length) {
                                val tail = MimeDecode.xferTake(handle, ByteArray(0), true)
                                    ?: throw MimeDecodeException("could not decode this part")
                                if (tail.isNotEmpty()) out.write(tail)
                                break
                            }
                        }
                    }
                    if (!tmp.renameTo(dest)) {
                        deleteChecked(tmp)
                        throw java.io.IOException(notConnected)
                    }
                    markAttachment(current.section, offset, done = true, busy = false, path = dest.absolutePath)
                } catch (error: CancellationException) {
                    deleteChecked(tmp)
                    throw error
                } catch (error: MimeDecodeException) {
                    deleteChecked(tmp)
                    markAttachment(current.section, current.fetched, done = false, busy = false)
                    postSnack(error.message ?: "could not decode this part")
                } catch (error: java.io.IOException) {
                    deleteChecked(tmp)
                    markAttachment(current.section, current.fetched, done = false, busy = false)
                    postSnack(error.message ?: notConnected)
                } catch (error: MailFailure) {
                    deleteChecked(tmp)
                    markAttachment(current.section, current.fetched, done = false, busy = false)
                    postSnack(error.text)
                } finally {
                    MimeDecode.xferClose(handle)
                }
        }
    }

    // Before open or save: sniff the first 4 KB (the cached file, else a BODY.PEEK partial fetch, which neither
    // downloads the part nor sets \Seen) and compare it with the claimed type and the extension.
    fun checkTypeThen(row: AttachmentRow, action: AttachmentAction, proceed: () -> Unit) {
        scope.launch {
            val cached = File(appContext.cacheDir, attachmentCacheName(accountId, cacheToken, readerEpoch, uid, row.section))
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
                        peekRef(row.section, 0, SniffBytes)
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
        val uri = attachmentUri(attachmentCacheName(accountId, cacheToken, readerEpoch, uid, row.section))
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
        val uri = attachmentUri(attachmentCacheName(accountId, cacheToken, readerEpoch, uid, row.section))
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

    @Composable
    fun ReaderTail() {
    LaunchedEffect(session, mailbox, uid, uidValidity, loadToken) {
        val reuse = held.holds(MessageRef(accountId, mailbox, uidValidity, uid)) && loadToken == 0
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
        val token = nextBodyLoad()
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
        allowImages = false
        noTextPart = false
        var openOk = false
        coroutineScope {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@coroutineScope
            }
            account = settings
            if (token == bodyGeneration) {
                messageDisplay = settings.readerDisplay
                textLayout = textLayoutOf(settings.readerDisplay, textLayout)
                selectedView = bodyViewOf(settings.readerDisplay, selectedView)
                allowImages = settings.displayImages
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@coroutineScope
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                return@coroutineScope
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    return@coroutineScope
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    return@coroutineScope
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
                    val fields = headerFields(headerText, account.unlabeledCharset)
                    viewerExtra = viewerHeaderLines(headerText, settings.viewerHeaders, account.unlabeledCharset)
                    if (fields.from.contains('@')) headerFrom = fields.from
                    headerTo = fields.to
                    headerCc = fields.cc
                    headerSender = fields.sender
                    headerResentTo = fields.resentTo
                    headerListId = fields.listId
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
                if (connected) {
                    val useToken = bodyGeneration
                    val useView = selectedView
                    if (useToken == bodyGeneration) {
                        when (useView) {
                            BodyView.Headers -> {
                                charsetNote = null
                                pullUnbounded("HEADER", markSeen = !seenStored, useToken)
                            }
                            BodyView.Raw -> {
                                charsetNote = null
                                pullUnbounded("*", markSeen = !seenStored, useToken)
                            }
                            else -> loadPreferred(useView, useToken)
                        }
                    }
                }
            held.openAccount = accountId
            held.openMailbox = mailbox
            held.openUid = uid
            held.openEpoch = uidValidity
            held.ready = true
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
                    if (renderedHtml) return@collect
                    val root = structure ?: return@collect
                    val part = textPart(root, "plain") ?: return@collect
                    if (part.section != bodySection) return@collect
                    if (part.size > 0 && bodyOffset < part.size) {
                        val token = pageGeneration
                        pullBody(part, reset = false, markSeen = false, token)
                    }
            }
        }
    }

    bridge.onNearEnd = {
        val token = pageGeneration
        scope.launch {
                if (!renderedHtml) return@launch
                val root = structure ?: return@launch
                val part = textPart(root, "html") ?: return@launch
                if (part.section != bodySection) return@launch
                val stripped = serverStrippedTransferEncoding(session.featureCaps.fetchKind(), part.section)
                if (transferStrippedHasMore(stripped, part.size, bodyOffset, RawViewCapBytes, bodyTruncated)) {
                    pullBody(part, reset = false, markSeen = false, token)
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
        val nextSequence = try {
            OpenMessageOrder.sequence(next)
        } catch (_: MailFailure) {
            return null
        }
        return next to nextSequence
    }

    fun openNextOrIndex(target: Pair<Long, Int>?) {
        val nextUid = target?.first
        val step = IndexRecord(emptySet(), uid, null).afterDelete(account.deleteMovesToNext, nextUid)
        onMark(step.index.cursorUid)
        if (step.message == MessageAfterDelete.ShowNext && target != null) {
            onAdvance(MessageRef(accountId, mailbox, readerEpoch, target.first), target.second)
        } else {
            onBack()
        }
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
                val helper = IndexModel(session, store, mailbox)
                helper.undo(offer)
                // F98: the undo selects other folders; never trust the reader's remembered selection after it.
                selectedMailbox = null
                val reported = helper.notice
                if (reported != null) postSnack(reported)
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    fun runReaderExpunge(uids: List<Long>) {
        if (uids.isEmpty()) return
        scope.launch {
            var failed = false
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

    suspend fun runReaderPolicyLocked(policy: DeletePolicy) {
        val target = nextAfterDeleteOrMove(useFollowingAfterDelete = true)
        try {
            when (policy) {
                DeletePolicy.MarkDeleted -> {
                    val epoch = inRef { e, s -> // F43: act on this folder's UID
                        s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        e
                    }
                    showActionUndo(refUndo(accountId, mailbox, epoch, uid), target)
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
                    val usedMove = moveCommandKind(account.disableImapMove, session.featureCaps.move) == "Move"
                    showActionUndo(refUndo(accountId, mailbox, epoch, uid, trash, dest.asList(), targetEpoch, usedMove), target)
                }
                DeletePolicy.DeletePermanently -> {
                    if (!session.featureCaps.uidPlus) {
                        inRef { _, s ->
                            s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                            s.expungeAllDeleted()
                        }
                    } else {
                        inRef { _, s -> // F43: act on this folder's UID
                            s.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                            s.uidExpunge(listOf(uid))
                        }
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
            if (policy == DeletePolicy.DeletePermanently && !session.featureCaps.uidPlus) {
                deleteWarn = org.dlang.liveimap.i18n.literal.S("This server cannot delete permanently. The alternate is to mark deleted. Continuing expunges every deleted message in this folder.")
                pendingReaderPolicy = DeletePolicy.DeletePermanently
                confirmPermanent = true
                return@launch
            }
            if (policy == DeletePolicy.MoveToTrash && knownTrashName.isEmpty()) {
                deleteWarn = org.dlang.liveimap.i18n.literal.S("This server has no trash mailbox. The alternate is to mark deleted.")
                pendingReaderPolicy = DeletePolicy.MarkDeleted
                confirmPermanent = true
                return@launch
            }
            if (policy == DeletePolicy.DeletePermanently && account.askBeforeExpunge) {
                deleteWarn = ""
                pendingReaderPolicy = DeletePolicy.DeletePermanently
                confirmPermanent = true
                return@launch
            }
            pendingReaderPolicy = policy
            runReaderPolicyLocked(policy)
        }
    }

    fun storeReaderFlag(flag: String, set: Boolean) {
        if (flag.isEmpty()) return
        scope.launch {
            try {
                inRef { _, s -> // F43: act on this folder's UID
                    if (set) s.storeFlags(listOf(uid), setOf(flag), emptySet())
                    else s.storeFlags(listOf(uid), emptySet(), setOf(flag))
                }
                rowFlags = if (set) rowFlags + flag else rowFlags - flag
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                postSnack(error.text)
            }
        }
    }

    fun runDelete() {
        scope.launch {
            val trash = try {
                session.knownTrash()
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
                trashNamedByUser = usableTrashName(account.trashMailbox).isNotEmpty(),
            )
            val warn = deleteCapabilityWarn(account.deletePolicy, session.featureCaps.uidPlus, trash.isNotEmpty())
            if (warn.isNotEmpty()) {
                deleteWarn = warn
                pendingReaderPolicy = if (warn == org.dlang.liveimap.i18n.literal.S("This server has no trash mailbox. The alternate is to mark deleted.")) {
                    DeletePolicy.MarkDeleted
                } else {
                    DeletePolicy.DeletePermanently
                }
                confirmPermanent = true
                return@launch
            }
            if (policy == DeletePolicy.DeletePermanently && account.askBeforeExpunge) {
                deleteWarn = ""
                pendingReaderPolicy = DeletePolicy.DeletePermanently
                confirmPermanent = true
                return@launch
            }
            runReaderPolicyLocked(policy)
        }
    }

    fun performReaderSwipe(binding: SwipeBinding) {
        when (binding.action) {
            SwipeAction.Next -> {
                if (!OpenMessageOrder.matches(accountId, mailbox, readerEpoch)) return
                val next = followingUid(OpenMessageOrder.uids, uid) ?: return
                val nextSequence = try {
                    OpenMessageOrder.sequence(next)
                } catch (_: MailFailure) {
                    return
                }
                onMark(IndexRecord(emptySet(), uid, null).next(next).index.cursorUid)
                onAdvance(MessageRef(accountId, mailbox, readerEpoch, next), nextSequence)
            }
            SwipeAction.Previous -> {
                if (!OpenMessageOrder.matches(accountId, mailbox, readerEpoch)) return
                val previous = precedingUid(OpenMessageOrder.uids, uid) ?: return
                val previousSequence = try {
                    OpenMessageOrder.sequence(previous)
                } catch (_: MailFailure) {
                    return
                }
                onMark(IndexRecord(emptySet(), uid, null).previous(previous).index.cursorUid)
                onAdvance(MessageRef(accountId, mailbox, readerEpoch, previous), previousSequence)
            }
            SwipeAction.Reply ->
                onCompose(ComposeSeed(ComposeKind.Reply, mailbox, listOf(uid), readerEpoch, quoteView = quoteViewName()))
            SwipeAction.ReplyAll ->
                onCompose(ComposeSeed(ComposeKind.ReplyAll, mailbox, listOf(uid), readerEpoch, quoteView = quoteViewName()))
            SwipeAction.Delete -> runDelete()
            SwipeAction.Move -> choosingMove = true
            SwipeAction.SetFlag -> {
                if (binding.flag.isNotEmpty()) storeReaderFlag(binding.flag, set = true)
            }
            SwipeAction.ClearFlag -> {
                if (binding.flag.isNotEmpty()) storeReaderFlag(binding.flag, set = false)
            }
            SwipeAction.FlagScreen -> showFlags = true
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
                val target = nextAfterDeleteOrMove()
                try {
                    // F116: select, act and read the COPYUID result as one unit
                    val (epoch, targetEpoch, dest) = inRef { e, s -> // F43: act on this folder's UID
                        s.copyThenDelete(listOf(uid), spamMailbox)
                        Triple(e, s.copiedEpoch(), s.takeCopiedUids())
                    }
                    val usedMove = moveCommandKind(account.disableImapMove, session.featureCaps.move) == "Move"
                    showActionUndo(
                        refUndo(accountId, mailbox, epoch, uid, spamMailbox, dest.asList(), targetEpoch, usedMove),
                        target,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                }
        }
    }

    /** Opens/closes all headers in the header card; the first open fetches BODY.PEEK[HEADER], capped. */
    fun toggleAllHeaders() {
        allHeadersOpen = !allHeadersOpen
        if (!allHeadersOpen || allHeaderText != null) return
        scope.launch {
                if (!connected) return@launch
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

    fun runReaderAction(action: ReaderToolbarAction) {
        when (action) {
            ReaderToolbarAction.Refresh -> if (!loading) loadToken += 1
            ReaderToolbarAction.Reply ->
                onCompose(ComposeSeed(ComposeKind.Reply, mailbox, listOf(uid), readerEpoch, quoteView = quoteViewName()))
            ReaderToolbarAction.ReplyAll ->
                onCompose(ComposeSeed(ComposeKind.ReplyAll, mailbox, listOf(uid), readerEpoch, quoteView = quoteViewName()))
            ReaderToolbarAction.Forward ->
                onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid), readerEpoch, quoteView = quoteViewName()))
            ReaderToolbarAction.Delete -> runDelete()
            ReaderToolbarAction.Move -> choosingMove = true
            ReaderToolbarAction.Spam -> runSpam()
            ReaderToolbarAction.Bounce ->
                onCompose(ComposeSeed(ComposeKind.Bounce, mailbox, listOf(uid), readerEpoch))
            ReaderToolbarAction.Headers -> toggleAllHeaders()
        }
    }

    @Composable
    fun ReaderChrome() {
    val systemDark = isSystemInDarkTheme()
    val dark = when (account.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.FollowSystem -> systemDark
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteTint = MaterialTheme.colorScheme.onSurfaceVariant
    val htmlBackground = MaterialTheme.colorScheme.background.toArgb()
    val htmlForeground = MaterialTheme.colorScheme.onBackground.toArgb()
    val plainFont = if (textLayout == TextLayout.Monospaced) FontFamily.Monospace else null
    val noWrap = textLayout == TextLayout.Monospaced
    val textZoom = effectiveTextZoom(appTextZoom, pinchZoom)
    linkBridge.onLink = { url -> pendingLink = url }
    @Composable
    fun cachedPlainLines(): List<Pair<Boolean, AnnotatedString>> {
        val plainCache = remember {
            object {
                var consumed = 0
                var epoch = -1
                var styled = false
                var quote = Color.Unspecified
                var link = Color.Unspecified
                var bridge: LinkBridge? = null
                var layout: TextLayout? = null
                var flowed = false
                var delSp = false
                var pushedFinished = false
                val stream = FlowedStream()
                val processed = ArrayList<String>()
                val annotated = ArrayList<Pair<Boolean, AnnotatedString>>()
            }
        }
        return remember(plainVersion, bodyEpoch, bodyComplete, quoteTint, linkColor, linkBridge, textLayout, plainFlowed, plainDelSp) {
            val styleChanged = !plainCache.styled ||
                plainCache.quote != quoteTint || plainCache.link != linkColor || plainCache.bridge != linkBridge ||
                plainCache.layout != textLayout || plainCache.flowed != plainFlowed || plainCache.delSp != plainDelSp
            if (styleChanged || plainCache.epoch != bodyEpoch || bodyLines.size < plainCache.consumed) {
                plainCache.processed.clear()
                plainCache.annotated.clear()
                plainCache.consumed = 0
                plainCache.epoch = bodyEpoch
                plainCache.styled = true
                plainCache.quote = quoteTint
                plainCache.link = linkColor
                plainCache.bridge = linkBridge
                plainCache.layout = textLayout
                plainCache.flowed = plainFlowed
                plainCache.delSp = plainDelSp
                plainCache.stream.reset()
                plainCache.pushedFinished = false
            }
            if (bodyLines.size > plainCache.consumed || (bodyComplete && !plainCache.pushedFinished)) {
                val fresh = ArrayList<String>(bodyLines.size - plainCache.consumed)
                for (i in plainCache.consumed until bodyLines.size) fresh.add(bodyLines[i])
                val pushed = plainCache.stream.push(fresh, textLayout, plainFlowed, plainDelSp, bodyComplete)
                plainCache.pushedFinished = bodyComplete
                val kept = pushed.firstChanged
                if (kept < plainCache.annotated.size) {
                    plainCache.annotated.subList(kept, plainCache.annotated.size).clear()
                }
                if (kept < plainCache.processed.size) {
                    plainCache.processed.subList(kept, plainCache.processed.size).clear()
                }
                for (line in pushed.lines) {
                    val quoted = quotedReaderLine(line)
                    plainCache.annotated.add(quoted to plainLineText(line, if (quoted) quoteTint else null, linkColor, linkBridge))
                    plainCache.processed.add(line)
                }
                plainCache.consumed = bodyLines.size
            }
            plainCache.annotated
        }
    }
    val plainLines = cachedPlainLines()
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
        trashNamedByUser = usableTrashName(account.trashMailbox).isNotEmpty(),
    )
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("smoke-reader"),
        topBar = {
            TopAppBar(
                title = { MailboxTitleLines(heading.leaf, heading.parent) },
                navigationIcon = {
                    HelpIconButton(
                        label = S("Back", name = "reader_back"),
                        help = S("Returns to the message list."),
                        page = "reading.html#back",
                        onClick = onBack,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = S("Back", name = "reader_back"),
                        )
                    }
                },
                actions = {
                    ReaderDisplayMenu(
                        selected = messageDisplay,
                        onOpen = {
                            displayMenu = true
                            moreMenu = false
                        },
                    )
                    DebugStatusIcon(debugStatus)
                    if (renderedHtml) {
                        HelpIconButton(
                            label = if (allowImages) S("Hide images")
                                else S("Show images"),
                            help = if (allowImages) S("Hides remote images in this HTML message.")
                                else S("Shows remote images in this HTML message."),
                            page = "reading.html#images",
                            onClick = { allowImages = !allowImages },
                        ) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = if (allowImages) S("Hide images")
                                        else S("Show images"),
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
                                IconButton(onClick = { runReaderAction(action) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = S("Refresh"),
                                    )
                                }
                            }
                        } else {
                            readerIcons.add {
                                val label = if (action == ReaderToolbarAction.Delete) {
                                    deletePolicyLabel(effectivePolicy)
                                } else {
                                    readerActionName(action)
                                }
                                val helpRes = when (action) {
                                    ReaderToolbarAction.Reply -> org.dlang.liveimap.i18n.literal.S("Answers the sender.")
                                    ReaderToolbarAction.ReplyAll -> org.dlang.liveimap.i18n.literal.S("Answers the sender and the other recipients.")
                                    ReaderToolbarAction.Forward -> org.dlang.liveimap.i18n.literal.S("Forwards this message.")
                                    ReaderToolbarAction.Delete -> org.dlang.liveimap.i18n.literal.S("Deletes this message, using the account delete setting.")
                                    else -> null
                                }
                                val helpPage = when (action) {
                                    ReaderToolbarAction.Reply,
                                    ReaderToolbarAction.ReplyAll,
                                    ReaderToolbarAction.Forward,
                                    -> "reading.html#reply"
                                    ReaderToolbarAction.Delete -> "reading.html#delete"
                                    else -> null
                                }
                                if (helpRes != null && helpPage != null) {
                                    HelpIconButton(
                                        label = label,
                                        help = S(helpRes),
                                        page = helpPage,
                                        onClick = { runReaderAction(action) },
                                    ) {
                                        Icon(
                                            imageVector = readerActionImage(action),
                                            contentDescription = label,
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { runReaderAction(action) }) {
                                        Icon(
                                            imageVector = readerActionImage(action),
                                            contentDescription = label,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ToolbarIconRows(
                        maxRows = org.dlang.liveimap.ui.toolbar.ToolbarStore.readerRows(),
                        onRows = { count -> iconRows = count },
                        icons = readerIcons,
                    )
                    Box {
                HelpIconButton(
                    label = S("More"),
                    help = S("Refresh, save, and the other message actions."),
                    page = "reading.html#more",
                    onClick = {
                        moreMenu = true
                        displayMenu = false
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = S("More"),
                    )
                }
            DropdownMenu(
                expanded = moreMenu || displayMenu,
                onDismissRequest = {
                    moreMenu = false
                    displayMenu = false
                },
            ) {
                ObscuredTouchFilter()
                if (displayMenu) {
                    ReaderDisplay.entries.forEach { display ->
                        val label = readerDisplayName(display)
                        HelpDropdownMenuItem(
                            label = label,
                            help = S("Shows the message in this view."),
                            page = "reading.html",
                            modifier = Modifier.testTag("reader-display-${display.name}"),
                            text = { Text(label) },
                            onClick = {
                                displayMenu = false
                                useDisplay(display)
                            },
                        )
                    }
                } else {
                for (policy in alternateDeletePolicies(effectivePolicy, readerUidPlus, trashKnown)) {
                    val policyLabel = deletePolicyLabel(policy)
                    HelpDropdownMenuItem(
                        label = policyLabel,
                        help = S("Deletes with this policy."),
                        page = "reading.html",
                        text = { Text(policyLabel) },
                        onClick = {
                            moreMenu = false
                            runChosenPolicy(policy)
                        },
                    )
                }
                for (action in menuActions) {
                    if (action == ReaderToolbarAction.Refresh) {
                        val title = S("Refresh")
                        HelpDropdownMenuItem(
                            label = title,
                            help = S("Loads the message again."),
                            page = "reading.html",
                            text = { Text(title) },
                            onClick = {
                                moreMenu = false
                                runReaderAction(action)
                            },
                        )
                    } else {
                        val title = if (action == ReaderToolbarAction.Delete) {
                            deletePolicyLabel(effectivePolicy)
                        } else {
                            readerActionName(action)
                        }
                        HelpDropdownMenuItem(
                            label = title,
                            help = readerMenuHelp(action),
                            page = "reading.html",
                            text = { Text(title) },
                            onClick = {
                                moreMenu = false
                                runReaderAction(action)
                            },
                        )
                    }
                }
                val saveLabel = S("Save")
                HelpDropdownMenuItem(
                    label = saveLabel,
                    help = S("Saves the message without deleting it."),
                    page = "reading.html",
                    text = { Text(saveLabel) },
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
                val takeAddress = S("Take address")
                HelpDropdownMenuItem(
                    label = takeAddress,
                    help = S("Copies an address from the message."),
                    page = "reading.html",
                    text = { Text(takeAddress) },
                    onClick = {
                        moreMenu = false
                        if (account.addressSave() is AddressSave.None) {
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
                val filterLike = S("Filter messages like this…")
                HelpDropdownMenuItem(
                    label = filterLike,
                    help = S("Starts a filter from this message."),
                    page = "reading.html",
                    text = { Text(filterLike) },
                    onClick = {
                        moreMenu = false
                        onFilterLike(headerFrom, headerTo, listIdKey(headerListId), headerSubject, accountId)
                    },
                )
                if (renderedHtml && dark) {
                    val colorLabel = if (originalColors) S("Dark theme colors") else S("Original colors")
                    HelpDropdownMenuItem(
                        label = colorLabel,
                        help = if (originalColors) S("Shows the message with dark colors.")
                            else S("Shows the message with its own colors."),
                        page = "reading.html",
                        text = { Text(colorLabel) },
                        onClick = {
                            moreMenu = false
                            originalColors = !originalColors
                        },
                        modifier = Modifier.testTag("reader-original-colors"),
                    )
                }
                if (renderedHtml && trustSenderKey(headerFrom).isNotEmpty()) {
                    val on = trustSenderKey(headerFrom) in imageTrust.senders
                    val senderKey = trustSenderKey(headerFrom)
                    val senderLabel = if (on) S("Stop always showing images from %1\$s", senderKey) else S("Always show images from %1\$s", senderKey)
                    HelpDropdownMenuItem(
                        label = senderLabel,
                        help = if (on) S("Blocks images from this sender.")
                            else S("Allows images from this sender."),
                        page = "reading.html",
                        text = { Text(senderLabel) },
                        onClick = {
                            moreMenu = false
                            val nextTrust = withSender(imageTrust, headerFrom, !on)
                            imageTrust = nextTrust
                            if (trustCanSave) scope.launch {
                                val saved = store.onAccount {
                                    saveImageTrust(context.filesDir, accountId, nextTrust, SettingsHub.uiNow().maxImageTrustBytes.toInt().toLong())
                                }
                                if (saved.error != null) postSnack(S(context, "Could not save image trust"))
                            }
                            if (!on) allowImages = true
                        },
                        modifier = Modifier.testTag("reader-images-sender"),
                    )
                }
                if (renderedHtml && listIdKey(headerListId).isNotEmpty()) {
                    val on = listIdKey(headerListId) in imageTrust.lists
                    val listKey = listIdKey(headerListId)
                    val listLabel = if (on) S("Stop always showing images from this list (%1\$s)", listKey) else S("Always show images from this list (%1\$s)", listKey)
                    HelpDropdownMenuItem(
                        label = listLabel,
                        help = if (on) S("Blocks images from this list.")
                            else S("Allows images from this list."),
                        page = "reading.html",
                        text = { Text(listLabel) },
                        onClick = {
                            moreMenu = false
                            val nextTrust = withList(imageTrust, headerListId, !on)
                            imageTrust = nextTrust
                            if (trustCanSave) scope.launch {
                                val saved = store.onAccount {
                                    saveImageTrust(context.filesDir, accountId, nextTrust, SettingsHub.uiNow().maxImageTrustBytes.toInt().toLong())
                                }
                                if (saved.error != null) postSnack(S(context, "Could not save image trust"))
                            }
                            if (!on) allowImages = true
                        },
                        modifier = Modifier.testTag("reader-images-list"),
                    )
                }
                val forwardLabel = forwardStyleName(account.forwardAsAttachment)
                HelpDropdownMenuItem(
                    label = forwardLabel,
                    help = S("Forwards in this style."),
                    page = "reading.html",
                    text = { Text(forwardLabel) },
                    onClick = {
                        moreMenu = false
                        onCompose(
                            ComposeSeed(
                                ComposeKind.Forward,
                                mailbox,
                                listOf(uid),
                                readerEpoch,
                                forwardAsAttachment = !account.forwardAsAttachment,
                                quoteView = quoteViewName(),
                            ),
                        )
                    },
                )
                // Headers and Raw are this message only. They are not saved.
                listOf(BodyView.Headers, BodyView.Raw).forEach { view ->
                    val viewLabel = bodyViewName(view)
                    HelpDropdownMenuItem(
                        label = viewLabel,
                        help = S("Shows the message in this view."),
                        page = "reading.html",
                        text = { Text(viewLabel) },
                        onClick = {
                            moreMenu = false
                            requestView(view)
                        },
                    )
                }
                HorizontalDivider()
                val customize = S("Customize toolbar…")
                HelpDropdownMenuItem(
                    label = customize,
                    help = S("Chooses which actions stay on the bar."),
                    page = "reading.html",
                    text = { Text(customize) },
                    onClick = {
                        moreMenu = false
                        onCustomize()
                    },
                )
                }
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
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    var swipeWidth by remember { mutableIntStateOf(0) }
    val swipeHolder = remember { ReaderSwipeBox() }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * swipeWidthPercent() / 100f },
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.Settled) {
                true
            } else {
                val box = swipeHolder.state ?: return@rememberSwipeToDismissBoxState false
                val offset = runCatching { box.requireOffset() }.getOrDefault(0f)
                swipeReached(offset, swipeWidth.toFloat())
            }
        },
    )
    swipeHolder.state = dismissState
    LaunchedEffect(dismissState.currentValue) {
        val value = dismissState.currentValue
        if (value == SwipeToDismissBoxValue.Settled) return@LaunchedEffect
        val binding = swipeBindingForOffset(
            offsetPx = readerDismissOffset(value, swipeWidth.toFloat(), leftToRight),
            leftToRight = leftToRight,
            trailing = account.readerSwipeTrailing,
            leading = account.readerSwipeLeading,
        )
        if (binding != null) performReaderSwipe(binding)
        dismissState.reset()
    }
    Box(Modifier.fillMaxSize().onSizeChanged { swipeWidth = it.width }) {
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val offset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
            val binding = swipeBindingForOffset(
                offsetPx = offset,
                leftToRight = leftToRight,
                trailing = account.readerSwipeTrailing,
                leading = account.readerSwipeLeading,
            )
            if (binding != null) {
                val visual = swipeVisual(binding.action)
                val tint = if (binding.action == SwipeAction.Delete) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val atStart = if (leftToRight) offset > 0f else offset < 0f
                val reached = swipeReached(offset, swipeWidth.toFloat())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            readerSwipeContainerColor(visual.container).copy(
                                alpha = if (reached) 1f else 0.45f,
                            ),
                        )
                        .padding(
                            start = if (atStart) 24.dp else 0.dp,
                            end = if (atStart) 0.dp else 24.dp,
                        ),
                    contentAlignment = if (atStart) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (atStart) {
                            ReaderSwipeIcon(visual.icon, tint)
                            Text(
                                text = readerSwipeActionName(binding.action),
                                color = tint,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        } else {
                            Text(
                                text = readerSwipeActionName(binding.action),
                                color = tint,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            ReaderSwipeIcon(visual.icon, tint)
                        }
                    }
                }
            }
        },
    ) {
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
            if (headerReady && headerExpanded) {
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
                    onCollapse = { headerExpanded = false },
                    extraHeaders = viewerExtra,
                    unlabeledCharset = account.unlabeledCharset,
                )
            }
            if (headerReady && !headerExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = org.dlang.liveimap.ui.nonAscii(headerSubject),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { headerExpanded = true },
                        modifier = Modifier.testTag("reader-header-toggle"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = S("Expand header"),
                        )
                    }
                }
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
                when {
                    noTextPart -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(S("There is no text part"))
                                if (showHtmlButton) {
                                    TextButton(onClick = { requestView(BodyView.PlainOrHtml) }) {
                                        Text(S("Show HTML"))
                                    }
                                }
                            }
                        }
                    }
                    absent == null && renderedHtml -> {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                        val note = charsetNote
                        if (note != null) Text(note, modifier = Modifier.padding(horizontal = 8.dp))
                        if (bodyTruncated) Text(S("Showing the first %1\$d KB only.", RawViewCapBytes / 1024), modifier = Modifier.padding(horizontal = 8.dp))
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
                                view.settings.textZoom = effectiveTextZoom(appTextZoom, pinchZoom)
                                view.settings.setSupportZoom(true)
                                view.settings.builtInZoomControls = true
                                view.settings.displayZoomControls = false
                                view.settings.blockNetworkLoads = !allowImages
                                // Original colors (dark theme only): the sender's own colors on a white page.
                                val recolor = dark && !originalColors
                                view.setBackgroundColor(if (dark && originalColors) android.graphics.Color.WHITE else htmlBackground)
                                applyHtmlDark(view.settings)
                                val loaded = loadThemedHtml(view.tag, bodyText, recolor, htmlBackground, htmlForeground, allowImages)
                                val page = loaded.second
                                if (page != null) {
                                    view.tag = loaded.first
                                    view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                                    val y = held.webScroll
                                    if (y > 0) view.post { view.scrollTo(0, y) }
                                }
                            },
                            onRelease = { view ->
                                val parent = view.parent
                                if (parent is android.view.ViewGroup) parent.removeView(view)
                                view.destroy()
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
                            unlabeledCharset = account.unlabeledCharset,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    absent == null -> {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                        if (showHtmlButton) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                TextButton(onClick = { requestView(BodyView.PlainOrHtml) }) {
                                    Text(S("Show HTML"))
                                }
                            }
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .pinchToZoom { factor ->
                                    pinchZoom = effectiveTextZoom(appTextZoom, pinchZoom * factor) /
                                        clampTextZoom(appTextZoom).toFloat()
                                }
                                .verticalScroll(scroll)
                                .padding(8.dp)
                                .testTag("reader-plain"),
                        ) {
                          val base = LocalTextStyle.current
                          CompositionLocalProvider(LocalTextStyle provides base.copy(fontSize = base.fontSize * pinchZoom)) {
                            val note = charsetNote
                            if (note != null) Text(note)
                            if (bodyTruncated && selectedView == BodyView.PlainOrText) {
                                Text(S("Showing the first %1\$d KB only.", RawViewCapBytes / 1024))
                            }
                            // Monospaced: original breaks, no wrapping; the whole body scrolls sideways together.
                            val hscroll = rememberScrollState()
                            Column(if (noWrap) Modifier.horizontalScroll(hscroll).testTag("reader-nowrap") else Modifier) {
                                for ((quoted, line) in plainLines) {
                                    if (noWrap) {
                                        Text(text = line, fontFamily = plainFont, softWrap = false)
                                    } else {
                                        Text(
                                            text = line,
                                            color = if (quoted) quoteTint else Color.Unspecified,
                                            fontFamily = plainFont,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
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
                        TextButton(onClick = { runReaderUndo(offer) }) { Text(S("Undo", name = "reader_undo")) }
                        if (showExpunge) {
                            TextButton(onClick = { askOrExpunge(offer.uids) }) { Text(S("Expunge", name = "reader_expunge")) }
                        }
                    },
                ) { Text(data.visuals.message) }
            } else {
                Snackbar(data)
            }
        }
    }
    }
    }
    ReaderChrome()
    @Composable
    fun ReaderDialogs() {
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
                            S("The link text shows %1\$s, but the link goes to %2\$s.", check.claimedHost, check.host),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("link-mismatch"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (check.asciiHost.isNotEmpty()) {
                        Text(
                            S("This address uses non-Latin look-alike letters. Its real form is %1\$s.", check.asciiHost),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("link-idn"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    SelectionContainer { Text(confirmedLink.take(2000), style = MaterialTheme.typography.bodySmall) }
                    when (safety) {
                        LinkSafety.Safe -> {}
                        LinkSafety.Warn -> Text("\n" + S("This is not a web or mail link. It opens another app, which may call, send a message or act for you. Open it only if you trust the sender."))
                        LinkSafety.Blocked -> Text("\n" + S("Links of this type are blocked: they can reach files, other apps' data or run code."))
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
                            S("Open"),
                            color = if (safety == LinkSafety.Warn || check.claimedHost.isNotEmpty() || check.asciiHost.isNotEmpty()) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) { Text(S("Cancel", name = "reader_cancel")) }
            },
        )
    }
    if (confirmExpunge) {
        val uidPlus = session.featureCaps.uidPlus
        val expungeBody = S("Permanently removes messages marked deleted in this folder. This cannot be undone.", name = "reader_expunge_body")
        val expungeOthers = S("This includes messages marked deleted by other clients.", name = "reader_expunge_others")
        val body = if (uidPlus) expungeBody else "$expungeBody $expungeOthers"
        AlertDialog(
            onDismissRequest = { confirmExpunge = false },
            title = { Text(S("Expunge?")) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    val uids = expungeUids
                    confirmExpunge = false
                    runReaderExpunge(uids)
                }) { Text(S("Expunge", name = "reader_expunge"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExpunge = false }) { Text(S("Cancel", name = "reader_cancel")) }
            },
        )
    }
    if (confirmPermanent) {
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            text = { Text(if (deleteWarn.isNotEmpty()) S(deleteWarn) else S("The messages will be removed permanently.")) },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = pendingReaderPolicy
                    confirmPermanent = false
                    scope.launch {
                        runReaderPolicyLocked(chosen)
                    }
                }) {
                    Text(
                        stringResource(SettingsTreeGenerated.resource("settings_delete_permanently")),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanent = false }) {
                    Text(S("Cancel", name = "reader_cancel"))
                }
            },
        )
    }
    if (showFlags) {
        AlertDialog(
            onDismissRequest = { showFlags = false },
            title = { Text(S("Flags")) },
            text = {
                Column {
                    for (flag in listOf("\\Seen", "\\Answered", "\\Flagged", "\\Deleted")) {
                        Row {
                            TextButton(onClick = { storeReaderFlag(flag, set = true) }) {
                                Text(S("Set %1\$s", flag))
                            }
                            TextButton(onClick = { storeReaderFlag(flag, set = false) }) {
                                Text(S("Clear %1\$s", flag))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFlags = false }) { Text(S("Close")) }
            },
        )
    }
    if (choosingMove) {
        MailboxChooser(
            store = store,
            onStored = { loaded ->
                account = account.copy(expandedFolders = loaded.expandedFolders)
            },
            onPick = { picked ->
                choosingMove = false
                if (picked.isNotEmpty() && sameMailbox(picked, mailbox)) {
                    postSnack("Already in ${mailboxLeaf(picked)}") // F103
                } else if (picked.isNotEmpty()) {
                    scope.launch {
                            val target = nextAfterDeleteOrMove()
                            try {
                                // F116: select, act and read the COPYUID result as one unit
                                val (epoch, targetEpoch, dest) = inRef { e, s -> // F43: this folder's UID
                                    s.copyThenDelete(listOf(uid), picked)
                                    Triple(e, s.copiedEpoch(), s.takeCopiedUids())
                                }
                                val usedMove = moveCommandKind(account.disableImapMove, session.featureCaps.move) == "Move"
                                showActionUndo(
                                    refUndo(accountId, mailbox, epoch, uid, picked, dest.asList(), targetEpoch, usedMove),
                                    target,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                postSnack(error.text)
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
            if (!copied) return@launch
            try {
                store.onAccount(publish = { updated ->
                    account = updated
                }) {
                    store.update { it.copy(lastSaveMailbox = destination) }
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
            text = { Text(S("Save to %1\$s?", mailbox)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    copySavedMessage(mailbox)
                }) { Text(S("Save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmSave = false
                        choosingSave = true
                    }) { Text(S("Choose")) }
                    TextButton(onClick = { confirmSave = false }) {
                        Text(S("Cancel", name = "reader_cancel"))
                    }
                }
            },
        )
    }
    if (choosingSave) {
        MailboxChooser(
            store = store,
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
    }
    fun takeChosenAddress(choice: TakeAddress, book: String) {
        if (book.isEmpty()) {
            postSnack(noAddressBookText)
            return
        }
        scope.launch {
                try {
                    when (val read = readPineBook(session, book, account.bodyFetchChunk)) {
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
                                    account.addressBookHistory.toInt(),
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
    fun startTake(choice: TakeAddress, book: AddressBookEntry) {
        if (book.kind == "android") {
            val granted = context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingAndroidTake = choice to book
                val launch = launchWriteContacts
                if (launch == null) {
                    pendingAndroidTake = null
                    postSnack(S(context, "Contacts permission is needed."))
                    return
                }
                launch()
                return
            }
            scope.launch { writeAndroidContact(choice, book) }
            return
        }
        takeChosenAddress(choice, book.mailbox)
    }
    @Composable
    fun TakeAddressDialog() {
        if (!confirmTake) return
        AlertDialog(
            onDismissRequest = { confirmTake = false },
            text = { Text(S("Add to the address book?")) },
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
                            when (val save = account.addressSave()) {
                                AddressSave.None -> postSnack(noAddressBookText)
                                AddressSave.Ask -> pendingTake = choice
                                is AddressSave.One -> startTake(choice, save.book)
                            }
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTake = false }) {
                    Text(S("Cancel", name = "reader_cancel"))
                }
            },
        )
    }
    @Composable
    fun PickSaveBookDialog() {
        val choice = pendingTake ?: return
        val androidFallback = S("Add Android contacts")
        val books = saveBookChoices(account.addressBooks)
        AlertDialog(
            onDismissRequest = { pendingTake = null },
            title = { Text(S("Choose an address book")) },
            text = {
                Column {
                    for (book in books) {
                        val label = if (book.kind == "android") book.accountName.ifEmpty { androidFallback } else book.mailbox
                        TextButton(onClick = {
                            pendingTake = null
                            startTake(choice, book)
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingTake = null }) {
                    Text(S("Cancel", name = "reader_cancel"))
                }
            },
        )
    }
    ReaderDialogs()
    TakeAddressDialog()
    PickSaveBookDialog()
    }
    ReaderTail()
    val requestWriteContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingAndroidTake
        pendingAndroidTake = null
        if (!granted || pending == null) {
            if (pending != null) postSnack(S(context, "Contacts permission was denied."))
            return@rememberLauncherForActivityResult
        }
        scope.launch { writeAndroidContact(pending.first, pending.second) }
    }
    launchWriteContacts = { requestWriteContacts.launch(Manifest.permission.WRITE_CONTACTS) }

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
            TextButton(onClick = onRetry) { Text(S("Retry", name = "reader_retry")) }
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

@Composable
private fun deletePolicyLabel(policy: DeletePolicy): String = stringResource(
    when (policy) {
        DeletePolicy.MarkDeleted -> SettingsTreeGenerated.resource("settings_mark_deleted")
        DeletePolicy.MoveToTrash -> SettingsTreeGenerated.resource("settings_move_to_trash")
        DeletePolicy.DeletePermanently -> SettingsTreeGenerated.resource("settings_delete_permanently")
    },
)

@Composable
private fun readerMenuHelp(action: ReaderToolbarAction): String = when (action) {
        ReaderToolbarAction.Refresh -> S("Loads the message again.")
        ReaderToolbarAction.Reply -> S("Replies to the sender.")
        ReaderToolbarAction.ReplyAll -> S("Replies to every recipient.")
        ReaderToolbarAction.Forward -> S("Forwards the message.")
        ReaderToolbarAction.Delete -> S("Deletes with this policy.")
        ReaderToolbarAction.Move -> S("Moves the message.")
        ReaderToolbarAction.Spam -> S("Marks the message as spam.")
        ReaderToolbarAction.Bounce -> S("Bounces the message.")
        ReaderToolbarAction.Headers -> S("Shows every header.")
    }

@Composable
private fun readerActionName(action: ReaderToolbarAction): String = when (action) {
        ReaderToolbarAction.Refresh -> S("Refresh")
        ReaderToolbarAction.Reply -> S("Reply")
        ReaderToolbarAction.ReplyAll -> S("Reply all")
        ReaderToolbarAction.Forward -> S("Forward")
        ReaderToolbarAction.Delete -> S("Delete")
        ReaderToolbarAction.Move -> S("Move")
        ReaderToolbarAction.Spam -> S("Spam")
        ReaderToolbarAction.Bounce -> S("Bounce")
        ReaderToolbarAction.Headers -> S("All headers")
    }

@Composable
private fun bodyViewName(view: BodyView): String = when (view) {
        BodyView.PlainOrError -> S("Plain text")
        BodyView.PlainOrHtml -> stringResource(SettingsTreeGenerated.resource("label_html"))
        BodyView.PlainOrText -> stringResource(SettingsTreeGenerated.resource("label_html_text"))
        BodyView.Headers -> S("Headers")
        BodyView.Raw -> S("Raw source")
    }

@Composable
private fun forwardStyleName(asAttachment: Boolean): String = oppositeForwardLabel(
    S("Forward inline"),
    S("Forward as attachment", name = "label_forward_attachment"),
    asAttachment,
)

private fun readerActionImage(action: ReaderToolbarAction) = when (action) {
    ReaderToolbarAction.Refresh -> Icons.Filled.Refresh
    ReaderToolbarAction.Reply -> Icons.AutoMirrored.Filled.Reply
    ReaderToolbarAction.ReplyAll -> Icons.AutoMirrored.Filled.ReplyAll
    ReaderToolbarAction.Forward -> Icons.AutoMirrored.Filled.Forward
    ReaderToolbarAction.Delete -> Icons.Filled.Delete
    ReaderToolbarAction.Move -> moveImage
    ReaderToolbarAction.Spam -> spamImage
    ReaderToolbarAction.Bounce -> Icons.AutoMirrored.Filled.Redo
    ReaderToolbarAction.Headers -> Icons.Filled.Code
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
    onCollapse: () -> Unit = {},
    extraHeaders: List<Pair<String, String>> = emptyList(),
    unlabeledCharset: String,
) {
    val RawViewCapBytes = SettingsHub.uiNow().maxRawViewBytes.toInt()
    var recipientsOpen by remember(toLine) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(org.dlang.liveimap.ui.nonAscii(S("From: %1\$s", from)), modifier = Modifier.weight(1f))
                // Short header <-> all headers, in place (Pine's H).
                IconButton(onClick = onToggleAllHeaders, modifier = Modifier.testTag("reader-all-headers")) {
                    Icon(
                        if (allHeaders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (allHeaders) S("Short header") else S("All headers"),
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
                    Text(S("Decode encoded words (RFC 2047)"), modifier = Modifier.padding(start = 8.dp))
                }
                if (allHeadersTruncated) Text(S("Showing the first %1\$d KB only.", RawViewCapBytes / 1024))
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = allHeaderText?.let { headerViewText(it, decode, unlabeledCharset) } ?: "…",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = toLine,
                            maxLines = if (recipientsOpen) Int.MAX_VALUE else 1,
                            overflow = if (recipientsOpen) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (recipientsOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                            contentDescription = null,
                        )
                    }
                }
            }
            Text(date)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    org.dlang.liveimap.ui.nonAscii(subject),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse, modifier = Modifier.testTag("reader-header-toggle")) {
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = S("Collapse header"),
                    )
                }
            }
            if (deleted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(S("Deleted")) })
                    TextButton(onClick = onUndelete) { Text(S("Undelete", name = "reader_undelete")) }
                }
            }
        }
    }
}

/** A chunk leaves [bodyText] empty, so the WebView token stays put and [themedHtml] does not run. */
internal fun loadThemedHtml(
    previous: Any?,
    bodyText: String,
    recolor: Boolean,
    background: Int,
    foreground: Int,
    allowImages: Boolean,
): Pair<Any?, String?> {
    if (bodyText.isEmpty()) return previous to null
    val token = bodyText to recolor to background to foreground to allowImages
    if (previous == token) return previous to null
    return token to themedHtml(bodyText, recolor, background, foreground)
}

/** Several HTML chunks theme the page once, when the part is complete. */
internal fun themedHtmlChunkLoads(chunks: List<String>): Int {
    val carry = Utf8Carry()
    val lines = ArrayList<String>()
    var bodyText = ""
    var token: Any? = null
    var calls = 0
    chunks.forEachIndexed { index, chunk ->
        val htmlBody = carry.appendPiece(chunk, lines, index == chunks.lastIndex, html = true)
        if (htmlBody != null) bodyText = if (bodyText.isEmpty()) htmlBody else bodyText + htmlBody
        val loaded = loadThemedHtml(token, bodyText, false, 0, 0, false)
        if (loaded.second != null) {
            token = loaded.first
            calls += 1
        }
    }
    return calls
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
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            if (row.done) {
                Text(row.label)
            } else {
                TextButton(onClick = onFetch, enabled = !row.busy) { Text(row.label) }
            }
            if (detail != null) Text(detail)
        }
        if (row.done) {
            TextButton(onClick = onOpen) { Text(S("Open")) }
            TextButton(onClick = onShare) { Text(S("Share")) }
            TextButton(onClick = onSave) { Text(S("Save")) }
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
    encoding = part.encoding,
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

private val attachmentAccountId = Regex("^[0-9a-f-]{36}$")
private val attachmentSectionChars = Regex("[^A-Za-z0-9._-]")

internal fun attachmentPrefixFor(accountId: String): String {
    if (!attachmentAccountId.matches(accountId)) {
        throw IllegalArgumentException("account id")
    }
    return "liveimap-$accountId-"
}

internal fun attachmentCacheName(
    accountId: String,
    token: String,
    uidValidity: Long,
    uid: Long,
    section: String,
): String {
    val safe = section.replace(attachmentSectionChars, "_")
    return attachmentPrefixFor(accountId) + "$token-$uidValidity-$uid-$safe"
}

private fun attachmentUri(name: String): Uri = Uri.parse("content://com.davidlang.liveimap.attachments/$name")

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

private fun deleteReaderAttachmentCache(dir: File, accountId: String, token: String) {
    val prefix = attachmentPrefixFor(accountId) + "$token-"
    val files = dir.listFiles() ?: return
    for (file in files) {
        if (file.isFile && file.name.startsWith(prefix)) file.delete()
    }
}

@Composable
private fun recipientLine(to: String, cc: String): String? {
    val toText = to.trim()
    val ccText = cc.trim()
    val toLine = S("To: %1\$s", toText)
    val ccLine = S("Cc: %1\$s", ccText)
    val bothLine = S("To: %1\$s  Cc: %2\$s", toText, ccText)
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
fun headerViewText(raw: String, decode: Boolean, fallback: String): String =
    if (!decode) raw else raw.lineSequence().joinToString("\n") {
        org.dlang.liveimap.ui.compose.decodeHeaderWordsForView(it, fallback)
    }

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
    unlabeledCharset: String,
) {
    val RawViewCapBytes = SettingsHub.uiNow().maxRawViewBytes.toInt()
    Column(modifier.verticalScroll(scroll).padding(8.dp)) {
        if (headers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = decode,
                    onCheckedChange = onDecode,
                    modifier = Modifier.testTag("reader-decode-headers"),
                )
                Text(S("Decode encoded words (RFC 2047)"), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (truncated) Text(S("Showing the first %1\$d KB only.", RawViewCapBytes / 1024))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = if (headers) headerViewText(text, decode, unlabeledCharset) else text,
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
        title = { Text(S("Attachment type does not match")) },
        text = {
            Text(
                S("%1\$s\n\nClaimed type: %2\$s\nFile extension type: %3\$s\nContent looks like: %4\$s",
                    name,
                    mismatch.claimed,
                    mismatch.extension ?: "-",
                    "${mismatch.detected.label} (${mismatch.detected.mime})",
                ) + if (mismatch.detected.dangerous) "\n\n" + S("This kind of file can run code or install an app. Open it only if you trust the sender.") else "",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSave) { Text(S("Save", name = "reader_type_mismatch_save")) }
                TextButton(onClick = onOpenAs) {
                    Text(
                        if (share) S("Share as %1\$s", openAs) else S("Open as %1\$s", openAs),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S("Cancel", name = "reader_cancel")) } },
    )
}

@Composable
private fun ReaderDisplayMenu(selected: ReaderDisplay, onOpen: () -> Unit) {
    TextButton(
        onClick = onOpen,
        modifier = Modifier.testTag("reader-display"),
    ) { Text(readerDisplayName(selected)) }
}

@Composable
private fun readerSwipeActionName(action: SwipeAction): String = when (action) {
        SwipeAction.Delete -> S("Delete")
        SwipeAction.Move -> S("Move")
        SwipeAction.Reply -> S("Reply")
        SwipeAction.ReplyAll -> S("Reply all")
        SwipeAction.SetFlag -> S("Set flag")
        SwipeAction.ClearFlag -> S("Clear flag")
        SwipeAction.FlagScreen -> S("Flag screen")
        SwipeAction.Next -> S("Next")
        SwipeAction.Previous -> S("Previous")
    }

@Composable
private fun readerSwipeContainerColor(name: String): Color = when (name) {
    "errorContainer" -> MaterialTheme.colorScheme.errorContainer
    "tertiaryContainer" -> MaterialTheme.colorScheme.tertiaryContainer
    "primaryContainer" -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun ReaderSwipeIcon(name: String, tint: Color) {
    Icon(
        imageVector = when (name) {
            "delete" -> Icons.Filled.Delete
            "drive_file_move" -> Icons.Filled.DriveFileMove
            "reply" -> Icons.AutoMirrored.Filled.Reply
            "reply_all" -> Icons.AutoMirrored.Filled.ReplyAll
            "arrow_forward" -> Icons.AutoMirrored.Filled.ArrowForward
            "arrow_back" -> Icons.AutoMirrored.Filled.ArrowBack
            "flag" -> Icons.Filled.Flag
            else -> Icons.Outlined.OutlinedFlag
        },
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = tint,
    )
}

private class ReaderSwipeBox {
    var state: SwipeToDismissBoxState? = null
}

private fun readerDismissOffset(value: SwipeToDismissBoxValue, widthPx: Float, leftToRight: Boolean): Float {
    val span = if (widthPx > 0f) widthPx else 1f
    return when (value) {
        SwipeToDismissBoxValue.EndToStart -> if (leftToRight) -span else span
        SwipeToDismissBoxValue.StartToEnd -> if (leftToRight) span else -span
        SwipeToDismissBoxValue.Settled -> 0f
    }
}

@Composable
internal fun readerDisplayName(display: ReaderDisplay): String {
    val title = org.dlang.liveimap.settings.installedLeaf("readerDisplay")?.choiceTitles?.get(display.name)
    val id = if (title.isNullOrEmpty()) 0 else org.dlang.liveimap.settings.SettingsTreeGenerated.resource(title)
    if (id != 0) return stringResource(id)
    return when (display) {
        ReaderDisplay.Html -> stringResource(SettingsTreeGenerated.resource("label_html"))
        ReaderDisplay.HtmlAsText -> stringResource(SettingsTreeGenerated.resource("label_html_text"))
        ReaderDisplay.Flowed -> stringResource(SettingsTreeGenerated.resource("settings_flowed_text"))
        ReaderDisplay.Monospaced -> S("Mono")
    }
}
