package org.dlang.liveimap.accounts

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import org.dlang.liveimap.LiveImapApplication

object BlockStorePasswords {
    private const val logTag = "LiveIMAP"

    fun available(): BackupAvailability {
        val context = LiveImapApplication.context ?: return BackupAvailability.Unavailable(BackupReason.NoPlay)
        val play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (play != ConnectionResult.SUCCESS) return BackupAvailability.Unavailable(BackupReason.NoPlay)
        return try {
            val client = Blockstore.getClient(context)
            val encrypted = Tasks.await(client.isEndToEndEncryptionAvailable)
            if (encrypted) BackupAvailability.Available else BackupAvailability.Unavailable(BackupReason.NoLock)
        } catch (error: Exception) {
            Log.w(logTag, "block store availability", error)
            BackupAvailability.Unavailable(BackupReason.NoPlay)
        }
    }

    suspend fun save(context: Context, accountId: String, record: ByteArray): BackupAvailability {
        val key = "a:$accountId"
        if (record.size > BlockstoreClient.MAX_SIZE || key.length > BlockstoreClient.MAX_SIZE) {
            return BackupAvailability.Unavailable(BackupReason.TooLarge)
        }
        val existing = restoreAll(context)
        if (key !in existing && existing.size >= BlockstoreClient.MAX_ENTRY_COUNT) {
            return BackupAvailability.Unavailable(BackupReason.TooMany)
        }
        val data = StoreBytesData.Builder()
            .setBytes(record)
            .setKey(key)
            .setShouldBackupToCloud(true)
            .build()
        Tasks.await(Blockstore.getClient(context).storeBytes(data))
        return BackupAvailability.Available
    }

    suspend fun delete(context: Context, accountId: String) {
        val request = DeleteBytesRequest.Builder()
            .setKeys(listOf("a:$accountId"))
            .build()
        Tasks.await(Blockstore.getClient(context).deleteBytes(request))
    }

    suspend fun restoreAll(context: Context): Map<String, ByteArray> {
        val request = RetrieveBytesRequest.Builder().setRetrieveAll(true).build()
        val response = Tasks.await(Blockstore.getClient(context).retrieveBytes(request))
        val out = linkedMapOf<String, ByteArray>()
        for ((key, value) in response.blockstoreDataMap) {
            out[key] = value.bytes
        }
        return out
    }
}
