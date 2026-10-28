package org.dlang.liveimap.engine

import java.security.cert.X509Certificate

/**
 * Release build: the lab hooks do not exist. The real implementation (lab CA trust, TLS 1.2 cap) lives in the
 * debug source set only; this stub keeps the call sites compiling and never trusts a lab leaf.
 */
internal object LabTestHooks {
    @Suppress("UNUSED_PARAMETER")
    fun trustsLabLeaf(imapHost: String, leaf: X509Certificate): Boolean = false

    fun moveChunk(): Int? = null
}
