package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.settings.AccountSettings

fun offerForAccount(
    account: AccountSettings,
    open: (String) -> JmapHttpExchange,
    /** Basic authorization; sent only when the unauthenticated session request answers 401 (RFC 8620 needs auth). */
    authorization: String = "",
    get: (String, String) -> JmapBlobExchange = pinnedJmapGet(account.jmapCertPin),
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check, // last: tests pass it as a trailing lambda
): JmapOffer {
    if (account.imapHost.isBlank()) return JmapOffer.None
    val fetch = JmapHttpsFetch(account.jmapCertPin, open, trust)  // F63: the JMAP server's own pin
    // The JMAP server field takes host, host:port or an https URL (port 443 unless given).
    val target = jmapServerTarget(account.imapHost)
    if (target.plainHttp) JmapPlainHttp.allow(target.discoveryUrl)
    val result = fetch.get(target.discoveryUrl)
    if (result.status != 401 || authorization.isEmpty()) return discoverJmap(result.status, result.body, result.finalUrl)
    // Discovery found the session resource (redirects, trust and pin already checked without credentials);
    // fetch it once more with credentials, on the same URL, refusing redirects, through the pinned GET.
    return discoverJmap(200, jmapAuthenticatedSession(result.finalUrl, authorization, account.jmapCertPin, get, trust), result.finalUrl)
}

fun jmapAuthenticatedSession(
    url: String,
    authorization: String,
    pin: String,
    get: (String, String) -> JmapBlobExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): String {
    if (!jmapUrlAllowed(url)) throw JmapFailure("jmap session url is not https")
    val host = jmapUrlHost(url) ?: throw JmapFailure("jmap session url has no host")
    val exchange = jmapNet { get(url, authorization) }
    try {
        val verdict = jmapVerdict(url) { jmapNet { trust(host.removePrefix("[").removeSuffix("]"), exchange.peerDer(), pin) } }
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = jmapNet { exchange.status }
        if (status == 401 || status == 403) throw JmapFailure("jmap login refused (status $status)")
        if (status != 200) throw JmapFailure("jmap session status $status")
        return jmapNet { exchange.bytes() }.toString(Charsets.UTF_8)
    } finally {
        exchange.close()
    }
}
