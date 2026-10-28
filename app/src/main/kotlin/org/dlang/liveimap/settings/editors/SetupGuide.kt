package org.dlang.liveimap.settings.editors

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
import com.davidlang.liveimap.R
import kotlinx.coroutines.launch
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.PasswordStorage
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.newAccountSettings
import org.dlang.liveimap.settings.testAccountLogins
import org.dlang.liveimap.settings.testAccountProbes
import org.dlang.liveimap.ui.TextButton

internal object PendingNewAccount {
    var active: Boolean = false
    var settings: AccountSettings? = null
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
    onAdvanced: (AccountSettings, String, String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inboundJmap by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("587") }
    var smtpSame by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val existing = accountId?.takeIf { it.isNotEmpty() }
    androidx.compose.runtime.LaunchedEffect(existing) {
        val id = existing ?: return@LaunchedEffect
        val bound = DataStoreSettingsStore(context, id)
        val loaded = bound.load()
        email = loaded.email.ifBlank { loaded.username }
        host = loaded.imapHost
        port = loaded.imapPort.toString()
        inboundJmap = loaded.jmapFolders
        smtpHost = loaded.smtpHost
        smtpPort = loaded.smtpPort.toString()
        smtpSame = loaded.smtpSameAsImap
        password = bound.password().orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.setup_title))
        if (step == 0) {
            Text(stringResource(R.string.setup_email))
            androidx.compose.material3.OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true)
            Text(stringResource(R.string.setup_password))
            androidx.compose.material3.OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = {
                val activity = context.findActivity() ?: return@TextButton
                scope.launch {
                    val found = PasswordStorage.fetchPassword(activity)
                    if (found != null) {
                        if (email.isBlank()) email = found.id
                        password = found.password
                    }
                }
            }) { Text(stringResource(R.string.password_storage_manager)) }
        }
        if (step == 1) {
            Text(stringResource(R.string.setup_inbound_step))
            androidx.compose.material3.OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true)
            androidx.compose.material3.OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { inboundJmap = !inboundJmap }) {
                Text(if (inboundJmap) stringResource(R.string.settings_mail_access) else stringResource(R.string.settings_imap_host))
            }
        }
        if (step == 2) {
            Text(stringResource(R.string.setup_sending_step))
            androidx.compose.material3.OutlinedTextField(smtpHost, { smtpHost = it }, Modifier.fillMaxWidth(), singleLine = true)
            androidx.compose.material3.OutlinedTextField(smtpPort, { smtpPort = it }, Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { smtpSame = !smtpSame }) { Text(stringResource(R.string.settings_smtp_same_as_imap)) }
        }
        if (report.isNotEmpty()) Text(report)
        if (busy) Text(stringResource(R.string.setup_testing))
        androidx.compose.foundation.layout.Row {
            if (step > 0) {
                TextButton(onClick = { step -= 1 }) { Text(stringResource(R.string.setup_back)) }
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
            }) { Text(if (step < 2) stringResource(R.string.setup_next) else stringResource(R.string.setup_test)) }
            TextButton(onClick = {
                val settings = draftSettings(email, inboundJmap, host, port, smtpHost, smtpPort, smtpSame)
                scope.launch {
                    val saved = finishSetup(context, existing, settings, password, if (smtpSame) password else "")
                    if (saved) onDone() else report = context.getString(R.string.setup_failed)
                }
            }) { Text(stringResource(R.string.setup_finish)) }
            TextButton(onClick = {
                val settings = draftSettings(email, inboundJmap, host, port, smtpHost, smtpPort, smtpSame)
                onAdvanced(settings, password, if (smtpSame) password else "")
            }) { Text(stringResource(R.string.setup_advanced)) }
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
): AccountSettings {
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

private suspend fun finishSetup(
    context: Context,
    existing: String?,
    settings: AccountSettings,
    password: String,
    smtpPassword: String,
): Boolean {
    return try {
        val store = AccountStore.shared(context)
        val id = if (existing == null) {
            store.createAccount(settings, password, smtpPassword)
        } else {
            val bound = DataStoreSettingsStore(context, existing)
            bound.save(settings)
            bound.setPassword(password)
            bound.setSmtpPassword(smtpPassword)
            existing
        }
        if (settings.passwordStorage == org.dlang.liveimap.settings.PasswordStorageChoice.passwordManager) {
            context.findActivity()?.let { PasswordStorage.savePassword(it, settings.username, password) }
        }
        id.isNotEmpty()
    } catch (_: Exception) {
        false
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
