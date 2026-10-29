package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoadCheckLinesTest {
    @Before
    fun tree() {
        installGeneratedSettings()
    }

    @Test
    fun inboundRuleContinuationsStayOneKey() {
        val text = "friendlyName=Ada\n" +
            "inboundRules=From=ann%40example.com\tfile=INBOX\n" +
            "Subject=x\tfile=Sent\n"
        val map = linesOf(text)
        assertEquals(setOf("friendlyName", "inboundRules"), map.keys)
        assertTrue(map.getValue("inboundRules").contains("\nSubject=x\tfile=Sent"))
        val proposal = propose(emptyMap(), mapOf("a" to map), mapOf("a" to "Ada"))
        assertTrue(proposal.questions.extras.none { it.key == "From" || it.key == "Subject" })
        assertEquals(2, decodeSettings(textOfForTest(map)).inboundRules.size)
    }

    @Test
    fun aMissingAccountKeyStaysMissing() {
        val accounts = mapOf(
            "a" to linkedMapOf("friendlyName" to "Ada"),
            "b" to linkedMapOf<String, String>(),
        )
        val proposal = propose(emptyMap(), accounts, mapOf("a" to "A", "b" to "B"))
        assertFalse(proposal.accounts.getValue("b").containsKey("friendlyName"))
        assertTrue(proposal.questions.moves.none { it.key == "friendlyName" })
    }

    @Test
    fun aUiValueIsCopiedOntoEveryAccount() {
        val ui = linkedMapOf("imapHost" to "mail.example")
        val accounts = mapOf(
            "a" to linkedMapOf<String, String>(),
            "b" to linkedMapOf("imapHost" to "mail.example"),
        )
        val proposal = propose(ui, accounts, mapOf("a" to "A", "b" to "B"))
        assertEquals("mail.example", proposal.accounts.getValue("a")["imapHost"])
        assertEquals("mail.example", proposal.accounts.getValue("b")["imapHost"])
        assertFalse(proposal.ui.containsKey("imapHost"))
        assertTrue(proposal.questions.moves.isEmpty())
    }

    @Test
    fun aPickedAccountValueIsNotCopiedAcrossAccounts() {
        val ui = linkedMapOf("imapHost" to "ui.example")
        val accounts = linkedMapOf(
            "a" to linkedMapOf("imapHost" to "a.example"),
            "b" to linkedMapOf<String, String>(),
        )
        val proposal = propose(ui, accounts, mapOf("a" to "A", "b" to "B"))
        assertTrue(proposal.questions.moves.any { it.key == "imapHost" })
        assertFalse(proposal.accounts.getValue("b").containsKey("imapHost"))
        applyMove("imapHost", "a.example", proposal.ui, proposal.accounts)
        assertFalse(proposal.ui.containsKey("imapHost"))
        assertEquals("a.example", proposal.accounts.getValue("a")["imapHost"])
        assertFalse(proposal.accounts.getValue("b").containsKey("imapHost"))
        applyMove("imapHost", "ui.example", linkedMapOf("imapHost" to "ui.example"), proposal.accounts)
        assertEquals("ui.example", proposal.accounts.getValue("b")["imapHost"])
    }

    @Test
    fun aBadValueIsRepairedWithAWarningAndNoQuestion() {
        val accounts = mapOf("a" to linkedMapOf("maxConnections" to "9999"))
        val proposal = propose(emptyMap(), accounts, mapOf("a" to "Ada"))
        assertTrue(proposal.warning.contains("Ada"))
        assertTrue(proposal.questions.extras.isEmpty())
        assertTrue(proposal.questions.moves.isEmpty())
        assertEquals(Settings().maxConnections.toString(), proposal.accounts.getValue("a")["maxConnections"])
    }
}

private fun textOfForTest(map: Map<String, String>): String =
    map.entries.joinToString("\n") { (key, value) -> "$key=$value" }
