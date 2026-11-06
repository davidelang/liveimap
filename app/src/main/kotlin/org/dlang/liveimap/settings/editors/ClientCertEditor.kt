package org.dlang.liveimap.settings.editors
import org.dlang.liveimap.i18n.S

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

/** Advanced: import a PKCS#12 client certificate (.p12 + password) for IMAP and SMTP, or remove it. */
@Composable
internal fun ClientCertField(editor: SettingsEditor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending = uri
        password = ""
        message = null
    }
    val alias = editor.settings.clientCertAlias
    Text(S("Client certificate (IMAP and SMTP; SASL EXTERNAL when the server offers it)"))
    if (alias.isBlank()) {
        Text(S("None"))
    } else {
        Text(S("Android certificate: %1\$s", alias))
        TextButton(onClick = {
            val failed = runCatching { org.dlang.liveimap.engine.KeyChainCerts.removeImported(alias) }
            if (failed.isFailure) {
                message = failed.exceptionOrNull()?.message ?: failed.exceptionOrNull().toString()
            } else {
                editor.persist(editor.settings.copy(clientCertAlias = ""))
            }
        }) {
            Text(S("Remove certificate"))
        }
    }
    TextButton(onClick = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) {
        Text(S("Import .p12…"))
    }
    TextButton(onClick = {
        var c = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val activity = c as? android.app.Activity ?: return@TextButton
        android.security.KeyChain.choosePrivateKeyAlias(
            activity,
            { chosen ->
                if (chosen != null) {
                    scope.launch {
                        val oldAlias = editor.settings.clientCertAlias
                        editor.persist(editor.settings.copy(clientCertAlias = chosen))
                        if (oldAlias.isNotBlank() && oldAlias != chosen && oldAlias.startsWith(org.dlang.liveimap.engine.KeyChainCerts.LAB_PREFIX)) {
                            val failed = runCatching { org.dlang.liveimap.engine.KeyChainCerts.removeImported(oldAlias) }
                            if (failed.isFailure) {
                                org.dlang.liveimap.accounts.writeOrphanSweep(context.applicationContext)
                                message = failed.exceptionOrNull()?.message ?: failed.exceptionOrNull().toString()
                            }
                        }
                    }
                }
            },
            arrayOf("RSA", "EC"),
            null,
            editor.settings.imapHost.ifBlank { null },
            editor.settings.imapPort,
            alias.ifBlank { null },
        )
    }) { Text(S("Choose Android certificate…")) }
    val uri = pending
    if (uri != null) {
        var revealed by remember(uri) { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(S("Certificate password")) },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                PasswordVisibilityIcon(revealed = revealed, onToggle = { revealed = !revealed })
            },
        )
        TextButton(onClick = {
            val typed = password
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        org.dlang.liveimap.engine.KeyChainCerts.check(bytes, typed)
                        org.dlang.liveimap.engine.KeyChainCerts.importP12(java.util.UUID.randomUUID().toString(), bytes, typed)
                    }
                }
                result.onSuccess { newAlias ->
                    val oldAlias = editor.settings.clientCertAlias
                    editor.persist(editor.settings.copy(clientCertAlias = newAlias))
                    if (oldAlias.isNotBlank() && oldAlias != newAlias) {
                        val failed = withContext(Dispatchers.IO) {
                            runCatching { org.dlang.liveimap.engine.KeyChainCerts.removeImported(oldAlias) }
                        }
                        if (failed.isFailure) {
                            org.dlang.liveimap.accounts.writeOrphanSweep(context.applicationContext)
                            message = failed.exceptionOrNull()?.message ?: failed.exceptionOrNull().toString()
                        }
                    }
                    pending = null
                    password = ""
                    if (message == null) message = null
                }.onFailure { message = it.message ?: it.toString() }
            }
        }) { Text(S("Use this certificate")) }
    }
    message?.let { Text(it) }
}
