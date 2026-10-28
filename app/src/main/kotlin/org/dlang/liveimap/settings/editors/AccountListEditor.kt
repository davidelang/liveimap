package org.dlang.liveimap.settings.editors

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
import com.davidlang.liveimap.R
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.TextButton

@Composable
fun AccountListEditor(
    accounts: List<AccountChoice>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var removing by remember { mutableStateOf<String?>(null) }
    Column {
        accounts.forEach { account ->
            ListItem(
                headlineContent = { Text(account.name) },
                supportingContent = {
                    Text(
                        if (account.editing) {
                            stringResource(R.string.settings_account)
                        } else {
                            ""
                        },
                    )
                },
                leadingContent = {
                    RadioButton(selected = account.editing, onClick = { onPick(account.id) })
                },
                trailingContent = if (accounts.size < 2) {
                    null
                } else {
                    {
                        TextButton(onClick = { removing = account.id }) {
                            Text(stringResource(R.string.settings_remove_account))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable { onPick(account.id) },
            )
        }
        TextButton(onClick = onAdd) {
            Text(stringResource(R.string.settings_add_account))
        }
    }
    val pending = removing
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.settings_remove_account)) },
            text = { Text(stringResource(R.string.settings_remove_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(pending)
                }) { Text(stringResource(R.string.settings_remove_account)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

fun openingAccount(accounts: List<AccountChoice>, opensOn: String, lastEdited: String): AccountChoice? {
    if (accounts.isEmpty()) return null
    if (accounts.size == 1) return accounts.first()
    val drawer = accounts.firstOrNull { it.drawer } ?: accounts.first()
    if (opensOn != "lastEdited") return drawer
    if (lastEdited.isEmpty()) return drawer
    val named = accounts.firstOrNull { it.id == lastEdited }
    if (named == null) {
        runCatching { Log.w("liveimap", "settingsLastEditedAccount missing $lastEdited") }
        return drawer
    }
    return named
}
