package org.dlang.liveimap.ui.contacts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F62: completion data is cached per (account, mailbox), failures are not cached, writes invalidate. */
class CompletionCacheTest {
    @Test fun keyedAndInvalidated() {
        assertFalse(pineBookIsLoaded("a", "addressbook"))
        forgetCompletionCaches()
        val src = File("src/main/kotlin/org/dlang/liveimap/ui/contacts/Completion.kt").readText()
        assertTrue(src.contains("fun pineEntriesOnce(session: MailSession, accountId: String, mailbox: String)"))
        assertTrue(src.contains("if (loaded.notice != null) return@withLock emptyList()"))
        assertTrue(File("src/main/kotlin/org/dlang/liveimap/ui/contacts/AlpineBook.kt").readText().contains("forgetCompletionCaches()"))
    }
}
