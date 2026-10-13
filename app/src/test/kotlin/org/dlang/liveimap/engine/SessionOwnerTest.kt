package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F75: a session's secrets and pin saves come from the account it opened as, never the drawer's current one. */
class SessionOwnerTest {
    private val src = File("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt").readText()

    @Test fun noUnboundStoreReads() {
        assertFalse(src.contains("DataStoreSettingsStore(context).password()"))
        assertFalse(src.contains("DataStoreSettingsStore(context).smtpPassword()"))
        assertFalse(src.contains("DataStoreSettingsStore(context).save("))
        assertTrue(src.contains("ownStore(context).update { it.copy(certPin = prompt.fingerprint) }"))
        assertTrue(src.contains("ownStore(context).update { it.copy(allowPlaintextAuth = true) }"))
        assertTrue(src.contains("coroutineContext[org.dlang.liveimap.accounts.AccountTarget]"))
    }

    @Test fun screensOpenAsTheirAccount() {
        for (f in listOf("ui/index/MessageIndexScreen.kt", "ui/folder/FolderListScreen.kt", "ui/folder/MailboxChooser.kt",
            "ui/reader/MessageReaderScreen.kt", "ui/compose/ComposeScreen.kt", "ui/contacts/AddressBookPicker.kt",
            "ui/contacts/ContactCopyScreen.kt")) {
            val text = File("src/main/kotlin/org/dlang/liveimap/$f").readText()
            assertTrue(f, text.contains("session.openBound(store,"))
            assertFalse(f, Regex("session\\.open\\((settings|account)\\)").containsMatchIn(text))
        }
    }
}
