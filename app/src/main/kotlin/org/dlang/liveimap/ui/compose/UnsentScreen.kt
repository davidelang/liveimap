package org.dlang.liveimap.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.dlang.liveimap.ui.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.davidlang.liveimap.R
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.ui.LocalScreenAccounts

@Composable
fun UnsentScreen(
    onOpenCopy: (String, Boolean, String) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var copies by remember { mutableStateOf<List<DeviceCopy>>(emptyList()) }
    var charset by remember { mutableStateOf("") }
    var discardId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (accountId == null) accountId = store.chosenAccountId()
    }
    val id = accountId ?: ""
    LocalScreenAccounts.current.Register(id)

    LaunchedEffect(id) {
        copies = readCopies(appContext, id)
        charset = if (id.isEmpty()) {
            ""
        } else {
            try {
                store.forAccount(id).load().unlabeledCharset
            } catch (_: Exception) {
                ""
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        for (copy in copies) {
            Text(unsentRowLabel(copy, stringResource(R.string.unsent_no_subject), charset))
            Text(copy.recipients.joinToString(", "))
            Text(
                if (copy.appendOnly) {
                    stringResource(R.string.unsent_sent_copy)
                } else if (copy.uncertain) {
                    stringResource(R.string.unsent_uncertain)
                } else {
                    stringResource(R.string.unsent_not_sent)
                },
            )
            if (copy.modifiedMillis > 0L) {
                Text(shortDateTime(copy.modifiedMillis))
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenCopy(copy.id, false, id) }) {
                    Text(stringResource(R.string.unsent_open))
                }
                TextButton(onClick = { onOpenCopy(copy.id, true, id) }) {
                    Text(stringResource(R.string.unsent_retry))
                }
                TextButton(onClick = { discardId = copy.id }) {
                    Text(stringResource(R.string.unsent_discard))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        }
    }
    val pending = discardId
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { discardId = null },
            title = { Text(stringResource(R.string.unsent_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    discardId = null
                    deleteCopy(appContext, pending, id)
                    copies = readCopies(appContext, id)
                }) { Text(stringResource(R.string.unsent_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardId = null }) {
                    Text(stringResource(R.string.unsent_cancel))
                }
            },
        )
    }
}

private fun shortDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Meta subject when the line is present. Otherwise header lines only, through the blank line. */
private fun unsentRowLabel(copy: DeviceCopy, missingSubject: String, charset: String): String {
    copy.error?.let { return it }
    copy.subject?.let { return it.ifEmpty { missingSubject } }
    val headers = try {
        headerThroughBlankLine(copy.file)
    } catch (_: Exception) {
        return missingSubject
    }
    return unsentSubject(headers, missingSubject, charset)
}
