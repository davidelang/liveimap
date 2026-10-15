package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Box pass-5 items without a JVM entry point, checked in the source; plus F18's mapping. */
class BoxPass5GuardTest {
    private fun src(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
    private val jni = src("cpp/liveimap_jni.cpp")

    @Test fun authTypeIsTheKeyExchange() {
        val peerTrust = src("kotlin/org/dlang/liveimap/engine/PeerTrust.kt")
        assertTrue(peerTrust.contains("checkServerTrusted(chain, \"UNKNOWN\")"))
        assertFalse(peerTrust.contains("tlsAuthType(leaf.publicKey.algorithm)"))
        assertEquals("UNKNOWN", tlsAuthType("Ed25519"))
    }

    /** F38: the watch poll sleeps until the renew when a wake fd exists. */
    @Test fun watchPollIsNotAFixedWake() {
        assertTrue(jni.contains("int pollMs = watchPollMs(session->watchWakeFd >= 0, renewLeft,"))
        assertFalse(jni.contains("poll(pfd, session->watchWakeFd >= 0 ? 2 : 1, gWatchWakeMs.load())"))
    }

    /** F42: native CR/LF backstop on every mailbox name the remaining entry points send. */
    @Test fun nativeWireBackstops() {
        for (name in listOf("StatusMessages", "ListLevel", "MailboxListed", "CopyThenDelete", "CopyAllThenDelete", "Append", "Watch")) {
            val start = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_native$name(")
            assertTrue(name, start >= 0)
            val body = jni.substring(start, jni.indexOf("\n}\n", start))
            assertTrue(name, body.contains("// F42 native backstop"))
        }
    }

    /** F21: the IMAP password is masked at rest in the session and the watch's clear copy is wiped. */
    @Test fun sessionPasswordIsMasked() {
        assertTrue(jni.contains("xorMask(session->password, session->passwordMask);"))
        assertTrue(jni.contains("wipeSecret(passwordMask);"))
        assertFalse(jni.contains("const std::string password = session->password;"))
        assertTrue(jni.contains("    wipeSecret(password);\n    session->mu.lock();"))
    }

    /** F64: a channel-binding downgrade is recorded and shown, and a pin confirmed this open carries to later retries. */
    @Test fun plusDowngradeIsNotSilent() {
        val s = src("kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
        assertTrue(s.contains("channelBindingDropped = true"))
        assertTrue(s.contains("val allowed = trustedAccount.copy(allowPlaintextAuth = true)"))
    }

    /** F77: every AlertDialog in the app is the guarded one. */
    @Test fun dialogsFilterObscuredTouches() {
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.exists() }
        val offenders = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "GuardedDialog.kt" }
            .filter { val t = it.readText(); t.contains("import androidx.compose.material3.AlertDialog\n") || t.contains("androidx.compose.material3.AlertDialog(") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /** The two-pane group list reloads after a save (stale "Traffic log on"). */
    @Test fun groupListFollowsSaves() {
        val s = src("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        assertTrue(s.contains("LaunchedEffect(revision) { if (revision > 0) editor.load() }"))
        assertTrue(s.contains("SettingsRevision.bump()"))
    }
}
