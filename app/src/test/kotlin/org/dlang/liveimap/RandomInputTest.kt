package org.dlang.liveimap

import kotlin.random.Random
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.ui.compose.addrSpec
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.ui.compose.splitAddresses
import org.dlang.liveimap.ui.index.parseAdvancedQuery
import org.dlang.liveimap.ui.reader.checkLink
import org.dlang.liveimap.ui.reader.decodeImageTrust
import org.dlang.liveimap.ui.reader.headerFields
import org.dlang.liveimap.ui.reader.htmlAsText
import org.dlang.liveimap.ui.reader.linkSafety
import org.dlang.liveimap.ui.reader.listIdKey
import org.dlang.liveimap.ui.reader.readerLines
import org.dlang.liveimap.ui.reader.sniffContent
import org.dlang.liveimap.ui.reader.stripMetaRefresh
import org.dlang.liveimap.ui.reader.typeMismatch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Random-input pass over the Kotlin code that handles server/mail-controlled text: header decoding, address
 * parsing, HTML-to-text, meta-refresh stripping, link checks, content sniffing, stored settings and filter queries.
 * Mutates seed inputs; any exception or a call slower than [SlowMs] is a finding. LIVEIMAP_FUZZ_ITERS raises the
 * per-target count (default keeps the unit run short); LIVEIMAP_FUZZ_SEED picks the run.
 */
class RandomInputTest {
    private val iters = System.getenv("LIVEIMAP_FUZZ_ITERS")?.toIntOrNull() ?: 400
    private val seed = System.getenv("LIVEIMAP_FUZZ_SEED")?.toLongOrNull() ?: 20261011L
    private val SlowMs = 2000L

    private val tokens = listOf(
        "=?UTF-8?B?", "=?utf-8?q?", "=?ISO-8859-1?Q?caf=E9?=", "?=", "=?", "==", "=00", "=FF", "\r\n ", "\r\n\t",
        "\r", "\n", "\u0000", "\uFFFD", "\uD800", "<", ">", "\"", "'", "\\", ",", ";", ":", "@", "(", ")", "<a href=\"",
        "</a>", "<meta http-equiv=refresh content=\"0;url=x\">", "<!--", "-->", "<script>", "&amp;", "&#x41;", "&#99999999;",
        "https://", "xn--", "javascript:", "%00", "\t", "\nSubject: x", "List-Id: <", "From: ", "\u202E", "\u00e9",
        "Or\n", "Yes\t", "\u001e", "\u001f", "Group\t", "theme=", "readerTextZoom=",
    )
    private val seeds = listOf(
        "=?UTF-8?B?w6l0w6k=?= <a@b.c>, \"Doe, J\" <j@d.e>",
        "From: Ann <ann@example.org>\r\nTo: b@c.d\r\nList-Id: Dev <dev.example>\r\nSubject: =?utf-8?q?hi_there?=\r\n",
        "<html><head><meta charset=utf-8></head><body><p>Hi <b>x</b></p><a href=\"https://a.test/x\">www.b.test</a>" +
            "<blockquote>q</blockquote><table><tr><td>1</td></tr></table></body></html>",
        "Or\nYes\tSubject\tLiveIMAP\nNo\tFrom\tbob",
        "imapHost=h\nimapPort=993\ntheme=Dark\nreaderTextZoom=130\n",
        "sender a@b.c\nlist l.example\n",
        "a\r\nb\r\n> quoted\r\n",
    )

    private fun mutate(r: Random, s: String): String {
        val sb = StringBuilder(s)
        repeat(1 + r.nextInt(8)) {
            when (r.nextInt(6)) {
                0 -> sb.insert(r.nextInt(sb.length + 1), tokens[r.nextInt(tokens.size)])
                1 -> if (sb.isNotEmpty()) sb.deleteCharAt(r.nextInt(sb.length))
                2 -> if (sb.isNotEmpty()) sb.setCharAt(r.nextInt(sb.length), r.nextInt(0, 0x3000).toChar())
                3 -> { val t = tokens[r.nextInt(tokens.size)]; sb.insert(r.nextInt(sb.length + 1), t.repeat(1 + r.nextInt(2000))) }
                4 -> if (sb.length > 2) { val a = r.nextInt(sb.length); val b = r.nextInt(a, sb.length); sb.insert(r.nextInt(sb.length), sb.substring(a, b)) }
                else -> sb.append(seeds[r.nextInt(seeds.size)])
            }
        }
        return sb.toString()
    }

    private val targets: List<Pair<String, (String) -> Unit>> = listOf(
        "decodeHeaderWords" to { s -> decodeHeaderWords(s) },
        "headerFields" to { s -> headerFields(s).let { listIdKey(it.listId) } },
        "splitAddresses" to { s -> splitAddresses(s).forEach { addrSpec(it) } },
        "htmlAsText" to { s -> htmlAsText(s) },
        "stripMetaRefresh" to { s -> stripMetaRefresh(s) },
        "checkLink" to { s -> checkLink(s.take(300), s); linkSafety(s.take(300)) },
        "sniff" to { s -> val b = s.toByteArray(Charsets.ISO_8859_1); sniffContent(b); typeMismatch("image/png", "x.png", b) },
        "decodeAccountSettings" to { s ->
            try { decodeAccountSettings(s) } catch (_: IllegalArgumentException) { /* documented rejection */ }
        },
        "decodeImageTrust" to { s -> decodeImageTrust(s) },
        "parseAdvancedQuery" to { s -> parseAdvancedQuery(s) },
        "readerLines" to { s -> readerLines(s) },
    )

    @Test
    fun randomInputsNeverThrowOrHang() {
        val findings = ArrayList<String>()
        for ((name, fn) in targets) {
            val r = Random(seed xor name.hashCode().toLong())
            for (i in 0 until iters) {
                val input = mutate(r, seeds[r.nextInt(seeds.size)])
                val start = System.nanoTime()
                try {
                    fn(input)
                } catch (e: Throwable) {
                    if (findings.size < 30) findings.add("$name #$i ${e::class.simpleName}: ${e.message?.take(120)} input=${input.take(160).replace("\n", "\\n")}")
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                if (ms > SlowMs && findings.size < 30) findings.add("$name #$i slow ${ms}ms len=${input.length}")
            }
        }
        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }
}
