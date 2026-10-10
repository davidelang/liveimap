package org.dlang.liveimap.engine

import org.dlang.liveimap.BuildConfig

/**
 * Lab-runner hooks. Only the instrumented lab suite (androidTest) sets these; app code never does.
 * Gate: the override applies only in a debug build, and only when the account's IMAP host is
 * exactly the lab host the suite named. A release build or any other host reads the stored password.
 */
internal object LabTestHooks {
    @Volatile
    private var host: String? = null

    @Volatile
    private var password: String? = null

    fun set(labHost: String, labPassword: String) {
        host = labHost
        password = labPassword
    }

    fun clear() {
        host = null
        password = null
    }

    fun passwordFor(imapHost: String): String? =
        passwordFor(imapHost, BuildConfig.DEBUG)

    internal fun passwordFor(imapHost: String, debug: Boolean): String? {
        val lab = host ?: return null
        if (!debug || lab.isEmpty() || imapHost != lab) return null
        return password
    }
}
