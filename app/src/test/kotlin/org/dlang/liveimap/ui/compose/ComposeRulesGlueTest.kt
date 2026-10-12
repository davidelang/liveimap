package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeRulesGlueTest {
    @Test
    fun sendingFieldsRoundTrip() {
        val s = AccountSettings(
            signature = "David\nLang & co", sigDashes = false, sigAtBottom = true, quotePrefix = "| ",
            replyLeadIn = "{name} wrote:", dsnMode = DsnMode.FailureDelay,
            composerHeaders = "Reply-To, X-Priority", viewerHeaders = "List-Id",
            sendingRules = encodeRules(listOf(RulePresets.mailingList("x.example.org", "me@example.org"))),
        )
        val back = decodeAccountSettings(s.encode())
        assertEquals(s.signature, back.signature)
        assertFalse(back.sigDashes)
        assertTrue(back.sigAtBottom)
        assertEquals("| ", back.quotePrefix)
        assertEquals("{name} wrote:", back.replyLeadIn)
        assertEquals(DsnMode.FailureDelay, back.dsnMode)
        assertEquals(s.composerHeaders, back.composerHeaders)
        assertEquals(s.viewerHeaders, back.viewerHeaders)
        assertEquals(s.sendingRules, back.sendingRules)
        assertFalse(AccountSettings().encode().contains("signature="))
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

    @Test
    fun extraHeadersEmittedButNotOverriding() {
        val text = String(
            buildPlain(
                PlainMessage(
                    fromName = "", fromEmail = "a@x", to = listOf("b@x"), cc = emptyList(), bcc = emptyList(),
                    subject = "s", body = "b", messageId = "<1@x>", date = "Mon, 12 Oct 2026 05:00:00 -0700",
                    extraHeaders = listOf("Reply-To" to "r@x", "Subject" to "evil", "X-Empty" to " "),
                ),
            ).rfc822,
        )
        assertTrue(text.contains("\r\nReply-To: r@x\r\n"))
        assertFalse(text.contains("evil"))
        assertFalse(text.contains("X-Empty"))
    }
}

class ViewerHeadersTest {
    @Test
    fun listOrderAndUnfolding() {
        val h = "From: a@x\r\nX-Mailer: M\r\nList-Id: Foo\r\n <foo.x>\r\nSubject: =?UTF-8?Q?h=C3=A9?=\r\n\r\n"
        assertEquals(
            listOf("List-Id" to "Foo <foo.x>", "X-Mailer" to "M", "Subject" to "hé"),
            org.dlang.liveimap.ui.reader.viewerHeaderLines(h, "List-Id, X-Mailer, Subject, X-None"),
        )
    }
}

class CrossAccountTest {
    @Test
    fun listPost() {
        fun p(h: String) = listPostAddress(parseRfc822("$h\r\nSubject: s\r\n\r\nb".toByteArray()))
        assertEquals("dev@lists.x", p("List-Post: <mailto:dev@lists.x?subject=hi>"))
        assertEquals(null, p("List-Post: NO (posting not allowed)"))
        assertEquals(null, p("From: a@x"))
    }

    @Test
    fun unsentCopyKeepsSendingAccount() {
        val dir = java.nio.file.Files.createTempDirectory("unsent").toFile()
        try {
            writeCopy(dir, DeviceCopy("c1", true, "Sent", listOf("b@x"), "x".toByteArray(), via = "acct-2"), "acct-1")
            writeCopy(dir, DeviceCopy("c2", false, "Sent", listOf("b@x"), "y".toByteArray()), "acct-1")
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
