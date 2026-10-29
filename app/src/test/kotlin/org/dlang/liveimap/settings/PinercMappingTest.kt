package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinercMappingTest {
    @Test
    fun importReadsTheKeysExportWrites() {
        val current = Settings()
        val exported = encodePinerc(current)
        assertEquals(current, pinercPreview(exported, current, testPinercPhrases()).next)
        val saved = pinercPreview("default-saved-msg-folder=\"Archive\"\n", current, testPinercPhrases())
        assertEquals("Archive", saved.next.savedMailbox)
        val spec = pinercPreview("{mail.example:993/ssl/novalidate-cert/user=ada}INBOX\n".let { "inbox-path=\"$it\"\n" }, current, testPinercPhrases())
        assertEquals("mail.example", spec.next.imapHost)
        assertTrue(spec.next.acceptInvalidCert)
    }
}
