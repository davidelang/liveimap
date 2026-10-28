package org.dlang.liveimap.session

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pass 5 (David, 2026-10-12): every folder operation names its folder and runs as one lowest-layer call. */
class FolderScopeTest {





    /** Guard: no UI code may select or run folder commands outside a folder scope. */
    @Test
    fun uiHasNoBareSelects() {
        val bare = Regex("""\bsession\.(select|unselect|selectChecked|pinned)\b""")
        val offenders = File("src/main/kotlin/org/dlang/liveimap/ui").walk().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (bare.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}

