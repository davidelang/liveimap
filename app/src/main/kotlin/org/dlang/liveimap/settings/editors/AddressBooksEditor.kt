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
import org.dlang.liveimap.ui.contacts.AndroidContacts
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

private fun moveBook(books: List<AddressBookEntry>, index: Int, delta: Int): List<AddressBookEntry> {
    val target = index + delta
    if (index !in books.indices || target !in books.indices) return books
    val next = books.toMutableList()
    val moved = next.removeAt(index)
    next.add(target, moved)
    return next
}

@Composable
internal fun ManageAddresses(
    editor: SettingsEditor,
    onEditBook: () -> Unit,
    onCopyContacts: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var granted by remember { mutableStateOf(hasReadContacts(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var sets by remember { mutableStateOf<List<AndroidContactSet>>(emptyList()) }
    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
        if (ok) {
            editor.ui.launch {
                val loaded = try {
                    androidContactSetsOnce(appContext.contentResolver, appContext.packageManager)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (loaded is AndroidContacts.Ready) sets = loaded.value
            }
        }
    }
    LaunchedEffect(granted) {
        if (!granted) {
            sets = emptyList()
            return@LaunchedEffect
        }
        val loaded = try {
            androidContactSetsOnce(appContext.contentResolver, appContext.packageManager)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (loaded is AndroidContacts.Ready) sets = loaded.value
    }
    val books = editor.settings.addressBooks
    Text(S("Address book", name = "settings_address_book"))
    books.forEachIndexed { index, book ->
        val label = if (book.kind == "android") {
            book.accountName.ifEmpty { S("Add Android contacts") }
        } else {
            book.mailbox.ifEmpty { S("Add Pine book") }
        }
        Text(label)
        Row {
            TextButton(onClick = {
                editor.persist(editor.settings.copy(addressBooks = moveBook(editor.settings.addressBooks, index, -1)))
            }) { Text(S("Move up")) }
            TextButton(onClick = {
                editor.persist(editor.settings.copy(addressBooks = moveBook(editor.settings.addressBooks, index, 1)))
            }) { Text(S("Move down")) }
            TextButton(onClick = {
                val next = editor.settings.addressBooks.filterIndexed { i, _ -> i != index }
                editor.persist(editor.settings.copy(addressBooks = next))
            }) { Text(S("Remove")) }
            if (book.kind == "pine") {
                TextButton(onClick = {
                    val next = editor.settings.addressBooks.mapIndexed { i, item ->
                        if (i != index) item else item.copy(readOnly = !item.readOnly, saveTarget = if (!item.readOnly) false else item.saveTarget)
                    }
                    editor.persist(editor.settings.copy(addressBooks = next))
                }) { Text(S("Read only")) }
            }
            if (book.isWritableBook()) {
                TextButton(onClick = {
                    editor.persist(editor.settings.copy(addressBooks = markAddressSaveTarget(editor.settings.addressBooks, index)))
                }) { Text(S("Save target")) }
            }
        }
    }
    var mailbox by remember { mutableStateOf("") }
    LineField(S("Add Pine book"), mailbox, ready = editor.ready) { text ->
        mailbox = ""
        val name = text.trim()
        if (name.isEmpty()) return@LineField
        editor.persist(editor.settings.copy(addressBooks = editor.settings.addressBooks + AddressBookEntry("pine", mailbox = name)))
    }
    if (!granted) {
        TextButton(onClick = { requestContacts.launch(android.Manifest.permission.READ_CONTACTS) }) {
            Text(S("Add Android contacts"))
        }
        if (denied) Text(S("Contacts permission was denied."))
    } else {
        for (set in sets) {
            val id = androidAccountOf(set.id)
            TextButton(onClick = {
                if (id == null) return@TextButton
                val (type, name) = id
                val entry = AddressBookEntry(kind = "android", accountType = type, accountName = name)
                if (editor.settings.addressBooks.any { it.kind == "android" && it.accountType == type && it.accountName == name }) return@TextButton
                editor.persist(editor.settings.copy(addressBooks = editor.settings.addressBooks + entry))
            }) { Text(set.label) }
        }
    }
    OpenRow(S("Edit address book"), onEditBook)
    OpenRow(S("Copy contacts"), onCopyContacts)
}
/** Extra or global Alpine address books: one row per book with Remove, and Add (mailbox picker). */
@Composable
internal fun BookList(label: String, books: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Text(label)
    for (book in books) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(book, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(book) }) { Text(S("Remove")) }
        }
    }
    TextButton(onClick = onAdd) { Text(S("Add address book…")) }
}
