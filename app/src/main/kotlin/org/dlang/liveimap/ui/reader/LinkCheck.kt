package org.dlang.liveimap.ui.reader

import java.net.IDN
import java.net.URI

/** What the link-confirm dialog shows: the real destination host and whether the visible link text claims another. */
data class LinkCheck(
    val url: String,
    /** Destination host as typed (Unicode), lower-case; "" for non-web links. */
    val host: String,
    /** ASCII (punycode) form when the host has non-ASCII characters (look-alike letters), else "". */
    val asciiHost: String,
    /** Host the visible link text shows, when it differs from [host]; else "". */
    val claimedHost: String,
)

private const val MaxScan = 2_000_000
private const val MaxAnchors = 2000
private val anchorRe = Regex("""<a\b[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>(.*?)</a\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val tagRe = Regex("<[^>]*>")
private val domainRe = Regex("""(?:https?://)?((?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,62}[\p{L}\p{N}])?\.)+[\p{L}]{2,63})(?=[/:?#\s]|$)""",
    RegexOption.IGNORE_CASE)

fun webHost(url: String): String {
    val uri = try { URI(url.trim()) } catch (_: Exception) { return "" }
    val scheme = uri.scheme?.lowercase() ?: return ""
    if (scheme != "http" && scheme != "https") return ""
    val raw = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: return ""
    val h = raw.trim('.').lowercase()
    return try { IDN.toUnicode(h) } catch (_: Exception) { h }
}

private fun asciiOf(host: String): String =
    if (host.all { it.code < 0x80 }) "" else try { IDN.toASCII(host) } catch (_: Exception) { "" }

private fun bare(host: String): String = host.lowercase().removePrefix("www.")

/** Same site: equal, or one is a subdomain of the other (www. ignored). */
fun sameSite(real: String, claimed: String): Boolean {
    val a = bare(real)
    val b = bare(claimed)
    return a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

private fun decodeEntities(s: String): String = s
    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

/** Host shown in the visible text of anchors in [html] whose href is [url], when it is a different site. */
fun claimedHostFor(html: String, url: String): String {
    val real = webHost(url)
    if (real.isEmpty()) return ""
    val text = if (html.length > MaxScan) html.substring(0, MaxScan) else html
    var n = 0
    for (m in anchorRe.findAll(text)) {
        if (++n > MaxAnchors) break
        val href = decodeEntities(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).trim()
        if (webHost(href) != real) continue
        val shown = decodeEntities(tagRe.replace(m.groupValues[4], " ")).trim()
        val claimed = domainRe.find(shown)?.groupValues?.get(1)?.trim('.')?.lowercase() ?: continue
        val claimedUnicode = try { IDN.toUnicode(claimed) } catch (_: Exception) { claimed }
        if (!sameSite(real, claimedUnicode)) return claimedUnicode
    }
    return ""
}

fun checkLink(url: String, html: String?): LinkCheck {
    val host = webHost(url)
    return LinkCheck(url, host, asciiOf(host), if (html != null) claimedHostFor(html, url) else "")
}
