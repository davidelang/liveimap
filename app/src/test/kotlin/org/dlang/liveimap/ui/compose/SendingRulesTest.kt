package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendingRulesTest {
    private val default = SendingRule(AccountDefaultRule, actions = RuleActions(fromAddress = "me@home.example", signature = "Me", fcc = "Sent"))

    @Test fun matching() {
        assertTrue(addressMatches("@work.example", "Boss <boss@work.example>"))
        assertTrue(addressMatches("@work.example", "a@eu.work.example"))
        assertTrue(addressMatches("me@x.org", "me+lists@x.org"))
        assertTrue(!addressMatches("me+a@x.org", "me+b@x.org"))
        assertTrue(addressMatches("me@gmail.com", "M.E+x@googlemail.com"))
        assertTrue(addressMatches("me@gmail.com", "m.e@gmail.com"))
        assertTrue(!addressMatches("me.too@gmail.com", "metoo@gmail.com"))
        assertTrue(!addressMatches("me.too@gmail.com", "me.too+x@gmail.com"))
        assertTrue(!addressMatches("ab@x.com", "a.b@x.com"))
        assertTrue(addressMatches("ab@x.com", "ab+z@x.com"))
        assertTrue(addressMatches("@gmail.com", "a@gmail.com"))
        assertTrue(!addressMatches("@gmail.com", "a@googlemail.com"))
        assertTrue(addressMatches("me*", "me.too@gmail.com"))
        assertTrue(globMatches("Work/*", "work/projects/a"))
        assertTrue(!globMatches("Work/%", "Work/a/b"))
    }

    @Test fun fieldWiseFirstMatchAndDefault() {
        val rules = listOf(
            SendingRule("Signature for reply", RuleConditions(kinds = setOf(ComposeKindTag.Reply)), RuleActions(signature = "short")),
            RulePresets.perRecipient("@work.example", "me@work.example", "Work sig", account = "work"),
        )
        val r = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.Reply, recipients = listOf("boss@work.example")))
        assertEquals("me@work.example", r.fromAddress)
        assertEquals("short", r.signature) // first rule setting a signature
        assertEquals("work", r.sendAccount)
        assertEquals("Sent", r.fcc)
        assertEquals("To @work.example", r.fromRule)
        val n = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.New, recipients = listOf("friend@x.org")))
        assertEquals("me@home.example", n.fromAddress)
        assertEquals(AccountDefaultRule, n.fromRule)
        assertEquals("home", n.sendAccount)
        assertEquals(DefaultQuotePrefix, n.quotePrefix)
    }

    @Test fun replyFromSentToKeepsPlusTag() {
        val rules = RulePresets.replyFromSentTo(listOf("alias@x.org"))
        val r = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.Reply, originalTo = listOf("Alias <alias+news@x.org>")))
        assertEquals("alias+news@x.org", r.fromAddress)
        val f = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.Forward, originalTo = listOf("alias@x.org")))
        assertEquals("alias@x.org", f.fromAddress)
    }

    @Test fun listAndFolderPresets() {
        val rules = listOf(
            RulePresets.mailingList("dev.lists.example", "me+dev@home.example"),
            RulePresets.perFolder("Work/*", "me@work.example", "Work/Sent", null),
        )
        val l = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.Reply, listId = "Dev <dev.lists.example>"))
        assertEquals("me+dev@home.example", l.fromAddress)
        assertTrue(l.replyToList)
        assertEquals("", l.signature)
        val f = resolveSending(rules, default, "home", ComposeContext(ComposeKindTag.New, folder = "Work/Clients"))
        assertEquals("Work/Sent", f.fcc)
        assertEquals("Me", f.signature)
    }

    @Test fun pinnedFrom() {
        val rules = listOf(RulePresets.perRecipient("@work.example", "me@work.example", "Work sig"))
        val r = resolveSending(rules, default, "home",
            ComposeContext(ComposeKindTag.New, recipients = listOf("b@work.example"), pinnedFrom = "me@home.example"))
        assertEquals("me@home.example", r.fromAddress)
        assertEquals("Me", r.signature)
    }

    @Test fun personalGmailIsOneMailbox() {
        val mine = foldedMailbox("davidlang@gmail.com")
        assertEquals(mine, foldedMailbox("Name <d.a.v.i.d.l.a.n.g@gmail.com>"))
        assertEquals(mine, foldedMailbox("davidlang+x@gmail.com"))
        assertEquals(mine, foldedMailbox("d.a.v.i.d.l.a.n.g+x@googlemail.com"))
        assertEquals(foldedMailbox("alt@gmail.com"), foldedMailbox("a.l.t+z@googlemail.com"))
        // Dots still have to be the same letters: d.lang is dlang, not davidlang.
        assertEquals(foldedMailbox("dlang@gmail.com"), foldedMailbox("d.lang+x@googlemail.com"))
        assertTrue(mine != foldedMailbox("d.lang+x@googlemail.com"))
        assertTrue(foldedMailbox("a.b@x.com") != foldedMailbox("ab@x.com"))
        assertEquals(foldedMailbox("a.b@x.com"), foldedMailbox("A.B+z@x.com"))
    }

    @Test fun fromMismatchStaysExactForPlus() {
        val froms = { _: String -> listOf("me@x") }
        val ctx = ComposeContext(ComposeKindTag.New, recipients = listOf("f@x.org"))
        val account = SendingRule(AccountDefaultRule, actions = RuleActions(fromAddress = "me@x"))
        assertNotNull(fromMismatch(emptyList(), account, "home", ctx, "me+work@x", "home", froms))
        assertNull(fromMismatch(emptyList(), account, "home", ctx, "me@x", "home", froms))
    }

    @Test fun safetyNet() {
        val rules = listOf(RulePresets.perRecipient("@work.example", "me@work.example", null, account = "work"))
        val froms = { acct: String -> if (acct == "work") listOf("me@work.example") else listOf("me@home.example") }
        val ctx = ComposeContext(ComposeKindTag.New, recipients = listOf("b@work.example"))
        assertNotNull(fromMismatch(rules, default, "home", ctx, "me@home.example", "home", froms))
        assertNotNull(fromMismatch(rules, default, "home", ctx, "me@work.example", "home", froms)) // wrong server
        assertNull(fromMismatch(rules, default, "home", ctx, "me@work.example", "work", froms))
        val personal = ComposeContext(ComposeKindTag.New, recipients = listOf("f@x.org"))
        assertNull(fromMismatch(rules, default, "home", personal, "me@home.example", "home", froms))
    }

    @Test fun storageRoundTrip() {
        val rules = listOf(
            RulePresets.mailingList("a&b=c", "me@x.org", "line1\nline2, more"),
            SendingRule("x", extra = mapOf("future" to "1")),
            RulePresets.plusFolder("me@x.org", "shop"),
        ).map { it.copy(actions = it.actions.copy(dsn = DsnMode.FailureDelay, leadIn = "On {date} {from}:")) }
        assertEquals(rules, decodeRules(encodeRules(rules)))
        assertEquals("me+shop@x.org", rules[2].actions.fromAddress)
        // F65: a non-hex escape in a hand-edited string stays literal instead of throwing on load
        val one = encodeRules(listOf(SendingRule("x")))
        val edited = one.replace("x", "x%zz")
        assertEquals("x%zz", decodeRules(edited).single().name)
    }

    @Test
    fun patchByIdKeepsTheOtherRule() {
        val a = SendingRule("A", id = "a")
        val b = SendingRule("B", id = "b")
        val deleted = listOf(b)
        assertEquals(deleted, patchSendingRule(deleted, "a") { it.copy(name = "Z") })
        val named = patchSendingRule(listOf(a, b), "a") { it.copy(name = "A2") }
        val both = patchSendingRule(named, "a") { it.copy(conditions = it.conditions.copy(recipient = listOf("r"))) }
        assertEquals("A2", both[0].name)
        assertEquals(listOf("r"), both[0].conditions.recipient)
        assertEquals("B", both[1].name)
        val swapped = listOf(b, a)
        val patched = patchSendingRule(swapped, "a") { it.copy(name = "A3") }
        assertEquals("b", patched[0].id)
        assertEquals("A3", patched[1].name)
        assertEquals(listOf(a, b), patchSendingRule(listOf(a, b), "missing") { it.copy(name = "nope") })
    }
}
