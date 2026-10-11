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

    // Read at run time from the installed app: a constant would be inlined into the test APK at its build time.
    private fun appCommit(): String = BuildConfig::class.java.getField("GIT_COMMIT").get(null) as String

    @Test
    fun imapSuite() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty())
        val expect = arg("labCommit")
        if (expect.isNotEmpty()) assertEquals("installed build is not master HEAD", expect, appCommit())
        val port = arg("labPort").toInt()
        val tls = TlsMode.valueOf(arg("labTls", "None"))
        val server = arg("labServer", "lab")
        val run = arg("labRun", "run")
        val box = arg("labFolder", "LiveIMAP-Test")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
        TrafficRecorder.start(
            File(dir, "$run.jsonl"),
            TrafficRecorder.RunHeader(BuildConfig.VERSION_NAME, appCommit(), "app", server, host, port, tls.name, "imap", "imapSuite"),
        )
        val steps = StringBuilder("step\tresult\tdetail\n")
        LabTestHooks.set(host, arg("labPass"))
        LabTestHooks.setMaxTls12(arg("labMaxTls12") == "true")
        val ca = arg("labCa")
        if (ca.isNotEmpty()) LabTestHooks.setLabCa(android.util.Base64.decode(ca, android.util.Base64.DEFAULT))
        val session = LibetpanMailSession()
        // Only the lab CA is trusted; an untrusted or changed certificate fails the run.
        session.setCertConfirmer { false }
        // Clear-text login goes through the real confirmation path: the account starts without
        // allowPlaintextAuth and the suite accepts the prompt, counting how often it was asked.
        var plaintextConfirms = 0
        session.setPlaintextConfirmer {
            plaintextConfirms++
            true
        }
        var failed = 0
        suspend fun step(name: String, body: suspend () -> String) {
            TrafficRecorder.event("main", "step", name)
            val (ok, detail) = try { "ok" to body() } catch (e: Throwable) { "fail" to (e.message ?: e.javaClass.simpleName) }
            if (ok == "fail") failed++
            steps.append(name).append('\t').append(ok).append('\t').append(detail.replace('\t', ' ').replace('\n', ' ').take(400)).append('\n')
        }
        val account = AccountSettings(
            imapHost = host, imapPort = port, username = arg("labUser"), tlsMode = tls,
            logImapTraffic = true, allowPlaintextAuth = arg("labPlainAuth") == "true",
            allowOldTls = arg("labOldTls") == "true",
            authMechanism = arg("labMech"),
            email = arg("labRcpt"),
            smtpHost = if (arg("labSmtpPort").isNotEmpty()) host else "",
            smtpPort = arg("labSmtpPort").toIntOrNull() ?: 25,
        )
        var opened = false
        try {
            step("open") {
                val r = session.open(account)
                opened = r is OpenResult.Connected
                if (!opened) {
                    throw IllegalStateException(
                        when (r) {
                            is OpenResult.Failed -> r.text
                            is OpenResult.Rejected -> "rejected: ${r.capabilities}"
                            else -> r.toString()
                        },
                    )
                }
                "plaintextConfirms=$plaintextConfirms " + session.capabilities.sorted().joinToString(" ")
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
                if ("THREAD=REFERENCES" in session.capabilities) step("threadReferences") { session.thread(SortKey.ThreadReferences).toString().take(40) }
                if ("THREAD=ORDEREDSUBJECT" in session.capabilities) step("threadOrderedSubject") { session.thread(SortKey.ThreadOrderedSubject).toString().take(40) }
                // Lab-only send: the SMTP host is the lab host (no relay), the recipient a lab test account.
                val rcpt = arg("labRcpt")
                if (account.smtpHost.isNotEmpty() && account.smtpHost == host && rcpt.isNotEmpty()) {
                    step("smtpSend") {
                        val msg = ("From: $rcpt\r\nTo: $rcpt\r\nSubject: LiveIMAP lab send $run\r\n" +
                            "Message-ID: <$run@liveimap.lab>\r\n\r\nLab SMTP recording test.\r\n").toByteArray()
                        session.smtpSend(msg, listOf(rcpt)); ""
                    }
                }
                val sievePort = arg("labSievePort").toIntOrNull()
                if (sievePort != null) {
                    step("sieveDeliver") {
                        TrafficRecorder.event("sieve", "connect", "$host:$sievePort ${arg("labSieveTls")}")
                        org.dlang.liveimap.engine.sieve.openAndDeliverLiveimap(
                            host, sievePort, TlsMode.valueOf(arg("labSieveTls")), "", { true }, { },
                            arg("labUser"), arg("labPass"), "# LiveIMAP lab test\r\nkeep;\r\n",
                            allowPlaintextAuth = false,
                        )
                    }
                }
                step("noop") { session.noop(); "" }
                step("unselect") { session.unselect(); "" }
                assertTrue("exists $exists", exists >= 0)
            }
        } finally {
            session.close()
            TrafficRecorder.stop()
            LabTestHooks.clear()
            File(dir, "$run.steps.tsv").writeText(steps.toString())
        }
        assertEquals("failed steps:\n$steps", 0, failed)
    }
}
