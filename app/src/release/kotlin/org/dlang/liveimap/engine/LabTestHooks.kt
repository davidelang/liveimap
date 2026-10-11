package org.dlang.liveimap.engine

import java.security.cert.X509Certificate

/**
 * Release build: the lab hooks do not exist. The real implementation (password override, lab CA trust, TLS 1.2
 * cap) lives in the debug source set only; this stub keeps the two call sites compiling and always answers
 * "no override, no lab trust".
 */
internal object LabTestHooks {
    @Suppress("UNUSED_PARAMETER")
    fun trustsLabLeaf(imapHost: String, leaf: X509Certificate): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun passwordFor(imapHost: String): String? = null

    fun moveChunk(): Int? = null
}
