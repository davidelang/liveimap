package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.TrafficRecorder
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lab suite: drives the native session against one lab server and records the traffic.
 * Arguments come from tools/test-servers/lab.sh (-e key value). Skipped without labHost.
 * Writes cache/recordings/<run>.jsonl and <run>.steps.tsv. Lab servers only.
 */
@RunWith(AndroidJUnit4::class)
class LabSuiteTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String = "") = args.getString(k) ?: d

    @Test
    fun imapSuite() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty())
        val expect = arg("labCommit")
        if (expect.isNotEmpty()) assertEquals("installed build is not master HEAD", expect, BuildConfig.GIT_COMMIT)
        val port = arg("labPort").toInt()
        val tls = TlsMode.valueOf(arg("labTls", "None"))
        val server = arg("labServer", "lab")
        val run = arg("labRun", "run")
        val box = arg("labFolder", "LiveIMAP-Test")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
        TrafficRecorder.start(
            File(dir, "$run.jsonl"),
            TrafficRecorder.RunHeader(BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT, "app", server, host, port, tls.name, "imap", "imapSuite"),
        )
        val steps = StringBuilder("step\tresult\tdetail\n")
        LabTestHooks.password = arg("labPass")
        val session = LibetpanMailSession()
        session.setCertConfirmer { true }
        session.setPlaintextConfirmer { true }
        var failed = 0
        suspend fun step(name: String, body: suspend () -> String) {
            TrafficRecorder.event("main", "step", name)
            val (ok, detail) = try { "ok" to body() } catch (e: Throwable) { "fail" to (e.message ?: e.javaClass.simpleName) }
            if (ok == "fail") failed++
            steps.append(name).append('\t').append(ok).append('\t').append(detail.replace('\t', ' ').replace('\n', ' ').take(200)).append('\n')
        }
        val account = AccountSettings(
            imapHost = host, imapPort = port, username = arg("labUser"), tlsMode = tls,
            logImapTraffic = true, allowPlaintextAuth = true,
        )
        var opened = false
        try {
            step("open") {
                val r = session.open(account)
                opened = r is OpenResult.Connected
                if (!opened) throw IllegalStateException(r.toString())
                session.capabilities.sorted().joinToString(" ")
            }
            if (opened) {
                step("namespaces") { session.namespaces().size.toString() }
                step("listRoot") { session.listLevel("", null, true).size.toString() }
                step("listTest") { session.mailboxListed(box).toString() }
                step("statusInbox") { session.statusMessages(listOf("INBOX")).toString() }
                var exists = 0
                step("select") { session.select(box).also { exists = it.exists }.toString() }
                var uids = emptyList<Long>()
                step("indexNewest") {
                    uids = session.fetchIndex(IndexRequest(box, IndexMode.ArrivalNewest, limit = 30, includePreview = true)).map { it.uid }
                    uids.size.toString()
                }
                val first = uids.firstOrNull()
                if (first != null) {
                    step("structure") { session.fetchStructure(first).toString().take(80) }
                    step("peek") { session.peekPart(first, "1", 0, 2048).size.toString() }
                    step("rfc822") { session.fetchRfc822(first).size.toString() }
                    step("flagOn") { session.storeFlags(listOf(first), setOf("\\Flagged"), emptySet()); "" }
                    step("flagOff") { session.storeFlags(listOf(first), emptySet(), setOf("\\Flagged")); "" }
                }
                step("searchText") { session.searchText("LiveIMAP").size.toString() }
                step("searchAdvanced") { session.searchCriterion("Advanced", "Or\nYes\tSubject\tLiveIMAP").size.toString() }
                if ("SORT" in session.capabilities) step("sortDate") { session.sort(SortKey.Date, true).size.toString() }
                if (session.capabilities.any { it.startsWith("THREAD=") }) step("thread") { session.thread(SortKey.Date).toString().take(40) }
                step("noop") { session.noop(); "" }
                step("unselect") { session.unselect(); "" }
                assertTrue("exists $exists", exists >= 0)
            }
        } finally {
            session.close()
            TrafficRecorder.stop()
            LabTestHooks.password = null
            File(dir, "$run.steps.tsv").writeText(steps.toString())
        }
        assertEquals("failed steps:\n$steps", 0, failed)
    }
}
