package org.dlang.liveimap.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun UnsentScreen(
    onOpenCopy: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    var copies by remember { mutableStateOf<List<DeviceCopy>>(emptyList()) }
    var discardId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        copies = readCopies(appContext)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        for (copy in copies) {
            Text(unsentSubject(copy.bytes))
            Text(copy.recipients.joinToString(", "))
            Text(unsentLabel(copy.appendOnly))
            if (copy.modifiedMillis > 0L) {
                Text(shortDateTime(copy.modifiedMillis))
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenCopy(copy.id, false) }) { Text("Open") }
                TextButton(onClick = { onOpenCopy(copy.id, true) }) { Text("Retry") }
                TextButton(onClick = { discardId = copy.id }) { Text("Discard") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        }
    }
    val pending = discardId
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { discardId = null },
            title = { Text("Discard this unsent message?") },
            confirmButton = {
                TextButton(onClick = {
                    discardId = null
                    deleteCopy(appContext, pending)
                    copies = readCopies(appContext)
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardId = null }) { Text("Cancel") }
            },
        )
    }
}

private fun shortDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
