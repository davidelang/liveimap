package org.dlang.liveimap.ui
import org.dlang.liveimap.i18n.S

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

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
        Toast.makeText(context, S(context, "No app can open %1\$s", url), Toast.LENGTH_LONG).show()
    }
}
