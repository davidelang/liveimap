package org.dlang.liveimap.engine.sieve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SieveScriptTest {
    @Test
    fun filesAndMarksRead() {
        val script = emitSieve(
            listOf(
                InboundRule(
                    criteria = listOf(
                        RuleCriterion(RuleField.From, "ann@example.com"),
                        RuleCriterion(RuleField.Subject, "Hello"),
                    ),
                    fileInto = "INBOX.lists",
                    flags = setOf(SystemFlag.Seen),
                ),
            ),
        )
        assertEquals(filesScript, script)
        assertClean(script)
    }

    @Test
    fun redirectsAndDiscards() {
        val script = emitSieve(
            listOf(
                InboundRule(
                    criteria = listOf(
                        RuleCriterion(RuleField.ListId, "<list.example.com>"),
                    ),
                    redirectTo = "other@example.com",
                    discard = true,
                ),
            ),
        )
        assertEquals(redirectScript, script)
        assertClean(script)
    }

    @Test
    fun quotesAndSkipsEmptyRules() {
        val noCriteria = InboundRule(
            criteria = emptyList(),
            fileInto = "INBOX.junk",
            discard = true,
        )
        val noActions = InboundRule(
            criteria = listOf(RuleCriterion(RuleField.From, "skip@example.com")),
        )
        val kept = InboundRule(
            criteria = listOf(
                RuleCriterion(RuleField.To, "me@example.com"),
                RuleCriterion(RuleField.Subject, "say \"hi\""),
            ),
            flags = setOf(SystemFlag.Flagged),
        )
        val script = emitSieve(listOf(noCriteria, noActions, kept))
        assertEquals(quoteScript, script)
        assertEquals("", emitSieve(emptyList()))
        assertEquals("", emitSieve(listOf(noCriteria, noActions)))
        val stripped = emitSieve(
            listOf(
                InboundRule(
                    criteria = listOf(RuleCriterion(RuleField.From, "a\r\nb")),
                    discard = true,
                ),
            ),
        )
        assertEquals(strippedScript, stripped)
        assertClean(script)
        assertClean(stripped)
    }

    @Test
    fun seedSkipsBlanks() {
        assertEquals(
            listOf(
                RuleCriterion(RuleField.From, "a@b"),
                RuleCriterion(RuleField.Subject, "Hello"),
            ),
            seedCriteria("a@b", "", "", "Hello"),
        )
    }

    private fun assertClean(script: String) {
        assertFalse(script.contains("vacation"))
        assertFalse(script.contains("include"))
        assertFalse(script.contains("setflag"))
        assertFalse(script.contains("stop"))
        assertFalse(script.contains(":copy"))
    }
}

private val filesScript = """
require ["fileinto", "imap4flags"];

if allof (address :is "from" "ann@example.com", header :contains "subject" "Hello") {
    addflag "\\Seen";
    fileinto "INBOX.lists";
}
""".trimIndent() + "\n"

private val redirectScript = """
if allof (header :is "list-id" "<list.example.com>") {
    redirect "other@example.com";
    discard;
}
""".trimIndent() + "\n"

private val quoteScript = """
require ["imap4flags"];

if allof (address :is "to" "me@example.com", header :contains "subject" "say \"hi\"") {
    addflag "\\Flagged";
}
""".trimIndent() + "\n"

private val strippedScript = """
if allof (address :is "from" "ab") {
    discard;
}
""".trimIndent() + "\n"
