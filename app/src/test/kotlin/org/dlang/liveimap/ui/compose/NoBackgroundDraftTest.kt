package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** F104: leaving the app never appends a draft (it raced Send and piled up stale copies in Postponed). */
class NoBackgroundDraftTest {
    @Test
    fun onStopSavesNoDraft() {
        val activity = File("src/main/kotlin/org/dlang/liveimap/MainActivity.kt").readText()
        val compose = File("src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt").readText()
        assertFalse(activity.contains("ComposeBackgroundSave"))
        assertFalse(compose.contains("saveDraftInBackground"))
        assertFalse(compose.contains("ComposeBackgroundSave"))
    }
}
