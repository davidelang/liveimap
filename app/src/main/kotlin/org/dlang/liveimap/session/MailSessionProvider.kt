package org.dlang.liveimap.session

import org.dlang.liveimap.engine.LibetpanMailSession

private val processSession: MailSession by lazy {
    SerialMailSession(LibetpanMailSession())
}

fun mailSession(): MailSession = processSession
