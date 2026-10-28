package org.dlang.liveimap.accounts

import android.content.Context

object BlockStorePasswords {
    fun available(): BackupAvailability = BackupAvailability.Unavailable(BackupReason.NoPlay)

    suspend fun save(context: Context, accountId: String, record: ByteArray): BackupAvailability =
        BackupAvailability.Unavailable(BackupReason.NoPlay)

    suspend fun delete(context: Context, accountId: String) = Unit

    suspend fun restoreAll(context: Context): Map<String, ByteArray> = emptyMap()
}
