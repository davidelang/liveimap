package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings

fun sameImapIdentity(current: AccountSettings, next: AccountSettings): Boolean =
    current.imapHost == next.imapHost &&
        current.imapPort == next.imapPort &&
        current.username == next.username &&
        current.smtpHost == next.smtpHost &&
        current.smtpPort == next.smtpPort
