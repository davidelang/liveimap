package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.ui.compose.nextWireCount
import java.io.File
import java.io.FileOutputStream

/**
 * Fetches an attachment chunk by chunk straight into [target]: each chunk is appended to a temporary file next to
 * it, which is renamed over [target] only when the fetch completes. Memory use is one chunk, whatever size the
 * sender or server declares. [size] <= 0 (unknown) fetches one chunk. Returns the number of bytes written.
 * On any failure the temporary file is removed and [target] is left as it was.
 */
suspend fun streamAttachment(
    target: File,
    size: Int,
    chunk: Int,
    fetch: suspend (offset: Int, length: Int) -> ByteArray,
    progress: (Int) -> Unit = {},
): Int {
    val part = File(target.parentFile, target.name + ".part")
    var offset = 0
    var ok = false
    try {
        FileOutputStream(part).use { out ->
            if (size <= 0) {
                val bytes = fetch(0, chunk)
                out.write(bytes)
                offset = bytes.size
                progress(offset)
            } else {
                while (offset < size) {
                    val length = nextWireCount(offset, size, chunk, true)
                    if (length <= 0) break
                    val bytes = fetch(offset, length)
                    if (bytes.isEmpty()) break
                    out.write(bytes)
                    offset += bytes.size
                    progress(offset)
                }
            }
        }
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("cannot store ${target.name}")
        }
        ok = true
        return offset
    } finally {
        if (!ok) part.delete()
    }
}
