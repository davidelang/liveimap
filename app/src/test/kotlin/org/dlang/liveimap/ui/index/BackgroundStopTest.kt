package org.dlang.liveimap.ui.index

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundStopTest {
    @Test
    fun stopCancelsThePollLoopAndWatchRecovery() {
        val text = listOf(
            File("src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt"),
            File("app/src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt"),
        ).first { it.exists() }.readText()
        val at = text.indexOf("onStopOrDispose {")
        assertTrue(at >= 0)
        val stop = text.substring(at, at + 180)
        assertTrue(stop.contains("indexWatch[0]?.cancel()"))
        assertTrue(stop.contains("watchRecovery.cancel()"))
        val load = listOf(
            File("src/main/kotlin/org/dlang/liveimap/ui/index/IndexSessionLoad.kt"),
            File("app/src/main/kotlin/org/dlang/liveimap/ui/index/IndexSessionLoad.kt"),
        ).first { it.exists() }.readText()
        assertTrue(load.contains("suspend fun pollLoop("))
    }
}
