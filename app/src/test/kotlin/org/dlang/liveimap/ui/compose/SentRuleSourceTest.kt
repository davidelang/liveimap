package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** David's Sent rules on the compose send path (IMAP and JMAP). */
class SentRuleSourceTest {
    private val compose = listOf(
        "src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
        "app/src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
    ).map(::File).first { it.exists() }.readText()

    @Test
    fun blankSentSendsWithoutACopyInsteadOfBlocking() {
        assertFalse(compose.contains("if (account.sentMailbox.isEmpty()) {\n                        notice = sentMailboxMissing\n                        return@launchLocked false"))
        assertTrue(compose.contains("if (account.sentMailbox.isNotEmpty()) try {\n                        outbound(via).append(account.sentMailbox, accepted)"))
    }

    @Test
    fun aFailedCopyOffersSendingWithoutIt() {
        assertTrue(compose.contains("catch (error: JmapSentFolderMissing)"))
        assertTrue(Regex("offerNoCopy = true").findAll(compose).count() >= 3)
        assertTrue(compose.contains("R.string.compose_send_without_copy"))
        assertTrue(compose.contains("saveCopy = !withoutCopy"))
    }
}
