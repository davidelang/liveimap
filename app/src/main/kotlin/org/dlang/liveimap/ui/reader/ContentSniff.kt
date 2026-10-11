package org.dlang.liveimap.ui.reader

/**
 * A small `file`-style sniffer for attachments. The claimed MIME type and the filename both come from the
 * sender; the first few KB of the part (fetched with BODY.PEEK partial, or read from the cache) say what it
 * really is. A disagreement is shown to the user before the attachment is opened or saved.
 */
data class Sniffed(val mime: String, val label: String, val dangerous: Boolean = false)

const val SniffBytes = 4096

private fun ByteArray.startsWith(vararg prefix: Int, at: Int = 0): Boolean =
    size >= at + prefix.size && prefix.indices.all { (this[at + it].toInt() and 0xFF) == prefix[it] }

private fun ByteArray.startsWithAscii(text: String, at: Int = 0): Boolean =
    size >= at + text.length && text.indices.all { this[at + it].toInt() == text[it].code }

private fun ByteArray.containsAscii(text: String): Boolean {
    val needle = text.toByteArray(Charsets.ISO_8859_1)
    if (needle.isEmpty() || size < needle.size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

private fun leadingText(head: ByteArray): String {
    var start = 0
    if (head.startsWith(0xEF, 0xBB, 0xBF)) start = 3
    val text = String(head, start, minOf(head.size - start, 1024).coerceAtLeast(0), Charsets.ISO_8859_1)
    return text.trimStart().lowercase()
}

fun sniffContent(head: ByteArray): Sniffed? {
    if (head.isEmpty()) return null
    when {
        head.startsWith(0x50, 0x4B, 0x03, 0x04) -> return when {
            head.containsAscii("AndroidManifest.xml") || head.containsAscii("classes.dex") ->
                Sniffed("application/vnd.android.package-archive", "Android app (APK)", dangerous = true)
            head.containsAscii("META-INF/MANIFEST.MF") -> Sniffed("application/java-archive", "Java archive (JAR)", dangerous = true)
            head.containsAscii("word/") -> Sniffed("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document")
            head.containsAscii("xl/") -> Sniffed("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel workbook")
            head.containsAscii("ppt/") -> Sniffed("application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint presentation")
            head.containsAscii("mimetypeapplication/vnd.oasis.opendocument.") -> Sniffed("application/vnd.oasis.opendocument", "OpenDocument file")
            head.containsAscii("mimetypeapplication/epub+zip") -> Sniffed("application/epub+zip", "EPUB book")
            else -> Sniffed("application/zip", "ZIP archive")
        }
        head.startsWith(0x7F, 0x45, 0x4C, 0x46) -> return Sniffed("application/x-executable", "Linux/Android program (ELF)", dangerous = true)
        head.startsWithAscii("MZ") && head.size >= 64 -> return Sniffed("application/vnd.microsoft.portable-executable", "Windows program (EXE)", dangerous = true)
        head.startsWithAscii("dex\n") -> return Sniffed("application/x-dex", "Android bytecode (DEX)", dangerous = true)
        head.startsWithAscii("%PDF-") -> return Sniffed("application/pdf", "PDF document")
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> return Sniffed("image/png", "PNG image")
        head.startsWith(0xFF, 0xD8, 0xFF) -> return Sniffed("image/jpeg", "JPEG image")
        head.startsWithAscii("GIF87a") || head.startsWithAscii("GIF89a") -> return Sniffed("image/gif", "GIF image")
        head.startsWithAscii("RIFF") && head.startsWithAscii("WEBP", at = 8) -> return Sniffed("image/webp", "WebP image")
        head.startsWithAscii("RIFF") && head.startsWithAscii("WAVE", at = 8) -> return Sniffed("audio/wav", "WAV audio")
        head.startsWithAscii("BM") && head.size >= 26 && head[14].toInt() in listOf(12, 40, 56, 108, 124) ->
            return Sniffed("image/bmp", "BMP image")
        head.startsWithAscii("ftyp", at = 4) -> {
            val brand = if (head.size >= 12) String(head, 8, 4, Charsets.ISO_8859_1) else ""
            return if (brand.startsWith("hei") || brand.startsWith("mif1") || brand == "heix") {
                Sniffed("image/heic", "HEIC image")
            } else {
                Sniffed("video/mp4", "MP4 video")
            }
        }
        head.startsWith(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> return Sniffed("application/x-ole-storage", "Office 97-2003 document (OLE)")
        head.startsWithAscii("Rar!\u001A\u0007") -> return Sniffed("application/vnd.rar", "RAR archive")
        head.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> return Sniffed("application/x-7z-compressed", "7-Zip archive")
        head.startsWith(0x1F, 0x8B) -> return Sniffed("application/gzip", "gzip archive")
        head.startsWithAscii("#!") -> return Sniffed("text/x-script", "script", dangerous = true)
    }
    val text = leadingText(head)
    return when {
        text.startsWith("<svg") || (text.startsWith("<?xml") && text.contains("<svg")) ->
            Sniffed("image/svg+xml", "SVG image (can contain scripts)", dangerous = true)
        listOf("<!doctype html", "<html", "<head", "<body", "<script", "<iframe").any { text.startsWith(it) } ||
            text.contains("<script") ->
            Sniffed("text/html", "web page (HTML)", dangerous = true)
        else -> null
    }
}

private val extensionTypes = mapOf(
    "pdf" to "application/pdf",
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heic",
    "svg" to "image/svg+xml", "htm" to "text/html", "html" to "text/html", "xhtml" to "text/html",
    "zip" to "application/zip", "apk" to "application/vnd.android.package-archive", "jar" to "application/java-archive",
    "exe" to "application/vnd.microsoft.portable-executable", "dll" to "application/vnd.microsoft.portable-executable",
    "scr" to "application/vnd.microsoft.portable-executable", "so" to "application/x-executable",
    "dex" to "application/x-dex", "sh" to "text/x-script", "py" to "text/x-script",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odt" to "application/vnd.oasis.opendocument", "ods" to "application/vnd.oasis.opendocument",
    "odp" to "application/vnd.oasis.opendocument", "epub" to "application/epub+zip",
    "doc" to "application/x-ole-storage", "xls" to "application/x-ole-storage", "ppt" to "application/x-ole-storage",
    "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed", "gz" to "application/gzip",
    "tgz" to "application/gzip", "mp4" to "video/mp4", "m4a" to "video/mp4", "mov" to "video/mp4",
    "wav" to "audio/wav",
)

fun extensionMime(filename: String?): String? {
    val name = filename?.trim()?.lowercase() ?: return null
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return null
    return extensionTypes[name.substring(dot + 1)]
}

/** Normalizes declared types to the sniffer's vocabulary, so equivalent spellings compare equal. */
private fun canonical(mime: String): String {
    val m = mime.trim().lowercase()
    return when {
        m.startsWith("application/vnd.oasis.opendocument") -> "application/vnd.oasis.opendocument"
        m == "application/msword" || m == "application/vnd.ms-excel" || m == "application/vnd.ms-powerpoint" ->
            "application/x-ole-storage"
        m == "image/jpg" || m == "image/pjpeg" -> "image/jpeg"
        m == "image/heif" -> "image/heic"
        m == "application/x-zip-compressed" || m == "application/x-zip" -> "application/zip"
        m == "application/x-rar-compressed" -> "application/vnd.rar"
        m == "application/x-gzip" -> "application/gzip"
        m == "audio/x-wav" || m == "audio/wave" -> "audio/wav"
        m == "application/x-msdownload" || m == "application/x-dosexec" -> "application/vnd.microsoft.portable-executable"
        m == "video/quicktime" || m == "audio/mp4" -> "video/mp4"
        m == "application/xhtml+xml" -> "text/html"
        else -> m
    }
}

/** Claimed types that do not say what the content is: they never disagree with a sniffed type by themselves. */
private fun uninformative(mime: String) = mime == "application/octet-stream" || mime.isEmpty() || mime == "application/x-liveimap-too-deep"

/** A generic ZIP sniff matches the ZIP-based formats (the marker may lie past the first KB). Not APK/JAR. */
private val zipBased = setOf(
    "application/zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument",
    "application/epub+zip",
)

private fun agrees(detected: String, declared: String): Boolean {
    if (detected == declared) return true
    if (detected == "application/zip" && declared in zipBased) return true
    if (detected in zipBased && declared == "application/zip") return true
    return false
}

data class TypeMismatch(val claimed: String, val extension: String?, val detected: Sniffed)

/**
 * Compares the sniffed type with the claimed MIME type and the filename's extension. Returns null when they
 * agree (or nothing was recognised and nothing dangerous is claimed by the extension).
 */
fun typeMismatch(claimedMime: String, filename: String?, head: ByteArray): TypeMismatch? {
    val detected = sniffContent(head) ?: return null
    val claimed = canonical(claimedMime)
    val ext = extensionMime(filename)
    val claimedDisagrees = !uninformative(claimed) && !agrees(detected.mime, claimed)
    val extDisagrees = ext != null && !agrees(detected.mime, ext)
    // A dangerous payload behind an uninformative type and no telling extension is still worth a warning.
    val hiddenDanger = detected.dangerous && (uninformative(claimed) || claimed != detected.mime) && ext != detected.mime
    if (!claimedDisagrees && !extDisagrees && !hiddenDanger) return null
    return TypeMismatch(claimedMime.lowercase(), ext, detected)
}
