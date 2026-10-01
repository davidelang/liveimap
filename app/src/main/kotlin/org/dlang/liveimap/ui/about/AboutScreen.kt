package org.dlang.liveimap.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.dlang.liveimap.BuildConfig

private const val SUPPORT_ADDRESS = "david+liveimap@lang.hm"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    Column {
        Text("LiveIMAP")
        Text("Version ${BuildConfig.VERSION_NAME}")
        TextButton(onClick = { sendSupport(context) }) {
            Text("Support")
        }
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
