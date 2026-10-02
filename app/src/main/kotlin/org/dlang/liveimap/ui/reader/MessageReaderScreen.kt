package org.dlang.liveimap.ui.reader

import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.settings.bodyViewLabel
import org.dlang.liveimap.ui.folder.MailboxChooser
import org.dlang.liveimap.ui.compose.attachmentParts
import org.dlang.liveimap.ui.compose.missingPartText
import org.dlang.liveimap.ui.compose.nextWireCount
import org.dlang.liveimap.ui.compose.textPart

private class Utf8Carry {
    var pending: ByteArray = ByteArray(0)
}

private class ScrollBridge {
    var onNearEnd: () -> Unit = {}
}

private data class AttachmentRow(
    val section: String,
    val label: String,
    val size: Int,
    val fetched: Int,
    val done: Boolean,
)

@Composable
fun MessageReaderScreen(
    mailbox: String,
    uid: Long,
    sequence: Int,
    onCompose: (ComposeSeed) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val carry = remember { Utf8Carry() }
    val bridge = remember { ScrollBridge() }
    val scroll = rememberScrollState()
    var notice by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf(AccountSettings()) }
    var structure by remember { mutableStateOf<MimePart?>(null) }
    var selectedView by remember { mutableStateOf(BodyView.PlainOrError) }
    var renderedHtml by remember { mutableStateOf(false) }
    var bodyText by remember { mutableStateOf("") }
    var bodyOffset by remember { mutableIntStateOf(0) }
    var bodySize by remember { mutableIntStateOf(0) }
    var bodySection by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf<String?>(null) }
    var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }
    var seenStored by remember { mutableStateOf(false) }
    var selectedMailbox by remember { mutableStateOf<String?>(null) }
    var choosingMove by remember { mutableStateOf(false) }
    val saveMutex = remember { Mutex() }

    BackHandler(enabled = choosingMove) {
        choosingMove = false
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
                notice = error.text
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
        }
        if (bodySection != part.section) return
        if (part.size > 0 && bodyOffset >= part.size) return
        val length = if (part.size > 0) {
            nextWireCount(bodyOffset, part.size, 4096, false)
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
            notice = error.text
            return
        }
        noteSeen(markSeen)
        if (chunk.isEmpty()) {
            bodyOffset = if (part.size > 0) part.size else bodyOffset + length
            return
        }
        bodyOffset += chunk.size
        val (text, rest) = appendUtf8(carry.pending, chunk)
        carry.pending = rest
        bodyText += text
    }

    suspend fun pullUnbounded(section: String, markSeen: Boolean) {
        missing = null
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
                notice = error.text
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
                notice = error.text
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

    fun showMissing(text: String) {
        missing = text
        renderedHtml = false
        bodyText = ""
        bodySection = null
        bodyOffset = 0
        bodySize = 0
        carry.pending = ByteArray(0)
    }

    suspend fun loadPreferred(view: BodyView) {
        val root = structure ?: return
        val plain = textPart(root, "plain")
        if (plain != null) {
            missing = null
            renderedHtml = false
            pullBody(plain, reset = true, markSeen = !seenStored)
            return
        }
        when (view) {
            BodyView.PlainOrHtml -> {
                val html = textPart(root, "html")
                if (html == null) {
                    showMissing(missingPartText(true))
                } else {
                    missing = null
                    renderedHtml = true
                    pullBody(html, reset = true, markSeen = !seenStored)
                }
            }
            BodyView.PlainOrText -> {
                val html = textPart(root, "html")
                if (html == null) {
                    showMissing(missingPartText(true))
                } else {
                    renderedHtml = false
                    missing = null
                    bodyText = ""
                    bodySection = null
                    bodyOffset = 0
                    bodySize = html.size
                    carry.pending = ByteArray(0)
                    val bytes = readPart(html, markSeen = !seenStored) ?: return
                    bodyOffset = bytes.size
                    bodyText = htmlAsText(bytes.toString(Charsets.UTF_8))
                }
            }
            BodyView.PlainOrError -> showMissing(missingPartText(false))
            BodyView.Headers, BodyView.Raw -> Unit
        }
    }

    fun requestView(view: BodyView) {
        selectedView = view
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                when (view) {
                    BodyView.Headers -> pullUnbounded("HEADER", markSeen = !seenStored)
                    BodyView.Raw -> pullUnbounded("*", markSeen = !seenStored)
                    else -> loadPreferred(view)
                }
            }
        }
    }

    fun fetchAttachment(row: AttachmentRow) {
        if (row.done) return
        scope.launch {
            gate.withLock {
                if (!connected) return@withLock
                val current = attachments.firstOrNull { it.section == row.section } ?: return@withLock
                if (current.done) return@withLock
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
                    } else {
                        while (offset < current.size) {
                            val length = nextWireCount(offset, current.size, 65536, true)
                            if (length <= 0) break
                            val chunk = session.peekPart(uid, current.section, offset, length)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            offset += chunk.size
                            val fetchedNow = offset
                            attachments = attachments.map { item ->
                                if (item.section == current.section) {
                                    item.copy(fetched = fetchedNow, done = false)
                                } else {
                                    item
                                }
                            }
                        }
                    }
                    val safe = current.section.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    try {
                        File(appContext.cacheDir, "liveimap-$uid-$safe").writeBytes(out.toByteArray())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        notice = error.message ?: "not connected"
                    }
                    val fetchedNow = offset
                    attachments = attachments.map { item ->
                        if (item.section == current.section) {
                            item.copy(fetched = fetchedNow, done = true)
                        } else {
                            item
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    notice = error.text
                }
            }
        }
    }

    LaunchedEffect(session, mailbox, uid) {
        var initialView = BodyView.PlainOrError
        var openOk = false
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
            selectedView = settings.bodyView
            initialView = settings.bodyView
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
                    )
                }
                connected = true
                openOk = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
            }
        }
        if (openOk) requestView(initialView)
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

    val systemDark = isSystemInDarkTheme()
    val dark = when (account.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.FollowSystem -> systemDark
    }
    Column(Modifier.fillMaxSize()) {
        val status = notice
        if (status != null) {
            Text(text = status, modifier = Modifier.padding(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            IconButton(onClick = {
                scope.launch {
                    gate.withLock {
                        try {
                            session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: MailFailure) {
                            notice = error.text
                        }
                    }
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                )
            }
            IconButton(onClick = { choosingMove = true }) {
                Icon(
                    imageVector = moveImage,
                    contentDescription = "Move",
                )
            }
            val spamMailbox = account.spamMailbox
            if (spamMailbox.isNotEmpty()) {
                IconButton(onClick = {
                    scope.launch {
                        gate.withLock {
                            try {
                                session.copyThenDelete(listOf(uid), spamMailbox)
                                onBack()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                notice = error.text
                            }
                        }
                    }
                }) {
                    Icon(
                        imageVector = spamImage,
                        contentDescription = "Spam",
                    )
                }
            }
            if (sequence != 0) {
                Text("Message $sequence")
            }
            TextButton(onClick = {
                onCompose(ComposeSeed(ComposeKind.Reply, mailbox, listOf(uid)))
            }) { Text("Reply") }
            TextButton(onClick = {
                onCompose(ComposeSeed(ComposeKind.ReplyAll, mailbox, listOf(uid)))
            }) { Text("Reply all") }
            TextButton(onClick = {
                onCompose(ComposeSeed(ComposeKind.Forward, mailbox, listOf(uid)))
            }) { Text("Forward") }
            TextButton(onClick = {
                onCompose(ComposeSeed(ComposeKind.Bounce, mailbox, listOf(uid)))
            }) { Text("Bounce") }
            MessageViewMenu(selected = selectedView, onSelect = { requestView(it) })
        }
        for (row in attachments) {
            TextButton(
                onClick = { fetchAttachment(row) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(attachmentCaption(row))
            }
        }
        val absent = missing
        if (absent != null) {
            Text(text = absent, modifier = Modifier.padding(8.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (absent == null && renderedHtml) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.blockNetworkLoads = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            applyHtmlDark(settings, dark)
                            webViewClient = object : WebViewClient() {
                                @Deprecated("Deprecated in API 24")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = true
                            }
                            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                val extent = (contentHeight * scale) - height
                                if (scrollY > 0 && extent - scrollY < 48f) bridge.onNearEnd()
                            }
                        }
                    },
                    update = { view ->
                        applyHtmlDark(view.settings, dark)
                        val page = bodyText
                        if (view.tag != page) {
                            view.tag = page
                            view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (absent == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                ) {
                    Text(text = bodyText, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
    if (choosingMove) {
        MailboxChooser(
            store = store,
            saveMutex = saveMutex,
            onStored = { loaded ->
                account = account.copy(expandedFolders = loaded.expandedFolders)
            },
            onPick = { mailbox ->
                choosingMove = false
                if (mailbox.isNotEmpty()) {
                    scope.launch {
                        gate.withLock {
                            try {
                                session.copyThenDelete(listOf(uid), mailbox)
                                onBack()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                notice = error.text
                            }
                        }
                    }
                }
            },
            onDismiss = { choosingMove = false },
        )
    }
}

@Composable
private fun MessageViewMenu(selected: BodyView, onSelect: (BodyView) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(bodyViewLabel(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BodyView.entries.forEach { view ->
                DropdownMenuItem(
                    text = { Text(bodyViewLabel(view)) },
                    onClick = {
                        expanded = false
                        onSelect(view)
                    },
                )
            }
        }
    }
}

private fun applyHtmlDark(settings: WebSettings, dark: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        settings.isAlgorithmicDarkeningAllowed = dark
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        settings.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
    }
}

private fun attachmentCaption(row: AttachmentRow): String {
    val progress = "${row.label} ${row.fetched}/${row.size}"
    return if (row.done) "$progress fetched" else progress
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
