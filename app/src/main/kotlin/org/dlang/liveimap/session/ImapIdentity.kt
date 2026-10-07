package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings

fun sameImapIdentity(
    current: AccountSettings,
    next: AccountSettings,
    currentPassword: String,
    nextPassword: String,
): Boolean =
    current.imapHost == next.imapHost &&
        current.imapPort == next.imapPort &&
        current.tlsMode == next.tlsMode &&
        current.username == next.username &&
        currentPassword == nextPassword &&
        current.smtpHost == next.smtpHost &&
        current.smtpPort == next.smtpPort
