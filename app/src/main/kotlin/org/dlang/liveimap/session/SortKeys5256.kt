package org.dlang.liveimap.session

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F108: client-side sort keys as RFC 5256 defines them, so the fallback orders like server SORT.
 */

private val blob = Regex("""^\[[^\[\]]*]\s*""")
private val refwd = Regex("""^(re|fwd?)\s*(\[[^\[\]]*]\s*)?:\s*""", RegexOption.IGNORE_CASE)
private val trailer = Regex("""\s*\(fwd\)\s*$""", RegexOption.IGNORE_CASE)

/** RFC 5256 2.1 base subject (on decoded text), case-folded for comparison. */
fun baseSubject(decoded: String): String {
    var s = decoded.replace(Regex("""\s+"""), " ").trim()
    while (true) {
        val before = s
        // (2) trailing "(fwd)"
        while (true) {
            val t = trailer.replace(s, "").trim()
            if (t == s) break
            s = t
        }
        // (5) leading subj-refwd and [blob] (a blob only when something remains after it)
        while (true) {
            val r = refwd.find(s)
            if (r != null) {
                s = s.substring(r.range.last + 1).trim()
                continue
            }
            val b = blob.find(s)
            if (b != null && s.length > b.range.last + 1) {
                s = s.substring(b.range.last + 1).trim()
                continue
            }
            break
        }
        // (6) "[fwd:" ... "]" wrapper
        if (s.startsWith("[fwd:", ignoreCase = true) && s.endsWith("]")) s = s.substring(5, s.length - 1).trim()
        if (s == before) break
    }
    return s.lowercase(Locale.ROOT)
}

/** RFC 5256 FROM/TO/CC key: the addr-mailbox (local part) of the first address, case-folded. */
fun firstAddrMailbox(header: String): String {
    val first = header.split(',').firstOrNull().orEmpty()
    val spec = Regex("<([^>]*)>").find(first)?.groupValues?.get(1) ?: first
    return spec.trim().substringBefore('@').trim().trim('"').lowercase(Locale.ROOT)
}

/** RFC 5256 DATE key from the Date: header, epoch seconds; null when absent or unparseable. */
fun dateHeaderEpoch(header: String): Long? {
    val text = header.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\s+"""), " ").trim()
    if (text.isEmpty()) return null
    val noDay = text.substringAfter(", ", text)
    return try {
        ZonedDateTime.parse(noDay, DateTimeFormatter.ofPattern("d MMM yyyy H:mm[:ss] Z", Locale.ENGLISH)).toEpochSecond()
    } catch (_: Exception) {
        null
    }
}
