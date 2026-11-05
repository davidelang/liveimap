package org.dlang.liveimap.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * English at the call, plus the catalog key when that English is more than one resource.
 * [name] is the resource it already had. A call that passes a variable is a lookup, not a definition.
 */
class Phrase(val english: String, val name: String?) {
    override fun toString(): String = english

    override fun equals(other: Any?): Boolean = when (other) {
        is Phrase -> english == other.english
        is String -> english == other
        else -> false
    }

    override fun hashCode(): Int = english.hashCode()
}

/** A sentence carried to a later [S] call, still attached to [name]. */
fun phrase(english: String, name: String? = null): Phrase = Phrase(english, name)

@Composable
@ReadOnlyComposable
fun S(english: String, vararg args: Any, name: String? = null): String =
    S(LocalContext.current, english, *args, name = name)

fun S(context: Context, english: String, vararg args: Any, name: String? = null): String {
    val id = StringCatalog.id(english, name)
    if (id == 0) return formatSentence(english, args)
    return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}

@Composable
@ReadOnlyComposable
fun S(phrase: Phrase, vararg args: Any): String = S(phrase.english, *args, name = phrase.name)

fun S(context: Context, phrase: Phrase, vararg args: Any): String =
    S(context, phrase.english, *args, name = phrase.name)

/** Quantity strings are the English sentences. [one] may be absent; [other] is required. */
@Composable
@ReadOnlyComposable
fun P(count: Int, vararg args: Any, one: String? = null, other: String, name: String? = null): String =
    P(LocalContext.current, count, *args, one = one, other = other, name = name)

fun P(context: Context, count: Int, vararg args: Any, one: String? = null, other: String, name: String? = null): String {
    val id = StringCatalog.plural(one, other, name)
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
