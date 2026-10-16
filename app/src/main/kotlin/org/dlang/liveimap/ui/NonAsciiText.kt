package org.dlang.liveimap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import org.dlang.liveimap.settings.NonAsciiDisplay

/**
 * Anti-phishing display of non-ASCII text in folder names, From names/addresses and subjects.
 *
 * Suspicious = a word mixing letter scripts (Cyrillic а inside a Latin word), a non-Latin word made only of
 * Latin look-alikes next to Latin text ("аррӏе"), invisible or bidi control characters, and IDN domains
 * (xn-- labels or non-ASCII labels) in addresses. Plain non-Latin text (Café, Grüße, Привет, 日本語) is not
 * suspicious.
 */
object NonAsciiText {
    /** Display mode of the open account, set by the nav host. */
    var active by mutableStateOf(NonAsciiDisplay.HighlightSuspicious)

    private val lookalikes: Set<Int> = (
        "аАвВеЕһНіІјЈкКмМоОрРсСѕЅтТхХуУԁԛԝӏӀ" + // Cyrillic
            "ΑΒΕΖΗΙΚΜΝΟΡΤΥΧαικνορτυχ" + // Greek
            "ꓲꓳꓴꓵꓶꓷꓸꓹꓺꓻꓼꓽ" // Lisu
        ).codePoints().toArray().toSet()

    private fun invisible(cp: Int): Boolean =
        cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x2069 ||
            cp == 0xFEFF || cp == 0x00AD || cp == 0x034F || cp == 0x180E ||
            cp == 0x061C || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 ||
            cp == 0x2028 || cp == 0x2029

    private fun script(cp: Int): Character.UnicodeScript? {
        if (!Character.isLetter(cp)) return null
        val s = Character.UnicodeScript.of(cp)
        return if (s == Character.UnicodeScript.COMMON || s == Character.UnicodeScript.INHERITED) null else s
    }

    /** Japanese/Chinese/Korean mix scripts in ordinary words; count them as one. */
    private val cjk = listOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
    ).associateWith { "CJK" }

    /** Full-width ASCII look-alikes (ｐａｙｐａｌ) count as their own "script" next to Latin. */
    private fun fullWidth(cp: Int) = cp in 0xFF01..0xFF5E

    private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
        invisible(cp) || cp == '-'.code || cp == '_'.code

    /** Char ranges (end exclusive) that look like spoofing. */
    fun suspiciousRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val textHasLatin = text.codePoints().anyMatch { script(it) == Character.UnicodeScript.LATIN }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isWordChar(cp)) { i += Character.charCount(cp); continue }
            val start = i
            val scripts = HashSet<Any>()
            var allLook = true
            var letters = 0
            var hidden = false
            while (i < text.length) {
                val c = text.codePointAt(i)
                if (!isWordChar(c)) break
                if (invisible(c)) hidden = true
                if (fullWidth(c)) scripts += "fullwidth"
                script(c)?.let { s ->
                    scripts += cjk[s] ?: s
                    letters++
                    if (c !in lookalikes) allLook = false
                }
                i += Character.charCount(c)
            }
            val word = text.substring(start, i)
            val nonLatinLook = letters >= 2 && allLook && textHasLatin && Character.UnicodeScript.LATIN !in scripts
            val idn = word.split('.', '-').any { it.startsWith("xn--", ignoreCase = true) } ||
                word.lowercase().startsWith("xn--")
            if (hidden || scripts.size > 1 || nonLatinLook || idn) out += start until i
        }
        return out
    }

    /** Domain part of an address containing an IDN (punycode or non-ASCII); whole domain range. */
    fun idnDomainRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        Regex("@([^\\s<>()\\[\\],;\"]+)").findAll(text).forEach { m ->
            val d = m.groups[1]!!
            if (d.value.any { it.code > 0x7F } || d.value.split('.').any { it.startsWith("xn--", true) }) out += d.range
        }
        return out
    }

    fun nonAsciiRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp > 0x7F) out += i until i + n
            i += n
        }
        return out
    }

    fun isSuspicious(text: String) = suspiciousRanges(text).isNotEmpty() || idnDomainRanges(text).isNotEmpty()

    /** \u{XXXX} for every non-ASCII code point, so nothing can pass as ASCII. */
    fun escaped(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            if (cp in 0x20..0x7E || cp == '\t'.code) appendCodePoint(cp) else append("\\u{%04X}".format(cp))
        }
    }

    const val WarnMark = "\u26A0 "

    fun styled(text: String, mode: NonAsciiDisplay, highlight: SpanStyle): AnnotatedString {
        val idn = idnDomainRanges(text)
        if (idn.isEmpty() && text.all { it.code < 0x80 } &&
            mode != NonAsciiDisplay.HighlightSuspicious && mode != NonAsciiDisplay.Warn
        ) {
            return AnnotatedString(text)
        }
        return when (mode) {
            NonAsciiDisplay.Normal -> AnnotatedString(text)
            NonAsciiDisplay.Escape -> if (idn.isEmpty()) AnnotatedString(escaped(text)) else markEscaped(text, idn, highlight)
            NonAsciiDisplay.HighlightAll -> mark(text, nonAsciiRanges(text) + suspiciousRanges(text) + idn, highlight)
            NonAsciiDisplay.HighlightSuspicious -> mark(text, suspiciousRanges(text) + idn, highlight)
            NonAsciiDisplay.Warn -> {
                val ranges = suspiciousRanges(text) + idn
                if (ranges.isEmpty()) AnnotatedString(text)
                else buildAnnotatedString { append(WarnMark); append(mark(text, ranges, highlight)) }
            }
        }
    }

    /** Escape text, then mark [ranges] from the original string on the escaped form. */
    private fun markEscaped(text: String, ranges: List<IntRange>, highlight: SpanStyle): AnnotatedString {
        val shown = StringBuilder()
        val startAt = IntArray(text.length)
        val endAt = IntArray(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val start = shown.length
            if (cp in 0x20..0x7E || cp == '\t'.code) shown.appendCodePoint(cp) else shown.append("\\u{%04X}".format(cp))
            val end = shown.length
            for (k in 0 until n) {
                startAt[i + k] = start
                endAt[i + k] = end
            }
            i += n
        }
        return mark(shown.toString(), ranges.map { startAt[it.first] until endAt[it.last] }, highlight)
    }

    private fun mark(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString {
        if (ranges.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            ranges.forEach { addStyle(style, it.first, it.last + 1) }
        }
    }
}

/** [text] styled per the active account's non-ASCII display mode. */
@Composable
fun nonAscii(text: String): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    // Background plus underline: the underline survives black-and-white e-ink.
    val style = SpanStyle(background = cs.errorContainer, color = cs.onErrorContainer, textDecoration = TextDecoration.Underline)
    return NonAsciiText.styled(text, NonAsciiText.active, style)
}

