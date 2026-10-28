package org.dlang.liveimap.accounts

enum class BackupReason {
    NoPlay,
    NoLock,
    NoManager,
    TooLarge,
    TooMany,
}

sealed class BackupAvailability {
    data object Available : BackupAvailability()
    data class Unavailable(val reason: BackupReason) : BackupAvailability()
}
