package org.dlang.liveimap.i18n.literal

import java.util.Locale

/**
 * The English sentence, for a caller with no Context.
 * Display goes through [org.dlang.liveimap.i18n.S], which localizes this text.
 */
fun S(english: String, vararg args: Any): String {
    if (args.isEmpty()) return english
    return try {
        String.format(Locale.getDefault(), english, *args)
    } catch (_: Exception) {
        english
    }
}
