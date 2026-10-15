package org.dlang.liveimap.engine

import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.ui.index.parseAdvancedQuery

/*
 * F42: libetpan sends mailbox names and search strings as IMAP quoted strings without escaping CR/LF, and
 * keywords as raw tokens. A CR or LF there splits the command and the rest runs as a new command (a folder
 * name from LIST on a shared namespace, a pasted search, a keyword). Everything bound for the wire passes
 * through these checks first; such text is refused, never sent.
 */

fun wireTextOk(text: String): Boolean = text.none { it == '\r' || it == '\n' || it == '\u0000' }

fun requireWireText(text: String): String {
    if (!wireTextOk(text)) throw MailFailure("text with a line break cannot be sent to the server")
    return text
}

/** RFC 3501 atom (flag-keyword), or a system flag like \Seen. */
fun flagAtomOk(flag: String): Boolean {
    val body = if (flag.startsWith("\\")) flag.substring(1) else flag
    if (body.isEmpty()) return false
    return body.all { c -> c.code in 0x21..0x7e && c !in "(){ %*\"\\]" }
}

fun requireFlags(flags: Collection<String>): Array<String> {
    for (flag in flags) if (!flagAtomOk(flag)) throw MailFailure("not a valid IMAP keyword: ${flag.filter { it >= ' ' }}")
    return flags.toTypedArray()
}

private val keywordKinds = setOf("Keyword", "NotKeyword")

fun requireSearchArgument(kind: String, argument: String): String {
    // An Advanced query is newline-separated steps (IndexModel encodeAdvancedQuery); check each step instead.
    if (kind != "Advanced") requireWireText(argument)
    if (kind in keywordKinds && !flagAtomOk(argument)) throw MailFailure("not a valid IMAP keyword")
    if (kind == "Advanced") {
        val parsed = parseAdvancedQuery(argument)
        parsed?.steps?.forEach { step ->
            if (step.argument.contains('\r') || step.argument.contains('\u0000')) throw MailFailure("text with a line break cannot be sent to the server")
            if (step.kind in keywordKinds && !flagAtomOk(step.argument)) throw MailFailure("not a valid IMAP keyword")
        }
    }
    return argument
}

/** SORT/THREAD criteria. An advanced query keeps its newlines; each step is checked. Raw IMAP text still refuses CR, LF, and NUL. */
fun requireCriteria(criteria: String): String {
    val parsed = parseAdvancedQuery(criteria)
    if (parsed == null) return requireWireText(criteria)
    parsed.steps.forEach { step ->
        if (step.argument.contains('\r') || step.argument.contains('\u0000')) throw MailFailure("text with a line break cannot be sent to the server")
        if (step.kind in keywordKinds && !flagAtomOk(step.argument)) throw MailFailure("not a valid IMAP keyword")
    }
    return criteria
}
