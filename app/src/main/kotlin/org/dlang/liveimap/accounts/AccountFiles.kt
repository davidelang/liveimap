package org.dlang.liveimap.accounts

import java.io.File
import org.dlang.liveimap.ui.compose.deleteUnsentAccount
import org.dlang.liveimap.ui.compose.unsentAccountDir
import org.dlang.liveimap.ui.reader.attachmentPrefixFor
import org.dlang.liveimap.ui.reader.imageTrustFile

internal class NamedFileRemains(val path: String) : Exception(path)

private val attachmentAccountId = Regex("^[0-9a-f-]{36}$")

/** Files named by [accountId]. Traffic files are not named here. */
internal fun deleteNamedAccountFiles(filesDir: File, cacheDir: File, accountId: String) {
    deleteUnsentAccount(filesDir, accountId)
    val unsent = unsentAccountDir(filesDir, accountId)
    if (unsent.exists()) throw NamedFileRemains(unsent.path)
    val trust = imageTrustFile(filesDir, accountId)
    if (trust != null) {
        val tmp = File(trust.parentFile, trust.name + ".tmp")
        if (!trust.delete() && trust.exists()) throw NamedFileRemains(trust.path)
        if (!tmp.delete() && tmp.exists()) throw NamedFileRemains(tmp.path)
    }
    if (!attachmentAccountId.matches(accountId)) return
    val prefix = attachmentPrefixFor(accountId)
    val files = cacheDir.listFiles() ?: return
    for (file in files) {
        if (!file.name.startsWith(prefix)) continue
        val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!gone || file.exists()) throw NamedFileRemains(file.path)
    }
}
