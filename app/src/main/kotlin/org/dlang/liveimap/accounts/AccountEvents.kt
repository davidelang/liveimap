package org.dlang.liveimap.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object AccountEvents {
    private val removed = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val removedIds: SharedFlow<String> = removed
    private var known: Set<String>? = null

    fun onUpdated(context: Context, accounts: Array<out Account>) {
        val manager = AccountManager.get(context)
        val ids = accounts.filter { it.type == liveimapAccountType }.mapNotNull { account ->
            manager.getUserData(account, userAccountId)
        }.toSet()
        val previous = known
        known = ids
        if (previous == null) return
        for (id in previous - ids) removed.tryEmit(id)
    }
}
