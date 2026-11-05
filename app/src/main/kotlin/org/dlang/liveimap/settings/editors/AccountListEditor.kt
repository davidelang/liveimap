package org.dlang.liveimap.settings.editors
import org.dlang.liveimap.i18n.S

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.dlang.liveimap.accounts.parseAccountId
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.settings.SettingsTreeGenerated
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.TextButton

@Composable
fun AccountListEditor(
    accounts: List<AccountChoice>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    deletingName: String? = null,
    deleteError: String? = null,
    onRetryDelete: () -> Unit = {},
) {
    var removing by remember { mutableStateOf<String?>(null) }
    Column {
        accounts.forEach { account ->
            ListItem(
                headlineContent = { Text(account.name) },
                supportingContent = {
                    Text(
                        if (account.editing) {
                            stringResource(SettingsTreeGenerated.resource("settings_account"))
                        } else {
                            ""
                        },
                    )
                },
                leadingContent = {
                    RadioButton(selected = account.editing, onClick = { onPick(account.id) })
                },
                trailingContent = {
                    TextButton(onClick = { removing = account.id }) {
                        Text(S("Remove account"))
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { onPick(account.id) },
            )
        }
        TextButton(onClick = onAdd) {
            Text(S("Add account"))
        }
        if (deletingName != null) Text(S("Deleting %1\$s…", deletingName))
        if (deleteError != null) {
            Text(deleteError)
            TextButton(onClick = onRetryDelete) { Text(S("Retry")) }
        }
    }
    val pending = removing
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(S("Remove account")) },
            text = { Text(S("Remove this account from this phone. Mail on the server stays.")) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(pending)
                }) { Text(S("Remove account")) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(S("Cancel"))
                }
            },
        )
    }
}

fun openingAccount(accounts: List<AccountChoice>, opensOn: String, lastEdited: String): AccountChoice? {
    if (accounts.isEmpty()) return null
    if (accounts.size == 1) return accounts[0]
    if (opensOn == "lastEdited") {
        val id = parseAccountId(lastEdited)
        val named = if (id == null) null else accounts.firstOrNull { it.id == id }
        if (named != null) return named
        if (id != null) runCatching { Log.w("liveimap", "settingsLastEditedAccount missing $id") }
    }
    return accounts.firstOrNull { it.drawer }
}
