package org.dlang.liveimap.settings.editors

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
import androidx.compose.ui.res.pluralStringResource
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
import com.davidlang.liveimap.R
import org.dlang.liveimap.engine.ClientCertStore
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
import org.dlang.liveimap.ui.paneControlsEnabled
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
    val noSieveHost = stringResource(R.string.settings_sieve_no_host)
    TextButton(
        onClick = {
            if (drafts.checkingSieve) return@TextButton
            val sieveStore = editor.bound
            val sieveAccount = editor.editingId
            val (host, port) = sieveEndpoint(editor.settings)
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
            val cacheDir = LocalContext.current.applicationContext.cacheDir
            drafts.checkingSieve = true
            drafts.sieveReport = ""
            editor.ui.launch {
                try {
                    drafts.sieveReport = sieveCheckText(
                        withContext(editor.settings.tlsPolicy()) {
                            greetSieve(
                                host,
                                port,
                                sieveTlsMode,
                                pin,
                                ::confirmMailCertificate,
                                org.dlang.liveimap.engine.TrafficLog.writer(cacheDir, sieveAccount),
                                editor.settings.sieveConnectSeconds,
                                editor.settings.sieveReadSeconds,
                            ) { fingerprint ->
                                persistSievePin(sieveStore, sieveAccount, fingerprint)
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
        Text(stringResource(R.string.settings_sieve_check))
    }
    if (drafts.sieveReport.isNotEmpty()) Text(drafts.sieveReport)
}
@Composable
internal fun ServerEditor(node: TreeNode.Group, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val sections = walk.sections
    val settings = editor.settings
    val chosenAccount = editor.accounts.firstOrNull { it.editing }?.id
    val drafts = remember(editor.editingId, chosenAccount) { ServerDrafts() }
    walk.drafts = drafts
    val server = drafts.serverDraft ?: settings
    DisposableEffect(drafts.hasDraft) {
        UnsavedEdits.pending = drafts.hasDraft
        UnsavedEdits.ask = { go -> drafts.leaveAfter = go; drafts.discardAsk = true }
        onDispose { UnsavedEdits.pending = false; UnsavedEdits.ask = null }
    }
    LaunchedEffect(drafts.hasDraft, drafts.serverReview == null, drafts.serverChecking) {
        if (!drafts.hasDraft && drafts.serverReview == null && !drafts.serverChecking) {
            drafts.leaveAfter?.let { drafts.leaveAfter = null; it() }
        }
    }
    BackHandler(enabled = drafts.hasDraft) { drafts.discardAsk = true }
    drafts.serverReview?.let { review ->
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(stringResource(R.string.settings_save_server_title)) },
            text = {
                Text(
                    (listOfNotNull(review.tlsWarning?.let { stringResource(it) }) +
                        (if (review.unreachable) listOf(stringResource(R.string.settings_check_unreachable)) else emptyList()) +
                        review.lines).joinToString("\n"),
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    drafts.serverReview = null
                    drafts.serverDraft?.let { editor.persist(drafts.savedServer(editor, it)) }
                    drafts.commitPasswords(editor)
                    drafts.serverDraft = null
                }) { Text(stringResource(R.string.settings_check_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { drafts.serverReview = null; drafts.leaveAfter = null }) { Text(stringResource(R.string.settings_keep_editing)) }
            },
        )
    }
    if (drafts.discardAsk) {
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            confirmButton = {
                androidx.compose.material3.Button(onClick = { drafts.discardAsk = false; drafts.saveServer(editor) }) {
                    Text(stringResource(R.string.settings_save))
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { drafts.discardAsk = false; drafts.leaveAfter = null }) { Text(stringResource(R.string.settings_keep_editing)) }
                    TextButton(onClick = { drafts.discardAsk = false; drafts.discard() }) {
                        Text(stringResource(R.string.settings_discard))
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
            onAdd = { editor.addAccount() },
        )
        for (child in node.children) {
            renderSettingsNode(child, depth, walk)
        }
        PinercEditor(editor, sections, depth)
        // David, 2026-10-12: Save and Test account side by side; Test account logs in (IMAP or JMAP, and SMTP).
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = { drafts.saveServer(editor) },
                enabled = drafts.hasDraft && !drafts.serverChecking,
                modifier = Modifier.testTag("account-save"),
            ) { Text(stringResource(R.string.settings_save)) }
            TextButton(
                onClick = {
                    if (!drafts.probing) {
                        val candidate = drafts.savedServer(editor, server)
                        drafts.probing = true
                        drafts.serverReport = emptyList()
                        editor.ui.launch {
                            val id = editor.bound.chosenAccountId()
                            try {
                                drafts.serverReport = if (id == null) emptyList() else testAccountLogins(candidate, testAccountProbes(id, candidate, drafts.passwordDraft ?: editor.password, drafts.smtpPasswordDraft ?: editor.smtpPassword))
                            } finally {
                                drafts.probing = false
                            }
                        }
                    }
                },
                enabled = !drafts.probing,
                modifier = Modifier.testTag("account-test"),
            ) {
                Text(stringResource(if (drafts.probing) R.string.settings_testing else R.string.settings_test_account))
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
    var importError by remember { mutableStateOf<Int?>(null) }
    val settingsState = editor.settingsState
    val contextState = rememberUpdatedState(LocalContext.current)
    val pinercPhrases = PinercPhrases(
        tls = stringResource(R.string.pinerc_tls),
        tlsMode = stringResource(R.string.settings_tls),
        tlsNone = stringResource(R.string.settings_tls_none),
        tlsStart = stringResource(R.string.settings_tls_starttls),
        tlsImplicit = stringResource(R.string.settings_tls_implicit),
        smtpUsername = stringResource(R.string.settings_smtp_username),
        local = stringResource(R.string.pinerc_local),
        history = stringResource(R.string.pinerc_history),
        sort = stringResource(R.string.pinerc_sort),
        rule = stringResource(R.string.pinerc_rule),
        expunge = stringResource(R.string.pinerc_expunge),
        passwords = stringResource(R.string.pinerc_passwords),
        folders = stringResource(R.string.pinerc_folders),
        signature = stringResource(R.string.pinerc_signature),
        perFolder = stringResource(R.string.pinerc_per_folder),
        inboxDefault = stringResource(R.string.pinerc_inbox_default),
        change = stringResource(R.string.pinerc_change),
        imapHost = stringResource(R.string.settings_imap_host),
        imapPort = stringResource(R.string.settings_imap_port),
        smtpHost = stringResource(R.string.settings_smtp_host),
        smtpPort = stringResource(R.string.settings_smtp_port),
        username = stringResource(R.string.settings_username),
        displayName = stringResource(R.string.settings_display_name),
        altAddresses = stringResource(R.string.settings_alt_addresses),
        email = stringResource(R.string.settings_email),
        sentMailbox = stringResource(R.string.settings_sent_mailbox),
        postponedMailbox = stringResource(R.string.settings_postponed_mailbox),
        addressBookMailbox = stringResource(R.string.settings_address_book_mailbox),
        historyLabel = stringResource(R.string.settings_history_label),
        extraAddressBooks = stringResource(R.string.settings_extra_books),
        globalAddressBooks = stringResource(R.string.settings_global_books),
        defaultView = stringResource(R.string.settings_default_view),
        newest = stringResource(R.string.reader_newest),
        askExpunge = stringResource(R.string.settings_ask_expunge),
        inboxOpens = stringResource(R.string.settings_inbox_opens),
        otherHost = stringResource(R.string.pinerc_other_host),
        inboxBraces = stringResource(R.string.pinerc_inbox_braces),
        missingFolder = stringResource(R.string.pinerc_folder_missing),
        folderCheck = stringResource(R.string.pinerc_folder_check),
        favorite = stringResource(R.string.pinerc_favorite),
        signatureFile = stringResource(R.string.pinerc_signature_file),
        signatureLabel = stringResource(R.string.pinerc_signature_label),
        quotePrefixLabel = stringResource(R.string.pinerc_quote_prefix),
        leadInLabel = stringResource(R.string.pinerc_lead_in),
        sigDashesLabel = stringResource(R.string.pinerc_sig_dashes),
        sigBottomLabel = stringResource(R.string.pinerc_sig_bottom),
        dsnLabel = stringResource(R.string.pinerc_dsn),
        composerHeadersLabel = stringResource(R.string.pinerc_composer_headers),
        viewerHeadersLabel = stringResource(R.string.pinerc_viewer_headers),
        rolesLabel = stringResource(R.string.pinerc_role_rule),
        roleSkipped = stringResource(R.string.pinerc_role_skipped),
        roleNoConditions = stringResource(R.string.pinerc_role_no_conditions),
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
                    if (built.next.addressBookMailbox.isNotEmpty() &&
                        built.next.addressBookMailbox != current.addressBookMailbox
                    ) {
                        names.add(built.next.addressBookMailbox)
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
                importError = R.string.settings_file_large
            }
            PinercRead.Bad -> {
                importPreview = null
                importError = R.string.settings_file_unreadable
            }
        }
    }
    @Composable
    fun PinercButtons() {

            TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
                Text(stringResource(R.string.settings_import_pinerc))
            }
            TextButton(onClick = {
                sharePinerc(contextState.value, encodePinerc(editor.settings))
            }) {
                Text(stringResource(R.string.settings_export_pinerc))
            }
    }
    if (sections == null) {
        PinercButtons()
    } else {
        SettingsNode(
            title = stringResource(R.string.settings_import),
            depth = depth,
            summary = stringResource(R.string.settings_import_pinerc),
            expanded = "import" in sections.open,
            onToggle = { sections.toggle("import") },
        ) {            PinercButtons()
        }
    }
    val error = importError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            text = { Text(stringResource(error)) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(R.string.compose_close)) }
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
                    Text(stringResource(R.string.settings_import_title), style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.settings_will_change))
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
                                Text(stringResource(R.string.settings_import_auto_expunge))
                            }
                        }
                        Text(stringResource(R.string.settings_not_applied))
                        preview.skipped.forEach { line -> Text(line) }
                        Text(stringResource(R.string.settings_ignored))
                        if (preview.omittedCount != 0) {
                            Text(
                                pluralStringResource(
                                    R.plurals.settings_omitted,
                                    preview.omittedCount,
                                    preview.omittedCount,
                                ),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importPreview = null }) { Text(stringResource(R.string.unsent_cancel)) }
                        TextButton(
                            onClick = {
                                editor.persist(applied)
                                importPreview = null
                            },
                            enabled = applied != editor.settings,
                        ) { Text(stringResource(R.string.settings_apply)) }
                    }
                }
            }
        }
    }
}
