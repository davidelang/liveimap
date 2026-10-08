package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.settings.AccountSettings

fun offerForAccount(
    account: AccountSettings,
    open: (String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapOffer {
    if (account.imapHost.isBlank()) return JmapOffer.None
    val fetch = JmapHttpsFetch(account.certPin, open, trust)
    return discoverAt(account.imapHost, 443, fetch)
}
