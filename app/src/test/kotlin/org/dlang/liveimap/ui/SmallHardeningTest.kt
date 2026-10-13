package org.dlang.liveimap.ui

import java.io.File
import org.dlang.liveimap.session.MaxExtraIdle
import org.dlang.liveimap.session.extraIdleBudgetFromText
import org.dlang.liveimap.session.planWatchedFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F70, F73, F81, F82, F84 guards. */
class SmallHardeningTest {
    private fun src(p: String) = File("src/main/$p").readText()

    @Test fun f70NoUnguardedViewIntents() {
        for (f in listOf("ui/about/AboutScreen.kt", "ui/help/HelpScreen.kt", "ui/help/IconHelp.kt")) {
            val t = src("kotlin/org/dlang/liveimap/$f")
            assertFalse(f, t.contains("context.startActivity("))
            assertFalse(f, t.contains("resolveActivity("))
        }
        assertTrue(src("AndroidManifest.xml").contains("<queries>"))
    }

    @Test fun f73NoDoubleDecode() {
        assertFalse(src("kotlin/org/dlang/liveimap/ui/NavHost.kt").contains("Uri.decode"))
        assertFalse(src("kotlin/org/dlang/liveimap/ui/NavHost.kt").contains("Uri::decode"))
    }

    @Test fun f81IdleBudgetClamped() {
        assertNull(extraIdleBudgetFromText("500"))
        assertEquals(8, extraIdleBudgetFromText("8"))
        assertEquals(MaxExtraIdle, planWatchedFolders((1..50).map { "f$it" }, 500).idle.size)
    }

    @Test fun f82PushTimeoutAbovePing() {
        assertTrue(src("kotlin/org/dlang/liveimap/jmap/JmapPush.kt").contains("readTimeout = 150_000"))
    }

    @Test fun f84LaneShutsDownAndReopens() = kotlinx.coroutines.runBlocking {
        val inner = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(org.dlang.liveimap.session.MailSession::class.java),
        ) { _, m, _ -> if (m.name == "getCapabilities") emptySet<String>() else null } as org.dlang.liveimap.session.MailSession
        val s = org.dlang.liveimap.session.SerialMailSession(inner)
        s.capabilities
        s.close()
        assertFalse(s.laneOpen())
    }
}
