package org.dlang.liveimap.accounts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException

object PasswordStorage {
    private const val logTag = "LiveIMAP"
    private val reported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun status(context: Context, accountId: String, value: String): BackupAvailability {
        val availability = when (value) {
            "googleRestore" -> BlockStorePasswords.available()
            "passwordManager" -> credentialAvailability(context)
            "none" -> BackupAvailability.Available
            else -> BackupAvailability.Unavailable(BackupReason.NoPlay)
        }
        if (availability is BackupAvailability.Unavailable) {
            val line = "$accountId $value ${availability.reason}"
            if (reported.add(line)) Log.w(logTag, "password storage $line")
        }
        return availability
    }

    suspend fun movedOffGoogleRestore(context: Context, accountId: String, previous: String, next: String) {
        if (previous != "googleRestore" || next == "googleRestore") return
        val availability = BlockStorePasswords.available()
        if (availability is BackupAvailability.Unavailable) {
            Log.w(logTag, "password storage $accountId $next ${availability.reason}")
            return
        }
        BlockStorePasswords.delete(context, accountId)
    }

    suspend fun refresh(context: Context, accountId: String, value: String, record: ByteArray): BackupAvailability {
        if (value != "googleRestore") return BackupAvailability.Available
        val availability = BlockStorePasswords.available()
        if (availability is BackupAvailability.Unavailable) {
            Log.w(logTag, "password storage $accountId $value ${availability.reason}")
            return availability
        }
        val saved = BlockStorePasswords.save(context, accountId, record)
        if (saved is BackupAvailability.Unavailable) {
            Log.w(logTag, "password storage $accountId $value ${saved.reason}")
        }
        return saved
    }

    suspend fun fetchPassword(activity: Context): PasswordCredential? {
        return try {
            val request = GetCredentialRequest(listOf(GetPasswordOption()))
            val result = CredentialManager.create(activity).getCredential(activity, request)
            result.credential as? PasswordCredential
        } catch (_: NoCredentialException) {
            null
        } catch (_: GetCredentialProviderConfigurationException) {
            null
        }
    }

    suspend fun savePassword(activity: Context, username: String, password: String): BackupAvailability {
        if (credentialAvailability(activity) is BackupAvailability.Unavailable) {
            return BackupAvailability.Unavailable(BackupReason.NoManager)
        }
        return try {
            CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(username, password))
            BackupAvailability.Available
        } catch (_: CreateCredentialProviderConfigurationException) {
            BackupAvailability.Unavailable(BackupReason.NoManager)
        } catch (error: Exception) {
            Log.w(logTag, "password manager save", error)
            BackupAvailability.Unavailable(BackupReason.NoManager)
        }
    }

    private fun credentialAvailability(context: Context): BackupAvailability {
        val intent = Intent("android.service.credentials.CredentialProviderService")
        val found = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        return if (found.isEmpty()) BackupAvailability.Unavailable(BackupReason.NoManager) else BackupAvailability.Available
    }
}
