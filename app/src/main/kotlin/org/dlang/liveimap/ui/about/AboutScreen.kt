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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.R
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.TlsMode

private const val SUPPORT_ADDRESS = "david+liveimap@lang.hm"

internal fun aboutConnectionNotice(host: String, mode: TlsMode): Int? {
    if (host.isBlank()) return null
    return when (mode) {
        TlsMode.None -> R.string.about_plaintext
        TlsMode.StartTls -> R.string.about_starttls
        TlsMode.Implicit -> R.string.about_implicit_tls
    }
}

@Composable
fun AboutScreen(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DataStoreSettingsStore(context.applicationContext) }
    var imapHost by remember { mutableStateOf("") }
    var tlsMode by remember { mutableStateOf(TlsMode.None) }
    LaunchedEffect(store) {
        val loaded = store.load()
        imapHost = loaded.imapHost
        tlsMode = loaded.tlsMode
    }
    val appName = stringResource(R.string.app_name)
    val versionLine = stringResource(R.string.about_version, BuildConfig.VERSION_NAME)
    val notice = aboutConnectionNotice(imapHost, tlsMode)?.let { stringResource(it) }
    val support = stringResource(R.string.about_support)
    val licenses = stringResource(R.string.about_licenses)
    val licenseNotice = stringResource(R.string.about_license_notice)
    val noEmail = stringResource(R.string.about_no_email)
    Column {
        Text(appName)
        Text(versionLine)
        if (notice != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    notice,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        TextButton(onClick = { sendSupport(context, appName, versionLine, support, noEmail) }) {
            Text(support)
        }
        ListItem(
            headlineContent = { Text(licenses) },
            supportingContent = { Text(licenseNotice) },
            modifier = Modifier.clickable(onClick = onOpenLicenses),
        )
    }
}

private fun sendSupport(
    context: Context,
    subject: String,
    body: String,
    chooserTitle: String,
    noEmail: String,
) {
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
        context.startActivity(Intent.createChooser(send, chooserTitle))
    } else {
        Toast.makeText(context, noEmail, Toast.LENGTH_SHORT).show()
    }
}
