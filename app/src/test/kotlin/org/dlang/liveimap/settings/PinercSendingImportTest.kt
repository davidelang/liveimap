package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.compose.DsnMode
import org.dlang.liveimap.ui.compose.decodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinercSendingImportTest {
    @Test
    fun sendingSettingsImported() {
        val text = """
            literal-signature="David Lang\nlang.hm"
            reply-indent-string="| "
            reply-leadin="_PERSONAL_ wrote on _DATE_:"
            feature-list=enable-sigdashes,signature-at-bottom,enable-delivery-status-notification
            customized-hdrs=Reply-To: me@x,X-Priority
            viewer-hdrs=List-Id,X-Mailer
            patterns-roles=LIT:pattern="/NICK=work/TO=@corp.example" action="/ROLE=1/FROM=David <david@corp.example>/FCC=Work.Sent/LSIG=Work",
                LIT:pattern="/NICK=odd/SCORE=5" action="/ROLE=1/FROM=x@y"
        """.trimIndent() + "\n"
        val r = previewPinerc(text, AccountSettings())
        val n = r.next
        assertEquals("David Lang\nlang.hm", n.signature)
        assertEquals("| ", n.quotePrefix)
        assertEquals("{name} wrote on {date}:", n.replyLeadIn)
        assertTrue(n.sigDashes)
        assertTrue(n.sigAtBottom)
        assertEquals(DsnMode.FailureDelay, n.dsnMode)
        assertEquals("Reply-To, X-Priority", n.composerHeaders)
        assertEquals("List-Id, X-Mailer", n.viewerHeaders)
        val rules = decodeRules(n.sendingRules)
        assertEquals(listOf("work"), rules.map { it.name })
        assertEquals("david@corp.example", rules[0].actions.fromAddress)
        assertEquals("David", rules[0].actions.fromName)
        assertEquals("Work.Sent", rules[0].actions.fcc)
        assertEquals(listOf("@corp.example"), rules[0].conditions.sentTo)
        assertTrue(r.skipped.any { "odd" in it })
        // F106: the preview shows what the role does and when
        assertTrue(r.rows.any { "work (when sent to @corp.example; sets From David <david@corp.example>, Fcc Work.Sent" in it })
    }

    @Test
    fun roleNeedsNickAndRoleFlag() {
        assertNull(pineRole("LIT:pattern=\"/TO=a\" action=\"/ROLE=1/FROM=a\""))
        assertNull(pineRole("LIT:pattern=\"/NICK=a\" action=\"/ROLE=0\""))
    }

    @Test
    fun roleWithoutConditionsIsNotImported() {
        // F106: /NICK only would match every compose and silently add Reply-To
        val text = "patterns-roles=LIT:pattern=\"/NICK=Work\" action=\"/ROLE=1/REPLYTO=collector@evil.example\"\n"
        val r = previewPinerc(text, AccountSettings())
        assertTrue(decodeRules(r.next.sendingRules).isEmpty())
        assertTrue(r.skipped.any { "Work" in it && "every message" in it })
    }
}
