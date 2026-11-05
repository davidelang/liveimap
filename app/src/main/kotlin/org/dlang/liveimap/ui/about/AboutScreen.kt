package org.dlang.liveimap.ui.about
import org.dlang.liveimap.i18n.S

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.davidlang.liveimap.BuildConfig
import org.dlang.liveimap.ui.help.PRIVACY_URL

private const val SUPPORT_ADDRESS = "david+liveimap@lang.hm"

internal fun aboutLibraryLines(): List<String> = listOf(
    org.dlang.liveimap.i18n.literal.S("libEtPan!, BSD 3-Clause"),
    org.dlang.liveimap.i18n.literal.S("libfastjson, MIT"),
    org.dlang.liveimap.i18n.literal.S("OpenSSL, Apache License 2.0"),
    org.dlang.liveimap.i18n.literal.S("Cyrus SASL, Carnegie Mellon University license"),
)

@Composable
fun AboutScreen(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val appName = S("LiveIMAP")
    val versionLine = S("Version %1\$s", BuildConfig.VERSION_NAME)
    val support = S("Ask for help")
    val libraries = S("Libraries")
    val privacy = S("Privacy policy")
    val supportDeveloper = S("How to support the developer")
    val tip = S("Tip with Google Play")
    val tipPending = S("This build cannot take a tip yet.")
    val licenses = S("Open-source licenses")
    val licenseNotice = S("LiveIMAP is licensed under the Apache License 2.0")
    val noEmail = S("No email app found")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(appName)
        Text(versionLine)
        Text(
            text = libraries,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        aboutLibraryLines().forEach { line ->
            Text(S(line))
        }
        ListItem(
            headlineContent = { Text(licenses) },
            supportingContent = { Text(licenseNotice) },
            modifier = Modifier.clickable(onClick = onOpenLicenses),
        )
        TextButton(onClick = {
            org.dlang.liveimap.ui.openWebPage(context, PRIVACY_URL)
        }) { Text(privacy) }
        Text(
            text = supportDeveloper,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(tipPending)
        Row {
            TextButton(onClick = { sendSupport(context, appName, versionLine, support, noEmail) }) {
                Text(support)
            }
            TextButton(onClick = {}, enabled = false) {
                Text(tip)
            }
        }
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
    // F70: no resolve-activity gate (package visibility hides mail apps on API 30+); just try.
    if (org.dlang.liveimap.ui.startExternal(context, sendTo)) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    if (!org.dlang.liveimap.ui.startExternal(context, Intent.createChooser(send, chooserTitle))) {
        Toast.makeText(context, noEmail, Toast.LENGTH_SHORT).show()
    }
}
