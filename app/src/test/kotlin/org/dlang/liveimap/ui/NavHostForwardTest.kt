package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHostForwardTest {
    @Test
    fun navForwardChoiceMapsStoredText() {
        assertNull(navForwardChoice(""))
        assertEquals(true, navForwardChoice("true"))
        assertEquals(false, navForwardChoice("false"))
    }

    @Test
    fun composeRouteKeepsTheForwardMode() {
        val nav = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt",
        ).map(::File).first { it.exists() }.readText()
        val open = nav.substringAfter("fun openCompose(").substringBefore("\n    fun ")
        assertTrue(open.contains("composeForward.value"))
        assertTrue(open.contains("seed.forwardAsAttachment"))
        val compose = nav.substringAfter("composable(\"compose\")").substringBefore("composable(\"unsent\")")
        assertTrue(compose.contains("forwardAsAttachment = navForwardChoice("))
        val retry = nav.substringAfter("onOpenCopy").substringBefore("navigate(\"compose\")")
        assertTrue(retry.contains("composeForward.value = \"\""))
    }
}
