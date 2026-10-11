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
