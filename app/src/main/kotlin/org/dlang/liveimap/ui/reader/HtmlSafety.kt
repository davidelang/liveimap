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
    if (isForMainFrame) return true
    val s = scheme?.lowercase() ?: return false
    if (s != "http" && s != "https") return false
    return accept?.trim()?.lowercase()?.startsWith("image/") == true
}
