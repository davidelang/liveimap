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


/** The account's ordered sending rules and presets. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SendingRulesRows(editor: SettingsEditor) {
    val settings = editor.settings
    val rules = org.dlang.liveimap.ui.compose.decodeRules(settings.sendingRules)
    fun save(list: List<org.dlang.liveimap.ui.compose.SendingRule>) =
        editor.persist(editor.settings.copy(sendingRules = org.dlang.liveimap.ui.compose.encodeRules(list)))
    LaunchedEffect(rules.map { it.id }) {
        if (rules.any { it.id.isBlank() }) {
            save(org.dlang.liveimap.ui.compose.assignSendingIds(rules))
        }
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var accounts by remember { mutableStateOf<List<AccountChoice>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accounts = DataStoreSettingsStore(context.applicationContext).listAccounts()
    }

    Text(stringResource(R.string.settings_rules_list))
    rules.forEachIndexed { index, rule ->
        ListItem(
            headlineContent = { Text(stringResource(R.string.sending_rule_numbered, index + 1, rule.name)) },
            supportingContent = { Text(ruleSummary(rule)) },
            modifier = Modifier.clickable { editing = if (editing == index) null else index },
        )
        Row {
            TextButton(enabled = index > 0, onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                editing = null
            }) { Text(stringResource(R.string.settings_rule_up)) }
            TextButton(enabled = index < rules.size - 1, onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
                editing = null
            }) { Text(stringResource(R.string.settings_rule_down)) }
            TextButton(onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.filterIndexed { i, _ -> i != index })
                editing = null
            }) {
                Text(stringResource(R.string.settings_rule_delete))
            }
        }
        if (editing == index) RuleEditor(rule, editor.ready, accounts) { edit ->
            val current = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
            save(org.dlang.liveimap.ui.compose.patchSendingRule(current, rule.id, edit))
        }
    }
    Text(stringResource(R.string.settings_rules_presets))
    val me = settings.email
    FlowRow {
        fun add(new: List<org.dlang.liveimap.ui.compose.SendingRule>) {
            if (new.isEmpty()) return
            val current = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
            save(org.dlang.liveimap.ui.compose.assignSendingIds(current + new))
            editing = current.size
        }
        val p = org.dlang.liveimap.ui.compose.RulePresets
        AssistChip(onClick = { add(p.replyFromSentTo(settings.altAddresses.ifEmpty { listOf(me) })) },
            label = { Text(stringResource(R.string.settings_preset_sent_to)) })
        AssistChip(onClick = { add(listOf(p.perRecipient("@example.com", me))) },
            label = { Text(stringResource(R.string.settings_preset_recipient)) })
        AssistChip(onClick = { add(listOf(p.perFolder("Work*", me, null, null))) },
            label = { Text(stringResource(R.string.settings_preset_folder)) })
        AssistChip(onClick = { add(listOf(p.mailingList("list.example.org", me))) },
            label = { Text(stringResource(R.string.settings_preset_list)) })
        AssistChip(onClick = { add(listOf(p.plusFolder(me, "tag"))) },
            label = { Text(stringResource(R.string.settings_preset_plus)) })
        AssistChip(onClick = { add(listOf(org.dlang.liveimap.ui.compose.SendingRule(
            "Rule ${rules.size + 1}")) ) },
            label = { Text(stringResource(R.string.settings_preset_blank)) })
    }
}
@Composable
internal fun dsnLabel(mode: org.dlang.liveimap.ui.compose.DsnMode): String = stringResource(
    when (mode) {
        org.dlang.liveimap.ui.compose.DsnMode.Off -> R.string.settings_dsn_off
        org.dlang.liveimap.ui.compose.DsnMode.Failure -> R.string.settings_dsn_failure
        org.dlang.liveimap.ui.compose.DsnMode.FailureDelay -> R.string.settings_dsn_delay
        org.dlang.liveimap.ui.compose.DsnMode.All -> R.string.settings_dsn_all
    },
)

internal fun ruleSummary(rule: org.dlang.liveimap.ui.compose.SendingRule): String {
    val c = rule.conditions
    val a = rule.actions
    val when_ = listOfNotNull(
        c.kinds.takeIf { it.isNotEmpty() }?.joinToString("/") { it.code },
        c.recipient.takeIf { it.isNotEmpty() }?.let { "to " + it.joinToString(",") },
        c.sentTo.takeIf { it.isNotEmpty() }?.let { "sent to " + it.joinToString(",") },
        c.listId.takeIf { it.isNotEmpty() }?.let { "list $it" },
        c.folder.takeIf { it.isNotEmpty() }?.let { "folder $it" },
        c.originalFrom.takeIf { it.isNotEmpty() }?.let { "from " + it.joinToString(",") },
    ).ifEmpty { listOf("always") }.joinToString(", ")
    val then = listOfNotNull(
        a.fromAddress?.let { "From $it" }, a.replyTo?.let { "Reply-To $it" },
        a.signature?.let { "signature" }, a.fcc?.let { "Fcc $it" }, a.dsn?.let { "DSN ${it.code}" },
        a.sendAccount?.let { "send via another account" }, a.replyToList?.takeIf { it }?.let { "reply to list" },
    ).joinToString(", ")
    return "$when_ → $then"
}
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
/** Field-by-field editor for one rule; list fields are comma separated, empty means "not set". */
@Composable
internal fun RuleEditor(
    rule: org.dlang.liveimap.ui.compose.SendingRule,
    ready: Boolean,
    accounts: List<AccountChoice>,
    onPatch: ((org.dlang.liveimap.ui.compose.SendingRule) -> org.dlang.liveimap.ui.compose.SendingRule) -> Unit,
) {
    fun list(t: String) = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun opt(t: String) = t.ifBlank { null }
    val c = rule.conditions
    val a = rule.actions
    LineField(stringResource(R.string.settings_rule_name), rule.name, ready = ready) { text ->
        onPatch { latest -> latest.copy(name = text.ifBlank { latest.name }) }
    }
    LineField(stringResource(R.string.settings_rule_recipient), c.recipient.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(recipient = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_sent_to), c.sentTo.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(sentTo = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_list_id), c.listId, ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(listId = text.trim())) }
    }
    LineField(stringResource(R.string.settings_rule_folder), c.folder, ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(folder = text.trim())) }
    }
    LineField(stringResource(R.string.settings_rule_orig_from), c.originalFrom.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(originalFrom = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_from_name), a.fromName.orEmpty(), ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fromName = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_from), a.fromAddress.orEmpty(), KeyboardType.Email, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fromAddress = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_reply_to), a.replyTo.orEmpty(), KeyboardType.Email, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(replyTo = opt(text))) }
    }
    LineField(stringResource(R.string.settings_signature), a.signature.orEmpty(), singleLine = false, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(signature = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_fcc), a.fcc.orEmpty(), ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fcc = opt(text))) }
    }
    BoolField(stringResource(R.string.settings_rule_reply_list), a.replyToList == true) { enabled ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(replyToList = if (enabled) true else null)) }
    }
    Text(stringResource(R.string.settings_rule_send_account))
    FlowRow {
        FilterChip(
            selected = a.sendAccount == null,
            onClick = { onPatch { latest -> latest.copy(actions = latest.actions.copy(sendAccount = null)) } },
            label = { Text(stringResource(R.string.settings_rule_send_this)) },
        )
        for (choice in accounts) {
            FilterChip(
                selected = a.sendAccount == choice.id,
                onClick = { onPatch { latest -> latest.copy(actions = latest.actions.copy(sendAccount = choice.id)) } },
                label = { Text(choice.name) },
            )
        }
    }
}
