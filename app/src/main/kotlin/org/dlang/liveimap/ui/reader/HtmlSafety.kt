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

/*
 * The reader never sends data without the user's approval (F05, reworked per David: markup, CSS and layout are
 * left alone). JavaScript is off, so there is no fetch/XHR/beacon/WebSocket. A link tap or GET form submission
 * is a navigation: shouldOverrideUrlLoading cancels it and only a real tap prompts. A POST form submission
 * never reaches that hook, so every network main-frame request is answered with an empty page. Subresources
 * load only as images, and only while "show images" is on.
 */

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
