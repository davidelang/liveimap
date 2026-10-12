package org.dlang.liveimap.ui.reader

/**
 * Sender HTML runs with JS, file and content access off, but markup alone can still navigate: a
 * <meta http-equiv="refresh"> redirects without any click, and that navigation used to raise the link prompt.
 * Refresh tags are removed before loading, and only a real tap on a main-frame link may prompt.
 */
private val metaRefresh = Regex(
    """<meta\b[^>]*\bhttp-equiv\s*=\s*(?:"\s*refresh\s*"|'\s*refresh\s*'|refresh\b)[^>]*>""",
    setOf(RegexOption.IGNORE_CASE),
)

fun stripMetaRefresh(html: String): String = metaRefresh.replace(html, "")

/** Elements that can submit, navigate, embed or re-base sender HTML; removed with their content (F05). */
private val droppedElements = listOf(
    "script", "noscript", "form", "input", "button", "select", "textarea", "option", "iframe", "frame",
    "frameset", "object", "embed", "applet", "base", "meta", "link", "portal", "template", "dialog",
)

private val droppedAttributes = setOf("formaction", "action", "srcdoc", "ping", "http-equiv", "autofocus")

/** Content-Security-Policy for the reader: no script, no forms, no frames; images only. */
const val ReaderCsp =
    "default-src 'none'; img-src http: https: data: cid:; style-src 'unsafe-inline'; font-src 'none'; " +
        "form-action 'none'; frame-src 'none'; base-uri 'none'"

/**
 * Sender HTML with every form, frame, embed, script, base, meta and link element removed, and event-handler
 * and form-target attributes stripped. Layout markup and inline styles stay, so mail still looks like mail.
 */
fun sanitizeMailHtml(html: String): String {
    val doc = org.jsoup.Jsoup.parse(html)
    doc.outputSettings().prettyPrint(false)
    for (tag in droppedElements) doc.select(tag).remove()
    for (el in doc.getAllElements()) {
        val names = el.attributes().asList().map { it.key }
        for (name in names) {
            val lower = name.lowercase()
            if (lower.startsWith("on") || lower in droppedAttributes) el.removeAttr(name)
        }
    }
    return doc.outerHtml()
}

/**
 * The message page itself is loaded from data (no scheme, data: or about:). Any network main-frame request
 * is a navigation the URL hook did not see, such as a form POST, and is answered with an empty page (F05).
 */
fun mainFrameRequestAllowed(scheme: String?): Boolean =
    scheme == null || scheme.equals("data", ignoreCase = true) || scheme.equals("about", ignoreCase = true)

/** Whether a WebView navigation may raise the link prompt: a user tap, in the main frame, not a redirect. */
fun navigationMayPrompt(hasGesture: Boolean, isRedirect: Boolean, isForMainFrame: Boolean): Boolean =
    hasGesture && !isRedirect && isForMainFrame

enum class LinkSafety { Safe, Warn, Blocked }

private val blockedSchemes = setOf(
    "javascript", "vbscript", "data", "file", "content", "intent", "android-app", "about", "blob", "chrome",
    "chrome-extension", "jar", "view-source", "filesystem",
)

/**
 * Links in mail are sender-controlled. http, https and mailto open after the usual prompt; schemes that reach
 * local files, other apps' content, intents or script are blocked; anything else (tel:, sms:, geo:, app
 * schemes) gets an extra warning in the prompt.
 */
fun linkSafety(url: String): LinkSafety {
    val trimmed = url.trim()
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return LinkSafety.Blocked
    val scheme = trimmed.substring(0, colon).lowercase()
    if (!Regex("[a-z][a-z0-9+.-]*").matches(scheme)) return LinkSafety.Blocked
    return when (scheme) {
        "http", "https", "mailto" -> LinkSafety.Safe
        in blockedSchemes -> LinkSafety.Blocked
        else -> LinkSafety.Warn
    }
}

/**
 * With "show images" on, the WebView may reach the network, but only for images: stylesheets (and their
 * @import), fonts, frames, media, prefetches and other subresources stay blocked, so remote content is
 * limited to what the user asked for. Chromium marks image fetches (<img>, CSS backgrounds) with an
 * Accept header starting with image/.
 */
fun remoteRequestAllowed(scheme: String?, accept: String?, isForMainFrame: Boolean): Boolean {
    if (isForMainFrame) return mainFrameRequestAllowed(scheme)
    val s = scheme?.lowercase() ?: return false
    if (s != "http" && s != "https") return false
    return accept?.trim()?.lowercase()?.startsWith("image/") == true
}
