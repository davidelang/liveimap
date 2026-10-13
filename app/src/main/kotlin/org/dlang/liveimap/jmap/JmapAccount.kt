package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.settings.AccountSettings

fun offerForAccount(
    account: AccountSettings,
    open: (String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapOffer {
    if (account.imapHost.isBlank()) return JmapOffer.None
    val fetch = JmapHttpsFetch(account.jmapCertPin, open, trust)  // F63: the JMAP server's own pin
    // The JMAP server field takes host, host:port or an https URL (port 443 unless given).
    val target = jmapServerTarget(account.imapHost)
    if (target.plainHttp) JmapPlainHttp.allow(target.discoveryUrl)
    val result = fetch.get(target.discoveryUrl)
    return discoverJmap(result.status, result.body, result.finalUrl)
}
