package org.dlang.liveimap.engine.sieve

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundRuleIdTest {
    @Test
    fun saveAndDeleteFindTheId() {
        val a = InboundRule(id = "a", criteria = listOf(RuleCriterion(RuleField.From, "a@x")), fileInto = "Old")
        val b = InboundRule(id = "b", criteria = listOf(RuleCriterion(RuleField.From, "b@x")), fileInto = "B")
        val rules = listOf(a, b)
        val saved = patchInboundRule(rules, "a") { it.copy(fileInto = "New") }
        assertEquals("New", saved[0].fileInto)
        assertEquals(a.criteria, saved[0].criteria)
        assertEquals(b, saved[1])
        assertEquals(rules, deleteInboundRule(rules, "missing"))
    }

    @Test
    fun blankIdStaysBlankUntilAssigned() {
        val stored = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "ann@example.com")),
            fileInto = "INBOX.lists",
        )
        val text = AccountSettings(inboundRules = listOf(stored)).encode()
        val line = text.lines().first { it.startsWith("inboundRules=") }
        assertFalse(line.contains("id="))
        val decoded = decodeAccountSettings(text).inboundRules.single()
        assertEquals("", decoded.id)
        val kept = InboundRule(
            id = "keep",
            criteria = listOf(RuleCriterion(RuleField.To, "me@x")),
            fileInto = "Y",
        )
        val assigned = assignInboundIds(listOf(decoded, kept))
        assertTrue(assigned[0].id.isNotBlank())
        assertNotEquals("keep", assigned[0].id)
        assertEquals(decoded.copy(id = assigned[0].id), assigned[0])
        assertEquals(kept, assigned[1])
    }
}
