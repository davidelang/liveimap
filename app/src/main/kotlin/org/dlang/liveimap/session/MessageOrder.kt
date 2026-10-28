package org.dlang.liveimap.session

import java.util.Locale

/** How a client sort compares text keys. [Locale] is the default. Server SORT is not re-placed with this. */
enum class ClientSortCollation {
    Locale,
    Plain,
}

/**
 * One captured order. [uids] and [depths] are arrays, not boxed lists. [depths] is empty when this is not a thread.
 * A client sort also keeps parallel key columns; a server reply does not.
 */
class MessageOrder(
    val uids: LongArray,
    val depths: ShortArray,
    val mailbox: String,
    val accountId: String,
    val uidValidity: Long,
    val dateEpoch: LongArray,
    val size: LongArray,
    val textKey: ByteArray,
    val textOff: IntArray,
    val textLen: IntArray,
) {
    /** 1-based position. An unknown uid is a failure, not position 0. */
    fun sequence(uid: Long): Int {
        val index = uids.indexOf(uid)
        if (index < 0) throw MailFailure("anchor $uid")
        return index + 1
    }
}

/** Rows fetched for one client-sort chunk. A UID the server no longer has is absent. */
class ClientFetch(
    val uids: LongArray,
    val numbers: LongArray,
    val blanks: BooleanArray,
    val texts: Array<String>,
)

/** Headers for a client thread, one captured UID set. */
class ClientHeaders(
    val uids: LongArray,
    val messageId: Array<String>,
    val references: Array<String>,
    val inReplyTo: Array<String>,
    val subject: Array<String>,
    val date: Array<String>,
)

/** Thread order as arrays: preorder [uids] and [depths]. A negative uid is a placeholder with no message. */
class ThreadFlat(
    val uids: LongArray,
    val depths: ShortArray,
) {
    fun messageCount(): Int {
        var n = 0
        for (uid in uids) if (uid >= 0L) n++
        return n
    }
}

/** [flat] as a [ThreadNode] tree. The synthetic root has a null uid. */
fun threadNodeOf(flat: ThreadFlat): ThreadNode = messageOrderThread(
    MessageOrder(
        flat.uids,
        flat.depths,
        "",
        "",
        0L,
        LongArray(0),
        LongArray(0),
        ByteArray(0),
        IntArray(0),
        IntArray(0),
    ),
)

/** Preorder arrays back into a [ThreadNode]. The synthetic root has a null uid and is not stored. */
fun messageOrderThread(order: MessageOrder): ThreadNode {
    if (order.depths.isEmpty()) {
        return ThreadNode(null, order.uids.map { ThreadNode(if (it < 0L) null else it, emptyList()) })
    }
    val rootChildren = ArrayList<ThreadNode>()
    val stack = ArrayDeque<Pair<Int, MutableList<ThreadNode>>>()
    stack.addLast(-1 to rootChildren)
    val n = minOf(order.uids.size, order.depths.size)
    for (i in 0 until n) {
        val depth = order.depths[i].toInt()
        while (stack.size > 1 && stack.last().first >= depth) stack.removeLast()
        val children = ArrayList<ThreadNode>()
        val uid = order.uids[i]
        stack.last().second.add(ThreadNode(if (uid < 0L) null else uid, children))
        stack.addLast(depth to children)
    }
    return ThreadNode(null, rootChildren)
}

/** Plain text key: Unicode lowercase of the whole string, not only A–Z. */
fun plainKeyBytes(text: String): ByteArray = text.lowercase(Locale.ROOT).encodeToByteArray()

/** Locale text key: ICU collation bytes. One collator for the whole sort. */
fun localeKeyBytes(texts: Array<String>): Triple<ByteArray, IntArray, IntArray> {
    val collator = android.icu.text.Collator.getInstance(Locale.getDefault())
    val parts = Array(texts.size) { collator.getCollationKey(texts[it]).toByteArray() }
    return packKeyBytes(parts)
}

fun packKeyBytes(parts: Array<ByteArray>): Triple<ByteArray, IntArray, IntArray> {
    var total = 0
    for (part in parts) total += part.size
    val bytes = ByteArray(total)
    val off = IntArray(parts.size)
    val len = IntArray(parts.size)
    var at = 0
    for (i in parts.indices) {
        off[i] = at
        len[i] = parts[i].size
        parts[i].copyInto(bytes, at)
        at += parts[i].size
    }
    return Triple(bytes, off, len)
}

private val subjectSpace = Regex("""\s+""")
private val blob = Regex("""^\[[^\[\]]*]\s*""")
private val refwd = Regex("""^(re|fwd?)\s*(\[[^\[\]]*]\s*)?:\s*""", RegexOption.IGNORE_CASE)
private val trailer = Regex("""\s*\(fwd\)\s*$""", RegexOption.IGNORE_CASE)

/** RFC 5256 base subject on decoded text, folded with [Locale.ROOT]. Plain client order uses these bytes. */
fun baseSubject(decoded: String): String {
    var s = decoded.replace(subjectSpace, " ").trim()
    while (true) {
        val before = s
        while (true) {
            val t = trailer.replace(s, "").trim()
            if (t == s) break
            s = t
        }
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
        if (s.startsWith("[fwd:", ignoreCase = true) && s.endsWith("]")) s = s.substring(5, s.length - 1).trim()
        if (s == before) break
    }
    return s.lowercase(Locale.ROOT)
}

/**
 * RFC 5256 FROM/TO/CC key: the addr-mailbox of the first address, case-folded.
 * Quotes, comments, angle addresses, obs-routes and groups are honoured. Encoded words in the local part are
 * decoded afterwards by [decode].
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
                if (c == '\\' && i + 1 < header.length) {
                    i++
                    (if (inAngle) angle else plain).append(header[i])
                } else if (c == '"') {
                    inQuote = false
                } else {
                    (if (inAngle) angle else plain).append(c)
                }
            }
            depth > 0 -> {
                if (c == '\\') i++
                else if (c == '(') depth++
                else if (c == ')') depth--
            }
            c == '(' -> depth = 1
            c == '"' -> inQuote = true
            inAngle -> if (c == '>') inAngle = false else angle.append(c)
            c == '<' -> {
                inAngle = true
                sawAngle = true
                angle.setLength(0)
            }
            c == ':' -> plain.setLength(0)
            c == ',' || c == ';' -> {
                if (found()) break
                plain.setLength(0)
                angle.setLength(0)
                sawAngle = false
            }
            else -> plain.append(c)
        }
        i++
    }
    var spec = (if (sawAngle) angle else plain).toString().trim()
    if (sawAngle) spec = spec.substringAfterLast(':')
    val at = spec.lastIndexOf('@')
    val local = if (at >= 0) spec.substring(0, at) else spec
    return decode(local.trim()).trim().lowercase(Locale.ROOT)
}

private val dateComment = Regex("""\([^)]*\)""")
private val dateSpace = Regex("""\s+""")
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
 * RFC 5256 DATE key from the Date header, epoch seconds. Null when absent or unparseable.
 * Obsolete zones, 2-digit years and 3-digit years match the previous Plain cases.
 */
fun dateHeaderEpoch(header: String): Long? {
    val text = header.replace(dateComment, " ").replace(dateSpace, " ").trim()
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
