package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pass-5 fixes that have no JVM entry point; checked in the source. */
class Pass5GuardTest {
    /** F141: an expunge ahead of the client sort walk restarts it instead of "sort failed". */
    @Test fun sortChunkPastShrunkenExistsIsDrift() {
        assertTrue(sortChunkDrift(100, 90, false))
        assertTrue(sortChunkDrift(100, 100, true))
        assertFalse(sortChunkDrift(100, 100, false))
        val kt = listOf(
            "src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt",
            "app/src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt",
        ).map(::File).first { it.exists() }.readText()
        assertTrue(kt.contains("if (!sequencesStale) return built"))
    }
}
