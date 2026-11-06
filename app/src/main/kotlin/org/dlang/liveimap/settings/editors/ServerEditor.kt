package org.dlang.liveimap.settings.editors
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.i18n.P

import org.dlang.liveimap.settings.*
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.dlang.liveimap.settings.editors.AccountListEditor
import org.dlang.liveimap.settings.editors.openingAccount
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.dlang.liveimap.session.ClientSortCollation
import org.dlang.liveimap.ui.compose.formatMailbox
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.ObscuredTouchFilter
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.davidlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.ServerCheck
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.engine.sieve.SieveCapabilities
import org.dlang.liveimap.engine.sieve.SieveFailure
import org.dlang.liveimap.engine.sieve.greetSieve
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.dropMailSession
import org.dlang.liveimap.session.existingMailSession
import org.dlang.liveimap.session.extraIdleBudgetFromText
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.session.watchedFolderText
import org.dlang.liveimap.session.watchedFoldersFromText
import org.dlang.liveimap.ui.contacts.AndroidContactSet
import org.dlang.liveimap.ui.contacts.androidContactSetsOnce
import org.dlang.liveimap.ui.contacts.hasReadContacts
import org.dlang.liveimap.ui.contacts.moveCompletionSource
import org.dlang.liveimap.ui.contacts.withSourceEnabled
import org.dlang.liveimap.ui.debug.DebugReportReview
import org.dlang.liveimap.ui.FavoriteEditDialog
import org.dlang.liveimap.ui.FavoriteWhere
import org.dlang.liveimap.ui.folder.MailboxChooser
import org.dlang.liveimap.ui.toolbar.ToolbarEditorScreen
import org.dlang.liveimap.ui.toolbar.ToolbarScreen
import org.dlang.liveimap.ui.toolbar.notePaneScroll

/**
 * Writes only the Sieve pin through [store], bound to [editingId] when that was captured.
 * Does not save the rest of the settings. The open editor for that account keeps the pin too.
 */
internal suspend fun persistSievePin(store: DataStoreSettingsStore, editingId: String?, fingerprint: String) {
    val target = if (editingId.isNullOrEmpty()) store else store.forAccount(editingId)
    // The pin is stored in this command and published on the caller before the next one, so a save cannot encode the old pin.
    target.onAccount(publish = {
        val editors = openSettingsEditors.get()
        for (editor in editors) {
            if (!sameSieveAccount(editor.editingId, editingId)) continue
            editor.settings = editor.settings.copy(sieveCertPin = fingerprint)
        }
    }) {
        target.update { it.copy(sieveCertPin = fingerprint) }
    }
    SettingsRevision.bump()
}
internal fun sieveCheckText(caps: SieveCapabilities): String {
    if (caps.implementation.isBlank() && caps.version.isBlank()) return "OK"
    val parts = ArrayList<String>(2)
    if (caps.implementation.isNotBlank()) parts.add(caps.implementation)
    if (caps.version.isNotBlank()) parts.add(caps.version)
    return parts.joinToString(" ")
}
@Composable
internal fun SieveCheckButton(editor: SettingsEditor, drafts: ServerDrafts) {
    val noSieveHost = S("Set a Sieve host, or turn on Same as inbound.")
    val cacheDir = LocalContext.current.applicationContext.cacheDir
    TextButton(
        onClick = {
            if (drafts.checkingSieve) return@TextButton
            val sieveStore = editor.bound
            val sieveAccount = editor.editingId
            val login = try {
                org.dlang.liveimap.settings.sieveTarget(editor.settings, "", "")
            } catch (_: org.dlang.liveimap.settings.SieveTargetException) {
                drafts.sieveReport = noSieveHost
                return@TextButton
            }
            val host = login.host
            val port = login.port
            if (host.isBlank()) {
                drafts.sieveReport = noSieveHost
                return@TextButton
            }
            val blocked = editor.settings.preservedSieveTlsKey()
            if (blocked != null) {
                drafts.sieveReport = "account settings need attention: $blocked"
                return@TextButton
            }
            val sieveTlsMode = editor.settings.sieveTlsMode
            val pin = editor.settings.sieveCertPin
            val trafficId = sieveAccount.orEmpty()
            drafts.checkingSieve = true
            drafts.sieveReport = ""
            editor.ui.launch {
                try {
                    drafts.sieveReport = sieveCheckText(
                        withContext(editor.settings.tlsPolicy().ioContext()) {
                            greetSieve(
                                host,
                                port,
                                sieveTlsMode,
                                pin,
                                ::confirmMailCertificate,
                                org.dlang.liveimap.engine.TrafficLog.writer(cacheDir, trafficId),
                                editor.settings.sieveConnectSeconds.toInt(),
                                editor.settings.sieveReadSeconds.toInt(),
                            ) { fingerprint ->
                                editor.persist(editor.settings.copy(sieveCertPin = fingerprint))
                            }
                        },
                    )
                } catch (failure: SieveFailure) {
                    drafts.sieveReport = failure.text
                } finally {
                    drafts.checkingSieve = false
                }
            }
        },
        enabled = !drafts.checkingSieve,
    ) {
        Text(S("Check ManageSieve"))
    }
    if (drafts.sieveReport.isNotEmpty()) Text(drafts.sieveReport)
}
@Composable
internal fun ServerEditor(node: TreeNode.Group, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val sections = walk.sections
    val chosenAccount = editor.accounts.firstOrNull { it.editing }?.id
    val drafts = remember(editor.editingId, chosenAccount) { ServerDrafts() }
    walk.drafts = drafts
    val pagePending = drafts.hasDraft || editor.pageDirty || PendingLines.pending()
    DisposableEffect(pagePending) {
        UnsavedEdits.pending = pagePending
        UnsavedEdits.ask = { go -> drafts.leaveAfter = go; drafts.discardAsk = true }
        onDispose { UnsavedEdits.pending = false; UnsavedEdits.ask = null }
    }
    LaunchedEffect(pagePending, drafts.serverReview == null, drafts.serverChecking) {
        if (!pagePending && drafts.serverReview == null && !drafts.serverChecking) {
            drafts.leaveAfter?.let { drafts.leaveAfter = null; it() }
        }
    }
    BackHandler(enabled = pagePending) { drafts.discardAsk = true }
    val saveFailure = settingsSaveFailure.value
    drafts.serverReview?.let { review ->
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(S("Save server settings?")) },
            text = {
                val lines = listOfNotNull(review.tlsWarning?.let { S(it) }) +
                    (if (review.unreachable) listOf(S("Server not reachable")) else emptyList()) +
                    review.lines
                val shown = if (saveFailure.isNullOrEmpty()) lines else lines + saveFailure
                Text(shown.joinToString("\n"))
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    confirmSettingsSave(editor, drafts, "save-anyway")
                }) { Text(S("Save anyway")) }
            },
            dismissButton = {
                TextButton(onClick = { drafts.serverReview = null; drafts.leaveAfter = null }) { Text(S("Keep editing", name = "settings_keep_editing")) }
            },
        )
    }
    if (drafts.discardAsk) {
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(S("Unsaved server changes")) },
            text = saveFailure?.takeIf { it.isNotEmpty() }?.let { failure -> { Text(failure) } },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    confirmSettingsSave(editor, drafts, "unsaved")
                }) {
                    Text(S("Save"))
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { drafts.discardAsk = false; drafts.leaveAfter = null }) { Text(S("Keep editing", name = "settings_keep_editing")) }
                    TextButton(onClick = {
                        drafts.discardAsk = false
                        drafts.discard()
                        editor.abandonPage()
                    }) {
                        Text(S("Discard changes"))
                    }
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountListEditor(
            accounts = editor.accounts,
            onPick = { editor.selectAccount(it) },
            onRemove = { editor.removeAccount(it) },
            onAdd = {
                if (editor.accounts.isEmpty()) walk.onNavigate("settings/setup")
                else {
                    editor.beginNewAccount()
                    walk.onNavigate("settings/account/_")
                }
            },
            deletingName = editor.deleteName,
            deleteError = accountDeleteError(editor),
            onRetryDelete = { editor.retryDelete() },
        )
        for (child in node.children) {
            renderSettingsNode(child, depth, walk)
        }
        // David, 2026-10-12: Save and Test account side by side; Test account logs in (IMAP or JMAP, and SMTP).
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = { saveSettingsPage(editor, drafts, "account-save") },
                enabled = settingsSaveEnabled(
                    accountsPage = false,
                    creating = editor.creating,
                    pageDirty = editor.pageDirty,
                    hasDraft = drafts.hasDraft,
                    pendingLine = PendingLines.pending(),
                    serverChecking = drafts.serverChecking,
                ),
                modifier = Modifier.testTag("account-save"),
            ) { Text(S("Save")) }
            TextButton(
                onClick = {
                    if (!drafts.probing) {
                        PendingLines.commitAll()
                        val chosen = drafts.serverDraft ?: editor.settings
                        val candidate = drafts.savedServer(editor, chosen)
                        val account = settingsFolderAccount(editor.editingId, candidate, "")
                        drafts.probing = true
                        drafts.serverReport = emptyList()
                        editor.ui.launch {
                            val id = settingsFolderProbeId(account)
                            try {
                                drafts.serverReport = testAccountLogins(
                                    candidate,
                                    testAccountProbes(
                                        id,
                                        candidate,
                                        drafts.passwordDraft ?: editor.password,
                                        drafts.smtpPasswordDraft ?: editor.smtpPassword,
                                        onDraft = { next ->
                                            editor.persist(editor.settings.copy(
                                                certPin = next.certPin,
                                                allowPlaintextAuth = next.allowPlaintextAuth,
                                            ))
                                        },
                                    ),
                                )
                            } finally {
                                drafts.probing = false
                            }
                        }
                    }
                },
                enabled = !drafts.probing,
                modifier = Modifier.testTag("account-test"),
            ) {
                Text(if (drafts.probing) S("Testing…") else S("Test account"))
            }
        }
        for (line in drafts.serverReport) {
            Text(text = line, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp))
        }
        // F46: the acceptance runner is a developer tool; release builds do not show it.
        if (com.davidlang.liveimap.BuildConfig.DEBUG) org.dlang.liveimap.debug.acceptance.AcceptanceSection()
    }
}


@Composable
internal fun PinercEditor(editor: SettingsEditor, sections: SectionOpen? = null, depth: Int = 0) {
    var importPreview by remember { mutableStateOf<PinercPreview?>(null) }
    var importGeneration by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    val settingsState = editor.settingsState
    val contextState = rememberUpdatedState(LocalContext.current)
    val pinercPhrases = PinercPhrases(
        tls = S("IMAP and SMTP ask for different TLS"),
        tlsMode = stringResource(SettingsTreeGenerated.resource("settings_tls")),
        tlsNone = stringResource(SettingsTreeGenerated.resource("settings_tls_none")),
        tlsStart = stringResource(SettingsTreeGenerated.resource("settings_tls_starttls")),
        tlsImplicit = stringResource(SettingsTreeGenerated.resource("settings_tls_implicit")),
        smtpUsername = stringResource(SettingsTreeGenerated.resource("settings_smtp_username")),
        local = S("Local path is not a mailbox"),
        history = S("Address book history is not a number."),
        sort = S("Sort key is not supported"),
        rule = S("Startup rule not recognized"),
        expunge = S("Expunge already happens only when asked"),
        passwords = S("Passwords are not imported"),
        folders = S("Folder lists are not imported"),
        signature = S("Signature is not a setting"),
        perFolder = S("Per-folder startup rules are not imported"),
        inboxDefault = S("INBOX opens at: First unread (alpine's default)"),
        change = S("%1\$s: %2\$s → %3\$s"),
        imapHost = stringResource(SettingsTreeGenerated.resource("settings_imap_host")),
        imapPort = stringResource(SettingsTreeGenerated.resource("settings_imap_port")),
        smtpHost = stringResource(SettingsTreeGenerated.resource("settings_smtp_host")),
        smtpPort = stringResource(SettingsTreeGenerated.resource("settings_smtp_port")),
        username = stringResource(SettingsTreeGenerated.resource("settings_username")),
        displayName = stringResource(SettingsTreeGenerated.resource("settings_display_name")),
        altAddresses = stringResource(SettingsTreeGenerated.resource("settings_alt_addresses")),
        email = stringResource(SettingsTreeGenerated.resource("settings_email")),
        sentMailbox = stringResource(SettingsTreeGenerated.resource("settings_sent_mailbox")),
        postponedMailbox = stringResource(SettingsTreeGenerated.resource("settings_postponed_mailbox")),
        addressBookMailbox = S("Save contacts to"),
        historyLabel = stringResource(SettingsTreeGenerated.resource("settings_history_label")),
        extraAddressBooks = S("Extra address books"),
        globalAddressBooks = S("Global address books (read only)"),
        defaultView = stringResource(SettingsTreeGenerated.resource("settings_default_view")),
        newest = stringResource(SettingsTreeGenerated.resource("reader_newest")),
        askExpunge = stringResource(SettingsTreeGenerated.resource("settings_ask_expunge")),
        inboxOpens = stringResource(SettingsTreeGenerated.resource("settings_inbox_opens")),
        otherHost = S("%1\$s is a different server"),
        inboxBraces = S("inbox-path needs a server in braces"),
        missingFolder = S("%1\$s was not found"),
        folderCheck = S("Could not check folders"),
        favorite = S("Favorite", name = "pinerc_favorite"),
        signatureFile = S("signature-file is not read; paste the text into the account's signature"),
        signatureLabel = S("Signature"),
        quotePrefixLabel = stringResource(SettingsTreeGenerated.resource("pinerc_quote_prefix")),
        leadInLabel = S("Reply lead-in"),
        sigDashesLabel = S("Signature dashes"),
        sigBottomLabel = S("Signature at bottom"),
        dsnLabel = S("Delivery status notifications", name = "pinerc_dsn"),
        composerHeadersLabel = S("Composer headers"),
        viewerHeadersLabel = S("Viewer headers"),
        rolesLabel = S("Sending rule from role"),
        roleSkipped = S("Role %1\$s not imported (unsupported pattern)"),
        roleNoConditions = S("Role %1\$s not imported: it has no conditions, so it would apply to every message"),
    )
    val openPinerc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val outcome = readPinercStream(contextState.value.contentResolver, uri)) {
            is PinercRead.Ok -> {
                importError = null
                importGeneration += 1
                val generation = importGeneration
                importPreview = null
                val text = outcome.text
                editor.ui.launch {
                    val current = settingsState.value
                    val built = pinercPreview(text, current, pinercPhrases)
                    val names = ArrayList<String>(3)
                    if (built.next.sentMailbox.isNotEmpty() && built.next.sentMailbox != current.sentMailbox) {
                        names.add(built.next.sentMailbox)
                    }
                    if (built.next.postponedMailbox.isNotEmpty() &&
                        built.next.postponedMailbox != current.postponedMailbox
                    ) {
                        names.add(built.next.postponedMailbox)
                    }
                    val had = current.addressBooks.map { it.mailbox }.filter { it.isNotEmpty() }.toSet()
                    for (book in built.next.addressBooks) {
                        if (book.mailbox.isNotEmpty() && book.mailbox !in had) names.add(book.mailbox)
                    }
                    val id = editor.bound.chosenAccountId()
                    val shown = if (id == null) {
                        withoutCheckedFavorites(
                            withoutCheckedMailboxes(built, current, pinercPhrases),
                            current,
                            pinercPhrases,
                        )
                    } else {
                        val session = mailSession(id)
                        try {
                            val checked = if (names.isEmpty()) {
                                built
                            } else {
                                val missing = linkedSetOf<String>()
                                for (name in names) {
                                    if (!pinercMailboxListed(session, name)) missing.add(name)
                                }
                                withoutMissingMailboxes(built, current, missing, pinercPhrases)
                            }
                            val fresh = newLeafMailboxes(checked.next.favorites, current.favorites)
                            if (fresh.isEmpty()) {
                                checked
                            } else {
                                try {
                                    val delimiter = favoriteDelimiter(session.namespaces())
                                    val missingFavorites = linkedSetOf<String>()
                                    for (name in fresh) {
                                        if (!pinercMailboxListed(session, name)) missingFavorites.add(name)
                                    }
                                    withFavoriteDelimiter(
                                        withoutMissingFavorites(checked, current, missingFavorites, pinercPhrases),
                                        current,
                                        delimiter,
                                    )
                                } catch (_: MailFailure) {
                                    withoutCheckedFavorites(checked, current, pinercPhrases)
                                }
                            }
                        } catch (_: MailFailure) {
                            withoutCheckedFavorites(
                                withoutCheckedMailboxes(built, current, pinercPhrases),
                                current,
                                pinercPhrases,
                            )
                        }
                    }
                    if (importGeneration == generation) importPreview = shown
                }
            }
            PinercRead.TooLarge -> {
                importPreview = null
                importError = org.dlang.liveimap.i18n.literal.S("That file is too large.")
            }
            PinercRead.Bad -> {
                importPreview = null
                importError = org.dlang.liveimap.i18n.literal.S("Could not read that file.")
            }
        }
    }
    @Composable
    fun PinercButtons() {

            TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
                Text(S("Import from .pinerc…"))
            }
            TextButton(onClick = {
                sharePinerc(contextState.value, encodePinerc(editor.settings))
            }) {
                Text(S("Export .pinerc…"))
            }
    }
    if (sections == null) {
        PinercButtons()
    } else {
        SettingsNode(
            title = S("Import"),
            depth = depth,
            summary = S("Import from .pinerc…"),
            expanded = "import" in sections.open,
            onToggle = { sections.toggle("import") },
        ) {            PinercButtons()
        }
    }
    val error = importError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            text = { Text(S(error)) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(S("Close")) }
            },
        )
    }
    val preview = importPreview
    if (preview != null) {
        var turnOnAutoExpunge by remember(importGeneration) { mutableStateOf(false) }
        val applied = pinercApplied(preview, turnOnAutoExpunge)
        Dialog(
            onDismissRequest = { importPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ObscuredTouchFilter()
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.displayCutout),
                        )
                        .padding(16.dp),
                ) {
                    Text(S("Import from .pinerc"), style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(S("Will change"))
                        preview.rows.forEach { line -> Text(line) }
                        if (preview.offerAutoExpunge) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { turnOnAutoExpunge = !turnOnAutoExpunge },
                            ) {
                                Checkbox(
                                    checked = turnOnAutoExpunge,
                                    onCheckedChange = { turnOnAutoExpunge = it },
                                )
                                Text(S("Also turn on Auto-expunge?"))
                            }
                        }
                        Text(S("Not applied"))
                        preview.skipped.forEach { line -> Text(line) }
                        Text(S("Ignored"))
                        if (preview.omittedCount != 0) {
                            Text(
                                P(preview.omittedCount,
                                    preview.omittedCount, one = "%1\$d other line was left out", other = "%1\$d other lines were left out"),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importPreview = null }) { Text(S("Cancel", name = "unsent_cancel")) }
                        TextButton(
                            onClick = {
                                editor.persist(applied)
                                importPreview = null
                            },
                            enabled = applied != editor.settings,
                        ) { Text(S("Apply")) }
                    }
                }
            }
        }
    }
}
