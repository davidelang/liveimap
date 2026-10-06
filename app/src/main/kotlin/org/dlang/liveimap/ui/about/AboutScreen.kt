package org.dlang.liveimap.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
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
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.settings.DataStoreSettingsStore

private const val SUPPORT_ADDRESS = "david+liveimap@lang.hm"

@Composable
fun AboutScreen(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DataStoreSettingsStore(context.applicationContext) }
    var imapHost by remember { mutableStateOf("") }
    LaunchedEffect(store) {
        imapHost = store.load().imapHost
    }
    Column {
        Text("LiveIMAP")
        Text("Version ${BuildConfig.VERSION_NAME}")
        if (imapHost.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "This connection is not encrypted. The password and messages travel in the clear.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        TextButton(onClick = { sendSupport(context) }) {
            Text("Support")
        }
        ListItem(
            headlineContent = { Text("Open-source licenses") },
            supportingContent = { Text("LiveIMAP is licensed under the Apache License 2.0") },
            modifier = Modifier.clickable(onClick = onOpenLicenses),
        )
    }
}

private fun sendSupport(context: Context) {
    val subject = "LiveIMAP"
    val body = "Version ${BuildConfig.VERSION_NAME}"
    val sendTo = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$SUPPORT_ADDRESS")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    if (sendTo.resolveActivity(context.packageManager) != null) {
        context.startActivity(sendTo)
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    if (send.resolveActivity(context.packageManager) != null) {
        context.startActivity(Intent.createChooser(send, "Support"))
    } else {
        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
    }
}
