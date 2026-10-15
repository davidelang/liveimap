package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.applyTlsPolicy
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.tlsPolicy

fun offerForAccount(
    account: AccountSettings,
    open: (String) -> JmapHttpExchange,
    /** Basic authorization; sent only when the unauthenticated session request answers 401 (RFC 8620 needs auth). */
    authorization: String = "",
    get: (String, String) -> JmapBlobExchange = pinnedJmapGet(account.jmapCertPin, account.tlsPolicy()),
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check, // tests pass it as a trailing lambda
    accountKey: String = "",
): JmapOffer {
    if (account.imapHost.isBlank()) return JmapOffer.None
    val policy = account.tlsPolicy()
    val fetch = JmapHttpsFetch(account.jmapCertPin, open, policy, trust, accountKey)  // F63: the JMAP server's own pin
    // The JMAP server field takes host, host:port or an https URL (port 443 unless given).
    val target = jmapServerTarget(account.imapHost)
    if (target.plainHttp) JmapPlainHttp.allow(accountKey, target.discoveryUrl)
    val result = fetch.get(target.discoveryUrl)
    if (result.status != 401 || authorization.isEmpty()) {
        val open = discoverJmap(result.status, result.body, result.finalUrl, accountKey)
        // Stalwart answers the unauthenticated session request with 200 and an anonymous session (no accounts);
        // that is not this user's session, so fetch it again with credentials, as for 401.
        val anonymous = open is JmapOffer.Mail && open.session.primaryMailAccountId.isNullOrBlank()
        if (!anonymous || authorization.isEmpty()) return open
    }
    // Discovery found the session resource (redirects, trust and pin already checked without credentials);
    // fetch it once more with credentials, on the same URL, refusing redirects, through the pinned GET.
    return discoverJmap(200, jmapAuthenticatedSession(result.finalUrl, authorization, account.jmapCertPin, get, trust, policy, accountKey), result.finalUrl, accountKey)
}

fun jmapAuthenticatedSession(
    url: String,
    authorization: String,
    pin: String,
    get: (String, String) -> JmapBlobExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
): String {
    if (!jmapUrlAllowed(accountKey, url)) throw JmapFailure("jmap session url is not https")
    val host = jmapUrlHost(url) ?: throw JmapFailure("jmap session url has no host")
    val exchange = jmapNet { get(url, authorization) }
    try {
        val verdict = applyTlsPolicy(
            jmapVerdict(accountKey, url) { jmapNet { trust(host.removePrefix("[").removeSuffix("]"), exchange.peerDer(), pin) } },
            policy,
        )
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = jmapNet { exchange.status }
        if (status == 401 || status == 403) throw JmapFailure("jmap login refused (status $status)")
        if (status != 200) throw JmapFailure("jmap session status $status")
        return jmapNet { exchange.bytes() }.toString(Charsets.UTF_8)
    } finally {
        exchange.close()
    }
}
