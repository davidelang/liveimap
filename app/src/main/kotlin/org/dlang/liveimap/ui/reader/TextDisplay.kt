package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.TextLayout

/** The reader's four-way display selector (David, 2026-10-12): one control, replacing the HTML/text toggle. */
enum class ReaderDisplay { Html, Text, Flowed, Monospaced }

/** The selector position for a body view and a text layout. */
fun readerDisplayOf(view: BodyView, layout: TextLayout): ReaderDisplay = when {
    view == BodyView.PlainOrHtml -> ReaderDisplay.Html
    layout == TextLayout.Flowed -> ReaderDisplay.Flowed
    layout == TextLayout.Monospaced -> ReaderDisplay.Monospaced
    else -> ReaderDisplay.Text
}

/** The text layout a selector position uses (HTML keeps [current] for its plain fallback). */
fun textLayoutOf(display: ReaderDisplay, current: TextLayout): TextLayout = when (display) {
    ReaderDisplay.Html -> current
    ReaderDisplay.Text -> TextLayout.Text
    ReaderDisplay.Flowed -> TextLayout.Flowed
    ReaderDisplay.Monospaced -> TextLayout.Monospaced
}

/** The body view a selector position asks for, given the view now shown. */
fun bodyViewOf(display: ReaderDisplay, current: BodyView): BodyView = when {
    display == ReaderDisplay.Html -> BodyView.PlainOrHtml
    current == BodyView.PlainOrHtml || current == BodyView.PlainOrText -> BodyView.PlainOrError
    else -> current
}

/**
 * Lines to show for [lines] in [layout]. Text and Monospaced keep the original breaks. Flowed joins RFC 3676
 * soft breaks when [flowed] (format=flowed; [delSp] drops the soft-break space), and otherwise reflows ordinary
 * paragraphs sensibly. Quote depth is kept: each output line carries its own "> " prefix.
 */
fun displayLines(lines: List<String>, layout: TextLayout, flowed: Boolean, delSp: Boolean): List<String> = when (layout) {
    TextLayout.Text, TextLayout.Monospaced -> lines
    TextLayout.Flowed -> if (flowed) unflow(lines, delSp) else reflow(lines)
}

private data class Quoted(val depth: Int, val text: String)

private fun quoted(line: String, stuffed: Boolean): Quoted {
    var i = 0
    var depth = 0
    while (i < line.length && line[i] == '>') { depth++; i++ }
    var rest = line.substring(i)
    if (stuffed && rest.startsWith(" ")) rest = rest.substring(1) // RFC 3676 4.4 space-stuffing
    return Quoted(depth, rest)
}

private fun prefix(depth: Int): String = if (depth == 0) "" else ">".repeat(depth) + " "

private fun isSignature(text: String) = text == "-- "

/** RFC 3676: a line ending in a space (not the signature line) continues on the next line of the same depth. */
internal fun unflow(lines: List<String>, delSp: Boolean): List<String> {
    val out = ArrayList<String>()
    var depth = -1
    val cur = StringBuilder()
    var open = false
    fun flush() {
        if (open) out += prefix(depth) + cur.toString()
        cur.setLength(0)
        open = false
    }
    for (line in lines) {
        val q = quoted(line, stuffed = true)
        if (open && q.depth != depth) flush()
        depth = q.depth
        val soft = q.text.endsWith(" ") && !isSignature(q.text)
        cur.append(if (soft && delSp) q.text.dropLast(1) else q.text)
        open = true
        if (!soft) flush()
    }
    flush()
    return out
}

private val listMarker = Regex("""^\s*([-*+•]|\d+[.)]|[a-zA-Z][.)])\s""")

/** A line that must keep its own break: blank, indented, list item, table-ish, signature, or header-like. */
private fun structured(text: String): Boolean =
    text.isBlank() || text[0] == ' ' || text[0] == '\t' || listMarker.containsMatchIn(text) ||
        text.contains('\t') || text.contains("   ") || text.trimEnd() == "--" || isSignature(text) ||
        text.all { it == '-' || it == '=' || it == '_' || it == '*' || it == ' ' }

/**
 * Ordinary text (not format=flowed): join a line to the previous one when both are prose at the same quote
 * depth and the previous line ran near the message's wrap width (so short lines such as addresses, signatures
 * and poetry keep their breaks).
 */
internal fun reflow(lines: List<String>): List<String> {
    val parsed = lines.map { quoted(it, stuffed = false).let { q -> Quoted(q.depth, q.text.trimStart(' ').let { t -> if (q.depth > 0) t else q.text }) } }
    val width = parsed.filter { !structured(it.text) }.map { it.text.trimEnd().length }.sorted().let { l ->
        if (l.isEmpty()) 0 else l[(l.size * 9) / 10.coerceAtLeast(1)].coerceAtMost(l.last())
    }
    val threshold = maxOf(30, (width * 0.6).toInt())
    val out = ArrayList<String>()
    var depth = -1
    val cur = StringBuilder()
    var prevLen = 0
    var open = false
    fun flush() {
        if (open) out += prefix(depth) + cur.toString()
        cur.setLength(0)
        open = false
    }
    for (q in parsed) {
        val text = q.text.trimEnd()
        val canJoin = open && q.depth == depth && !structured(q.text) && prevLen >= threshold && cur.isNotEmpty()
        if (canJoin) {
            cur.append(' ').append(text)
        } else {
            flush()
            depth = q.depth
            cur.append(if (structured(q.text)) q.text.trimEnd() else text)
            open = true
        }
        prevLen = text.length
        if (structured(q.text)) { flush(); prevLen = 0 }
    }
    flush()
    return out
}

/**
 * Display list for a body that arrives in pieces. Text and Monospaced append each piece. Format=flowed keeps the
 * open paragraph (depth, text, and the first raw line it covers) and runs [unflow] again from that line. A plain
 * body under the Flowed layout stays unjoined until [finished], then [reflow] sees every raw line since [reset].
 */
internal class FlowedPush(val lines: List<String>, val firstChanged: Int)

internal class FlowedStream {
    private val raw = ArrayList<String>()
    private val shown = ArrayList<String>()
    private var open = false
    private var openDepth = 0
    private var openText = ""
    private var openAt = 0

    fun reset() {
        raw.clear()
        shown.clear()
        open = false
        openDepth = 0
        openText = ""
        openAt = 0
    }

    fun push(
        newLines: List<String>,
        layout: TextLayout,
        flowed: Boolean,
        delSp: Boolean,
        finished: Boolean,
    ): FlowedPush {
        val first = when (layout) {
            TextLayout.Text, TextLayout.Monospaced -> {
                val at = shown.size
                shown.addAll(newLines)
                at
            }
            TextLayout.Flowed -> if (flowed) pushFlowed(newLines, delSp) else pushPlain(newLines, finished)
        }
        return FlowedPush(ArrayList(shown.subList(first, shown.size)), first)
    }

    private fun pushFlowed(newLines: List<String>, delSp: Boolean): Int {
        val start = if (open) openAt else raw.size
        val first = if (open && shown.lastOrNull() == prefix(openDepth) + openText) shown.lastIndex else shown.size
        if (first < shown.size) shown.subList(first, shown.size).clear()
        raw.addAll(newLines)
        shown.addAll(unflow(raw.subList(start, raw.size), delSp))
        rememberOpen(start, delSp)
        return first
    }

    private fun pushPlain(newLines: List<String>, finished: Boolean): Int {
        raw.addAll(newLines)
        open = false
        openDepth = 0
        openText = ""
        openAt = raw.size
        if (finished) {
            shown.clear()
            shown.addAll(reflow(raw))
            return 0
        }
        val first = shown.size
        shown.addAll(newLines)
        return first
    }

    /** The last paragraph stays open when its line is a soft break and nothing after it closed it. */
    private fun rememberOpen(start: Int, delSp: Boolean) {
        var depth = 0
        var at = start
        var active = false
        val cur = StringBuilder()
        for (i in start until raw.size) {
            val q = quoted(raw[i], stuffed = true)
            if (active && q.depth != depth) active = false
            if (!active) {
                at = i
                cur.setLength(0)
            }
            depth = q.depth
            val soft = q.text.endsWith(" ") && !isSignature(q.text)
            cur.append(if (soft && delSp) q.text.dropLast(1) else q.text)
            active = true
            if (!soft) active = false
        }
        open = active
        if (active) {
            openDepth = depth
            openText = cur.toString()
            openAt = at
        } else {
            openDepth = 0
            openText = ""
            openAt = raw.size
        }
    }
}
