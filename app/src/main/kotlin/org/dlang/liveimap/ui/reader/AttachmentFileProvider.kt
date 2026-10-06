package org.dlang.liveimap.ui.reader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class AttachmentFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = cacheFile(uri) ?: return null
        if (!file.isFile) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val values = Array<Any?>(cols.size) { index ->
            when (cols[index]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(values)
        return cursor
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.indexOf('w') >= 0 || mode.indexOf('t') >= 0) {
            throw FileNotFoundException(uri.toString())
        }
        val file = cacheFile(uri) ?: throw FileNotFoundException(uri.toString())
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cacheFile(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name == "imap-traffic.log" || !ATTACHMENT_NAME.matches(name)) return null
        val dir = context?.cacheDir ?: return null
        val file = File(dir, name)
        val parent = file.canonicalFile.parentFile ?: return null
        if (parent != dir.canonicalFile) return null
        return file
    }

    private companion object {
        val ATTACHMENT_NAME = Regex("^liveimap-[0-9]+-[A-Za-z0-9._-]+$")
    }
}
