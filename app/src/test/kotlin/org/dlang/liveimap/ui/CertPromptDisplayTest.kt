package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F74/F76: prompts are serialized; certificate fields are shown cleaned and cut. */
class CertPromptDisplayTest {
    @Test fun fieldsAreCleaned() {
        assertEquals("CN=imap.example.com Verified by your provider", certDisplayText("CN=imap.example.com\nVerified by your provider"))
        assertEquals("abc", certDisplayText("a\u202Eb\u200Fc\u0000"))
        val long = certDisplayText("x".repeat(1000))
        assertEquals(161, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun promptsQueue() {
        val src = File("src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt").readText()
        assertTrue(src.contains("setMailCertConfirmer { prompt -> certGate.withLock {"))
        assertTrue(src.contains("setMailPlaintextConfirmer { plaintextGate.withLock {"))
        assertFalse(src.contains("Text(pendingCert.prompt.subject)"))
    }
}
