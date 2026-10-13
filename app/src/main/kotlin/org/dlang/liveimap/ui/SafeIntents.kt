package org.dlang.liveimap.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.dlang.liveimap.R

/** F70: start an external activity without crashing when nothing on the device handles it. */
fun startExternal(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

fun openWebPage(context: Context, url: String) {
    if (!startExternal(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
        Toast.makeText(context, context.getString(R.string.no_browser, url), Toast.LENGTH_LONG).show()
    }
}
