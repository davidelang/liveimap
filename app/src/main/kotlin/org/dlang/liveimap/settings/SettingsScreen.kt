package org.dlang.liveimap.settings
import org.dlang.liveimap.i18n.S

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.dlang.liveimap.accounts.NoAccountSelected
import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.settings.editors.AccountListEditor
import org.dlang.liveimap.settings.editors.*
import org.dlang.liveimap.settings.editors.openingAccount
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.davidlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.ServerCheck
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.trafficText
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
import org.dlang.liveimap.ui.paneControlsEnabled
import org.dlang.liveimap.ui.toolbar.ToolbarEditorScreen
import org.dlang.liveimap.ui.toolbar.ToolbarScreen
import org.dlang.liveimap.ui.toolbar.notePaneScroll

/**
 * F96: a failed save (e.g. Keystore unavailable, so a password is refused) is shown, never an uncaught
 * exception that ends the process.
 */
internal val settingsSaveFailure = androidx.compose.runtime.mutableStateOf<String?>(null)

internal fun logSettingsSave(control: String, accountId: String?, outcome: String) {
    android.util.Log.w("liveimap", settingsSaveReport(control, accountId, outcome))
}
internal val settingsIo = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            android.util.Log.w("liveimap", "settings save failed", error)
            settingsSaveFailure.value = error.message ?: error.toString()
        },
)
internal val openSettingsEditors = AtomicReference<List<SettingsEditor>>(emptyList())

internal fun bindSettingsEditor(editor: SettingsEditor) {
    openSettingsEditors.updateAndGet { it + editor }
}

internal fun unbindSettingsEditor(editor: SettingsEditor) {
    openSettingsEditors.updateAndGet { it - editor }
}

internal fun sameSieveAccount(openId: String?, capturedId: String?): Boolean =
    openId.orEmpty() == capturedId.orEmpty()

internal class EditorEpoch(val settings: Int, val password: Int, val smtp: Int)

internal class EditorLoad(
    val editingId: String?,
    val settings: Settings,
    val password: String,
    val smtpPassword: String,
    val accounts: List<AccountChoice>,
    val passwordMissing: Boolean = false,
    val smtpMissing: Boolean = false,
)

internal class SaveCapture(
    val accountId: String?,
    val before: Settings,
    val screen: Settings,
    val password: String?,
    val smtp: String?,
    val sieve: String?,
    val creating: Boolean,
)

internal class SettingsEditor(
    val store: DataStoreSettingsStore,
    val appContext: Context,
    val ui: CoroutineScope,
) {
    val settingsState = mutableStateOf(Settings())
    private val settingsMirror = AtomicReference(Settings())
    private val settingsEpoch = AtomicInteger(0)
    private val passwordEpoch = AtomicInteger(0)
    private val smtpEpoch = AtomicInteger(0)
    private val editingMirror = AtomicReference<String?>(null)
    private val editingIdState = mutableStateOf<String?>(null)
    private val dirtyKeys = AtomicReference<Set<String>>(emptySet())
    private val baselineMirror = AtomicReference(Settings())
    private val saveGate = kotlinx.coroutines.sync.Mutex()
    var pageDirty by mutableStateOf(false)
    private var passwordDirty = false
    private var smtpDirty = false
    private var sieveDirty = false
    private var baselinePassword = ""
    private var baselineSmtp = ""
    private var baselineSieve = ""
    val passwordState = mutableStateOf("")
    val smtpPasswordState = mutableStateOf("")
    val accountsState = mutableStateOf<List<AccountChoice>>(emptyList())
    var ready by mutableStateOf(false)
    var creating by mutableStateOf(false)
    var passwordMissing by mutableStateOf(false)
    var smtpMissing by mutableStateOf(false)
    var sievePassword by mutableStateOf("")
    var policy by mutableStateOf<List<PolicyLine>>(emptyList())

    fun noteSievePassword(value: String) {
        sievePassword = value
        sieveDirty = true
        pageDirty = true
        if (creating) org.dlang.liveimap.settings.editors.PendingNewAccount.sievePassword = value
    }
    var deleteName by mutableStateOf<String?>(null)
    var deleteStep by mutableStateOf<Int?>(null)
    var deleteFailedName by mutableStateOf<String?>(null)
    private var retryDeleteId: String? = null
    var settings: Settings
        get() = settingsState.value
        set(value) {
            settingsMirror.set(value)
            settingsState.value = value
        }
    var password by passwordState
    var smtpPassword by smtpPasswordState
    var accounts by accountsState

    /** F121: the account Settings is editing. Settings never changes the drawer's active account. */
    var editingId: String?
        get() = editingIdState.value
        set(value) {
            editingMirror.set(value)
            editingIdState.value = value
        }
    val bound: DataStoreSettingsStore
        get() = editingId?.let { store.forAccount(it) } ?: store

    fun persist(next: Settings): Boolean {
        if (!ready) return false
        // F32: the store drops passwords/pins when a server moves; show that here instead of a stale password
        val retarget = org.dlang.liveimap.accounts.retargetCredentials(settings, next)
        if (retarget.dropImapPassword) {
            passwordEpoch.incrementAndGet()
            password = ""
            passwordMissing = false
        }
        if (retarget.dropSmtpPassword) {
            smtpEpoch.incrementAndGet()
            smtpPassword = ""
            smtpMissing = false
        }
        if (retarget.dropSievePassword) sievePassword = ""
        val previous = settings
        settingsEpoch.incrementAndGet()
        settings = retarget.settings
        if (retarget.dropSievePassword) sieveDirty = true
        if (creating) {
            org.dlang.liveimap.settings.editors.PendingNewAccount.active = true
            org.dlang.liveimap.settings.editors.PendingNewAccount.settings = retarget.settings
            org.dlang.liveimap.settings.editors.PendingNewAccount.password = password
            org.dlang.liveimap.settings.editors.PendingNewAccount.smtpPassword = smtpPassword
            org.dlang.liveimap.settings.editors.PendingNewAccount.sievePassword = sievePassword
            pageDirty = true
            return true
        }
        val changed = retarget.settings.changedKeysAgainst(previous)
        val immediate = changed.filter { it in immediateSettingKeys }.toSet()
        val page = changed.filter { it !in immediateSettingKeys }.toSet()
        val structural = retarget.settings.unknownSettings != previous.unknownSettings ||
            ScopePicks.chosen.isNotEmpty()
        if (page.isNotEmpty()) dirtyKeys.updateAndGet { it + page }
        if (page.isNotEmpty() || structural) {
            pageDirty = dirtyKeys.get().isNotEmpty() || structural
        }
        if (immediate.isEmpty()) return true
        val saving = retarget.settings
        val target = bound
        settingsIo.launch {
            try {
                target.commitKeys(saving, immediate)
                var base = baselineMirror.get()
                for (key in immediate) base = base.withSetting(key, SettingValue(saving.getSetting(key)))
                baselineMirror.set(base)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                settingsSaveFailure.value = error.message ?: error.toString()
            }
        }
        return true
    }

    fun persistPassword(value: String) {
        if (!ready) return
        passwordEpoch.incrementAndGet()
        password = value
        passwordMissing = false
        passwordDirty = true
        if (creating) {
            org.dlang.liveimap.settings.editors.PendingNewAccount.password = value
            pageDirty = true
            return
        }
        pageDirty = true
    }

    fun persistSmtpPassword(value: String) {
        if (!ready) return
        smtpEpoch.incrementAndGet()
        smtpPassword = value
        smtpMissing = false
        smtpDirty = true
        if (creating) {
            org.dlang.liveimap.settings.editors.PendingNewAccount.smtpPassword = value
            pageDirty = true
            return
        }
        pageDirty = true
    }

    /** Pinch writes appTextZoom now and does not leave that key dirty. */
    fun applySettingsPinch(next: Int): Boolean {
        if (!ready) return false
        val clamped = clampTextZoom(next)
        val wasDirty = "appTextZoom" in dirtyKeys.get()
        if (!wasDirty && settings.appTextZoom == clamped) return false
        settingsEpoch.incrementAndGet()
        settings = settings.copy(appTextZoom = clamped)
        dirtyKeys.updateAndGet { it - "appTextZoom" }
        baselineMirror.set(baselineMirror.get().copy(appTextZoom = clamped))
        pageDirty = dirtyKeys.get().isNotEmpty() || passwordDirty || smtpDirty || sieveDirty
        if (creating) {
            org.dlang.liveimap.settings.editors.PendingNewAccount.settings = settings
        }
        return true
    }

    fun beginNewAccount() {
        val pending = org.dlang.liveimap.settings.editors.PendingNewAccount
        val fresh = pending.settings ?: newAccountSettings()
        creating = true
        pending.active = true
        pending.settings = fresh
        editingId = null
        settingsEpoch.incrementAndGet()
        settings = fresh
        passwordEpoch.incrementAndGet()
        smtpEpoch.incrementAndGet()
        password = pending.password
        smtpPassword = pending.smtpPassword
        sievePassword = pending.sievePassword
        passwordMissing = false
        smtpMissing = false
        ready = true
    }

    fun finishNewAccount() {
        if (!creating) return
        val savingSettings = settingsMirror.get()
        val savingPassword = password
        val savingSmtp = smtpPassword
        val savingSieve = sievePassword
        settingsIo.launch {
            val id = store.createAccount(savingSettings, savingPassword, savingSmtp)
            if (savingSieve.isNotEmpty()) {
                org.dlang.liveimap.accounts.AccountStore.shared(appContext).setSievePassword(id, savingSieve)
            }
            val created = org.dlang.liveimap.settings.editors.PendingNewAccount.onCreated
            org.dlang.liveimap.settings.editors.PendingNewAccount.clear()
            creating = false
            created?.invoke(id)
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                applyReload(loaded, epoch, force = true)
            }) {
                computeReload(id)
            }
        }
    }

    fun retryDelete() {
        val id = retryDeleteId ?: return
        removeAccount(id)
    }

    fun load() {
        if (org.dlang.liveimap.settings.editors.PendingNewAccount.active) {
            beginNewAccount()
            return
        }
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                applyReload(loaded, epoch, force = false)
            }) {
                computeReload(editingMirror.get())
            }
        }
    }

    fun selectAccount(id: String) {
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                editingId = id // F121: edit it; the active account is unchanged
                applyReload(loaded, epoch, force = true)
            }) {
                val ui = SettingsHub.uiNow()
                if (ui.settingsLastEditedAccount != id) {
                    SettingsHub.saveUiKeys(appContext, ui.copy(settingsLastEditedAccount = id), setOf("settingsLastEditedAccount"))
                }
                computeReload(id)
            }
        }
    }

    fun removeAccount(id: String) {
        val name = accounts.firstOrNull { it.id == id }?.name ?: id
        deleteName = name
        deleteFailedName = null
        deleteStep = null
        retryDeleteId = id
        settingsIo.launch {
            val epoch = captureEpochs()
            try {
                if (!store.removeAccount(id)) {
                    deleteName = null
                    return@launch
                }
                deleteName = null
                val current = editingMirror.get()
                store.onAccount(publish = { loaded: EditorLoad ->
                    applyReload(loaded, epoch, force = true)
                }) {
                    computeReload(if (current == id) null else current)
                }
            } catch (failed: org.dlang.liveimap.accounts.AccountDeleteFailed) {
                deleteName = null
                deleteFailedName = name
                deleteStep = failed.step
            }
        }
    }

    private fun captureEpochs() = EditorEpoch(settingsEpoch.get(), passwordEpoch.get(), smtpEpoch.get())

    private suspend fun computeReload(preferred: String?): EditorLoad {
        val listed = store.listAccounts()
        val ui = SettingsHub.uiNow()
        val opensOn = ui.settingsOpensOn.ifEmpty { "drawer" }
        val lastEdited = ui.settingsLastEditedAccount
        val editing = if (preferred != null && listed.any { it.id == preferred }) {
            preferred
        } else {
            openingAccount(listed, opensOn, lastEdited)?.id
        }
        if (editing == null) {
            return EditorLoad(
                editingId = null,
                settings = ui,
                password = "",
                smtpPassword = "",
                accounts = listed.map { it.copy(editing = false) },
            )
        }
        val choice = listed.firstOrNull { it.id == editing }
        if (choice?.corrupt == true) {
            return missingBlobEditor(listed, editing, null, ui, "", "", false, false)
        }
        val boundStore = store.forAccount(editing)
        val rawPassword = boundStore.password()
        val rawSmtp = boundStore.smtpPassword()
        return try {
            EditorLoad(
                editingId = editing,
                settings = boundStore.load().withUiOf(ui),
                password = rawPassword ?: "",
                smtpPassword = rawSmtp ?: "",
                accounts = listed.map { it.copy(editing = it.id == editing) },
                passwordMissing = rawPassword == null,
                smtpMissing = rawSmtp == null,
            )
        } catch (_: NoAccountSelected) {
            missingBlobEditor(
                listed,
                editing,
                store.accountFields(editing),
                ui,
                rawPassword ?: "",
                rawSmtp ?: "",
                rawPassword == null,
                rawSmtp == null,
            )
        }
    }

    private fun applyReload(loaded: EditorLoad, epoch: EditorEpoch, force: Boolean) {
        if (force || settingsEpoch.get() == epoch.settings) {
            editingId = loaded.editingId
            settings = loaded.settings
        }
        if (force || passwordEpoch.get() == epoch.password) {
            password = loaded.password
            passwordMissing = loaded.passwordMissing
        }
        if (force || smtpEpoch.get() == epoch.smtp) {
            smtpPassword = loaded.smtpPassword
            smtpMissing = loaded.smtpMissing
        }
        accounts = loaded.accounts
        if (force || settingsEpoch.get() == epoch.settings) {
            baselineMirror.set(loaded.settings)
            dirtyKeys.set(emptySet())
            pageDirty = false
            passwordDirty = false
            smtpDirty = false
            sieveDirty = false
            baselinePassword = loaded.password
            baselineSmtp = loaded.smtpPassword
            baselineSieve = ""
        }
        ready = true
    }

    fun abandonPage() {
        val base = baselineMirror.get()
        var next = settings
        for (key in dirtyKeys.get()) next = next.withSetting(key, SettingValue(base.getSetting(key)))
        settingsEpoch.incrementAndGet()
        settings = next
        dirtyKeys.set(emptySet())
        pageDirty = false
        if (passwordDirty) {
            passwordEpoch.incrementAndGet()
            password = baselinePassword
            passwordDirty = false
        }
        if (smtpDirty) {
            smtpEpoch.incrementAndGet()
            smtpPassword = baselineSmtp
            smtpDirty = false
        }
        if (sieveDirty) {
            sievePassword = baselineSieve
            sieveDirty = false
        }
    }

    fun commitPage(): Boolean = runBlocking { commitPageAwait() }

    fun captureSave(screen: Settings, password: String?, smtp: String?): SaveCapture = SaveCapture(
        accountId = editingMirror.get(),
        before = baselineMirror.get(),
        screen = screen,
        password = password ?: if (passwordDirty) this.password else null,
        smtp = smtp ?: if (smtpDirty) smtpPassword else null,
        sieve = if (sieveDirty) sievePassword else null,
        creating = creating,
    )

    suspend fun commitPageAwait(requireWrite: Boolean = false, captured: SaveCapture? = null): Boolean {
        if (requireWrite || captured != null) return commitAttempt(captured)
        return commitLivePage()
    }

    private suspend fun commitAttempt(captured: SaveCapture?): Boolean {
        saveGate.lock()
        try {
            val attempt = captured ?: SaveCapture(
                accountId = editingMirror.get(),
                before = baselineMirror.get(),
                screen = settingsMirror.get(),
                password = if (passwordDirty) password else null,
                smtp = if (smtpDirty) smtpPassword else null,
                sieve = if (sieveDirty) sievePassword else null,
                creating = creating,
            )
            if (attempt.creating) {
                if (captured != null && !persist(attempt.screen)) {
                    if (settingsSaveFailure.value.isNullOrEmpty()) settingsSaveFailure.value = settingsSaveEmptyWrite
                    return false
                }
                if (captured != null) {
                    attempt.password?.let { persistPassword(it) }
                    attempt.smtp?.let { persistSmtpPassword(it) }
                    attempt.sieve?.let {
                        sievePassword = it
                        sieveDirty = true
                    }
                }
                settingsSaveFailure.value = null
                createAwait()
                return true
            }
            val retarget = org.dlang.liveimap.accounts.retargetCredentials(attempt.before, attempt.screen)
            val saving = if (captured != null) retarget.settings else attempt.screen
            val keys = if (captured != null) saving.changedKeysAgainst(attempt.before) else dirtyKeys.get()
            val unknownsChanged = saving.unknownSettings != attempt.before.unknownSettings
            val scopePick = ScopePicks.chosen.isNotEmpty()
            // A moved server drops the old secret in the settings write. A password that is part of this attempt is written after that.
            val writePassword = attempt.password
            val writeSmtp = attempt.smtp
            val writeSieve = attempt.sieve
            val rejected = null
            val preview = settingsSaveVerdict(
                rejected = rejected,
                before = attempt.before,
                screen = saving,
                keysWritten = keys,
                wrotePassword = writePassword != null,
                wroteSmtp = writeSmtp != null,
                wroteSieve = writeSieve != null,
                needPassword = writePassword != null,
                needSmtp = writeSmtp != null,
                needSieve = writeSieve != null,
                record = null,
                checkRecord = false,
            )
            val structuralOnly = (unknownsChanged || scopePick) && keys.isEmpty() &&
                saving.changedKeysAgainst(attempt.before).isEmpty() &&
                writePassword == null && writeSmtp == null && writeSieve == null
            if (!preview.stored && !structuralOnly) {
                if (rejected != null || settingsSaveFailure.value.isNullOrEmpty()) {
                    settingsSaveFailure.value = preview.failure
                }
                return false
            }
            val id = attempt.accountId
            val storeTarget = id?.let { store.forAccount(it) }
            if (storeTarget == null) {
                if (!structuralOnly && (accountIdentityChanged(attempt.before, saving) || writePassword != null || writeSmtp != null || writeSieve != null || keys.isEmpty())) {
                    if (settingsSaveFailure.value.isNullOrEmpty()) settingsSaveFailure.value = settingsSaveEmptyWrite
                    return false
                }
                settingsSaveFailure.value = null
                SettingsHub.saveUiKeys(appContext, saving, keys, replaceUnknowns = unknownsChanged)
                if (captured == null || editingMirror.get() == id) {
                    markAttemptStored(id, saving, writePassword, writeSmtp, writeSieve, replaceSettings = captured != null)
                }
                return true
            }
            settingsSaveFailure.value = null
            if (keys.isNotEmpty() || unknownsChanged || scopePick) {
                storeTarget.commitKeys(saving, keys, writeUnknowns = unknownsChanged)
            }
            if (writePassword != null) storeTarget.setPassword(writePassword)
            if (writeSmtp != null) storeTarget.setSmtpPassword(writeSmtp)
            if (writeSieve != null) {
                org.dlang.liveimap.accounts.AccountStore.shared(appContext).setSievePassword(id, writeSieve.ifEmpty { null })
            }
            val recorded = settingsSaveVerdict(
                rejected = null,
                before = attempt.before,
                screen = saving,
                keysWritten = keys,
                wrotePassword = writePassword != null,
                wroteSmtp = writeSmtp != null,
                wroteSieve = writeSieve != null,
                needPassword = writePassword != null,
                needSmtp = writeSmtp != null,
                needSieve = writeSieve != null,
                record = store.accountFields(id),
                checkRecord = true,
            )
            if (!recorded.stored && !structuralOnly) {
                settingsSaveFailure.value = recorded.failure
                return false
            }
            if (captured == null || editingMirror.get() == id) {
                markAttemptStored(id, saving, writePassword, writeSmtp, writeSieve, replaceSettings = captured != null)
            }
            return true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsSaveFailure.value = error.message ?: error.toString()
            return false
        } finally {
            saveGate.unlock()
        }
    }

    private fun markAttemptStored(
        accountId: String?,
        saving: Settings,
        writePassword: String?,
        writeSmtp: String?,
        writeSieve: String?,
        replaceSettings: Boolean,
    ) {
        if (replaceSettings) {
            if (editingMirror.get() != accountId) return
            val retarget = org.dlang.liveimap.accounts.retargetCredentials(baselineMirror.get(), saving)
            settingsEpoch.incrementAndGet()
            settings = saving
            if (writePassword != null) {
                passwordEpoch.incrementAndGet()
                password = writePassword
                passwordMissing = false
            } else if (retarget.dropImapPassword) {
                passwordEpoch.incrementAndGet()
                password = ""
                passwordMissing = false
            }
            if (writeSmtp != null) {
                smtpEpoch.incrementAndGet()
                smtpPassword = writeSmtp
                smtpMissing = false
            } else if (retarget.dropSmtpPassword) {
                smtpEpoch.incrementAndGet()
                smtpPassword = ""
                smtpMissing = false
            }
            if (writeSieve != null) sievePassword = writeSieve
            else if (retarget.dropSievePassword) sievePassword = ""
        }
        baselineMirror.set(if (replaceSettings) saving else settingsMirror.get())
        dirtyKeys.set(emptySet())
        pageDirty = false
        passwordDirty = false
        smtpDirty = false
        sieveDirty = false
        baselinePassword = password
        baselineSmtp = smtpPassword
        baselineSieve = sievePassword
        SettingsRevision.bump()
    }

    private suspend fun commitLivePage(): Boolean {
        saveGate.lock()
        try {
            settingsSaveFailure.value = null
            if (creating) {
                createAwait()
                return true
            }
            val keys = dirtyKeys.get()
            val draft = settingsMirror.get()
            val unknownsChanged = draft.unknownSettings != baselineMirror.get().unknownSettings
            val scopePick = ScopePicks.chosen.isNotEmpty()
            val id = editingMirror.get()
            val storeTarget = id?.let { store.forAccount(it) }
            if (storeTarget != null && (keys.isNotEmpty() || unknownsChanged || scopePick || passwordDirty || smtpDirty || sieveDirty)) {
                if (keys.isNotEmpty() || unknownsChanged || scopePick) {
                    storeTarget.commitKeys(draft, keys, writeUnknowns = unknownsChanged)
                }
                if (passwordDirty) storeTarget.setPassword(password)
                if (smtpDirty) storeTarget.setSmtpPassword(smtpPassword)
                val sieve = sievePassword
                if (sieveDirty) {
                    org.dlang.liveimap.accounts.AccountStore.shared(appContext).setSievePassword(id, sieve.ifEmpty { null })
                }
            } else if (storeTarget == null && (keys.isNotEmpty() || unknownsChanged || scopePick)) {
                SettingsHub.saveUiKeys(appContext, draft, keys, replaceUnknowns = unknownsChanged)
            }
            baselineMirror.set(draft)
            dirtyKeys.set(emptySet())
            pageDirty = false
            passwordDirty = false
            smtpDirty = false
            sieveDirty = false
            baselinePassword = password
            baselineSmtp = smtpPassword
            baselineSieve = sievePassword
            SettingsRevision.bump()
            return true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsSaveFailure.value = error.message ?: error.toString()
            return false
        } finally {
            saveGate.unlock()
        }
    }

    private suspend fun createAwait() {
        val savingSettings = settingsMirror.get()
        val savingPassword = password
        val savingSmtp = smtpPassword
        val savingSieve = sievePassword
        val id = store.createAccount(savingSettings, savingPassword, savingSmtp)
        if (savingSieve.isNotEmpty()) {
            org.dlang.liveimap.accounts.AccountStore.shared(appContext).setSievePassword(id, savingSieve)
        }
        val created = org.dlang.liveimap.settings.editors.PendingNewAccount.onCreated
        org.dlang.liveimap.settings.editors.PendingNewAccount.clear()
        creating = false
        pageDirty = false
        dirtyKeys.set(emptySet())
        passwordDirty = false
        smtpDirty = false
        sieveDirty = false
        created?.invoke(id)
        val epoch = captureEpochs()
        store.onAccount(publish = { loaded: EditorLoad ->
            applyReload(loaded, epoch, force = true)
        }) {
            computeReload(id)
        }
    }

    suspend fun saveAccount(target: DataStoreSettingsStore) {
        commitPageAwait()
    }
}

/** A missing settings blob. A corrupt row, or userData overlay cannot read, stays listed and shows the UI snapshot. */
internal fun missingBlobEditor(
    listed: List<AccountChoice>,
    editing: String,
    userData: Map<String, String>?,
    ui: Settings,
    password: String,
    smtpPassword: String,
    passwordMissing: Boolean,
    smtpMissing: Boolean,
): EditorLoad {
    val choice = listed.firstOrNull { it.id == editing }
    val overlaid = if (choice == null || choice.corrupt || userData == null) {
        null
    } else {
        runCatching { overlayAccountFields(newAccountSettings(), userData) }.getOrNull()
    }
    if (overlaid == null) {
        return EditorLoad(
            editingId = null,
            settings = ui,
            password = "",
            smtpPassword = "",
            accounts = listed.map { it.copy(editing = false) },
        )
    }
    return EditorLoad(
        editingId = editing,
        settings = overlaid.withUiOf(ui),
        password = password,
        smtpPassword = smtpPassword,
        accounts = listed.map { it.copy(editing = it.id == editing) },
        passwordMissing = passwordMissing,
        smtpMissing = smtpMissing,
    )
}

private val immediateSettingKeys = setOf(
    "favorites",
    "expandedFolders",
    "folderStarts",
    "savedSearches",
    "savedAdvanced",
    "sortAskSkip",
    "threadAskSkip",
    "searchAskSkip",
    "fallbackAskSkip",
    "inboundRules",
    "lastSaveMailbox",
)


/**
 * No open session, or a JMAP account, does not show an IMAP "server lacks" reason.
 * An open IMAP session uses what that session advertised.
 */
internal fun serverCapAllows(jmapFolders: Boolean, sessionOpen: Boolean, advertised: Boolean): Boolean {
    if (jmapFolders || !sessionOpen) return true
    return advertised
}

/** Bumped after every settings save, so another editor on screen reloads. */
internal object SettingsRevision {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    val value: Int get() = state.intValue
    fun bump() { state.intValue = state.intValue + 1 }
}

/**
 * Bumped when a toolbar save lands in the store. [ToolbarEditorScreen] does not watch [SettingsRevision],
 * so open copies reload from this instead of keeping a stale bar or row count.
 */
internal object ToolbarRevision {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    val value: Int get() = state.intValue
    fun bump() { state.intValue = state.intValue + 1 }
}

/**
 * Change layout is the only in-process apply. [generation] starts at 0.
 * [fullScreenGeneration] is what MainActivity collects to reload full screen.
 */
internal object LayoutApply {
    private val panes = androidx.compose.runtime.mutableIntStateOf(0)
    private val screen = androidx.compose.runtime.mutableIntStateOf(0)
    val generation: Int get() = panes.intValue
    val fullScreenGeneration: Int get() = screen.intValue
    fun request() { panes.intValue = panes.intValue + 1 }
    fun noteFullScreen() { screen.intValue = screen.intValue + 1 }
}

@Composable
internal fun LoadCheckDialog() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val questions by SettingsHub.questions.collectAsState()
    val notice by SettingsHub.notice.collectAsState()
    val pending = questions
    var picks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(pending) {
        val next = LinkedHashMap<String, String>()
        pending?.moves?.forEach { move ->
            move.options.firstOrNull()?.let { next[move.key] = it.value }
        }
        picks = next
    }
    if (pending != null && (pending.extras.isNotEmpty() || pending.moves.isNotEmpty())) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(S("Stored settings")) },
            text = {
                Column {
                    if (pending.warning.isNotEmpty()) Text(pending.warning)
                    if (pending.extras.isNotEmpty()) {
                        Text(S("These stored keys are not in this version. Remove them?"))
                        Text(pending.extras.joinToString("\n") { extra -> extra.blob + " " + extra.key })
                    }
                    for (move in pending.moves) {
                        Text(move.key)
                        for (option in move.options) {
                            TextButton(onClick = { picks = picks + (move.key to option.value) }) {
                                val mark = if (picks[move.key] == option.value) "* " else ""
                                Text(mark + option.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { scope.launch { SettingsHub.answer(context, true, picks) } }) {
                    Text(S("Remove", name = "settings_remove_extras"))
                }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { SettingsHub.answer(context, false, picks) } }) {
                    Text(S("Keep"))
                }
            },
        )
    }
    if (notice == "default-cleared") {
        AlertDialog(
            onDismissRequest = { SettingsHub.clearNotice() },
            title = { Text(S("The default account was removed. Choose an account.")) },
            confirmButton = {
                TextButton(onClick = { SettingsHub.clearNotice() }) {
                    Text(S("OK", name = "settings_ok"))
                }
            },
        )
    } else if (!notice.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { SettingsHub.clearNotice() },
            title = { Text(S("Stored settings")) },
            text = { Text(notice ?: "") },
            confirmButton = {
                TextButton(onClick = { SettingsHub.clearNotice() }) {
                    Text(S("OK", name = "settings_ok"))
                }
            },
        )
    }
}

@Composable
internal fun rememberSettingsGate(): String? {
    val context = LocalContext.current.applicationContext
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        error = startupSettingsError(context)
        ready = true
    }
    return if (!ready) "" else error
}

@Composable
internal fun accountDeleteError(editor: SettingsEditor): String? {
    val name = editor.deleteFailedName ?: return null
    val step = editor.deleteStep ?: return null
    return S("%1\$s could not be deleted (step %2\$d).", name, step)
}

@Composable
internal fun rememberSettingsEditor(): SettingsEditor {
    val appContext = LocalContext.current.applicationContext
    val ui = rememberCoroutineScope()
    val store = remember { DataStoreSettingsStore(appContext) }
    val editor = remember(store) { SettingsEditor(store, appContext, ui) }
    DisposableEffect(editor) {
        bindSettingsEditor(editor)
        onDispose { unbindSettingsEditor(editor) }
    }
    LaunchedEffect(store) { editor.load() }
    return editor
}

internal class SectionOpen(private val packed: MutableState<String>) {
    val open: Set<String>
        get() = if (packed.value.isEmpty()) emptySet() else packed.value.split('\n').toSet()

    fun toggle(id: String) {
        val now = open
        val next = if (id in now) now - id else now + id
        packed.value = next.joinToString("\n")
    }
}

@Composable
internal fun rememberSectionOpen(): SectionOpen {
    val packed = rememberSaveable { mutableStateOf("") }
    return remember(packed) { SectionOpen(packed) }
}

@Composable
fun SettingsTree(
    onCopyContacts: () -> Unit,
    onEditBook: () -> Unit,
    onOpenPanel: (String, String) -> Unit,
    onNavigate: (String) -> Unit = {},
    page: String = "settings",
    accountId: String? = null,
    editorId: String? = null,
) {
    val gate = rememberSettingsGate()
    val editor = rememberSettingsEditor()
    if (!gate.isNullOrEmpty()) {
        Text(gate)
        return
    }
    if (gate == "") return
    if (!editor.ready) return
    if (!accountId.isNullOrEmpty() && accountId != "_") {
        LaunchedEffect(accountId) {
            if (editor.editingId != accountId) editor.selectAccount(accountId)
        }
    }
    if (accountId == "_") {
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                if (org.dlang.liveimap.settings.editors.PendingNewAccount.active) {
                    org.dlang.liveimap.settings.editors.PendingNewAccount.clear()
                }
            }
        }
    }
    val sections = rememberSectionOpen()
    val settings = editor.settings
    val depth = 0
    val scroll = rememberScrollState()
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    val picking = remember { mutableStateOf<MailboxPick?>(null) }
    val walk = SettingsWalk(
        editor = editor,
        sections = sections,
        scroll = scroll,
        onCopyContacts = onCopyContacts,
        onEditBook = onEditBook,
        onOpenPanel = onOpenPanel,
        onNavigate = onNavigate,
        onEditFavorite = { favorite ->
            editingFavorite = favorite
            favoriteDraft = if (favorite.label.isNotBlank()) {
                favorite.label
            } else {
                favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
            }
        },
        picking = picking,
        settingsScroll = page == "settings",
    )
    BackHandler(enabled = picking.value != null) { picking.value = null }
    Column(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                notePaneScroll(scroll, bounds.top, bounds.bottom)
            }
            .verticalScroll(scroll)
            .pointerInput(editor) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val session = org.dlang.liveimap.ui.AppZoomSession(editor.settings.appTextZoom)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.count { it.pressed } >= 2) {
                            val step = event.calculateZoom()
                            session.zoomBy(step)
                            if (!step.isNaN() && step != 1f) pinched = true
                        }
                    } while (event.changes.any { it.pressed })
                    val next = session.commitForSettings(pinched) ?: return@awaitEachGesture
                    if (!editor.applySettingsPinch(next)) return@awaitEachGesture
                    settingsIo.launch {
                        val current = SettingsHub.uiNow()
                        SettingsHub.saveUiKeys(
                            editor.appContext,
                            current.withSetting("appTextZoom", SettingValue(next.toString())),
                            setOf("appTextZoom"),
                        )
                    }
                }
            }
            .padding(16.dp)
            .testTag("smoke-settings"),
    ) {
            val saveFailure = settingsSaveFailure.value
            if (saveFailure != null) {
                Text(saveFailure)
            }
            PolicyDialog(editor)
            if (page == "account" && !accountId.isNullOrEmpty() && accountId != "_") {
                TextButton(onClick = { onNavigate("settings/setup?accountId=$accountId") }) {
                    Text(S("Set up this account again"))
                }
            }
            val roots = installedSettingsRoots()
            if (page == "accounts") {
                AccountListEditor(
                    accounts = editor.accounts,
                    onPick = { id ->
                        editor.selectAccount(id)
                        onNavigate("settings/account/$id")
                    },
                    onRemove = { editor.removeAccount(it) },
                    onAdd = {
                        if (editor.accounts.isEmpty()) onNavigate("settings/setup")
                        else {
                            editor.beginNewAccount()
                            onNavigate("settings/account/_")
                        }
                    },
                    deletingName = editor.deleteName,
                    deleteError = accountDeleteError(editor),
                    onRetryDelete = { editor.retryDelete() },
                )
            } else if (page == "editor" && editorId == "pinerc") {
                PinercEditor(editor)
            } else if (page == "editor" && editorId == "addressBooks") {
                ManageAddresses(editor, onEditBook, onCopyContacts)
            } else if (page == "editor") {
                when (val found = findSettingsEditor(roots, editorId)) {
                    is TreeNode.Group -> renderSettingsNode(found, depth, walk)
                    is TreeNode.Editor -> found.children.forEach { renderSettingsNode(it, depth, walk) }
                    else -> Unit
                }
            } else {
                val shown: List<TreeNode> = when (page) {
                    "settings" -> roots
                    "editor" -> listOfNotNull(findSettingsEditor(roots, editorId))
                    else -> listOfNotNull(findSettingsPage(roots, page))
                }
                for (node in shown) {
                    if (
                        page == "settings" &&
                        node is TreeNode.Page &&
                        node.page == "account" &&
                        editor.accounts.isEmpty() &&
                        !editor.creating
                    ) {
                        AccountListEditor(
                            accounts = emptyList(),
                            onPick = {},
                            onRemove = {},
                            onAdd = {
                                if (editor.accounts.isEmpty()) onNavigate("settings/setup")
                                else {
                                    editor.beginNewAccount()
                                    onNavigate("settings/account/_")
                                }
                            },
                            deletingName = editor.deleteName,
                            deleteError = accountDeleteError(editor),
                            onRetryDelete = { editor.retryDelete() },
                        )
                    } else {
                        renderSettingsNode(node, depth, walk)
                    }
                }
            }
        }
        val drafts = walk.drafts
        val pendingLine = PendingLines.pending()
        if (settingsSaveShown(page == "accounts", editor.creating, editor.pageDirty, drafts?.hasDraft == true, pendingLine)) {
            Button(
                onClick = { saveSettingsPage(editor, drafts, "settings-save") },
                enabled = settingsSaveEnabled(
                    page == "accounts",
                    editor.creating,
                    editor.pageDirty,
                    drafts?.hasDraft == true,
                    pendingLine,
                    drafts?.serverChecking == true,
                ),
                modifier = Modifier.padding(16.dp).testTag("settings-save"),
            ) {
                Text(S("Save"))
            }
        }
    }
    MailboxPickDialog(editor, picking.value, { walk.drafts }) { picking.value = it }
    val editing = editingFavorite
    if (editing != null) {
        FavoriteEditDialog(
            where = FavoriteWhere(
                account = settings.email.ifBlank { settings.username },
                server = settings.imapHost,
                path = editing.mailbox,
            ),
            name = favoriteDraft,
            onName = { favoriteDraft = it },
            onMoveUp = { moveTreeFavorite(editor, editing, -1) },
            onMoveDown = { moveTreeFavorite(editor, editing, 1) },
            onSave = {
                val trimmed = favoriteDraft.trim()
                editor.persist(
                    editor.settings.copy(
                        favorites = editor.settings.favorites.map { item ->
                            if (item.node == editing.node && item.mailbox == editing.mailbox) {
                                item.copy(label = trimmed)
                            } else {
                                item
                            }
                        },
                    ),
                )
                editingFavorite = null
            },
            onDelete = {
                editor.persist(
                    editor.settings.copy(
                        favorites = editor.settings.favorites.filterNot {
                            it.node == editing.node && it.mailbox == editing.mailbox
                        },
                    ),
                )
                editingFavorite = null
            },
            onDismiss = { editingFavorite = null },
        )
    }
}

internal fun moveTreeFavorite(editor: SettingsEditor, current: FolderFavorite, delta: Int) {
    val list = editor.settings.favorites
    val index = list.indexOfFirst { it.node == current.node && it.mailbox == current.mailbox }
    if (index < 0) return
    val target = index + delta
    if (target !in list.indices) return
    val next = list.toMutableList()
    val swap = next[target]
    next[target] = next[index]
    next[index] = swap
    editor.persist(editor.settings.copy(favorites = next))
}








internal fun sharePinerc(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
    send.type = "text/plain"
    send.putExtra(Intent.EXTRA_TEXT, text)
    send.putExtra(Intent.EXTRA_SUBJECT, "liveimap.pinerc")
    val chooser = Intent.createChooser(send, "liveimap.pinerc")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Unit
    }
}


internal class ServerDrafts {
    var probing by mutableStateOf(false)
    var serverReport by mutableStateOf<List<String>>(emptyList())
    var checkingSieve by mutableStateOf(false)
    var sieveReport by mutableStateOf("")
    var serverChecking by mutableStateOf(false)
    var serverDraft by mutableStateOf<Settings?>(null)
    var passwordDraft by mutableStateOf<String?>(null)
    var smtpPasswordDraft by mutableStateOf<String?>(null)
    var serverReview by mutableStateOf<ServerSaveReview?>(null)
    var discardAsk by mutableStateOf(false)
    var leaveAfter by mutableStateOf<(() -> Unit)?>(null)

    /** Bumped when a confirmed save starts, so a check still in flight cannot put the draft or the review back. */
    @Volatile private var saveGeneration = 0

    val hasDraft: Boolean
        get() = serverDraft != null || passwordDraft != null || smtpPasswordDraft != null

    fun discard() {
        serverDraft = null
        passwordDraft = null
        smtpPasswordDraft = null
    }

    private fun clearDrafts() {
        serverDraft = null
        passwordDraft = null
        smtpPasswordDraft = null
    }

    private fun reportStore(accountId: String?, control: String, generation: Int, ok: Boolean) {
        val outcome = if (ok) "stored" else settingsSaveFailed(settingsSaveFailure.value.orEmpty())
        logSettingsSave(control, accountId, outcome)
        if (generation != saveGeneration) return
        val (reviewOpen, discardOpen) = confirmedSaveDialogs(serverReview != null, discardAsk, ok)
        if (!reviewOpen) serverReview = null
        discardAsk = discardOpen
        if (ok) clearDrafts()
    }

    /** Writes [capture] and reports once. settingsIo, not the composition, so leaving the screen still finishes the report. */
    private suspend fun finishStore(editor: SettingsEditor, control: String, generation: Int, capture: SaveCapture) {
        val ok = try {
            editor.commitPageAwait(requireWrite = true, captured = capture)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsSaveFailure.value = error.message ?: error.toString()
            false
        }
        reportStore(capture.accountId, control, generation, ok)
    }

    // Server fields edit a draft; nothing is stored or checked until Save.
    fun userServerSave(editor: SettingsEditor, next: Settings) {
        val settings = editor.settings
        if (!sameServer(next, settings)) {
            serverDraft = next
            return
        }
        val kept = editor.settings
        if (next.withoutPreserved("tlsMode") == next) {
            val cleared = kept.withoutPreserved("tlsMode")
            if (cleared != kept) editor.persist(cleared)
        }
        serverDraft = null
    }

    fun savedServer(editor: SettingsEditor, chosen: Settings, base: Settings = editor.settings): Settings {
        val merged = base.withServerOf(chosen)
        return if (chosen.withoutPreserved("tlsMode") == chosen) merged.withoutPreserved("tlsMode") else merged
    }

    private fun captureAttempt(editor: SettingsEditor, draft: Settings?, base: Settings): SaveCapture {
        val screen = if (draft != null) savedServer(editor, draft, base) else base
        return editor.captureSave(screen, passwordDraft, smtpPasswordDraft)
    }

    fun saveServer(editor: SettingsEditor, control: String) {
        val draft = serverDraft
        val settingsAtStart = editor.settings
        val generation = saveGeneration
        val started = captureAttempt(editor, draft, settingsAtStart)
        if (draft == null) {
            settingsIo.launch { finishStore(editor, control, generation, started) }
            return
        }
        settingsIo.launch {
            serverChecking = true
            val result = try {
                if (draft.jmapFolders) {
                    null
                } else {
                    withContext(Dispatchers.IO) { LibetpanMailSession.checkServerSettings(draft) }
                }
            } catch (_: Exception) {
                null
            } finally {
                serverChecking = false
            }
            if (generation != saveGeneration) return@launch
            val review = serverSaveReview(draft, result)
            if (settingsSaveChoice(confirmed = false, review) == SettingsSaveChoice.Hold) {
                serverReview = review
                logSettingsSave(control, started.accountId, settingsSaveHeld(review))
                return@launch
            }
            val finished = captureAttempt(editor, serverDraft, editor.settings)
            finishStore(editor, control, generation, boundSaveCapture(started, editor.editingId, finished))
        }
    }

    /** Unsaved-dialog Save and save-anyway. No second server check. */
    fun confirmStore(editor: SettingsEditor, control: String) {
        val generation = ++saveGeneration
        val draft = serverDraft
        val accountId = editor.editingId
        val settingsAtStart = editor.settings
        settingsSaveFailure.value = null
        settingsIo.launch {
            val base = if (editor.editingId == accountId) editor.settings else settingsAtStart
            finishStore(editor, control, generation, captureAttempt(editor, draft, base))
        }
    }
}

@Composable
internal fun PolicyDialog(editor: SettingsEditor) {
    val lines = editor.policy
    if (lines.isEmpty()) return
    var picks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    AlertDialog(
        onDismissRequest = { editor.policy = emptyList() },
        title = { Text(S("Review these settings")) },
        text = {
            Column {
                for (line in lines) {
                    Text(line.text)
                    if (line.needsPick) {
                        for ((label, value) in line.options) {
                            TextButton(onClick = { picks = picks + (line.key to value) }) {
                                Text(if (picks[line.key] == value) "* $label" else label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var next = editor.settings
                for (line in lines) {
                    if (line.needsPick && picks[line.key] == null) return@TextButton
                    next = line.clean(next, picks[line.key])
                }
                ScopePicks.chosen = picks
                editor.persist(next)
                editor.policy = emptyList()
                saveSettingsPage(editor, null, "policy-clean", bypassPolicy = true)
            }) { Text(S("Clean")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { editor.policy = emptyList() }) {
                    Text(S("Keep editing", name = "settings_keep_editing"))
                }
                TextButton(onClick = {
                    ScopePicks.chosen = emptyMap()
                    editor.policy = emptyList()
                    saveSettingsPage(editor, null, "policy-anyway", bypassPolicy = true)
                }) { Text(S("Save anyway")) }
            }
        },
    )
}

internal fun confirmSettingsSave(editor: SettingsEditor, drafts: ServerDrafts, control: String) {
    PendingLines.commitAll()
    drafts.confirmStore(editor, control)
}

internal fun saveSettingsPage(editor: SettingsEditor, drafts: ServerDrafts?, control: String, bypassPolicy: Boolean = false) {
    if (!bypassPolicy) {
        val issues = policyIssues(editor.settings, editor.editingId)
        if (issues.isNotEmpty()) {
            editor.policy = issues
            return
        }
    }
    editor.policy = emptyList()
    val pendingLine = PendingLines.pending()
    PendingLines.commitAll()
    if (drafts != null && drafts.hasDraft) drafts.saveServer(editor, control)
    else if (!editor.pageDirty && !pendingLine) {
        val ok = editor.commitPage()
        logSettingsSave(control, editor.editingId, if (ok) "stored" else settingsSaveFailed(settingsSaveFailure.value.orEmpty()))
    } else {
        val capture = editor.captureSave(editor.settings, null, null)
        settingsIo.launch {
            val ok = try {
                editor.commitPageAwait(requireWrite = true, captured = capture)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                settingsSaveFailure.value = error.message ?: error.toString()
                false
            }
            logSettingsSave(control, capture.accountId, if (ok) "stored" else settingsSaveFailed(settingsSaveFailure.value.orEmpty()))
        }
    }
}

internal class SettingsWalk(
    val editor: SettingsEditor,
    val sections: SectionOpen,
    val scroll: ScrollState,
    val onCopyContacts: () -> Unit,
    val onEditBook: () -> Unit,
    val onOpenPanel: (String, String) -> Unit,
    val onNavigate: (String) -> Unit,
    val onEditFavorite: (FolderFavorite) -> Unit,
    val picking: MutableState<MailboxPick?>,
    var drafts: ServerDrafts? = null,
    val settingsScroll: Boolean = false,
)

@Composable
internal fun renderSettingsNode(node: TreeNode, depth: Int, walk: SettingsWalk) {
    when (node) {
        is TreeNode.Leaf -> rowFor(node.leaf, walk)
        is TreeNode.Group -> renderGroup(node, depth, walk)
        is TreeNode.Page -> node.children.forEach { renderSettingsNode(it, depth, walk) }
        is TreeNode.Editor -> EditorEntry(node, walk)
    }
}

/** Account opens the server editor. Server, Sending, Logging, Layout, and Address book add a footer. These stay headers. */
private val groupsWithExtraControls = setOf(
    "Account",
    "Server",
    "Sending",
    "Logging",
    "Layout",
    "Address book",
    "Mailboxes",
)

internal fun leafShownInstead(node: TreeNode.Group, read: (String) -> String, sdk: Int): TreeLeaf? {
    if (node.title in groupsWithExtraControls) return null
    val visible = node.children.filter { childDrawn(it, read, sdk) }
    val only = visible.singleOrNull() as? TreeNode.Leaf ?: return null
    return only.leaf
}

internal fun childDrawn(node: TreeNode, read: (String) -> String, sdk: Int): Boolean = when (node) {
    is TreeNode.Leaf -> !node.leaf.hidden && (node.leaf.visibleWhen?.holds(read, sdk) ?: true)
    is TreeNode.Group -> (node.visibleWhen?.holds(read, sdk) ?: true) && node.children.any { childDrawn(it, read, sdk) }
    is TreeNode.Editor -> true
    is TreeNode.Page -> node.children.any { childDrawn(it, read, sdk) }
}

@Composable
internal fun renderGroup(node: TreeNode.Group, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val read = { key: String -> editor.settings.getSetting(key) }
    val sdk = android.os.Build.VERSION.SDK_INT
    if (!childDrawn(node, read, sdk)) return
    val shown = leafShownInstead(node, read, sdk)
    if (shown != null) {
        rowFor(shown, walk)
        return
    }
    val id = node.id
    val summaryPiece = summaryPiece(node.summary, editor.settings, editor.accounts.size, android.os.Build.VERSION.SDK_INT)
    SettingsNode(
        title = stringResource(SettingsTreeGenerated.titleOf(id)),
        depth = depth,
        summary = summaryPiece?.let { summaryText(it) }.orEmpty(),
        header = node.header,
        expanded = id in walk.sections.open,
        onToggle = { walk.sections.toggle(id) },
    ) {
        if (node.title == "Account") {
            ServerEditor(node, depth + 1, walk)
        } else {
            if (node.title == "Mailboxes") SpecialFolderLeaveWarning(editor)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groupIntro(node, editor)
                for (child in node.children) renderSettingsNode(child, depth + 1, walk)
                groupFooter(node.title, depth + 1, walk)
            }
        }
    }
}

@Composable
internal fun groupIntro(node: TreeNode.Group, editor: SettingsEditor) {
    val piece = summaryPiece(node.introWhen, editor.settings, editor.accounts.size, android.os.Build.VERSION.SDK_INT)
    if (piece != null) Text(summaryText(piece))
}

@Composable
internal fun groupFooter(title: String, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    when (title) {
        "Server" -> {
            if (drafts != null && drafts.serverChecking) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("server-checking"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(S("Checking server…"), Modifier.padding(start = 12.dp))
                }
            }
            if (drafts != null && drafts.hasDraft) {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton(onClick = { drafts.discard() }) {
                        Text(S("Discard changes"))
                    }
                }
            }
        }
        "Sending" -> {
            sendingProblem(settings)?.let { res ->
                Text(
                    S(res),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("no-way-to-send"),
                )
            }
        }
        "Logging" -> LoggingExtras(editor)
        "Layout" -> {
            SettingsNode(
                title = S("Panels"),
                depth = depth,
                summary = S("Panels"),
                expanded = "panels" in walk.sections.open,
                onToggle = { walk.sections.toggle("panels") },
            ) {
                val accountId = editor.editingId.orEmpty()
                OpenRow(S("Index toolbar")) { walk.onOpenPanel("index", accountId) }
                OpenRow(S("Selection toolbar")) { walk.onOpenPanel("selection", accountId) }
                OpenRow(S("Folder toolbar")) { walk.onOpenPanel("folders", accountId) }
                OpenRow(stringResource(SettingsTreeGenerated.resource("settings_panel_reader"))) { walk.onOpenPanel("reader", accountId) }
                OpenRow(stringResource(SettingsTreeGenerated.resource("settings_panel_compose"))) { walk.onOpenPanel("compose", accountId) }
            }
            Button(
                onClick = {
                    val target = editor.bound
                    settingsIo.launch {
                        editor.saveAccount(target)
                        LayoutApply.request()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(S("Change layout"))
            }
        }
        "Address book" -> {
            OpenRow(S("Copy contacts"), walk.onCopyContacts)
            OpenRow(S("Edit address book"), walk.onEditBook)
        }
    }
}

@Composable
internal fun LoggingExtras(editor: SettingsEditor) {
    var reportLines by remember { mutableStateOf<List<String>?>(null) }
    var confirmShareLog by remember { mutableStateOf(false) }
    val contextState = rememberUpdatedState(LocalContext.current)
    Text(S("Commands and server replies are written to this account's traffic log. Login data is replaced by its length; nothing else is hidden."))
    TextButton(onClick = { confirmShareLog = true }) { Text(S("Share log")) }
    TextButton(onClick = {
        val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        reportLines = TrafficLog.debugReport(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidVersion = Build.VERSION.RELEASE,
            device = device,
            settings = editor.settings,
            traffic = editor.editingId?.let { trafficText(editor.appContext.cacheDir, it) } ?: "",
        ).lines()
    }) { Text(S("Debug report")) }
    if (confirmShareLog) {
        AlertDialog(
            onDismissRequest = { confirmShareLog = false },
            title = { Text(S("Share log")) },
            text = { Text(S("Warning: this contains the full text of every message that was read or sent while logging was on, including bodies and attachments. Login data is replaced by its length, but a password typed into any other field is included. Share it only with someone you trust to see that mail.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShareLog = false
                    val id = editor.editingId
                    if (id != null && TrafficLog.trafficLogFile(editor.appContext.cacheDir, id) != null) {
                        TrafficLog.share(contextState.value, id)
                    } else if (id != null) {
                        android.util.Log.w("LiveIMAP", "traffic directory refused for $id")
                    }
                }) { Text(S("Share anyway")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShareLog = false }) { Text(S("Dismiss")) }
            },
        )
    }
    val shownReport = reportLines
    if (shownReport != null) {
        DebugReportReview(
            lines = shownReport,
            onLines = { reportLines = it },
            onDismiss = { reportLines = null },
        )
    }
}

@Composable
internal fun SpecialFolderLeaveWarning(editor: SettingsEditor) {
    val leaveContext = LocalContext.current
    val sentLabel = stringResource(SettingsTreeGenerated.resource("settings_sent_mailbox"))
    val postponedLabel = stringResource(SettingsTreeGenerated.resource("settings_postponed_mailbox"))
    val latest = rememberUpdatedState(editor.settings)
    DisposableEffect(Unit) {
        onDispose {
            val unset = unsetSpecialFolders(latest.value).map {
                if (it == SpecialFolder.Sent) sentLabel else postponedLabel
            }
            if (unset.isNotEmpty()) {
                android.widget.Toast.makeText(
                    leaveContext,
                    S(leaveContext, "Not set: %1\$s. Mail still works; nothing is saved to these folders.", unset.joinToString(", ")),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

@Composable
internal fun PasswordDraftField(editor: SettingsEditor, drafts: ServerDrafts) {
    if (editor.passwordMissing && drafts.passwordDraft == null && editor.password.isEmpty()) {
        Text(S("Password missing"))
    }
    LineField(
        S("Password"),
        drafts.passwordDraft ?: editor.password,
        KeyboardType.Password,
        password = true,
        revealKey = editor.editingId,
        ready = editor.ready,
        onDraft = { typed -> drafts.passwordDraft = typed.takeIf { it != editor.password } },
    ) { drafts.passwordDraft = it.takeIf { typed -> typed != editor.password } }
}

@Composable
internal fun SmtpPasswordDraftField(editor: SettingsEditor, drafts: ServerDrafts, ready: Boolean, enabled: Boolean) {
    if (editor.smtpMissing && drafts.smtpPasswordDraft == null && editor.smtpPassword.isEmpty()) {
        Text(S("Password missing"))
    }
    Text(S("Used when SMTP login is required. The IMAP password is used only when \"Same as IMAP password\" is ticked."))
    LineField(
        stringResource(SettingsTreeGenerated.resource("settings_smtp_password")),
        drafts.smtpPasswordDraft ?: editor.smtpPassword,
        KeyboardType.Password,
        password = true,
        revealKey = editor.editingId,
        ready = ready,
        enabled = enabled,
        onDraft = { typed -> drafts.smtpPasswordDraft = typed.takeIf { it != editor.smtpPassword } },
    ) { drafts.smtpPasswordDraft = it.takeIf { typed -> typed != editor.smtpPassword } }
}

@Composable
internal fun ShowUnreadLeaf(editor: SettingsEditor) {
    var warnUnread by remember { mutableStateOf(false) }
    BackHandler(enabled = warnUnread) { warnUnread = false }
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_show_unread")), settings.showUnreadCounts || warnUnread) { enabled ->
        if (enabled) {
            warnUnread = true
        } else {
            warnUnread = false
            editor.persist(editor.settings.copy(showUnreadCounts = false))
        }
    }
    if (warnUnread) {
        AlertDialog(
            onDismissRequest = { warnUnread = false },
            text = { Text(S("Counting unread messages asks the server for an unseen count for every mailbox in the list, and a large mailbox can make the folder list slow.")) },
            confirmButton = {
                TextButton(onClick = {
                    warnUnread = false
                    editor.persist(editor.settings.copy(showUnreadCounts = true))
                }) { Text(S("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { warnUnread = false }) { Text(S("Dismiss", name = "settings_dismiss")) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DsnChoices(editor: SettingsEditor) {
    val settings = editor.settings
    Text(stringResource(SettingsTreeGenerated.resource("settings_dsn")))
    FlowRow {
        for (mode in org.dlang.liveimap.ui.compose.DsnMode.entries) {
            FilterChip(
                selected = settings.dsnMode == mode,
                onClick = { editor.persist(editor.settings.copy(dsnMode = mode)) },
                label = { Text(dsnLabel(mode)) },
            )
        }
    }
}

internal data class CapFlags(
    val listExtended: Boolean,
    val uidPlus: Boolean,
    val canMove: Boolean,
    val compress: Boolean,
)

@Composable
internal fun rememberCapFlags(editor: SettingsEditor): CapFlags {
    val settings = editor.settings
    val sessionId = editor.accounts.firstOrNull { it.editing }?.id
    val session = if (sessionId.isNullOrEmpty()) null else existingMailSession(sessionId)
    val caps = session?.featureCaps
    fun allows(advertised: Boolean) = serverCapAllows(settings.jmapFolders, session != null, advertised)
    return CapFlags(
        listExtended = allows(caps?.listExtended == true),
        uidPlus = allows(caps?.uidPlus == true),
        canMove = allows(caps?.move == true),
        compress = allows(caps?.compressDeflate == true),
    )
}

@Composable
internal fun CountLeaf(key: String, count: Int) {
    Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    Text(count.toString())
}

@Composable
internal fun SkipSetField(key: String, value: List<String>, ready: Boolean, onValue: (List<String>) -> Unit) {
    LineField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        value.joinToString(", "),
        ready = ready,
    ) { text ->
        onValue(text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
}

@Composable
internal fun NumberLeaf(
    label: String,
    value: Int,
    ready: Boolean,
    ok: (Int) -> Boolean,
    onValue: (Int) -> Unit,
) {
    LineField(
        label,
        value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        interpret = { draft, _ ->
            val text = draft.trim()
            val number = text.toIntOrNull()
            if (number != null && ok(number)) number.toString() else null
        },
        onCommit = { text ->
            val number = text.toIntOrNull()
            if (number != null && ok(number)) onValue(number)
        },
    )
}

@Composable
internal fun NumberLeaf(
    label: String,
    value: Long,
    ready: Boolean,
    ok: (Int) -> Boolean,
    onValue: (Long) -> Unit,
) {
    NumberLeaf(label, value.toInt(), ready, ok) { onValue(it.toLong()) }
}

internal val LocalRowEnabled = compositionLocalOf { true }

@Composable
internal fun rowFor(leaf: TreeLeaf, walk: SettingsWalk) {
    if (leaf.hidden) return
    val settings = walk.editor.settings
    val read = { key: String -> settings.getSetting(key) }
    val sdk = android.os.Build.VERSION.SDK_INT
    if (leaf.visibleWhen != null && !leaf.visibleWhen.holds(read, sdk)) return
    val enabled = leaf.enabledWhen?.holds(read, sdk) ?: true
    CompositionLocalProvider(LocalRowEnabled provides enabled) {
    when (leaf.typeName) {
        "bool", "Bool" -> BoolRow(leaf.setting, walk)
        "either" -> EitherRow(leaf, walk)
        "number", "Int", "Long" -> if (leaf.many) NumberListRow(leaf.setting, walk) else NumberRow(leaf.setting, walk)
        "choice", "Enum" -> ChoiceRow(leaf.setting, walk)
        "text", "String", "Encoded" -> if (leaf.many) StringListRow(leaf.setting, walk) else TextRow(leaf.setting, walk)
        "password" -> PasswordRow(leaf.setting, walk)
        "mailbox" -> MailboxRow(leaf.setting, walk)
        "folder" -> if (leaf.setting == "addressBooks") AddressBooksRow(walk) else row_favorites(walk)
        "checkServer" -> CheckServerRow(walk)
        "clientCert" -> row_clientCertAlias(leaf.setting, walk)
        "pinerc" -> PinercEditor(walk.editor)
        "settingsFile" -> SettingsFileRow(leaf.setting, walk)
        "rules", "swipe" -> SubscreenRow(leaf.setting, walk)
        "toolbar" -> ToolbarLeafRow(leaf.setting, walk)
        "paneLayout" -> row_customLayout(walk)
        else -> if (leaf.setting.endsWith("Pin") || leaf.setting == "certPin") {
            PinRow(leaf.setting, walk)
        } else {
            TextRow(leaf.setting, walk)
        }
    }
    }
}

@Composable
internal fun EditorEntry(node: TreeNode.Editor, walk: SettingsWalk) {
    val titled = SettingsTreeGenerated.resource(node.title)
    val title = if (titled != 0) stringResource(titled) else S("Settings")
    if (node.editor == "toolbars") {
        OpenRow(S("Toolbars")) { walk.onOpenPanel("index", "") }
        return
    }
    if (node.editor == "accountList" && walk.editor.accounts.isEmpty() && !walk.settingsScroll) {
        AccountListEditor(
            accounts = emptyList(),
            onPick = {},
            onRemove = {},
            onAdd = {
                if (walk.editor.accounts.isEmpty()) walk.onNavigate("settings/setup")
                else {
                    walk.editor.beginNewAccount()
                    walk.onNavigate("settings/account/_")
                }
            },
            deletingName = walk.editor.deleteName,
            deleteError = accountDeleteError(walk.editor),
            onRetryDelete = { walk.editor.retryDelete() },
        )
        return
    }
    OpenRow(title) {
        if (node.editor == "accountList") {
            walk.onNavigate("settings/accounts")
        } else {
            val id = walk.editor.editingId ?: "_"
            walk.onNavigate("settings/account/$id/editor/${node.editor}")
        }
    }
}

@Composable
internal fun BoolRow(key: String, walk: SettingsWalk) {
    when (key) {
        "jmapFolders" -> row_jmapFolders(walk)
        "allowPlaintextAuth" -> row_allowPlaintextAuth(walk)
        "acceptInvalidCert" -> row_acceptInvalidCert(walk)
        "allowOldTls" -> row_allowOldTls(walk)

        "emailSubmission" -> row_emailSubmission(walk)
        "smtpSameAsImap" -> row_smtpSameAsImap(walk)
        "sieveSameAsInbound" -> row_sieveSameAsInbound(walk)
        "sigDashes" -> row_sigDashes(walk)
        "sigAtBottom" -> row_sigAtBottom(walk)
        "showUnreadCounts" -> row_showUnreadCounts(walk)
        "showRecentRules" -> row_showRecentRules(walk)
        "openAtInIndexMenu" -> row_openAtInIndexMenu(walk)
        "markSeenOnOpen" -> row_markSeenOnOpen(walk)
        "showDeleted" -> row_showDeleted(walk)
        "autoExpunge" -> row_autoExpunge(walk)
        "askBeforeExpunge" -> row_askBeforeExpunge(walk)
        "moveAllIncludesDeleted" -> row_moveAllIncludesDeleted(walk)
        "bounceFcc" -> row_bounceFcc(walk)
        "quellFlowed" -> row_quellFlowed(walk)
        "replyAboveQuote" -> row_replyAboveQuote(walk)
        "includeForwardAttachments" -> row_includeForwardAttachments(walk)
        "forwardAsAttachment" -> row_forwardAsAttachment(walk)
        "addressBookNeverTrim" -> row_addressBookNeverTrim(walk)
        "dynamicColor" -> row_dynamicColor(walk)
        "fullScreen" -> row_fullScreen(walk)
        "clientSort" -> row_clientSort(walk)
        "clientThread" -> row_clientThread(walk)
        "pollForNewMail" -> row_pollForNewMail(walk)
        "statusVisibleCounts" -> row_statusVisibleCounts(walk)
        "forceSlowerFallbacks" -> row_forceSlowerFallbacks(walk)
        "logImapTraffic" -> row_logImapTraffic(walk)
        "showUserInDebugReport" -> row_showUserInDebugReport(walk)
        else -> {
            val editor = walk.editor
            val on = editor.settings.getSetting(key) == "true"
            BoolField(stringResource(SettingsTreeGenerated.regTitle(key)), on) { value ->
                editor.persist(editor.settings.withSetting(key, SettingValue(if (value) "true" else "false")))
            }
        }
    }
}

@Composable
internal fun EitherRow(leaf: TreeLeaf, walk: SettingsWalk) {
    val editor = walk.editor
    val on = editor.settings.getSetting(leaf.setting) == "true"
    val falseName = leaf.choiceTitles?.get("false").orEmpty()
    val trueName = leaf.choiceTitles?.get("true").orEmpty()
    val falseText = SettingsTreeGenerated.resource(falseName).let { id ->
        if (id != 0) stringResource(id) else falseName
    }
    val trueText = SettingsTreeGenerated.resource(trueName).let { id ->
        if (id != 0) stringResource(id) else trueName
    }
    ListItem(
        headlineContent = { Text(stringResource(SettingsTreeGenerated.regTitle(leaf.setting))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(falseText)
                Switch(
                    checked = on,
                    onCheckedChange = { value ->
                        editor.persist(editor.settings.withSetting(leaf.setting, SettingValue(if (value) "true" else "false")))
                    },
                    enabled = LocalRowEnabled.current,
                )
                Text(trueText)
            }
        },
    )
}

@Composable
internal fun NumberListRow(key: String, walk: SettingsWalk) {
    if (key == "reconnectDelaysSec") {
        row_reconnectDelaysSec(key, walk)
        return
    }
    val editor = walk.editor
    LineField(stringResource(SettingsTreeGenerated.regTitle(key)), editor.settings.getSetting(key), ready = editor.ready) { text ->
        editor.persist(editor.settings.withSetting(key, SettingValue(text)))
    }
}

@Composable
internal fun PasswordRow(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val drafts = walk.drafts
    when (key) {
        "imapPassword" -> if (drafts != null) PasswordDraftField(editor, drafts)
        "smtpPassword" -> if (drafts != null) SmtpPasswordDraftField(editor, drafts, editor.ready, true)
        "sievePassword" -> {
            if (editor.sievePassword.isEmpty() && !editor.creating && editor.editingId != null) {
                Text(S("Password missing"))
            }
            LineField(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                editor.sievePassword,
                KeyboardType.Password,
                password = true,
                ready = editor.ready,
            ) { editor.noteSievePassword(it) }
        }
    }
}

@Composable
internal fun CheckServerRow(walk: SettingsWalk) {
    val editor = walk.editor
    val drafts = walk.drafts
    TextButton(onClick = {
        val chosen = drafts?.serverDraft ?: editor.settings
        val candidate = drafts?.savedServer(editor, chosen) ?: chosen
        editor.ui.launch {
            val report = try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.dlang.liveimap.engine.LibetpanMailSession.checkServerSettings(candidate)
                }
            } catch (error: Exception) {
                null
            }
            val lines = if (report == null) listOf("server not reachable") else report.conflicts + report.notes
            settingsSaveFailure.value = lines.joinToString("\n").ifEmpty { "ok" }
        }
    }) { Text(stringResource(SettingsTreeGenerated.regTitle("checkServer"))) }
}

@Composable
internal fun ToolbarLeafRow(key: String, walk: SettingsWalk) {
    val screen = when (key) {
        "selectionBar" -> "selection"
        "folderBar" -> "folders"
        "readerToolbar" -> "reader"
        "composeBar" -> "compose"
        else -> "index"
    }
    OpenRow(stringResource(SettingsTreeGenerated.regTitle(key))) { walk.onOpenPanel(screen, "") }
}

@Composable
internal fun AddressBooksRow(walk: SettingsWalk) {
    OpenRow(stringResource(SettingsTreeGenerated.regTitle("addressBooks"))) {
        val id = walk.editor.editingId ?: "_"
        walk.onNavigate("settings/account/$id/editor/addressBooks")
    }
}

@Composable
internal fun SettingsFileRow(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val accountScope = key == "accountSettingsFile"
    var includePasswords by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val body = exportSettingsDocument(
            if (accountScope) editor.settings.encode() else editor.settings.encodeUi(),
            includePasswords && accountScope,
            editor.password,
            editor.smtpPassword,
            editor.sievePassword,
        )
        context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@rememberLauncherForActivityResult
        val error = documentError(text)
        if (error != null) {
            settingsSaveFailure.value = error
            return@rememberLauncherForActivityResult
        }
        val root = parseJson(text) as JsonValue.Obj
        val include = (root.fields["includePasswords"] as? JsonValue.Bool)?.value == true
        val decoded = decodeSettings(text)
        val merged = if (accountScope) editor.settings.withAccountFields(decoded) else editor.settings.withUiOf(decoded)
        editor.persist(merged)
        if (include && accountScope) {
            (root.fields["imapPassword"] as? JsonValue.Str)?.value?.let { editor.persistPassword(it) }
            (root.fields["smtpPassword"] as? JsonValue.Str)?.value?.let { editor.persistSmtpPassword(it) }
            (root.fields["sievePassword"] as? JsonValue.Str)?.value?.let { editor.noteSievePassword(it) }
        }
    }
    Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    if (accountScope) {
        BoolField(S("Include passwords in the export"), includePasswords) { includePasswords = it }
    }
    Row {
        TextButton(onClick = { import.launch(arrayOf("application/json", "text/*", "*/*")) }) {
            Text(S("Import"))
        }
        TextButton(onClick = { export.launch(if (accountScope) "account-settings.json" else "ui-settings.json") }) {
            Text(S("Export"))
        }
    }
}

@Composable
internal fun ChoiceRow(key: String, walk: SettingsWalk) {
    when (key) {
        "tlsMode" -> row_tlsMode(walk)
        "smtpTlsMode" -> row_smtpTlsMode(key, walk)
        "smtpNoAuth" -> row_smtpNoAuth(key, walk)
        "passwordStorage" -> row_passwordStorage(key, walk)
        "sieveTlsMode" -> row_sieveTlsMode(walk)
        "dsnMode" -> row_dsnMode(walk)
        "threadIndexStyle" -> row_threadIndexStyle(walk)
        "inboxStart" -> row_inboxStart(walk)
        "folderStart" -> row_folderStart(walk)
        "startAfterChange" -> row_startAfterChange(walk)
        "pinercStartDefault" -> row_pinercStartDefault(walk)
        "readerDisplay" -> row_readerDisplay(walk)
        "nonAsciiDisplay" -> row_nonAsciiDisplay(walk)
        "deletePolicy" -> row_deletePolicy(walk)
        "saveNameRule" -> row_saveNameRule(walk)
        "theme" -> row_theme(walk)
        "density" -> row_density(walk)
        "dateFormat" -> row_dateFormat(walk)
        "multiPane" -> row_multiPane(walk)
        "folderPlace" -> row_folderPlace(walk)
        "foldPosture" -> row_foldPosture(walk)
        "paneArrangement" -> row_paneArrangement(walk)
        "previewSize" -> row_previewSize(walk)
        "clientSortCollation" -> row_clientSortCollation(key, walk)
        "settingsOpensOn" -> row_settingsOpensOn(walk)
        else -> Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    }
}

@Composable
internal fun NumberRow(key: String, walk: SettingsWalk) {
    when (key) {
        "imapPort" -> row_imapPort(walk)
        "smtpPort" -> row_smtpPort(walk)
        "sievePort" -> row_sievePort(walk)
        "extraIdleBudget" -> row_extraIdleBudget(walk)
        "maxConnections" -> row_maxConnections(walk)
        "askSort" -> row_askSort(walk)
        "askThread" -> row_askThread(walk)
        "askSearch" -> row_askSearch(walk)
        "askFallback" -> row_askFallback(walk)
        "appTextZoom" -> row_appTextZoom(walk)
        "bodyFetchChunk" -> row_bodyFetchChunk(walk)
        "composerWrapColumn" -> row_composerWrapColumn(walk)
        "addressBookHistory" -> row_addressBookHistory(walk)
        "pollSeconds" -> row_pollSeconds(key, walk)
        "idleFallbackSeconds" -> row_idleFallbackSeconds(key, walk)
        "keepAliveMinutes" -> row_keepAliveMinutes(key, walk)
        "idleRenewMinutes" -> row_idleRenewMinutes(key, walk)
        "longSearchProgressSec" -> row_longSearchProgressSec(key, walk)
        "longSearchQuietSec" -> row_longSearchQuietSec(key, walk)
        "maxJmapResponseBytes" -> row_maxJmapResponseBytes(key, walk)
        "idleWakeSeconds" -> row_idleWakeSeconds(walk)
        "trafficLogMaxMb" -> row_trafficLogMaxMb(walk)
        "maxResultUids" -> row_maxResultUids(key, walk)
        "maxRawViewBytes" -> row_maxRawViewBytes(key, walk)
        "maxImageTrustBytes" -> row_maxImageTrustBytes(key, walk)
        "indexPageSize" -> row_indexPageSize(key, walk)
        "swipeThresholdPercent" -> row_swipeThresholdPercent(key, walk)
        "moveChunk" -> row_moveChunk(key, walk)
        "clientSortChunk" -> row_clientSortChunk(key, walk)
        "folderCountFreshSeconds" -> row_folderCountFreshSeconds(walk)
        "androidContactsCacheSeconds" -> row_androidContactsCacheSeconds(walk)
        "sieveConnectSeconds" -> row_sieveConnectSeconds(key, walk)
        "sieveReadSeconds" -> row_sieveReadSeconds(key, walk)
        else -> {
            val editor = walk.editor
            LineField(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                editor.settings.getSetting(key),
                keyboardType = KeyboardType.Number,
                ready = editor.ready,
            ) { text ->
                editor.persist(editor.settings.withSetting(key, SettingValue(text.trim())))
            }
        }
    }
}

@Composable
internal fun TextRow(key: String, walk: SettingsWalk) {
    when (key) {
        "friendlyName" -> row_friendlyName(walk)
        "email" -> row_email(walk)
        "displayName" -> row_displayName(walk)
        "imapHost" -> row_imapHost(walk)
        "username" -> row_username(walk)
        "authMechanism" -> row_authMechanism(walk)
        "clientCertAlias" -> row_clientCertAlias(key, walk)
        "sieveUsername" -> row_sieveUsername(walk)
        "smtpHost" -> row_smtpHost(walk)
        "smtpUsername" -> row_smtpUsername(walk)
        "sieveHost" -> row_sieveHost(walk)
        "signature" -> row_signature(walk)
        "quotePrefix" -> row_quotePrefix(walk)
        "replyLeadIn" -> row_replyLeadIn(walk)
        "composerHeaders" -> row_composerHeaders(walk)
        "viewerHeaders" -> row_viewerHeaders(walk)
        "datePattern" -> row_datePattern(walk)
        "unlabeledCharset" -> row_unlabeledCharset(key, walk)
        "defaultView" -> row_defaultView(key, walk)
        "folderViews" -> row_folderViews(walk)
        "folderStarts" -> row_folderStarts(walk)
        else -> {
            val editor = walk.editor
            LineField(stringResource(SettingsTreeGenerated.regTitle(key)), editor.settings.getSetting(key), ready = editor.ready) { text ->
                editor.persist(editor.settings.withSetting(key, SettingValue(text)))
            }
        }
    }
}

@Composable
internal fun StringListRow(key: String, walk: SettingsWalk) {
    when (key) {
        "altAddresses" -> row_altAddresses(walk)
        "reconnectDelaysSec" -> row_reconnectDelaysSec(key, walk)
        "watchedFolders" -> row_watchedFolders(walk)
        "expandedFolders" -> row_expandedFolders(walk)
        "savedSearches" -> row_savedSearches(key, walk)
        "savedAdvanced" -> row_savedAdvanced(key, walk)
        "hiddenCapabilities" -> row_hiddenCapabilities(walk)
        "sortAskSkip" -> row_sortAskSkip(key, walk)
        "threadAskSkip" -> row_threadAskSkip(key, walk)
        "searchAskSkip" -> row_searchAskSkip(key, walk)
        "fallbackAskSkip" -> row_fallbackAskSkip(key, walk)
        else -> {
            val editor = walk.editor
            LineField(stringResource(SettingsTreeGenerated.regTitle(key)), editor.settings.getSetting(key), ready = editor.ready) { text ->
                editor.persist(editor.settings.withSetting(key, SettingValue(text)))
            }
        }
    }
}

@Composable
internal fun MailboxRow(key: String, walk: SettingsWalk) {
    when (key) {
        "sentMailbox" -> row_sentMailbox(walk)
        "postponedMailbox" -> row_postponedMailbox(walk)
        "addressBookMailbox" -> row_addressBookMailbox(walk)
        "spamMailbox" -> row_spamMailbox(walk)
        "trashMailbox" -> row_trashMailbox(walk)
        "savedMailbox" -> row_savedMailbox(walk)
        "lastSaveMailbox" -> row_lastSaveMailbox(key, walk)
        else -> Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    }
}

@Composable
internal fun PinRow(key: String, walk: SettingsWalk) {
    when (key) {
        "certPin" -> row_certPin(walk)
        "jmapCertPin" -> row_jmapCertPin(walk)
        "sieveCertPin" -> row_sieveCertPin(walk)
        else -> Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    }
}

@Composable
internal fun SubscreenRow(key: String, walk: SettingsWalk) {
    when (key) {
        "watchedFolders" -> row_watchedFolders(walk)
        "sendingRules" -> row_sendingRules(walk)
        "inboundRules" -> row_inboundRules(key, walk)
        "expandedFolders" -> row_expandedFolders(walk)
        "defaultView" -> row_defaultView(key, walk)
        "folderViews" -> row_folderViews(walk)
        "folderStarts" -> row_folderStarts(walk)
        "sortAskSkip" -> row_sortAskSkip(key, walk)
        "threadAskSkip" -> row_threadAskSkip(key, walk)
        "searchAskSkip" -> row_searchAskSkip(key, walk)
        "fallbackAskSkip" -> row_fallbackAskSkip(key, walk)
        "savedSearches" -> row_savedSearches(key, walk)
        "savedAdvanced" -> row_savedAdvanced(key, walk)
        "swipeTrailing" -> row_swipeTrailing(walk)
        "swipeLeading" -> row_swipeLeading(walk)
        "readerSwipeTrailing" -> row_readerSwipeTrailing(walk)
        "readerSwipeLeading" -> row_readerSwipeLeading(walk)
        "completionSources" -> row_completionSources(walk)
        "extraAddressBooks" -> row_extraAddressBooks(walk)
        "globalAddressBooks" -> row_globalAddressBooks(walk)
        "customLayout" -> row_customLayout(walk)
        "hiddenCapabilities" -> row_hiddenCapabilities(walk)
        "favorites" -> row_favorites(walk)
        else -> Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    }
}

@Composable
internal fun row_settingsOpensOn(walk: SettingsWalk) {
    val editor = walk.editor
    val current = editor.settings.settingsOpensOn.ifEmpty { "drawer" }
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("setting_settingsOpensOn_title")),
        listOf("drawer", "lastEdited"),
        current,
        { picked ->
            stringResource(
                if (picked == "lastEdited") SettingsTreeGenerated.resource("setting_settingsOpensOn_lastEdited")
                else SettingsTreeGenerated.resource("setting_settingsOpensOn_drawer"),
            )
        },
    ) { picked ->
        editor.persist(editor.settings.copy(settingsOpensOn = picked))
    }
}

@Composable
internal fun row_folderCountFreshSeconds(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle("folderCountFreshSeconds")),
        settings.folderCountFreshSeconds,
        editor.ready,
        { it in 1..86400 },
    ) { number ->
        editor.persist(editor.settings.copy(folderCountFreshSeconds = number))
    }
}

@Composable
internal fun row_androidContactsCacheSeconds(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle("androidContactsCacheSeconds")),
        settings.androidContactsCacheSeconds,
        editor.ready,
        { it in 0..86400 },
    ) { number ->
        editor.persist(editor.settings.copy(androidContactsCacheSeconds = number))
    }
}

@Composable
internal fun row_friendlyName(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_friendly_name")), settings.friendlyName, ready = editor.ready) {
        editor.persist(editor.settings.copy(friendlyName = it))
    }
}

@Composable
internal fun row_email(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_email")), settings.email, KeyboardType.Email, ready = editor.ready) {
        editor.persist(editor.settings.copy(email = it))
    }
}

@Composable
internal fun row_displayName(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_display_name")), settings.displayName, ready = editor.ready) {
        editor.persist(editor.settings.copy(displayName = it))
    }
}

@Composable
internal fun row_altAddresses(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(
        stringResource(SettingsTreeGenerated.resource("settings_alt_addresses")),
        settings.altAddresses.joinToString(", "),
        ready = editor.ready,
    ) { text ->
        editor.persist(
            editor.settings.copy(altAddresses = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
        )
    }
}

@Composable
internal fun row_jmapFolders(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_mail_access")),
        listOf(false, true),
        settings.jmapFolders,
        { stringResource(if (it) SettingsTreeGenerated.resource("settings_access_jmap") else SettingsTreeGenerated.resource("settings_access_imap")) },
    ) { jmap -> editor.persist(withMailAccess(editor.settings, jmap)) }
}

@Composable
internal fun row_imapHost(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    if (settings.jmapFolders) {
        LineField(stringResource(SettingsTreeGenerated.resource("settings_jmap_host")), server.imapHost, ready = editor.ready) {
            if (drafts != null) drafts.userServerSave(editor, (drafts.serverDraft ?: editor.settings).copy(imapHost = it))
        }
        if (server.imapHost.trim().startsWith("http://", ignoreCase = true)) {
            Text(S("Plain http: your password and mail travel unencrypted."), color = MaterialTheme.colorScheme.error)
        }
    } else {
        LineField(stringResource(SettingsTreeGenerated.resource("settings_imap_host")), server.imapHost, ready = editor.ready) {
            if (drafts != null) drafts.userServerSave(editor, (drafts.serverDraft ?: editor.settings).copy(imapHost = it))
        }
    }
}

@Composable
internal fun row_imapPort(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    PortField(stringResource(SettingsTreeGenerated.resource("settings_imap_port")), server.imapPort, ready = editor.ready) {
        if (drafts != null) drafts.userServerSave(editor, (drafts.serverDraft ?: editor.settings).copy(imapPort = it))
    }
}

@Composable
internal fun row_username(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    LineField(stringResource(SettingsTreeGenerated.resource("settings_username")), settings.username, ready = editor.ready) { draft ->
        editor.persist(
            editor.settings.copy(
                username = draft,
                email = emailDefaultedFromUsername(draft, editor.settings.email),
            ),
        )
    }
}

@Composable
internal fun row_tlsMode(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_tls")),
        TlsMode.entries,
        server.tlsMode,
        { tlsName(it) },
    ) { mode ->
        if (drafts != null) drafts.userServerSave(editor, server.withTlsMode(mode).withoutPreserved("tlsMode"))
    }
}

@Composable
internal fun row_allowPlaintextAuth(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    BoolField(
        S("Allow a clear-text password"),
        server.allowPlaintextAuth,
        note = S("On a connection that is not encrypted, LiveIMAP asks before sending the password. SCRAM and CRAM-MD5 do not ask."),
    ) { enabled ->
        if (drafts != null) drafts.userServerSave(editor, server.copy(allowPlaintextAuth = enabled))
    }
}

@Composable
internal fun row_acceptInvalidCert(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_accept_invalid_cert")),
        server.acceptInvalidCert,
        note = S("Warning: connects even when the server certificate fails validation (wrong name, expired, untrusted or changed), like pine /novalidate-cert. Anyone on the network path can then read your mail and password. Off by default."),
    ) { enabled ->
        if (drafts != null) drafts.userServerSave(editor, server.copy(acceptInvalidCert = enabled))
    }
}

@Composable
internal fun row_allowOldTls(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    BoolField(
        S("Allow old TLS and weak keys", name = "settings_allow_old_tls"),
        server.allowOldTls,
        note = S("Warning: allows TLS 1.0 and 1.1, small Diffie-Hellman keys and legacy ciphers for this account only. These can be broken; use only for an old server you cannot upgrade. Off by default."),
    ) { enabled ->
        if (drafts != null) drafts.userServerSave(editor, server.copy(allowOldTls = enabled))
    }
}

@Composable
internal fun row_authMechanism(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    LineField(S("Login method (blank = automatic; IMAP-LOGIN for the LOGIN command; PLAIN, LOGIN, CRAM-MD5, SCRAM-SHA-256…)"), settings.authMechanism, ready = editor.ready) {
        editor.persist(editor.settings.copy(authMechanism = it.trim().uppercase()))
    }
}

@Composable
internal fun row_clientCert(walk: SettingsWalk) {
    val editor = walk.editor
    ClientCertField(editor)
}

@Composable
internal fun row_clientCertAlias(key: String, walk: SettingsWalk) {
    ClientCertField(walk.editor)
}

@Composable
internal fun row_certPin(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    if (settings.certPin.isBlank()) {
        Text(S("No certificate is pinned."))
    } else {
        Text(settings.certPin)
        TextButton(onClick = { editor.persist(editor.settings.copy(certPin = "")) }) {
            Text(S("Clear pin"))
        }
    }
}

@Composable
internal fun row_jmapCertPin(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    if (settings.jmapCertPin.isBlank()) {
        Text(S("No JMAP certificate is pinned."))
    } else {
        Text(settings.jmapCertPin)
        TextButton(onClick = { editor.persist(editor.settings.copy(jmapCertPin = "")) }) {
            Text(S("Clear JMAP pin"))
        }
    }
}

@Composable
internal fun row_pipelineCommands(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_pipeline")), settings.pipelineCommands) {
        editor.persist(editor.settings.copy(pipelineCommands = it))
    }
}

@Composable
internal fun row_emailSubmission(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_send_over_jmap")),
        settings.emailSubmission,
        note = S("Off uses SMTP."),
    ) { enabled ->
        editor.persist(editor.settings.copy(emailSubmission = enabled))
    }
}

@Composable
internal fun row_smtpHost(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_smtp_host")), settings.smtpHost, ready = editor.ready) {
        editor.persist(editor.settings.copy(smtpHost = it))
    }
}

@Composable
internal fun row_smtpPort(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    PortField(stringResource(SettingsTreeGenerated.resource("settings_smtp_port")), settings.smtpPort, ready = editor.ready) {
        editor.persist(editor.settings.copy(smtpPort = it))
    }
}

@Composable
internal fun row_smtpTlsMode(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        TlsMode.entries,
        settings.smtpTlsMode,
        { tlsName(it) },
    ) { mode ->
        editor.persist(editor.settings.copy(smtpTlsMode = mode))
    }
}

@Composable
internal fun row_smtpUsername(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val authOn = settings.smtpNoAuth == SmtpNoAuth.On
    Text(S("Blank uses the IMAP username."))
    LineField(
        stringResource(SettingsTreeGenerated.resource("settings_smtp_username")),
        settings.smtpUsername,
        ready = editor.ready && !authOn,
        enabled = !authOn,
    ) {
        editor.persist(editor.settings.copy(smtpUsername = it))
    }
}

@Composable
internal fun row_smtpSameAsImap(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val authOn = settings.smtpNoAuth == SmtpNoAuth.On
    BoolField(
        S("Same as IMAP password", name = "settings_smtp_same_as_imap"),
        settings.smtpSameAsImap,
        enabled = !authOn,
    ) {
        editor.persist(editor.settings.copy(smtpSameAsImap = it))
    }
}

@Composable
internal fun row_smtpNoAuth(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        SmtpNoAuth.entries,
        settings.smtpNoAuth,
        { choice ->
            stringResource(
                when (choice) {
                    SmtpNoAuth.On -> SettingsTreeGenerated.resource("setting_smtpNoAuth_on")
                    SmtpNoAuth.Off -> SettingsTreeGenerated.resource("setting_smtpNoAuth_off")
                },
            )
        },
    ) { choice ->
        editor.persist(editor.settings.copy(smtpNoAuth = choice))
    }
}

@Composable
internal fun row_sieveSameAsInbound(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_sieve_same_as_inbound")), settings.sieveSameAsInbound) {
        editor.persist(editor.settings.copy(sieveSameAsInbound = it))
    }
}

@Composable
internal fun row_sieveUsername(walk: SettingsWalk) {
    val editor = walk.editor
    LineField(stringResource(SettingsTreeGenerated.resource("settings_sieve_username")), editor.settings.sieveUsername, ready = editor.ready) {
        editor.persist(editor.settings.copy(sieveUsername = it))
    }
}

@Composable
internal fun row_passwordStorage(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        org.dlang.liveimap.settings.PasswordStorageChoice.entries,
        settings.passwordStorage,
        { choice ->
            stringResource(
                when (choice) {
                    org.dlang.liveimap.settings.PasswordStorageChoice.googleRestore -> SettingsTreeGenerated.resource("password_storage_google_restore")
                    org.dlang.liveimap.settings.PasswordStorageChoice.none -> SettingsTreeGenerated.resource("password_storage_none")
                    org.dlang.liveimap.settings.PasswordStorageChoice.passwordManager -> SettingsTreeGenerated.resource("password_storage_manager")
                },
            )
        },
    ) { choice ->
        editor.persist(editor.settings.copy(passwordStorage = choice))
    }
    val status = org.dlang.liveimap.accounts.PasswordStorage.status(
        editor.appContext,
        editor.editingId ?: "new",
        settings.passwordStorage.name,
    )
    if (status is org.dlang.liveimap.accounts.BackupAvailability.Unavailable) {
        val label = when (status.reason) {
            org.dlang.liveimap.accounts.BackupReason.NoPlay -> S("Google restore isn't available in this build; passwords are not backed up")
            org.dlang.liveimap.accounts.BackupReason.NoLock -> S("Google restore needs a screen lock")
            org.dlang.liveimap.accounts.BackupReason.NoManager -> S("No password manager is available")
            org.dlang.liveimap.accounts.BackupReason.TooLarge -> S("The password backup is too large")
            org.dlang.liveimap.accounts.BackupReason.TooMany -> S("Too many password backups")
        }
        Text(label)
    }
}

@Composable
internal fun row_sieveHost(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_sieve_host")), settings.sieveHost, ready = editor.ready) {
        editor.persist(editor.settings.copy(sieveHost = it))
    }
}

@Composable
internal fun row_sievePort(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    PortField(stringResource(SettingsTreeGenerated.resource("settings_sieve_port")), settings.sievePort, ready = editor.ready) {
        editor.persist(editor.settings.copy(sievePort = it))
    }
}

@Composable
internal fun row_sieveTlsMode(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_sieve_tls")),
        TlsMode.entries,
        settings.sieveTlsMode,
        { tlsName(it) },
    ) { mode ->
        editor.persist(editor.settings.copy(sieveTlsMode = mode).withoutPreserved("sieveTlsMode"))
    }
}

@Composable
internal fun row_sieveCertPin(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    if (settings.sieveCertPin.isBlank()) {
        Text(S("No Sieve certificate is pinned."))
    } else {
        Text(settings.sieveCertPin)
        TextButton(onClick = { editor.persist(editor.settings.copy(sieveCertPin = "")) }) {
            Text(S("Clear Sieve pin"))
        }
    }
    if (drafts != null) SieveCheckButton(editor, drafts)
}

@Composable
internal fun row_watchedFolders(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    Text(S("Extra folders besides the open folder."))
    LineField(
        label = S("Extra folders"),
        value = watchedFolderText(settings.watchedFolders),
        singleLine = false,
        ready = editor.ready,
        interpret = { draft, stored ->
            val next = watchedFolderText(watchedFoldersFromText(draft))
            if (next == stored) null else next
        },
        onCommit = { text ->
            editor.persist(editor.settings.copy(watchedFolders = watchedFoldersFromText(text)))
        },
    )
}

@Composable
internal fun row_extraIdleBudget(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(
        label = stringResource(SettingsTreeGenerated.resource("settings_extra_idle_budget")),
        value = settings.extraIdleBudget.toString(),
        ready = editor.ready,
        interpret = { draft, _ -> extraIdleBudgetFromText(draft)?.toString() },
        onCommit = { text ->
            val budget = extraIdleBudgetFromText(text) ?: return@LineField
            editor.persist(editor.settings.copy(extraIdleBudget = budget))
        },
    )
}

@Composable
internal fun row_maxConnections(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(
        label = stringResource(SettingsTreeGenerated.resource("settings_max_connections")),
        value = settings.maxConnections.toString(),
        ready = editor.ready,
        interpret = { draft, _ -> org.dlang.liveimap.session.maxConnectionsFromText(draft)?.toString() },
        onCommit = { text ->
            val limit = org.dlang.liveimap.session.maxConnectionsFromText(text) ?: return@LineField
            editor.persist(editor.settings.copy(maxConnections = limit))
        },
    )
}

@Composable
internal fun row_sendingRules(walk: SettingsWalk) {
    val editor = walk.editor
    SendingRulesRows(editor)
}

@Composable
internal fun row_signature(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_signature")), settings.signature, singleLine = false, ready = editor.ready) {
        editor.persist(editor.settings.copy(signature = it))
    }
}

@Composable
internal fun row_sigDashes(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_sig_dashes")), settings.sigDashes) {
        editor.persist(editor.settings.copy(sigDashes = it))
    }
}

@Composable
internal fun row_sigAtBottom(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_sig_bottom")), settings.sigAtBottom) {
        editor.persist(editor.settings.copy(sigAtBottom = it))
    }
}

@Composable
internal fun row_quotePrefix(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(
        S("Reply quote prefix", name = "settings_quote_prefix"),
        settings.quotePrefix,
        ready = editor.ready,
        interpret = { draft, _ -> draft },
    ) {
        editor.persist(editor.settings.copy(quotePrefix = it))
    }
}

@Composable
internal fun row_replyLeadIn(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_lead_in")), settings.replyLeadIn, ready = editor.ready) {
        editor.persist(editor.settings.copy(replyLeadIn = it.ifBlank { org.dlang.liveimap.ui.compose.DefaultLeadIn }))
    }
}

@Composable
internal fun row_dsnMode(walk: SettingsWalk) {
    val editor = walk.editor
    DsnChoices(editor)
}

@Composable
internal fun row_composerHeaders(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_composer_headers")), settings.composerHeaders, ready = editor.ready) {
        editor.persist(editor.settings.copy(composerHeaders = it))
    }
}

@Composable
internal fun row_inboundRules(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    CountLeaf(key, settings.inboundRules.size)
}

@Composable
internal fun row_sentMailbox(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.resource("settings_sent_mailbox")), settings.sentMailbox, { walk.picking.value = MailboxPick.Sent }) {
        editor.persist(editor.settings.copy(sentMailbox = it))
    }
}

@Composable
internal fun row_postponedMailbox(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.resource("settings_postponed_mailbox")), settings.postponedMailbox, { walk.picking.value = MailboxPick.Postponed }) {
        editor.persist(editor.settings.copy(postponedMailbox = it))
    }
}
@Composable
internal fun row_addressBookMailbox(walk: SettingsWalk) { AddressBooksRow(walk) }


@Composable
internal fun row_spamMailbox(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.resource("settings_spam_mailbox")), settings.spamMailbox, { walk.picking.value = MailboxPick.Spam }) {
        editor.persist(editor.settings.copy(spamMailbox = it))
    }
}

@Composable
internal fun row_trashMailbox(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.resource("settings_trash_mailbox")), settings.trashMailbox, { walk.picking.value = MailboxPick.Trash }) {
        editor.persist(editor.settings.copy(trashMailbox = it))
    }
}

@Composable
internal fun row_showUnreadCounts(walk: SettingsWalk) {
    val editor = walk.editor
    ShowUnreadLeaf(editor)
}

@Composable
internal fun row_expandedFolders(walk: SettingsWalk) {

    val editor = walk.editor
    Text(stringResource(SettingsTreeGenerated.resource("settings_expanded_folders")))
    ExpandedFoldersBody(editor) { walk.drafts }
}

@Composable
internal fun row_defaultView(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_default_view")),
        SortKey.entries,
        settings.defaultView.key,
        { sortKeyName(it) },
    ) { sort ->
        editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(key = sort)))
    }
    BoolField(stringResource(SettingsTreeGenerated.resource("reader_newest")), settings.defaultView.newestFirst) { newest ->
        editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(newestFirst = newest)))
    }
}

@Composable
internal fun row_folderViews(walk: SettingsWalk) {

    val editor = walk.editor
    Text(stringResource(SettingsTreeGenerated.resource("settings_folder_views")))
    FolderViewsBody(editor) { walk.drafts }
}

@Composable
internal fun row_threadIndexStyle(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_thread_index")),
        ThreadIndexStyle.entries,
        settings.threadIndexStyle,
        { threadIndexStyleName(it) },
    ) { style ->
        editor.persist(editor.settings.copy(threadIndexStyle = style))
    }
}

@Composable
internal fun row_inboxStart(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    StartRuleField(stringResource(SettingsTreeGenerated.resource("settings_inbox_opens")), settings.inboxStart, settings.showRecentRules) { rule ->
        editor.persist(editor.settings.copy(inboxStart = rule))
    }
}

@Composable
internal fun row_folderStart(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    StartRuleField(stringResource(SettingsTreeGenerated.resource("settings_other_opens")), settings.folderStart, settings.showRecentRules) { rule ->
        editor.persist(editor.settings.copy(folderStart = rule))
    }
}

@Composable
internal fun row_folderStarts(walk: SettingsWalk) {

    val editor = walk.editor
    Text(S("Start position per folder", name = "settings_start_per_folder"))
    FolderStartsBody(editor)
}

@Composable
internal fun row_startAfterChange(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_after_change")),
        StartAfterChange.entries,
        settings.startAfterChange,
        { startAfterChangeName(it) },
    ) { value ->
        editor.persist(editor.settings.copy(startAfterChange = value))
    }
}

@Composable
internal fun row_showRecentRules(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_show_recent")), settings.showRecentRules) { enabled ->
        editor.persist(editor.settings.copy(showRecentRules = enabled))
    }
}

@Composable
internal fun row_openAtInIndexMenu(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_open_at_menu")), settings.openAtInIndexMenu) { enabled ->
        editor.persist(editor.settings.copy(openAtInIndexMenu = enabled))
    }
}

@Composable
internal fun row_pinercStartDefault(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        S("When a pinerc has no incoming-startup-rule", name = "settings_pinerc_missing"),
        PinercStartDefault.entries,
        settings.pinercStartDefault,
        { pinercStartDefaultName(it) },
    ) { value ->
        editor.persist(editor.settings.copy(pinercStartDefault = value))
    }
}

@Composable
internal fun row_askSort(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    Text(stringResource(SettingsTreeGenerated.resource("settings_ask_note")))
    AskLimitField(stringResource(SettingsTreeGenerated.resource("settings_ask_sort")), settings.askSort, editor.ready) { limit ->
        editor.persist(editor.settings.copy(askSort = limit))
    }
}

@Composable
internal fun row_askThread(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    AskLimitField(stringResource(SettingsTreeGenerated.resource("settings_ask_thread")), settings.askThread, editor.ready) { limit ->
        editor.persist(editor.settings.copy(askThread = limit))
    }
}

@Composable
internal fun row_askSearch(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    AskLimitField(stringResource(SettingsTreeGenerated.resource("settings_ask_search")), settings.askSearch, editor.ready) { limit ->
        editor.persist(editor.settings.copy(askSearch = limit))
    }
}

@Composable
internal fun row_askFallback(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    AskLimitField(stringResource(SettingsTreeGenerated.resource("settings_ask_fallback")), settings.askFallback, editor.ready) { limit ->
        editor.persist(editor.settings.copy(askFallback = limit))
    }
}

@Composable
internal fun row_sortAskSkip(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    SkipSetField(key, settings.sortAskSkip, editor.ready) {
        editor.persist(editor.settings.copy(sortAskSkip = it))
    }
}

@Composable
internal fun row_threadAskSkip(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    SkipSetField(key, settings.threadAskSkip, editor.ready) {
        editor.persist(editor.settings.copy(threadAskSkip = it))
    }
}

@Composable
internal fun row_searchAskSkip(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    SkipSetField(key, settings.searchAskSkip, editor.ready) {
        editor.persist(editor.settings.copy(searchAskSkip = it))
    }
}

@Composable
internal fun row_fallbackAskSkip(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    SkipSetField(key, settings.fallbackAskSkip, editor.ready) {
        editor.persist(editor.settings.copy(fallbackAskSkip = it))
    }
}

@Composable
internal fun row_savedSearches(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    CountLeaf(key, settings.savedSearches.size)
}

@Composable
internal fun row_savedAdvanced(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    CountLeaf(key, settings.savedAdvanced.size)
}

@Composable
internal fun row_markSeenOnOpen(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_mark_seen")), settings.markSeenOnOpen) {
        editor.persist(editor.settings.copy(markSeenOnOpen = it))
    }
}

@Composable
internal fun row_readerDisplay(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("setting_bodyView_title")),
        org.dlang.liveimap.ui.reader.ReaderDisplay.entries,
        settings.readerDisplay,
        { org.dlang.liveimap.ui.reader.readerDisplayName(it) },
    ) { display ->
        editor.persist(editor.settings.copy(readerDisplay = display))
    }
}

@Composable
internal fun row_appTextZoom(walk: SettingsWalk) {
    val editor = walk.editor
    NumberLeaf(
        stringResource(SettingsTreeGenerated.resource("setting_appTextZoom_title")),
        editor.settings.appTextZoom,
        editor.ready,
        { it in 50..300 },
    ) { number ->
        editor.persist(editor.settings.copy(appTextZoom = clampTextZoom(number)))
    }
}

@Composable
internal fun row_bodyFetchChunk(walk: SettingsWalk) {
    val editor = walk.editor
    NumberLeaf(
        stringResource(SettingsTreeGenerated.resource("setting_bodyFetchChunk_title")),
        editor.settings.bodyFetchChunk,
        editor.ready,
        { it in 1024..1048576 },
    ) { number ->
        editor.persist(editor.settings.copy(bodyFetchChunk = number.coerceIn(1024, 1048576)))
    }
}

@Composable
internal fun row_viewerHeaders(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_viewer_headers")), settings.viewerHeaders, ready = editor.ready) {
        editor.persist(editor.settings.copy(viewerHeaders = it))
    }
}

@Composable
internal fun row_nonAsciiDisplay(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_non_ascii")),
        NonAsciiDisplay.entries,
        settings.nonAsciiDisplay,
        { stringResource(when (it) {
            NonAsciiDisplay.Normal -> SettingsTreeGenerated.resource("settings_non_ascii_normal")
            NonAsciiDisplay.HighlightSuspicious -> SettingsTreeGenerated.resource("settings_non_ascii_suspicious")
            NonAsciiDisplay.HighlightAll -> SettingsTreeGenerated.resource("settings_non_ascii_all")
            NonAsciiDisplay.Warn -> SettingsTreeGenerated.resource("settings_non_ascii_warn")
            NonAsciiDisplay.Escape -> SettingsTreeGenerated.resource("settings_non_ascii_escape")
        }) },
    ) {
        editor.persist(editor.settings.copy(nonAsciiDisplay = it))
    }
}

@Composable
internal fun row_showDeleted(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_show_deleted")), settings.showDeleted) {
        editor.persist(editor.settings.copy(showDeleted = it))
    }
}

@Composable
internal fun row_autoExpunge(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_auto_expunge")),
        settings.autoExpunge,
        note = S("Leaving a folder expunges deleted messages. Off sends no CLOSE."),
    ) {
        editor.persist(editor.settings.copy(autoExpunge = it))
    }
}

@Composable
internal fun row_askBeforeExpunge(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_ask_expunge")), settings.askBeforeExpunge) {
        editor.persist(editor.settings.copy(askBeforeExpunge = it))
    }
}

@Composable
internal fun row_deletePolicy(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.regTitle("deletePolicy")),
        DeletePolicy.entries,
        settings.deletePolicy,
        { deletePolicyName(it) },
    ) { policy ->
        editor.persist(editor.settings.copy(deletePolicy = policy).withoutPreserved("deletePolicy"))
    }
}
@Composable
internal fun row_moveMethod(walk: SettingsWalk) { Unit }


@Composable
internal fun row_moveAllIncludesDeleted(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        S("Move entire folder includes deleted messages", name = "settings_move_all_deleted"),
        settings.moveAllIncludesDeleted,
        note = S("Off: messages already marked deleted stay behind (UID SEARCH UNDELETED)."),
    ) {
        editor.persist(editor.settings.copy(moveAllIncludesDeleted = it))
    }
}
@Composable
internal fun row_compression(walk: SettingsWalk) { Unit }


@Composable
internal fun row_swipeTrailing(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    SwipeEditor(
        if (leftToRight) S("Swipe left") else S("Swipe right"),
        settings.swipeTrailing,
        { walk.picking.value = MailboxPick.TrailingMove },
        SwipeAction.entries.filter { it != SwipeAction.Next && it != SwipeAction.Previous },
    ) { editor.persist(editor.settings.copy(swipeTrailing = it)) }
}

@Composable
internal fun row_swipeLeading(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    SwipeEditor(
        if (leftToRight) S("Swipe right") else S("Swipe left"),
        settings.swipeLeading,
        { walk.picking.value = MailboxPick.LeadingMove },
        SwipeAction.entries.filter { it != SwipeAction.Next && it != SwipeAction.Previous },
    ) { editor.persist(editor.settings.copy(swipeLeading = it)) }
}

@Composable
internal fun row_readerSwipeTrailing(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    SwipeEditor(
        if (leftToRight) S("Message swipe left") else S("Message swipe right"),
        settings.readerSwipeTrailing,
        { walk.picking.value = MailboxPick.ReaderTrailingMove },
        SwipeAction.entries,
    ) { editor.persist(editor.settings.copy(readerSwipeTrailing = it)) }
}

@Composable
internal fun row_readerSwipeLeading(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    SwipeEditor(
        if (leftToRight) S("Message swipe right") else S("Message swipe left"),
        settings.readerSwipeLeading,
        { walk.picking.value = MailboxPick.ReaderLeadingMove },
        SwipeAction.entries,
    ) { editor.persist(editor.settings.copy(readerSwipeLeading = it)) }
}

@Composable
internal fun row_bounceFcc(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_bounce_fcc")), settings.bounceFcc) {
        editor.persist(editor.settings.copy(bounceFcc = it))
    }
}

@Composable
internal fun row_composerWrapColumn(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.resource("settings_wrap_column")),
        settings.composerWrapColumn,
        editor.ready,
        { it in 0..998 },
    ) { number ->
        editor.persist(editor.settings.copy(composerWrapColumn = number))
    }
}

@Composable
internal fun row_quellFlowed(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_flowed_text")),
        !settings.quellFlowed,
        note = S("Off sends fixed lines."),
    ) { enabled ->
        editor.persist(editor.settings.copy(quellFlowed = !enabled))
    }
}

@Composable
internal fun row_replyAboveQuote(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_reply_above")), settings.replyAboveQuote) {
        editor.persist(editor.settings.copy(replyAboveQuote = it))
    }
}

@Composable
internal fun row_includeForwardAttachments(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_include_attachments")), settings.includeForwardAttachments) {
        editor.persist(editor.settings.copy(includeForwardAttachments = it))
    }
}

@Composable
internal fun row_forwardAsAttachment(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_forward_attachment")), settings.forwardAsAttachment) {
        editor.persist(editor.settings.copy(forwardAsAttachment = it))
    }
}

@Composable
internal fun row_savedMailbox(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.resource("settings_saved_mailbox")), settings.savedMailbox, { walk.picking.value = MailboxPick.Saved }) {
        editor.persist(editor.settings.copy(savedMailbox = it))
    }
}

@Composable
internal fun row_saveNameRule(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_save_rule")),
        SaveNameRule.entries,
        settings.saveNameRule,
        { saveNameRuleName(it) },
    ) { rule ->
        editor.persist(editor.settings.copy(saveNameRule = rule))
    }
}

@Composable
internal fun row_lastSaveMailbox(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    MailboxLine(stringResource(SettingsTreeGenerated.regTitle(key)), settings.lastSaveMailbox, { walk.picking.value = MailboxPick.LastSave }) {
        editor.persist(editor.settings.copy(lastSaveMailbox = it))
    }
}
@Composable
internal fun row_completionSources(walk: SettingsWalk) { AddressBooksRow(walk) }


@Composable
internal fun row_addressBookHistory(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.resource("settings_history_label")),
        settings.addressBookHistory,
        editor.ready,
        { true },
    ) { number ->
        editor.persist(editor.settings.copy(addressBookHistory = number))
    }
}

@Composable
internal fun row_addressBookNeverTrim(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_never_trim")), settings.addressBookNeverTrim) {
        editor.persist(editor.settings.copy(addressBookNeverTrim = it))
    }
}
@Composable
internal fun row_extraAddressBooks(walk: SettingsWalk) { AddressBooksRow(walk) }

@Composable
internal fun row_globalAddressBooks(walk: SettingsWalk) { AddressBooksRow(walk) }


@Composable
internal fun row_theme(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(stringResource(SettingsTreeGenerated.resource("settings_theme")), ThemeMode.entries, settings.theme, { themeName(it) }) {
        editor.persist(editor.settings.copy(theme = it))
    }
}

@Composable
internal fun row_dynamicColor(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_dynamic_color")), settings.dynamicColor) {
        editor.persist(editor.settings.copy(dynamicColor = it))
    }
}

@Composable
internal fun row_density(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(stringResource(SettingsTreeGenerated.resource("settings_density")), Density.entries, settings.density, { densityName(it) }) {
        editor.persist(editor.settings.copy(density = it))
    }
}

@Composable
internal fun row_dateFormat(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_date_format")),
        DateFormat.entries,
        settings.dateFormat,
        { dateFormatName(it) },
    ) {
        editor.persist(editor.settings.copy(dateFormat = it))
    }
}

@Composable
internal fun row_datePattern(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    LineField(stringResource(SettingsTreeGenerated.resource("settings_date_pattern")), settings.datePattern, ready = editor.ready) {
        editor.persist(editor.settings.copy(datePattern = it))
    }
}

@Composable
internal fun row_customLayout(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val panesOn = paneControlsEnabled(settings.multiPane)
    PaneLayoutEditor(settings.customLayout, persist = panesOn) { text ->
        if (panesOn) editor.persist(editor.settings.copy(customLayout = text))
    }
}

@Composable
internal fun row_multiPane(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_multi_pane")),
        MultiPane.entries,
        settings.multiPane,
        { multiPaneName(it) },
    ) {
        editor.persist(editor.settings.copy(multiPane = it))
    }
}

@Composable
internal fun row_folderPlace(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val panesOn = paneControlsEnabled(settings.multiPane)
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_folder_pane")),
        FolderPlace.entries,
        settings.folderPlace,
        { place ->
            stringResource(when (place) {
                FolderPlace.Off -> SettingsTreeGenerated.resource("settings_multi_pane_off")
                FolderPlace.Auto -> SettingsTreeGenerated.resource("settings_pane_auto")
                FolderPlace.Beside -> SettingsTreeGenerated.resource("settings_folder_beside")
                FolderPlace.AboveList -> SettingsTreeGenerated.resource("settings_folder_above")
            })
        },
        enabled = panesOn,
    ) {
        if (panesOn) editor.persist(editor.settings.copy(folderPlace = it))
    }
}

@Composable
internal fun row_foldPosture(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val panesOn = paneControlsEnabled(settings.multiPane)
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_fold_posture")),
        listOf(LayoutChoice.Off, LayoutChoice.On),
        settings.foldPosture,
        { layoutChoiceName(it) },
        enabled = panesOn,
    ) {
        if (panesOn) editor.persist(editor.settings.copy(foldPosture = it))
    }
}

@Composable
internal fun row_fullScreen(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_full_screen")), settings.fullScreen) {
        editor.persist(editor.settings.copy(fullScreen = it))
    }
}

@Composable
internal fun row_paneArrangement(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val panesOn = paneControlsEnabled(settings.multiPane)
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_pane_arrangement")),
        ArrangementChoice.entries,
        settings.paneArrangement,
        { stringResource(when (it) {
            ArrangementChoice.Auto -> SettingsTreeGenerated.resource("settings_pane_auto")
            ArrangementChoice.SideBySide -> SettingsTreeGenerated.resource("settings_pane_side_by_side")
            ArrangementChoice.Stacked -> SettingsTreeGenerated.resource("settings_pane_stacked")
        }) },
        enabled = panesOn,
    ) {
        if (panesOn) editor.persist(editor.settings.copy(paneArrangement = it))
    }
}

@Composable
internal fun row_previewSize(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.resource("settings_preview_size")),
        PreviewChoice.entries,
        settings.previewSize,
        { stringResource(when (it) {
            PreviewChoice.Auto -> SettingsTreeGenerated.resource("settings_pane_auto")
            PreviewChoice.Density -> SettingsTreeGenerated.resource("settings_preview_density")
            PreviewChoice.None -> SettingsTreeGenerated.resource("settings_preview_none")
            PreviewChoice.Small -> SettingsTreeGenerated.resource("settings_preview_small")
            PreviewChoice.Large -> SettingsTreeGenerated.resource("settings_preview_large")
        }) },
    ) {
        editor.persist(editor.settings.copy(previewSize = it))
    }
}

@Composable
internal fun row_trafficLogMaxMb(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.resource("setting_trafficLogMaxMb_title")),
        settings.trafficLogMaxMb,
        editor.ready,
        { it in 0..64 },
    ) { number ->
        editor.persist(editor.settings.copy(trafficLogMaxMb = number))
    }
    if (settings.trafficLogMaxMb == 0) Text(S("No limit"))
}

@Composable
internal fun row_clientSort(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_client_sort")),
        settings.clientSort,
        note = S("Downloads one field for every message."),
    ) {
        editor.persist(editor.settings.copy(clientSort = it))
    }
}

@Composable
internal fun row_clientSortCollation(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        listOf(ClientSortCollation.Locale, ClientSortCollation.Plain),
        settings.clientSortCollation,
        { clientSortCollationWord(it) },
    ) {
        editor.persist(editor.settings.copy(clientSortCollation = it))
    }
}

@Composable
internal fun row_unlabeledCharset(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    LineField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.unlabeledCharset,
        ready = editor.ready,
        interpret = { draft, stored ->
            if (!knownUnlabeledCharset(draft) || draft == stored) null else draft
        },
    ) { text ->
        if (knownUnlabeledCharset(text)) editor.persist(editor.settings.copy(unlabeledCharset = text))
    }
}

@Composable
internal fun row_clientThread(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_client_thread")),
        settings.clientThread,
        note = S("Downloads threading headers for every message."),
    ) {
        editor.persist(editor.settings.copy(clientThread = it))
    }
}

@Composable
internal fun row_pollForNewMail(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_poll_mail")),
        settings.pollForNewMail,
        note = S("Asks the server on a timer when it has no IDLE."),
    ) {
        editor.persist(editor.settings.copy(pollForNewMail = it))
    }
}

@Composable
internal fun row_pollSeconds(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.pollSeconds,
        editor.ready,
        { it in 15..600 },
    ) { number ->
        editor.persist(editor.settings.copy(pollSeconds = number))
    }
}

@Composable
internal fun row_idleFallbackSeconds(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.idleFallbackSeconds,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(idleFallbackSeconds = number))
    }
}

@Composable
internal fun row_keepAliveMinutes(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.keepAliveMinutes,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(keepAliveMinutes = number))
    }
}

@Composable
internal fun row_idleRenewMinutes(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.idleRenewMinutes,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(idleRenewMinutes = number))
    }
}

@Composable
internal fun row_longSearchProgressSec(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.longSearchProgressSec,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(longSearchProgressSec = number))
    }
}

@Composable
internal fun row_longSearchQuietSec(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.longSearchQuietSec,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(longSearchQuietSec = number))
    }
}

@Composable
internal fun row_maxJmapResponseBytes(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.maxJmapResponseBytes,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(maxJmapResponseBytes = number))
    }
}

@Composable
internal fun row_statusVisibleCounts(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        S("Folder message counts"),
        settings.statusVisibleCounts,
        note = S("Sends STATUS for each visible folder when the server has no LIST-STATUS."),
    ) {
        editor.persist(editor.settings.copy(statusVisibleCounts = it))
    }
}

@Composable
internal fun row_forceSlowerFallbacks(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(
        stringResource(SettingsTreeGenerated.resource("settings_force_slower")),
        settings.forceSlowerFallbacks,
        note = S("Debug only. Uses the slower fallbacks."),
    ) {
        editor.persist(editor.settings.copy(forceSlowerFallbacks = it))
    }
}

@Composable
internal fun row_logImapTraffic(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_log_traffic")), settings.logImapTraffic) {
        editor.persist(editor.settings.copy(logImapTraffic = it))
    }
}

@Composable
internal fun row_showUserInDebugReport(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    BoolField(stringResource(SettingsTreeGenerated.resource("settings_show_user")), settings.showUserInDebugReport) {
        editor.persist(editor.settings.copy(showUserInDebugReport = it))
    }
}

@Composable
internal fun row_idleWakeSeconds(walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    ChoiceField(
        S("IDLE wake interval"),
        IdleWakeSeconds,
        settings.idleWakeSeconds.takeIf { it in IdleWakeSeconds } ?: 5,
        { "$it s" },
    ) { editor.persist(editor.settings.copy(idleWakeSeconds = it)) }
}

@Composable
internal fun row_hiddenCapabilities(walk: SettingsWalk) {
    HiddenOptionsEditor(walk.editor)
}


@Composable
internal fun row_favorites(walk: SettingsWalk) {

    val editor = walk.editor
    val settings = editor.settings
    for (favorite in settings.favorites) {
        val shown = if (favorite.label.isNotBlank()) {
            favorite.label
        } else {
            favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
        }
        ListItem(
            headlineContent = { Text(shown) },
            trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { walk.onEditFavorite(favorite) },
        )
    }
}

@Composable
internal fun row_maxResultUids(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.maxResultUids,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(maxResultUids = number))
    }
}

@Composable
internal fun row_maxRawViewBytes(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.maxRawViewBytes.toInt(),
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(maxRawViewBytes = number.toLong()))
    }
}

@Composable
internal fun row_maxImageTrustBytes(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.maxImageTrustBytes.toInt(),
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(maxImageTrustBytes = number.toLong()))
    }
}

@Composable
internal fun row_indexPageSize(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.indexPageSize,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(indexPageSize = number))
    }
}

@Composable
internal fun row_swipeThresholdPercent(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.swipeThresholdPercent,
        editor.ready,
        { it in 1..99 },
    ) { number ->
        editor.persist(editor.settings.copy(swipeThresholdPercent = number))
    }
}

@Composable
internal fun row_moveChunk(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.moveChunk,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(moveChunk = number))
    }
}

@Composable
internal fun row_clientSortChunk(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.clientSortChunk,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(clientSortChunk = number))
    }
}

@Composable
internal fun row_reconnectDelaysSec(key: String, walk: SettingsWalk) {

    val editor = walk.editor
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LineField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        editor.settings.reconnectDelaysSec.joinToString(", "),
        ready = editor.ready,
    ) { text ->
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seconds = ArrayList<Int>(parts.size)
        for (part in parts) {
            val number = part.toIntOrNull() ?: return@LineField
            if (number !in 1..86400) return@LineField
            seconds.add(number)
        }
        if (seconds.isEmpty()) return@LineField
        editor.persist(editor.settings.copy(reconnectDelaysSec = seconds))
    }
}

@Composable
internal fun row_sieveConnectSeconds(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.sieveConnectSeconds,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(sieveConnectSeconds = number))
    }
}

@Composable
internal fun row_sieveReadSeconds(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    NumberLeaf(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        settings.sieveReadSeconds,
        editor.ready,
        { it >= 1 },
    ) { number ->
        editor.persist(editor.settings.copy(sieveReadSeconds = number))
    }
}



@Composable
internal fun MailboxPickDialog(
    editor: SettingsEditor,
    picking: MailboxPick?,
    drafts: () -> ServerDrafts?,
    onPicking: (MailboxPick?) -> Unit,
) {
    val field = picking ?: return
    // David's Sent rule: the server's marks only prefill (offer) a default for an unset folder here.
    val current = when (field) {
        MailboxPick.Sent -> editor.settings.sentMailbox
        MailboxPick.Postponed -> editor.settings.postponedMailbox
        MailboxPick.Trash -> editor.settings.trashMailbox
        MailboxPick.Spam -> editor.settings.spamMailbox
        else -> "x"
    }
    val suggestUse = if (current.isNotEmpty()) "" else when (field) {
        MailboxPick.Sent -> "\\Sent"
        MailboxPick.Postponed -> "\\Drafts"
        MailboxPick.Trash -> "\\Trash"
        MailboxPick.Spam -> "\\Junk"
        else -> ""
    }
    SettingsFolderChooser(
        editor = editor,
        drafts = drafts,
        suggestUse = suggestUse,
        onPick = { mailbox ->
            editor.persist(assignMailbox(editor.settings, field, mailbox))
            onPicking(null)
        },
        onDismiss = { onPicking(null) },
    )
}

@Composable
internal fun SettingsPage(content: @Composable () -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
internal fun SettingsNode(
    title: String,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    header: String = "label",
    children: @Composable () -> Unit,
) {
    val description = if (expanded) S("Expanded") else S("Collapsed")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp * depth)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) S("Collapse %1\$s", title) else S("Expand %1\$s", title),
            )
        }
        val showLabel = !expanded || header != "arrow"
        if (showLabel) Column(Modifier.weight(1f)) {
            Text(title)
            if (!expanded && summary.isNotEmpty()) Text(summary)
        }
    }
    if (expanded) children()
}

@Composable
internal fun OpenRow(title: String, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    )
}



internal object PendingLines {
    private val lines = ArrayList<LineCommitter>()
    var stamp by mutableStateOf(0)
        private set

    fun add(line: LineCommitter) {
        if (line in lines) return
        lines.add(line)
        stamp++
    }

    fun remove(line: LineCommitter) {
        if (lines.remove(line)) stamp++
    }

    fun note() {
        stamp++
    }

    fun pending(): Boolean = stamp.let { _ -> lines.any { line -> line.pendingText() } }

    fun commitAll() {
        for (line in lines.toList()) line.commit()
    }
}

internal class LineCommitter {
    var ready: Boolean = true
    var commitOnLeave: Boolean = true
    var onCommit: (String) -> Unit = {}
    var onDraft: ((String) -> Unit)? = null
    var interpret: (String, String) -> String? = { _, _ -> null }
    var skipLeave: () -> Boolean = { false }
    lateinit var draft: MutableState<String>
    lateinit var baseline: MutableState<String>

    fun commit() {
        if (!ready) return
        val current = draft.value
        val base = baseline.value
        val written = interpret(current, base)
        if (written == null) {
            if (current != base) {
                draft.value = base
                onDraft?.invoke(base)
            }
            PendingLines.note()
            return
        }
        baseline.value = written
        if (draft.value != written) draft.value = written
        onCommit(written)
        PendingLines.note()
    }

    fun commitFromDispose() {
        if (!commitOnLeave || !ready || skipLeave()) return
        commit()
    }

    fun pendingText(): Boolean {
        if (!::draft.isInitialized || !::baseline.isInitialized) return false
        return draft.value != baseline.value
    }
}



@Composable
internal fun LineField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    revealKey: Any? = null,
    singleLine: Boolean = true,
    ready: Boolean = true,
    enabled: Boolean = true,
    commitOnLeave: Boolean = true,
    accept: (String) -> Boolean = { true },
    onDraft: ((String) -> Unit)? = null,
    interpret: (String, String) -> String? = { draft, stored -> commitText(draft, stored) },
    onFocus: ((Boolean) -> Unit)? = null,
    onCommit: (String) -> Unit,
) {
    val draftState = if (password) {
        remember { mutableStateOf(value) }
    } else {
        rememberSaveable { mutableStateOf(value) }
    }
    val baselineState = remember { mutableStateOf(value) }
    if (value != baselineState.value) {
        if (draftState.value == baselineState.value || draftState.value == value) {
            draftState.value = value
        }
        baselineState.value = value
    }
    val committer = remember { LineCommitter() }
    committer.draft = draftState
    committer.baseline = baselineState
    val rowEnabled = enabled && LocalRowEnabled.current
    committer.ready = ready && rowEnabled
    committer.commitOnLeave = commitOnLeave
    committer.onCommit = onCommit
    committer.onDraft = onDraft
    committer.interpret = interpret
    val context = LocalContext.current
    committer.skipLeave = { context.findActivity()?.isChangingConfigurations == true }
    if (onDraft != null) {
        SideEffect { onDraft(draftState.value) }
    }
    DisposableEffect(committer) {
        PendingLines.add(committer)
        onDispose {
            committer.commitFromDispose()
            PendingLines.remove(committer)
        }
    }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    @Composable
    fun Editor() {
        val transformation: VisualTransformation
        val trailing: (@Composable () -> Unit)?
        if (password) {
            var revealed by remember(revealKey) { mutableStateOf(false) }
            transformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()
            trailing = {
                PasswordVisibilityIcon(revealed = revealed, onToggle = { revealed = !revealed })
            }
        } else {
            transformation = VisualTransformation.None
            trailing = null
        }
        OutlinedTextField(
            value = draftState.value,
            onValueChange = { next ->
                if (!accept(next)) return@OutlinedTextField
                draftState.value = next
                onDraft?.invoke(next)
                PendingLines.note()
            },
            enabled = rowEnabled,
            label = { Text(label) },
            singleLine = singleLine,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = {
                committer.commit()
                focusManager.clearFocus()
            }),
            trailingIcon = trailing,
            modifier = Modifier
                .fillMaxWidth()
                // Hardware Enter: commit on key-down, give up focus only on key-up. Clearing focus on key-down sent
                // the key-up to the next focused element, which activated it (Navigate up left the screen).
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    when (lineFieldKey(enter, event.type == KeyEventType.KeyDown, singleLine)) {
                        LineKey.Pass -> false
                        LineKey.Commit -> {
                            committer.commit()
                            true
                        }
                        LineKey.Swallow -> {
                            focusManager.clearFocus()
                            true
                        }
                    }
                }
                .onFocusChanged { state ->
                    if (hadFocus && !state.isFocused && !committer.skipLeave()) committer.commit()
                    if (hadFocus != state.isFocused) onFocus?.invoke(state.isFocused)
                    hadFocus = state.isFocused
                },
        )
    }
    Editor()
}

@Composable
internal fun AskLimitField(
    label: String,
    value: Long,
    ready: Boolean,
    onValue: (Long) -> Unit,
) {
    AskLimitField(label, value.toInt(), ready) { onValue(it.toLong()) }
}

@Composable
internal fun AskLimitField(
    label: String,
    value: Int,
    ready: Boolean,
    onValue: (Int) -> Unit,
) {
    LineField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        accept = { draft -> askLimitDraft(draft) },
        interpret = { draft, _ -> askLimitCommit(draft) },
        onCommit = { text ->
            val number = text.toIntOrNull() ?: return@LineField
            onValue(number)
        },
    )
}

internal fun askLimitDraft(draft: String): Boolean {
    if (draft.isEmpty() || draft == "-") return true
    val digits = if (draft.startsWith("-")) draft.drop(1) else draft
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
    if (digits.length > 10) return false
    val number = draft.toLongOrNull() ?: return false
    return number <= Int.MAX_VALUE.toLong()
}

internal fun askLimitCommit(draft: String): String? {
    if (draft.isEmpty() || !askLimitDraft(draft)) return null
    val number = draft.toLongOrNull() ?: return null
    if (number < 0) return "-1"
    if (number > Int.MAX_VALUE.toLong()) return null
    return number.toInt().toString()
}

@Composable
internal fun PortField(
    label: String,
    value: Int,
    ready: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    onValue: (Int) -> Unit,
) {
    val storedPort = value
    LineField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        onFocus = onFocus,
        interpret = { draft, stored ->
            commitPort(draft, stored.toIntOrNull() ?: storedPort)?.toString()
        },
        onCommit = { text ->
            val port = text.toIntOrNull() ?: return@LineField
            onValue(port)
        },
    )
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

@Composable
internal fun BoolField(
    label: String,
    value: Boolean,
    note: String? = null,
    enabled: Boolean = true,
    onValue: (Boolean) -> Unit,
) {
    val rowEnabled = enabled && LocalRowEnabled.current
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (note == null) {
            null
        } else {
            { Text(note) }
        },
        colors = if (rowEnabled) {
            androidx.compose.material3.ListItemDefaults.colors()
        } else {
            androidx.compose.material3.ListItemDefaults.colors(
                headlineColor = faded,
                supportingColor = faded,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (rowEnabled) {
                    Modifier.toggleable(value = value, role = Role.Switch, onValueChange = onValue)
                } else {
                    Modifier
                },
            ),
        trailingContent = {
            Switch(checked = value, onCheckedChange = null, enabled = rowEnabled)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartRuleField(
    label: String,
    selected: StartRule,
    showRecent: Boolean,
    onSelect: (StartRule) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = startRuleChoices(showRecent, selected)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = startRuleName(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(startRuleName(option))
                            if (startRuleIsRecent(option)) Text(S("\\Recent belongs to whichever app opens the folder first"))
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
    if (startRuleIsRecent(selected)) Text(S("\\Recent belongs to whichever app opens the folder first"))
}

@Composable
internal fun saveNameRuleName(rule: SaveNameRule): String = stringResource(
    when (rule) {
        SaveNameRule.DefaultFolder -> SettingsTreeGenerated.resource("settings_save_default")
        SaveNameRule.ByFrom -> SettingsTreeGenerated.resource("settings_save_from")
        SaveNameRule.BySender -> SettingsTreeGenerated.resource("settings_save_sender")
        SaveNameRule.ByRecipient -> SettingsTreeGenerated.resource("settings_save_recipient")
        SaveNameRule.LastFolderUsed -> SettingsTreeGenerated.resource("settings_save_last")
    },
)

@Composable
internal fun threadIndexStyleName(style: ThreadIndexStyle): String = stringResource(
    when (style) {
        ThreadIndexStyle.Expanded -> SettingsTreeGenerated.resource("settings_thread_expanded")
        ThreadIndexStyle.Collapsed -> SettingsTreeGenerated.resource("settings_thread_collapsed")
    },
)

@Composable
internal fun sortKeyName(key: SortKey): String = when (key) {
        SortKey.Arrival -> S("Arrival")
        SortKey.Date -> S("Date")
        SortKey.From -> S("From")
        SortKey.Subject -> S("Subject")
        SortKey.To -> S("To")
        SortKey.Cc -> S("Cc")
        SortKey.Size -> S("Size")
        SortKey.ThreadReferences -> S("Thread")
        SortKey.ThreadOrderedSubject -> S("Ordered subject")
    }

@Composable
internal fun startRuleName(rule: StartRule): String = stringResource(
    when (rule) {
        StartRule.FirstUnseen -> SettingsTreeGenerated.resource("label_first_unread")
        StartRule.FirstRecent -> SettingsTreeGenerated.resource("label_first_recent")
        StartRule.FirstImportant -> SettingsTreeGenerated.resource("label_first_important")
        StartRule.FirstImportantOrUnseen -> SettingsTreeGenerated.resource("label_first_important_unread")
        StartRule.FirstImportantOrRecent -> SettingsTreeGenerated.resource("label_first_important_recent")
        StartRule.First -> SettingsTreeGenerated.resource("label_top")
        StartRule.Last -> SettingsTreeGenerated.resource("label_bottom")
        StartRule.Newest -> SettingsTreeGenerated.resource("label_newest_message")
    },
)

@Composable
internal fun startAfterChangeName(value: StartAfterChange): String = stringResource(
    when (value) {
        StartAfterChange.RerunRule -> SettingsTreeGenerated.resource("label_rerun_rule")
        StartAfterChange.KeepTopVisible -> SettingsTreeGenerated.resource("label_keep_top")
    },
)

@Composable
internal fun pinercStartDefaultName(value: PinercStartDefault): String = stringResource(
    when (value) {
        PinercStartDefault.LeaveUnchanged -> SettingsTreeGenerated.resource("label_leave_unchanged")
        PinercStartDefault.AlpineDefault -> SettingsTreeGenerated.resource("label_alpine_default")
    },
)

@Composable
internal fun swipeActionName(action: SwipeAction): String = when (action) {
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
internal fun tlsName(mode: TlsMode): String = stringResource(
    when (mode) {
        TlsMode.None -> SettingsTreeGenerated.resource("settings_tls_none")
        TlsMode.StartTls -> SettingsTreeGenerated.resource("settings_tls_starttls")
        TlsMode.Implicit -> SettingsTreeGenerated.resource("settings_tls_implicit")
    },
)

@Composable
internal fun themeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.Dark -> SettingsTreeGenerated.resource("label_dark")
        ThemeMode.Light -> SettingsTreeGenerated.resource("label_light")
        ThemeMode.FollowSystem -> SettingsTreeGenerated.resource("label_system")
    },
)

@Composable
internal fun dateFormatName(format: DateFormat): String = stringResource(
    when (format) {
        DateFormat.Local -> SettingsTreeGenerated.resource("label_local")
        DateFormat.Short -> SettingsTreeGenerated.resource("label_short")
        DateFormat.Relative -> SettingsTreeGenerated.resource("label_relative")
        DateFormat.Custom -> SettingsTreeGenerated.resource("label_custom")
    },
)

@Composable
internal fun multiPaneName(mode: MultiPane): String = stringResource(
    when (mode) {
        MultiPane.Off -> SettingsTreeGenerated.resource("settings_multi_pane_off")
        MultiPane.Wide -> SettingsTreeGenerated.resource("settings_multi_pane_wide")
        MultiPane.Auto -> SettingsTreeGenerated.resource("settings_pane_auto")
    },
)

@Composable
internal fun layoutChoiceName(choice: LayoutChoice): String = stringResource(
    when (choice) {
        LayoutChoice.Off -> SettingsTreeGenerated.resource("settings_multi_pane_off")
        LayoutChoice.On -> SettingsTreeGenerated.resource("settings_multi_pane_wide")
        LayoutChoice.Auto -> SettingsTreeGenerated.resource("settings_pane_auto")
    },
)

@Composable
internal fun densityName(density: Density): String = stringResource(
    when (density) {
        Density.Compact -> SettingsTreeGenerated.resource("label_compact")
        Density.Medium -> SettingsTreeGenerated.resource("label_comfortable")
        Density.Large -> SettingsTreeGenerated.resource("label_large")
    },
)

@Composable
internal fun deletePolicyName(policy: DeletePolicy): String = stringResource(
    when (policy) {
        DeletePolicy.MarkDeleted -> SettingsTreeGenerated.resource("settings_mark_deleted")
        DeletePolicy.MoveToTrash -> SettingsTreeGenerated.resource("settings_move_to_trash")
        DeletePolicy.DeletePermanently -> SettingsTreeGenerated.resource("settings_delete_permanently")
    },
)
internal fun compressionName(mode: String): String = mode

internal fun moveMethodName(method: String): String = method

internal data class ReasonOption<T>(
    val value: T,
    val enabled: Boolean,
    val reason: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ReasonChoice(
    label: String,
    options: List<ReasonOption<T>>,
    selected: T,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = name(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name(option.value))
                            val reason = option.reason
                            if (reason != null) {
                                Text(
                                    reason,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    enabled = option.enabled,
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    name: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val rowEnabled = enabled && LocalRowEnabled.current
    var expanded by remember { mutableStateOf(false) }
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = name(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = if (rowEnabled) {
                OutlinedTextFieldDefaults.colors()
            } else {
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = faded,
                    unfocusedTextColor = faded,
                    focusedLabelColor = faded,
                    unfocusedLabelColor = faded,
                )
            },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(name(option)) },
                    onClick = {
                        if (rowEnabled) onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}


@Composable
internal fun MailboxLine(
    label: String,
    value: String,
    onChoose: () -> Unit,
    onValue: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LineField(label, value, onCommit = onValue)
        }
        TextButton(onClick = onChoose) { Text(S("Choose")) }
    }
}


internal enum class MailboxPick {
    Sent,
    Postponed,
    AddressBook,
    ExtraBook,
    GlobalBook,
    Spam,
    Trash,
    Saved,
    LastSave,
    TrailingMove,
    LeadingMove,
    ReaderTrailingMove,
    ReaderLeadingMove,
}

internal fun assignMailbox(base: Settings, field: MailboxPick, mailbox: String): Settings {
    return when (field) {
        MailboxPick.Sent -> base.copy(sentMailbox = mailbox)
        MailboxPick.Postponed -> base.copy(postponedMailbox = mailbox)
        MailboxPick.AddressBook -> base.copy(addressBooks = base.addressBooks + AddressBookEntry("pine", mailbox = mailbox))
        MailboxPick.ExtraBook -> base
        MailboxPick.GlobalBook -> base
        MailboxPick.Spam -> base.copy(spamMailbox = mailbox)
        MailboxPick.Trash -> base.copy(trashMailbox = mailbox)
        MailboxPick.Saved -> base.copy(savedMailbox = mailbox)
        MailboxPick.LastSave -> base.copy(lastSaveMailbox = mailbox)
        MailboxPick.TrailingMove -> base.copy(
            swipeTrailing = base.swipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.LeadingMove -> base.copy(
            swipeLeading = base.swipeLeading.copy(moveMailbox = mailbox),
        )
        MailboxPick.ReaderTrailingMove -> base.copy(
            readerSwipeTrailing = base.readerSwipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.ReaderLeadingMove -> base.copy(
            readerSwipeLeading = base.readerSwipeLeading.copy(moveMailbox = mailbox),
        )
    }
}

internal fun pinercMaxBytes(): Int = SettingsHub.uiNow().pinercMaxBytes

internal sealed class PinercRead {
    class Ok(val text: String) : PinercRead()
    object TooLarge : PinercRead()
    object Bad : PinercRead()
}

internal fun readPinercStream(resolver: ContentResolver, uri: Uri): PinercRead {
    return try {
        val input = resolver.openInputStream(uri) ?: return PinercRead.Bad
        input.use { stream ->
            val buf = ByteArray(pinercMaxBytes() + 1)
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total > pinercMaxBytes()) return PinercRead.TooLarge
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                PinercRead.Ok(decoder.decode(ByteBuffer.wrap(buf, 0, total)).toString())
            } catch (e: CharacterCodingException) {
                PinercRead.Bad
            }
        }
    } catch (e: IOException) {
        PinercRead.Bad
    } catch (e: SecurityException) {
        PinercRead.Bad
    }
}

/** Who the Sending rules summary names: From as composed, else the login, else the host. Never blank. */
internal fun rulesSummaryWho(settings: Settings): String =
    formatMailbox(settings.displayName, settings.email).trim()
        .ifEmpty { settings.username.trim() }
        .ifEmpty { settings.imapHost.trim() }
        .ifEmpty { "?" }


internal enum class SpecialFolder { Sent, Postponed }

/** The special folders a leave warning names: unset ones only. A warning, never a block (David's Sent rule d). */
internal fun unsetSpecialFolders(settings: Settings): List<SpecialFolder> = buildList {
    if (settings.sentMailbox.isBlank()) add(SpecialFolder.Sent)
    if (settings.postponedMailbox.isBlank()) add(SpecialFolder.Postponed)
}
