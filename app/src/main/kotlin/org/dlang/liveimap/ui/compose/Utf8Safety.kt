package org.dlang.liveimap.ui.compose

import java.net.IDN

/**
 * UTF-8 safety for submission. A non-ASCII domain is sent as punycode (works everywhere); a non-ASCII local
 * part needs SMTPUTF8 (RFC 6531), which the native send checks against EHLO and refuses with "smtputf8".
 */
fun asciiDomainAddress(address: String): String {
    val at = address.lastIndexOf('@')
    if (at < 0) return address
    val domain = address.substring(at + 1)
    if (domain.all { it.code < 0x80 }) return address
    val ascii = runCatching { IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return address
    return address.substring(0, at + 1) + ascii
}

/** Addresses that still need SMTPUTF8 after punycoding the domain. */
fun needsSmtpUtf8(addresses: List<String>): List<String> =
    addresses.map(::asciiDomainAddress).filter { a -> a.any { it.code >= 0x80 } }

/**
 * Saved mailbox names from before UTF8=ACCEPT are modified UTF-7 (RFC 3501 5.1.3). Once the session uses
 * UTF-8 names, send the UTF-8 form; a name that is not strict mUTF-7 is left as it is.
 */
object Mutf7 {
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

    fun encode(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code in 0x20..0x7E) {
                if (c == '&') append("&-") else append(c)
                i++
                continue
            }
            val start = i
            while (i < text.length && text[i].code !in 0x20..0x7E) i++
            val bytes = text.substring(start, i).toByteArray(Charsets.UTF_16BE)
            append('&')
            var bits = 0
            var n = 0
            for (b in bytes) {
                bits = (bits shl 8) or (b.toInt() and 0xFF); n += 8
                while (n >= 6) { n -= 6; append(B64[(bits shr n) and 0x3F]) }
            }
            if (n > 0) append(B64[(bits shl (6 - n)) and 0x3F])
            append('-')
        }
    }

    /** Strict decode; null when [text] is not valid mUTF-7. */
    fun decodeOrNull(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code !in 0x20..0x7E) return null
            if (c != '&') { out.append(c); i++; continue }
            val end = text.indexOf('-', i + 1)
            if (end < 0) return null
            if (end == i + 1) { out.append('&'); i = end + 1; continue }
            var bits = 0
            var n = 0
            val bytes = java.io.ByteArrayOutputStream()
            for (ch in text.substring(i + 1, end)) {
                val v = B64.indexOf(ch)
                if (v < 0) return null
                bits = (bits shl 6) or v; n += 6
                if (n >= 8) { n -= 8; bytes.write((bits shr n) and 0xFF) }
            }
            if (bytes.size() % 2 != 0 || (bits and ((1 shl n) - 1)) != 0) return null
            val chunk = String(bytes.toByteArray(), Charsets.UTF_16BE)
            if (chunk.any { it.code in 0x20..0x7E }) return null // must have been plain
            out.append(chunk)
            i = end + 1
        }
        return out.toString()
    }

    /** Saved name to send on a UTF8=ACCEPT session. */
    fun savedForUtf8(name: String): String {
        if ('&' !in name) return name
        return decodeOrNull(name) ?: name
    }
}
