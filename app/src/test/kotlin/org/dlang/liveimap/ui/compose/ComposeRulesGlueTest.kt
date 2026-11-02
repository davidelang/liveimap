package org.dlang.liveimap.ui.compose

import java.io.File
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeRulesGlueTest {
    @Test
    fun sendingFieldsRoundTrip() {
        val s = Settings(
            signature = "David\nLang & co", sigDashes = false, sigAtBottom = true, quotePrefix = "| ",
            replyLeadIn = "{name} wrote:", dsnMode = DsnMode.FailureDelay,
            composerHeaders = "Reply-To, X-Priority", viewerHeaders = "List-Id",
            sendingRules = encodeRules(listOf(RulePresets.mailingList("x.example.org", "me@example.org"))),
        )
        val back = decodeSettings(s.encode())
        assertEquals(s.signature, back.signature)
        assertFalse(back.sigDashes)
        assertTrue(back.sigAtBottom)
        assertEquals("| ", back.quotePrefix)
        assertEquals("{name} wrote:", back.replyLeadIn)
        assertEquals(DsnMode.FailureDelay, back.dsnMode)
        assertEquals(s.composerHeaders, back.composerHeaders)
        assertEquals(s.viewerHeaders, back.viewerHeaders)
        assertEquals(s.sendingRules, back.sendingRules)
        assertTrue(Settings().encode().contains("signature="))
    }

    @Test
    fun leadInTokensAndPrefix() {
        assertEquals("Ann <a@x> on Mon: Hi", attributionLine("Mon", "Ann <a@x>", "{from} on {date}: {subject}", "Hi"))
        assertEquals("Ann wrote:", attributionLine("Mon", "\"Ann\" <a@x>", "{name} wrote:"))
        assertTrue(quotePart("L:", "a\nb", "| ").contains("| a\n| b"))
    }

    @Test
    fun signaturePlacement() {
        val block = signatureBlock("Me\n", true)
        assertEquals("-- \nMe", block)
        assertEquals("\n\n-- \nMe\n\n> q", withSignature("> q", block, atBottom = false, cursorAtTop = true))
        assertEquals("> q\n\n-- \nMe", withSignature("> q\n", block, atBottom = true, cursorAtTop = true))
        assertEquals("x\n\n-- \nMe", withSignature("x\n\n-- \nMe", block, false, false))
        assertEquals("", signatureBlock("  ", true))
    }

    @Test
    fun dsnMasksAndHeaderNames() {
        assertEquals(0, dsnMask(DsnMode.Off))
        assertEquals(2, dsnMask(DsnMode.Failure))
        assertEquals(6, dsnMask(DsnMode.FailureDelay))
        assertEquals(7, dsnMask(DsnMode.All))
        assertEquals(listOf("Reply-To", "X-Priority"), headerNames("Reply-To:, X-Priority reply-to"))
    }
}

class CrossAccountTest {
    @Test
    fun unsentCopyKeepsSendingAccount() {
        val dir = java.nio.file.Files.createTempDirectory("unsent").toFile()
        try {
            val x = File(dir, "x.src").also { it.writeBytes("x".toByteArray()) }
            val y = File(dir, "y.src").also { it.writeBytes("y".toByteArray()) }
            writeCopy(dir, DeviceCopy("c1", true, "Sent", listOf("b@x"), x, via = "acct-2"), "acct-1")
            writeCopy(dir, DeviceCopy("c2", false, "Sent", listOf("b@x"), y), "acct-1")
            val read = readCopies(dir, "acct-1").associateBy { it.id }
            assertEquals("acct-2", read.getValue("c1").via)
            assertTrue(read.getValue("c1").appendOnly)
            assertEquals("", read.getValue("c2").via)
            assertFalse(read.getValue("c2").appendOnly)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class KnownFromsTest {
    @Test
    fun plusAddressesStayDistinct() {
        val rules = listOf(SendingRule("b", actions = RuleActions(fromAddress = "me+b@x")), SendingRule("c", actions = RuleActions(fromAddress = "ME@x")))
        assertEquals(listOf("me@x", "me+b@x"), knownFroms(rules, SendingRule(AccountDefaultRule, actions = RuleActions(fromAddress = "me@x"))))
    }
}

class SafetyNetTest {
    @Test
    fun plusIdentityMismatchIsFlagged() {
        val rules = listOf(SendingRule("b", RuleConditions(recipient = listOf("@other.test")), RuleActions(fromAddress = "me+b@x", sendAccount = "B")))
        val def = SendingRule(AccountDefaultRule, actions = RuleActions(fromAddress = "me@x"))
        val ctx = ComposeContext(ComposeKindTag.New, recipients = listOf("y@other.test"))
        val froms = { id: String -> if (id == "B") listOf("me+b@x") else listOf("me@x") }
        assertTrue(fromMismatch(rules, def, "A", ctx, "me@x", "B", froms) != null)
        assertEquals(null, fromMismatch(rules, def, "A", ctx, "me+b@x", "B", froms))
    }
}
