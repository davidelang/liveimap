package org.dlang.liveimap.settings.editors
import org.dlang.liveimap.i18n.S

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.NoAccountSelected
import org.dlang.liveimap.accounts.PasswordStorage
import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.newAccountSettings
import org.dlang.liveimap.settings.withUiOf
import org.dlang.liveimap.settings.testAccountLogins
import org.dlang.liveimap.settings.testAccountProbes
import org.dlang.liveimap.ui.TextButton

internal object PendingNewAccount {
    var active: Boolean = false
    var settings: Settings? = null
    var password: String = ""
    var smtpPassword: String = ""
    var sievePassword: String = ""
    var onCreated: ((String) -> Unit)? = null

    fun clear() {
        active = false
        settings = null
        password = ""
        smtpPassword = ""
        sievePassword = ""
        onCreated = null
    }
}

@Composable
fun SetupGuide(
    accountId: String? = null,
    onDone: () -> Unit = {},
    onAdvanced: (Settings, String, String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var smtpPasswordText by remember { mutableStateOf("") }
    var sievePasswordText by remember { mutableStateOf("") }
    var inboundJmap by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("587") }
    var smtpSame by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loadedAccount by remember { mutableStateOf<Settings?>(null) }
    var missingBlob by remember { mutableStateOf(false) }
    val existing = accountId?.takeIf { it.isNotEmpty() }
    androidx.compose.runtime.LaunchedEffect(existing) {
        loadedAccount = null
        missingBlob = false
        username = ""
        smtpPasswordText = ""
        sievePasswordText = ""
        val id = existing ?: return@LaunchedEffect
        val bound = DataStoreSettingsStore(context, id)
        var missing = false
        val loaded = try {
            bound.load()
        } catch (_: NoAccountSelected) {
            val fields = AccountStore.shared(context).accountFields(id)
            val overlaid = fields?.let { data: Map<String, String> ->
                runCatching { overlayAccountFields(newAccountSettings(), data).withUiOf(SettingsHub.uiNow()) }.getOrNull()
            }
            if (overlaid == null) return@LaunchedEffect
            missing = true
            overlaid
        }
        email = loaded.email.ifBlank { loaded.username }
        username = loaded.username
        host = loaded.imapHost
        port = loaded.imapPort.toString()
        inboundJmap = loaded.jmapFolders
        smtpHost = loaded.smtpHost
        smtpPort = loaded.smtpPort.toString()
        smtpSame = loaded.smtpSameAsImap
        password = readSlot { bound.password() }
        smtpPasswordText = readSlot { bound.smtpPassword() }
        sievePasswordText = readSlot { AccountStore.shared(context).sievePassword(id) }
        missingBlob = missing
        loadedAccount = loaded
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(S("Set up mail"))
        if (step == 0) {
            Text(S("Email address"))
            androidx.compose.material3.OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true)
            if (missingBlob && username.isNotEmpty()) {
                Text(stringResource(SettingsTreeGenerated.resource("settings_username")))
                Text(username)
            }
            Text(S("Password"))
            androidx.compose.material3.OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true)
            if (missingBlob && smtpPasswordText.isNotEmpty()) {
                Text(stringResource(SettingsTreeGenerated.resource("settings_smtp_password")))
                Text(smtpPasswordText)
            }
            if (missingBlob && sievePasswordText.isNotEmpty()) {
                Text(stringResource(SettingsTreeGenerated.resource("settings_sieve_password")))
                Text(sievePasswordText)
            }
            TextButton(onClick = {
                val activity = context.findActivity() ?: return@TextButton
                scope.launch {
                    val found = PasswordStorage.fetchPassword(activity)
                    if (found != null) {
                        if (email.isBlank()) email = found.id
                        password = found.password
                    }
                }
            }) { Text(stringResource(SettingsTreeGenerated.resource("password_storage_manager"))) }
        }
        if (step == 1) {
            Text(S("Incoming server"))
            androidx.compose.material3.OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true)
            androidx.compose.material3.OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { inboundJmap = !inboundJmap }) {
                Text(if (inboundJmap) stringResource(SettingsTreeGenerated.resource("settings_mail_access")) else stringResource(SettingsTreeGenerated.resource("settings_imap_host")))
            }
        }
        if (step == 2) {
            Text(S("Sending server"))
            androidx.compose.material3.OutlinedTextField(smtpHost, { smtpHost = it }, Modifier.fillMaxWidth(), singleLine = true)
            androidx.compose.material3.OutlinedTextField(smtpPort, { smtpPort = it }, Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { smtpSame = !smtpSame }) { Text(S("Same as IMAP password")) }
        }
        if (report.isNotEmpty()) Text(report)
        if (busy) Text(S("Testing…"))
        androidx.compose.foundation.layout.Row {
            if (step > 0) {
                TextButton(onClick = { step -= 1 }) { Text(S("Back")) }
            }
            TextButton(onClick = {
                if (step == 0 && host.isBlank()) host = email.substringAfter('@', "")
                if (step < 2) {
                    step += 1
                } else {
                    scope.launch {
                        busy = true
                        val settings = draftSettings(email, inboundJmap, host, port, smtpHost, smtpPort, smtpSame)
                        val lines = testAccountLogins(
                            settings,
                            testAccountProbes(existing ?: "setup", settings, password, if (smtpSame) password else password),
                        )
                        report = lines.joinToString("\n")
                        busy = false
                    }
                }
            }) { Text(if (step < 2) S("Next") else S("Test")) }
            TextButton(onClick = {
                val settings = draftSettings(email, inboundJmap, host, port, smtpHost, smtpPort, smtpSame)
                val loaded = loadedAccount
                val blobMissing = missingBlob
                scope.launch {
                    val saved = finishSetup(
                        context,
                        existing,
                        settings,
                        password,
                        if (smtpSame) password else "",
                        loaded,
                        blobMissing,
                    )
                    if (saved) onDone() else report = S(context, "Setup could not be saved")
                }
            }) { Text(S("Finish")) }
            TextButton(onClick = {
                val settings = draftSettings(email, inboundJmap, host, port, smtpHost, smtpPort, smtpSame)
                onAdvanced(settings, password, if (smtpSame) password else "")
            }) { Text(S("Advanced")) }
        }
    }
}

private fun draftSettings(
    email: String,
    jmap: Boolean,
    host: String,
    port: String,
    smtpHost: String,
    smtpPort: String,
    smtpSame: Boolean,
): Settings {
    return newAccountSettings().copy(
        email = email.trim(),
        username = email.substringBefore('@').trim(),
        jmapFolders = jmap,
        imapHost = host.trim(),
        imapPort = port.toIntOrNull() ?: if (jmap) 443 else 993,
        smtpHost = smtpHost.trim(),
        smtpPort = smtpPort.toIntOrNull() ?: 587,
        smtpSameAsImap = smtpSame,
    )
}

/** An existing account's Finish waits until that account's record has loaded. A new account does not. */
internal fun existingSetupReady(existing: String?, loaded: Settings?): Boolean {
    if (existing == null) return true
    return loaded != null
}

internal enum class SetupFinishKind {
    Nothing,
    SaveOverlaid,
    SaveLoaded,
    Create,
}

internal data class SetupFinishPlan(
    val kind: SetupFinishKind,
    val accountId: String?,
    val settings: Settings?,
    val rewritePasswords: Boolean,
)

/** Four Finish decisions: still loading, missing blob, loaded record, or a new account. */
internal fun planSetupFinish(
    existing: String?,
    loaded: Settings?,
    draft: Settings,
    missingBlob: Boolean,
): SetupFinishPlan {
    if (!existingSetupReady(existing, loaded)) {
        return SetupFinishPlan(SetupFinishKind.Nothing, null, null, false)
    }
    if (existing == null) {
        return SetupFinishPlan(SetupFinishKind.Create, null, draft, true)
    }
    if (missingBlob) {
        return SetupFinishPlan(SetupFinishKind.SaveOverlaid, existing, loaded, false)
    }
    return SetupFinishPlan(SetupFinishKind.SaveLoaded, existing, wizardOntoLoaded(loaded!!, draft), true)
}

/** Wizard fields written onto the loaded record. Every other field stays. */
internal fun wizardOntoLoaded(loaded: Settings, draft: Settings): Settings {
    return loaded.copy(
        email = draft.email,
        username = draft.username,
        jmapFolders = draft.jmapFolders,
        imapHost = draft.imapHost,
        imapPort = draft.imapPort,
        smtpHost = draft.smtpHost,
        smtpPort = draft.smtpPort,
        smtpSameAsImap = draft.smtpSameAsImap,
    )
}

private suspend fun finishSetup(
    context: Context,
    existing: String?,
    settings: Settings,
    password: String,
    smtpPassword: String,
    loaded: Settings?,
    missingBlob: Boolean,
): Boolean {
    val plan = planSetupFinish(existing, loaded, settings, missingBlob)
    // A Finish before the load writes nothing: no record and neither password.
    if (plan.kind == SetupFinishKind.Nothing) return false
    return try {
        val store = AccountStore.shared(context)
        val written = plan.settings ?: return false
        val id = when (plan.kind) {
            SetupFinishKind.Create -> store.createAccount(written, password, smtpPassword)
            SetupFinishKind.SaveOverlaid, SetupFinishKind.SaveLoaded -> {
                val accountId = plan.accountId ?: return false
                val bound = DataStoreSettingsStore(context, accountId)
                bound.save(written)
                // A missing blob keeps the IMAP slot (save) and does not replace SMTP or Sieve.
                if (plan.rewritePasswords) {
                    bound.setPassword(password)
                    bound.setSmtpPassword(smtpPassword)
                }
                accountId
            }
            SetupFinishKind.Nothing -> return false
        }
        if (written.passwordStorage == org.dlang.liveimap.settings.PasswordStorageChoice.passwordManager) {
            context.findActivity()?.let { PasswordStorage.savePassword(it, written.username, password) }
        }
        id.isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

private suspend fun readSlot(read: suspend () -> String?): String {
    return try {
        read().orEmpty()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        ""
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
