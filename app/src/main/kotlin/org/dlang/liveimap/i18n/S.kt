package org.dlang.liveimap.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * English is the sentence at the call. [StringCatalog] maps it to the resource name it already has.
 * A call that passes a variable is a lookup, not a definition.
 */
@Composable
@ReadOnlyComposable
fun S(english: String, vararg args: Any): String = S(LocalContext.current, english, *args)

fun S(context: Context, english: String, vararg args: Any): String {
    val id = StringCatalog.id(english)
    if (id == 0) return formatSentence(english, args)
    return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}

/** Quantity strings are the English sentences. [one] may be absent; [other] is required. */
@Composable
@ReadOnlyComposable
fun P(count: Int, vararg args: Any, one: String? = null, other: String): String =
    P(LocalContext.current, count, *args, one = one, other = other)

fun P(context: Context, count: Int, vararg args: Any, one: String? = null, other: String): String {
    val id = StringCatalog.plural(one, other)
    if (id == 0) {
        val sentence = if (count == 1 && one != null) one else other
        return formatSentence(sentence, args)
    }
    return if (args.isEmpty()) {
        context.resources.getQuantityString(id, count)
    } else {
        context.resources.getQuantityString(id, count, *args)
    }
}

internal fun formatSentence(english: String, args: Array<out Any>): String {
    if (args.isEmpty()) return english
    return try {
        String.format(Locale.getDefault(), english, *args)
    } catch (_: Exception) {
        english
    }
}
