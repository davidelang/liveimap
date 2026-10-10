package org.dlang.liveimap.engine

/** Lab-runner hooks. Instrumented lab suites set these; the app never does. */
internal object LabTestHooks {
    /** When set, LibetpanMailSession.open uses it instead of the stored password. */
    @Volatile
    var password: String? = null
}
