package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.jmap.JmapMailSession
import org.dlang.liveimap.jmap.offerForAccount
import org.dlang.liveimap.jmap.platformJmapExchange
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lab only: the JMAP session against one lab server (stalwart https, Cyrus http).
 * -e jmapServer <the account's JMAP server field> -e labHost <lab IP> -e labUser/labPass/labCa -e labRun.
 * Steps: open (login), folders, list, read, flags, move, push. Writes cache/recordings/<run>.steps.tsv.
 */
@RunWith(AndroidJUnit4::class)
class JmapLabSuiteTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String = "") = args.getString(k) ?: d

    @Test
    fun jmapSuite() = runBlocking {
        val field = arg("jmapServer")
        assumeTrue("no jmapServer argument", field.isNotEmpty())
        val host = arg("labHost")
        val pass = arg("labPass")
        LabTestHooks.set(host, pass)
        val ca = arg("labCa")
        if (ca.isNotEmpty()) LabTestHooks.setLabCa(android.util.Base64.decode(ca, android.util.Base64.DEFAULT))
        val account = AccountSettings(imapHost = field, username = arg("labUser"), jmapFolders = true)
        fun session() = JmapMailSession(discover = { a, auth -> offerForAccount(a, ::platformJmapExchange, authorization = auth) }, passwordOf = { pass })
            .also { it.setCertConfirmer { false } } // only the lab CA is trusted
        val s = session()
        val steps = StringBuilder("step\tresult\tdetail\n")
        var failed = 0
        suspend fun step(name: String, body: suspend () -> String) {
            val (ok, detail) = try { "ok" to body() } catch (e: Throwable) { "fail" to (e.message ?: e.javaClass.simpleName) }
            if (ok == "fail") failed++
            steps.append(name).append('\t').append(ok).append('\t').append(detail.replace('\t', ' ').replace('\n', ' ').take(400)).append('\n')
        }
        var inbox = ""
        var other = ""
        step("open") {
            val r = s.open(account)
            if (r != OpenResult.Connected) throw IllegalStateException("open: $r")
            "connected"
        }
        step("folders") {
            val top = s.listLevel("", null, false)
            inbox = top.firstOrNull { it.specialUse == "\\Inbox" || it.leaf.equals("inbox", true) }?.mailbox ?: throw IllegalStateException("no inbox in ${top.map { it.leaf }}")
            other = top.firstOrNull { it.leaf == arg("labFolder", "LiveIMAP-Test") }?.mailbox
                ?: top.firstOrNull { it.mailbox != inbox && it.specialUse == null }?.mailbox
                ?: top.first { it.mailbox != inbox }.mailbox
            "${top.size} top-level: " + top.joinToString(",") { it.leaf }
        }
        var uids = emptyList<Long>()
        step("list") {
            val sel = s.select(inbox)
            val rows = s.fetchIndex(IndexRequest(inbox, IndexMode.ArrivalNewest, limit = 10))
            uids = rows.map { it.uid }
            if (rows.isEmpty()) throw IllegalStateException("inbox empty (exists=${sel.exists})")
            "exists=${sel.exists} listed=${rows.size} first='${rows.first().subject.take(40)}'"
        }
        step("read") {
            val uid = uids.first()
            val structure = s.fetchStructure(uid)
            val raw = s.fetchRfc822(uid)
            if (raw.isEmpty()) throw IllegalStateException("empty message")
            "uid=$uid type=${structure.type}/${structure.subtype} bytes=${raw.size}"
        }
        step("flags") {
            val uid = uids.first()
            fun flagsOf() = runBlocking { s.fetchIndex(IndexRequest(inbox, IndexMode.ByUid, uids = listOf(uid))).single().flags }
            s.storeFlags(listOf(uid), setOf("\\Flagged"), emptySet())
            val on = flagsOf()
            s.storeFlags(listOf(uid), emptySet(), setOf("\\Flagged"))
            val off = flagsOf()
            if ("\\Flagged" !in on || "\\Flagged" in off) throw IllegalStateException("on=$on off=$off")
            "set and cleared \\Flagged"
        }
        step("move") {
            val before = s.selectedExists()
            val subject = s.fetchIndex(IndexRequest(inbox, IndexMode.ByUid, uids = listOf(uids.last()))).single().subject
            s.copyThenDelete(listOf(uids.last()), other)
            s.select(inbox)
            val mid = s.selectedExists()
            s.select(other)
            val there = s.fetchIndex(IndexRequest(other, IndexMode.ArrivalNewest, limit = 50)).firstOrNull { it.subject == subject }
                ?: throw IllegalStateException("not found in $other")
            s.copyThenDelete(listOf(there.uid), inbox)
            s.select(inbox)
            val after = s.selectedExists()
            if (mid != before - 1 || after != before) throw IllegalStateException("exists $before -> $mid -> $after")
            "INBOX -> $other -> INBOX ($before -> $mid -> $after)"
        }
        step("push") {
            s.select(inbox)
            val got = CompletableDeferred<MailboxChange>()
            s.watch(inbox) { c -> if (c is MailboxChange.Exists || c is MailboxChange.WatchLost) got.complete(c) }
            Thread.sleep(2000)
            val writer = session()
            if (writer.open(account) != OpenResult.Connected) throw IllegalStateException("second session")
            writer.listLevel("", null, false)
            val msg = "From: liveimap@lab.test\r\nTo: liveimap@lab.test\r\nSubject: jmap push ${System.currentTimeMillis()}\r\n\r\npush\r\n".toByteArray()
            val newUid = writer.appendReturningUid(inbox, msg, emptySet())
            val change = withTimeoutOrNull(45_000) { got.await() }
            s.stopWatch()
            writer.select(inbox)
            if (newUid > 0) writer.uidExpunge(listOf(newUid))
            writer.close()
            when (change) {
                is MailboxChange.Exists -> "Exists(${change.exists}) after append"
                null -> throw IllegalStateException("no push within 45 s")
                else -> throw IllegalStateException("watch: $change")
            }
        }
        s.close()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
        File(dir, arg("labRun", "jmap") + ".steps.tsv").writeText(steps.toString())
        android.util.Log.i("JmapLab", steps.toString())
        assertEquals("failed steps:\n$steps", 0, failed)
    }
}
