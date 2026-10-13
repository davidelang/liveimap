package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pass-5 fixes that have no JVM entry point; checked in the source. */
class Pass5GuardTest {
    private val jni = listOf("src/main/cpp/liveimap_jni.cpp", "app/src/main/cpp/liveimap_jni.cpp")
        .map(::File).first { it.exists() }.readText()

    /** F139: accept-invalid from the IMAP settings never relaxes a different SMTP host. */
    @Test fun smtpAcceptInvalidOnlyForTheImapHost() {
        assertFalse(jni.contains("if (!trust.empty() && session->acceptInvalidCert && certificateVerdict(trust)) trust.clear();"))
        assertTrue(jni.contains("if (!trust.empty() && imapHostToo && session->acceptInvalidCert && certificateVerdict(trust)) trust.clear();"))
        assertTrue(jni.contains("if (imapHostToo) pin = session->certPin.c_str();"))
    }

    /** F140: the JMAP long-poll's model callbacks run under the screen gate. */
    @Test fun jmapPushUpdatesTakeTheGate() {
        val screen = listOf("src/main/kotlin/org/dlang/liveimap/ui/folder/JmapFolderScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/folder/JmapFolderScreen.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(screen.contains("gate.withLock { model.applyPush(state, model.pushOpen()) }"))
        assertTrue(screen.contains("gate.withLock { model.messages(id) }"))
        assertFalse(screen.contains("reload = { id -> model.messages(id) }"))
    }

    /** F141: an expunge ahead of the client sort walk restarts it instead of "sort failed". */
    @Test fun sortChunkPastShrunkenExistsIsDrift() {
        assertTrue(sortChunkDrift(100, 90, false))
        assertTrue(sortChunkDrift(100, 100, true))
        assertFalse(sortChunkDrift(100, 100, false))
        val kt = listOf("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt", "app/src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(kt.contains("if (sortChunkDrift(exists, selectedExists(), sequencesStale)) { drift = true; break }"))
        assertFalse(kt.contains("nativeFetchSortFieldsRange(requireHandle(), wireField, first, last) ?: throw"))
    }

    /** F50: JMAP sends write the "may be sent" device copy before submitting, like SMTP. */
    @Test fun jmapSendKeepsADeviceCopyFirst() {
        val compose = listOf("src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt")
            .map(::File).first { it.exists() }.readText()
        val jmap = compose.indexOf("if (account.emailSubmission && via.isEmpty()) {")
        val copy = compose.indexOf("uncertain = true", jmap)
        val send = compose.indexOf("jmapSend(", jmap)
        assertTrue(jmap > 0 && copy in jmap until send)
    }

    /** F13/F136: the watch connection is opened without the session lock, and installs only if not superseded. */
    @Test fun watchSetupConnectsWithoutTheLock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val unlock = body.indexOf("session->mu.unlock();")
        val open = body.indexOf("openTls(")
        val relock = body.indexOf("session->mu.lock();")
        assertTrue(unlock in 0 until open && open < relock)
        assertTrue(body.contains("watchSetupSuperseded(session->closed, session->watchGeneration, generation, session->watch != nullptr)"))
        val serial = listOf("src/main/kotlin/org/dlang/liveimap/session/SerialMailSession.kt", "app/src/main/kotlin/org/dlang/liveimap/session/SerialMailSession.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(serial.contains("watchMutex.withLock { withContext(Dispatchers.IO) { inner.watch(mailbox, onChange) } }"))
        assertFalse(serial.contains("onLane { inner.watch("))
    }

    /** F27: the extra-IDLE close (reached from onStop on Main) runs on IO. */
    @Test fun watchedFolderCloseIsOffMain() {
        val w = listOf("src/main/kotlin/org/dlang/liveimap/session/WatchedFolders.kt", "app/src/main/kotlin/org/dlang/liveimap/session/WatchedFolders.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(w.contains("withContext(Dispatchers.IO) { session.close() }"))
    }

    /** Device test 2026-10-13: a typed password must count as an unsaved edit before the field loses focus. */
    @Test fun typedPasswordIsADraftAtOnce() {
        val w = listOf("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(w.contains("onDraft = { typed -> passwordDraft = typed.takeIf { it != editor.password } },"))
    }
}
