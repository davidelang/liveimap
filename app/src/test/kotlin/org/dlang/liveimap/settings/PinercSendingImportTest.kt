package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.compose.ComposeKindTag
import org.dlang.liveimap.ui.compose.DsnMode
import org.dlang.liveimap.ui.compose.RuleActions
import org.dlang.liveimap.ui.compose.RuleConditions
import org.dlang.liveimap.ui.compose.SendingRule
import org.dlang.liveimap.ui.compose.decodeRules
import org.dlang.liveimap.ui.compose.encodeRules
import org.dlang.liveimap.ui.toolbar.BarSection
import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarLayout
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultReaderToolbar
import org.dlang.liveimap.ui.toolbar.moveIndexAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun statsIsRefusedAndExportMatchesImport() {
        val withStats = "LIT:pattern=\"/NICK=work/TO=a@b/STATS=1\" action=\"/ROLE=1/FROM=me@x\""
        assertNull(pineRole(withStats))
        val without = "LIT:pattern=\"/NICK=work/TO=a@b\" action=\"/ROLE=1/FROM=me@x\""
        val imported = pineRole(without)
        assertEquals("work", imported?.name)
        assertEquals(listOf("a@b"), imported?.conditions?.sentTo)

        val quoted = pinercQuoted("a,b")
        assertEquals("\"a,b\"", quoted)
        assertEquals(listOf("a,b"), previewPinerc("alt-addresses=$quoted\n", AccountSettings()).next.altAddresses)

        val role = SendingRule(
            name = "work",
            conditions = RuleConditions(
                kinds = setOf(ComposeKindTag.Reply),
                sentTo = listOf("a@b"),
                listId = "list.example",
            ),
            actions = RuleActions(fromAddress = "me@x", sendAccount = "other-account"),
        )
        val account = AccountSettings(
            signature = "Hello\nthere",
            quotePrefix = "| ",
            replyLeadIn = "On {date}, {from} wrote:",
            composerHeaders = "Reply-To, X-Priority",
            viewerHeaders = "List-Id, X-Mailer",
            sigDashes = true,
            sigAtBottom = true,
            dsnMode = DsnMode.Off,
            sendingRules = encodeRules(listOf(role)),
        )
        val text = encodePinerc(account)
        val features = text.lines().first { it.startsWith("feature-list=") }.substringAfter("=").split(",")
        assertTrue(features.contains("enable-sigdashes"))
        assertTrue(features.contains("signature-at-bottom"))
        assertTrue(features.contains("no-enable-delivery-status-notification"))
        assertFalse(features.contains("enable-delivery-status-notification"))
        val rolesLine = text.lines().first { it.startsWith("patterns-roles=") }
        assertFalse(rolesLine.contains("reply"))
        assertFalse(rolesLine.contains("list.example"))
        assertFalse(rolesLine.contains("other-account"))
        assertFalse(text.contains("signature tokens are unread"))
        val back = previewPinerc(text, AccountSettings()).next
        assertEquals("Hello\nthere", back.signature)
        assertEquals("| ", back.quotePrefix)
        assertEquals("On {date}, {from} wrote:", back.replyLeadIn)
        assertEquals("Reply-To, X-Priority", back.composerHeaders)
        assertEquals("List-Id, X-Mailer", back.viewerHeaders)
        assertTrue(back.sigDashes)
        assertTrue(back.sigAtBottom)
        assertEquals(DsnMode.Off, back.dsnMode)
        val rules = decodeRules(back.sendingRules)
        assertEquals(1, rules.size)
        assertEquals("work", rules[0].name)
        assertEquals(listOf("a@b"), rules[0].conditions.sentTo)
        assertTrue(rules[0].conditions.kinds.isEmpty())
        assertEquals("", rules[0].conditions.listId)
        assertEquals("me@x", rules[0].actions.fromAddress)
        assertNull(rules[0].actions.sendAccount)
    }

    @Test
    fun toolbarCommentFollowsReaderToolbar() {
        val custom = ReaderToolbarLayout(
            toolbar = listOf(ReaderToolbarAction.Refresh, ReaderToolbarAction.Reply),
            overflow = listOf(
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
                ReaderToolbarAction.Headers,
            ),
            hidden = emptyList(),
        )
        assertTrue(encodePinerc(AccountSettings(readerToolbar = custom)).contains("# toolbar"))
        assertFalse(encodePinerc(AccountSettings()).contains("# toolbar"))
        assertFalse(encodePinerc(AccountSettings(readerToolbar = defaultReaderToolbar())).contains("# toolbar"))
        val index = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Overflow)
        assertTrue(encodePinerc(AccountSettings(indexBar = index)).contains("# toolbar"))
    }
}
