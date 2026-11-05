package org.dlang.liveimap.settings
import org.dlang.liveimap.i18n.S

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.dlang.liveimap.LiveImapApplication
import org.dlang.liveimap.accounts.AccountCredentials
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.jmap.JmapFolderRow
import org.dlang.liveimap.jmap.JmapHttpExchange
import org.dlang.liveimap.jmap.JmapOffer
import org.dlang.liveimap.jmap.JmapPlainHttp
import org.dlang.liveimap.jmap.JmapSession
import org.dlang.liveimap.jmap.jmapBasicAuthorization
import org.dlang.liveimap.jmap.jmapFolderLevel
import org.dlang.liveimap.jmap.offerForAccount
import org.dlang.liveimap.jmap.pinnedJmapPost
import org.dlang.liveimap.jmap.platformJmapExchange
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SerialMailSession
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.confirmMailPlaintext
import org.dlang.liveimap.ui.ObscuredTouchFilter
import org.dlang.liveimap.ui.TextButton
import org.dlang.liveimap.ui.UiDims
import org.dlang.liveimap.ui.folder.markedMailbox

internal data class ScreenLogin(
    val account: SettingsFolderAccount,
    val probeId: String,
    val settings: Settings,
    val password: String,
    val smtpPassword: String,
)

/** On-screen server, user, and passwords. Does not read the drawer account. */
internal fun screenLogin(editor: SettingsEditor, drafts: ServerDrafts?): ScreenLogin {
    val chosen = drafts?.serverDraft ?: editor.settings
    val settings = if (drafts == null) editor.settings else drafts.savedServer(editor, chosen)
    val account = settingsFolderAccount(editor.editingId, settings, "")
    return ScreenLogin(
        account,
        settingsFolderProbeId(account),
        settings,
        drafts?.passwordDraft ?: editor.password,
        drafts?.smtpPasswordDraft ?: editor.smtpPassword,
    )
}

private data class ProbeNode(
    val mailbox: String,
    val leaf: String,
    val hasChildren: Boolean,
    val specialUse: String?,
    val prefix: String,
    val namespaceRoot: Boolean,
)

private data class ProbeRow(
    val mailbox: String,
    val leaf: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val specialUse: String?,
)

private interface FolderSource {
    suspend fun top(): List<ProbeNode>
    suspend fun children(node: ProbeNode): List<ProbeNode>
    suspend fun close()
}

private class ProbeTree {
    private val expanded = LinkedHashSet<String>()
    private var top = emptyList<ProbeNode>()
    private var loaded = false
    private val children = HashMap<String, List<ProbeNode>>()

    suspend fun show(source: FolderSource): List<ProbeRow> {
        if (!loaded) {
            top = source.top()
            loaded = true
        }
        return flatten(source, top, 0)
    }

    suspend fun toggle(source: FolderSource, mailbox: String): List<ProbeRow> {
        if (!expanded.add(mailbox)) expanded.remove(mailbox)
        return show(source)
    }

    private suspend fun flatten(source: FolderSource, nodes: List<ProbeNode>, depth: Int): List<ProbeRow> {
        val out = ArrayList<ProbeRow>()
        for (node in nodes) {
            val open = node.hasChildren && node.mailbox in expanded
            out += ProbeRow(node.mailbox, node.leaf, depth, node.hasChildren, open, node.specialUse)
            if (!open) continue
            val kids = children[node.mailbox] ?: source.children(node).also { children[node.mailbox] = it }
            out += flatten(source, kids, depth + 1)
        }
        return out
    }
}

private class ImapFolderSource(private val session: MailSession) : FolderSource {
    override suspend fun top(): List<ProbeNode> {
        val nodes = ArrayList<ProbeNode>()
        val seen = HashSet<String>()
        val namespaces = session.namespaces()
        fun add(prefix: String, namespaceRoot: Boolean, entry: org.dlang.liveimap.session.FolderEntry) {
            if (!seen.add(entry.mailbox)) return
            nodes += ProbeNode(entry.mailbox, entry.leaf, entry.hasChildren, entry.specialUse, prefix, namespaceRoot)
        }
        for (ns in namespaces) {
            if (ns.kind != NamespaceKind.Personal && ns.prefix.isNotEmpty()) {
                if (seen.add(ns.prefix)) {
                    nodes += ProbeNode(ns.prefix, ns.prefix, true, null, ns.prefix, true)
                }
                continue
            }
            for (entry in session.listLevel(ns.prefix, null, false)) add(ns.prefix, false, entry)
        }
        return nodes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.leaf })
    }

    override suspend fun children(node: ProbeNode): List<ProbeNode> {
        val entries = if (node.namespaceRoot) {
            session.listLevel(node.prefix, null, false)
        } else {
            session.listLevel(node.prefix, node.mailbox, false)
        }
        return entries.map { entry ->
            ProbeNode(entry.mailbox, entry.leaf, entry.hasChildren, entry.specialUse, node.prefix, false)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.leaf })
    }

    override suspend fun close() {
        session.close()
    }
}

private class JmapFolderSource(
    private val session: JmapSession,
    private val username: String,
    private val password: String,
    private val pin: String,
    private val post: (String, String, String) -> JmapHttpExchange,
    private val policy: TlsPolicy,
    private val probeKey: String,
) : FolderSource {
    private val ids = HashMap<String, String>()

    override suspend fun top(): List<ProbeNode> = level(null)

    override suspend fun children(node: ProbeNode): List<ProbeNode> = level(node.mailbox)

    override suspend fun close() {
        JmapPlainHttp.drop(probeKey)
    }

    private suspend fun level(parentPath: String?): List<ProbeNode> {
        val parentId = if (parentPath == null) {
            null
        } else {
            ids[parentPath] ?: throw MailFailure("folder is not listed")
        }
        val rows = withContext(Dispatchers.IO) {
            jmapFolderLevel(session, parentId, username, password, pin, post, policy = policy, accountKey = probeKey)
        }
        return rows.map { row -> nodeOf(parentPath, row) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.leaf })
    }

    private fun nodeOf(parentPath: String?, row: JmapFolderRow): ProbeNode {
        val path = if (parentPath == null) row.name else parentPath + org.dlang.liveimap.jmap.JmapMailSession.DELIMITER + row.name
        ids[path] = row.id
        val use = row.role?.let { org.dlang.liveimap.jmap.JmapMailSession.specialUseOf(it) }
        return ProbeNode(path, row.name, row.hasChildren, use, "", false)
    }
}

private suspend fun openImapFolderSource(
    accountId: String,
    settings: Settings,
    password: String,
    smtpPassword: String,
    onDraft: (Settings) -> Unit,
): FolderSource {
    val app = LiveImapApplication.context ?: throw IllegalStateException("no application")
    val native = LibetpanMailSession(app, accountId).also { it.setDraftSettings(onDraft) }
    val live = SerialMailSession(native).apply {
        setCertConfirmer { confirmMailCertificate(it) }
        setPlaintextConfirmer { confirmMailPlaintext() }
    }
    try {
        when (val result = live.openWith(AccountCredentials(settings, password, smtpPassword))) {
            OpenResult.Connected -> return ImapFolderSource(live)
            is OpenResult.Rejected -> throw IllegalStateException(result.capabilities)
            is OpenResult.Failed -> throw IllegalStateException(result.text)
        }
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                live.close()
            } catch (_: Exception) {
            }
        }
        throw error
    }
}

private suspend fun openJmapFolderSource(
    accountId: String,
    settings: Settings,
    password: String,
    cacheDir: File,
): FolderSource {
    val blocked = settings.preservedSecurityKey()
    if (blocked != null) throw MailFailure("account settings need attention: $blocked")
    if (LiveImapApplication.context == null) throw IllegalStateException("no application")
    val probeKey = java.util.UUID.randomUUID().toString()
    try {
        return withContext(Dispatchers.IO) {
            val policy = settings.tlsPolicy()
            val traffic = TrafficLog.writer(cacheDir, accountId)
            val offer = offerForAccount(
                settings,
                platformJmapExchange(settings.jmapCertPin, policy, probeKey, traffic),
                accountKey = probeKey,
                traffic = traffic,
                authorization = jmapBasicAuthorization(settings.username, password),
            )
            if (offer !is JmapOffer.Mail) {
                throw IllegalStateException(
                    if (settings.jmapFolders) "no JMAP service found" else "no JMAP service found at ${settings.imapHost}",
                )
            }
            JmapFolderSource(
                offer.session,
                settings.username,
                password,
                settings.jmapCertPin,
                pinnedJmapPost(settings.jmapCertPin, probeKey, traffic, policy),
                policy,
                probeKey,
            )
        }
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { JmapPlainHttp.drop(probeKey) }
        throw error
    }
}

private suspend fun openSettingsFolderSource(
    login: ScreenLogin,
    cacheDir: File,
    onDraft: (Settings) -> Unit,
): FolderSource {
    val settings = login.settings
    return if (settings.jmapFolders) {
        openJmapFolderSource(login.probeId, settings, login.password, cacheDir)
    } else {
        openImapFolderSource(login.probeId, settings, login.password, login.smtpPassword, onDraft)
    }
}

@Composable
internal fun SettingsFolderChooser(
    editor: SettingsEditor,
    drafts: () -> ServerDrafts?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    suggestUse: String = "",
) {
    val notConnected = S("not connected")
    val cacheDir = LocalContext.current.applicationContext.cacheDir
    var rows by remember { mutableStateOf<List<ProbeRow>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }
    val tree = remember { ProbeTree() }
    val held = remember { arrayOfNulls<FolderSource>(1) }
    val turns = remember { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        var source: FolderSource? = null
        try {
            PendingLines.commitAll()
            val login = screenLogin(editor, drafts())
            source = openSettingsFolderSource(login, cacheDir) { next ->
                editor.persist(
                    editor.settings.copy(
                        certPin = next.certPin,
                        allowPlaintextAuth = next.allowPlaintextAuth,
                    ),
                )
            }
            held[0] = source
            rows = tree.show(source)
            for (turn in turns) turn()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.message?.takeIf { it.isNotBlank() } ?: notConnected
            stopped = true
            rows = emptyList()
        } finally {
            val open = source
            held[0] = null
            if (open != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        open.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        ObscuredTouchFilter()
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(S("Choose mailbox"))
                val message = notice
                if (message != null) Text(text = message)
                if (!stopped) {
                    val suggested = markedMailbox(rows, suggestUse, { it.mailbox }, { it.specialUse })
                    if (suggested != null) {
                        TextButton(onClick = { onPick(suggested) }) {
                            Text(S("Use %1\$s (marked by the server)", suggested))
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        itemsIndexed(rows, key = { index, row -> "$index:${row.mailbox}" }) { _, row ->
                            ProbePickRow(
                                row = row,
                                onPick = { onPick(row.mailbox) },
                                onToggle = {
                                    if (stopped) return@ProbePickRow
                                    val sent = turns.trySend {
                                        val current = held[0] ?: return@trySend
                                        if (stopped) return@trySend
                                        try {
                                            rows = tree.toggle(current, row.mailbox)
                                            notice = null
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            notice = error.message?.takeIf { it.isNotBlank() } ?: notConnected
                                            stopped = true
                                            rows = emptyList()
                                        }
                                    }
                                    if (!sent.isSuccess && !stopped) {
                                        notice = notConnected
                                        stopped = true
                                        rows = emptyList()
                                    }
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(S("Close")) }
            }
        }
    }
}

@Composable
private fun ProbePickRow(
    row: ProbeRow,
    onPick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = UiDims.expanderWidth * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (row.hasChildren) {
            Modifier
                .width(UiDims.expanderWidth)
                .clickable(onClick = onToggle)
        } else {
            Modifier.width(UiDims.expanderWidth)
        }
        Box(modifier = slot, contentAlignment = Alignment.Center) {
            if (row.hasChildren) Text(if (row.expanded) "-" else "+")
        }
        Text(
            text = row.leaf,
            modifier = Modifier
                .clickable(onClick = onPick)
                .padding(vertical = 8.dp),
        )
    }
}
