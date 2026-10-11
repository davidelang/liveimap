package org.dlang.liveimap.ui.reader

/**
 * The MIME type handed to other apps for an attachment. The declared type comes from the sender, so only
 * passive document and media types pass through; anything else (APK, HTML, SVG, scripts, unknown) opens as
 * application/octet-stream, so it cannot go straight to the package installer or a browser that runs JS.
 */
private val allowedExact = setOf(
    "application/pdf",
    "application/rtf",
    "application/zip",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "text/plain",
    "text/csv",
    "text/calendar",
    "text/vcard",
    "text/x-vcard",
    "message/rfc822",
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
    "image/bmp",
    "image/heic",
    "image/heif",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/ogg",
    "audio/opus",
    "audio/wav",
    "audio/flac",
    "video/mp4",
    "video/webm",
    "video/3gpp",
)

private val allowedPrefixes = listOf(
    "application/vnd.openxmlformats-officedocument.",
    "application/vnd.oasis.opendocument.",
)

const val FallbackAttachmentMime = "application/octet-stream"

fun safeAttachmentMime(type: String, subtype: String): String {
    val t = type.trim().lowercase()
    val s = subtype.trim().lowercase()
    if (t.isEmpty() || s.isEmpty() || !Regex("[a-z0-9][a-z0-9!#$&^_.+-]*").matches(t) ||
        !Regex("[a-z0-9][a-z0-9!#$&^_.+-]*").matches(s)
    ) {
        return FallbackAttachmentMime
    }
    val mime = "$t/$s"
    if (mime in allowedExact || allowedPrefixes.any { mime.startsWith(it) }) return mime
    return FallbackAttachmentMime
}
