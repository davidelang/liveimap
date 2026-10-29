package org.dlang.liveimap.session

import org.dlang.liveimap.settings.Settings

fun sameImapIdentity(
    current: Settings,
    next: Settings,
    currentPassword: String,
    nextPassword: String,
): Boolean =
    current.imapHost == next.imapHost &&
        current.imapPort == next.imapPort &&
        current.tlsMode == next.tlsMode &&
        current.certPin == next.certPin &&
        current.allowPlaintextAuth == next.allowPlaintextAuth &&
        current.acceptInvalidCert == next.acceptInvalidCert &&
        current.allowOldTls == next.allowOldTls &&
        current.authMechanism == next.authMechanism &&
        current.clientCertAlias == next.clientCertAlias &&
        current.username == next.username &&
        currentPassword == nextPassword &&
        current.smtpHost == next.smtpHost &&
        current.smtpPort == next.smtpPort
