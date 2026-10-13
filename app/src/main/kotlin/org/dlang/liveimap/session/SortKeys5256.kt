package org.dlang.liveimap.session

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

/**
 * RFC 5256 FROM/TO/CC key: the addr-mailbox (local part) of the first address, case-folded.
 * F137: parsed on the raw header with quotes, comments, angle addresses, obs-routes and groups honoured, so
 * `"Doe, John" <jd@x>` gives `jd`. Encoded words in the local part are decoded afterwards by [decode].
 */
fun firstAddrMailbox(header: String, decode: (String) -> String = { it }): String {
    val plain = StringBuilder()
    val angle = StringBuilder()
    var inQuote = false
    var depth = 0
    var inAngle = false
    var sawAngle = false
    var i = 0
    fun found(): Boolean = (if (sawAngle) angle else plain).isNotBlank()
    while (i < header.length) {
        val c = header[i]
        when {
            inQuote -> {
                if (c == '\\' && i + 1 < header.length) { i++; (if (inAngle) angle else plain).append(header[i]) }
                else if (c == '"') inQuote = false
                else (if (inAngle) angle else plain).append(c)
            }
            depth > 0 -> {
                if (c == '\\') i++
                else if (c == '(') depth++
                else if (c == ')') depth--
            }
            c == '(' -> depth = 1
            c == '"' -> inQuote = true
            inAngle -> if (c == '>') inAngle = false else angle.append(c)
            c == '<' -> { inAngle = true; sawAngle = true; angle.setLength(0) }
            c == ':' -> plain.setLength(0) // group name ends; its first member follows
            c == ',' || c == ';' -> {
                if (found()) break
                plain.setLength(0); angle.setLength(0); sawAngle = false
            }
            else -> plain.append(c)
        }
        i++
    }
    var spec = (if (sawAngle) angle else plain).toString().trim()
    if (sawAngle) spec = spec.substringAfterLast(':') // obs-route "@a,@b:local@domain"
    val at = spec.lastIndexOf('@')
    val local = if (at >= 0) spec.substring(0, at) else spec
    return decode(local.trim()).trim().lowercase(Locale.ROOT)
}

private val dateShape = Regex(
    """^(?:[A-Za-z]+\s*,\s*)?(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,5})?""",
)
private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val obsZones = mapOf(
    "ut" to 0, "gmt" to 0, "z" to 0,
    "edt" to -4 * 60, "est" to -5 * 60, "cdt" to -5 * 60, "cst" to -6 * 60,
    "mdt" to -6 * 60, "mst" to -7 * 60, "pdt" to -7 * 60, "pst" to -8 * 60,
)

/**
 * RFC 5256 DATE key from the Date: header, epoch seconds; null when absent or unparseable.
 * F138: RFC 5322 §4.3 obsolete forms are accepted: alphabetic zones (UT, GMT, EST…; military letters and unknown
 * names count as +0000), 2-digit years (< 50 → 20xx) and 3-digit years (+1900).
 */
fun dateHeaderEpoch(header: String): Long? {
    val text = header.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\s+"""), " ").trim()
    if (text.isEmpty()) return null
    val m = dateShape.find(text) ?: return null
    val (d, mon, y, hh, mm) = m.destructured
    val ss = m.groupValues[6]
    val zone = m.groupValues[7]
    val month = months.indexOf(mon.lowercase(Locale.ROOT)) + 1
    if (month == 0) return null
    var year = y.toInt()
    year = when (y.length) {
        2 -> if (year < 50) 2000 + year else 1900 + year
        3 -> 1900 + year
        else -> year
    }
    val offsetMinutes = when {
        zone.isEmpty() -> 0
        zone[0] == '+' || zone[0] == '-' -> {
            val v = zone.substring(1)
            val minutes = v.substring(0, 2).toInt() * 60 + v.substring(2).toInt()
            if (zone[0] == '-') -minutes else minutes
        }
        else -> obsZones[zone.lowercase(Locale.ROOT)] ?: 0
    }
    return try {
        java.time.LocalDateTime.of(year, month, d.toInt(), hh.toInt(), mm.toInt(), if (ss.isEmpty()) 0 else ss.toInt())
            .toEpochSecond(java.time.ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
    } catch (_: Exception) {
        null
    }
}
