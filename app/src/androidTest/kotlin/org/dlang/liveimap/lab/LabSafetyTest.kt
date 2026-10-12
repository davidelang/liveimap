package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.TrafficRecorder
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.MoveMethod
import org.dlang.liveimap.settings.TlsMode
import org.dlang.liveimap.ui.index.effectiveDeletePolicy
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mail-safety scenarios against the strict fake in model mode (tools/test-servers/safety_run.py).
 * The fake's command log is the oracle (EXPUNGE/CLOSE/MOVE only where the scenario allows them); this
 * test only drives the session the way the UI does and records what the app saw.
 * Args: labScenario, labAutoExpunge, labMoveMethod, labDeletePolicy, plus the LabSuiteTest connection args.
 */
@RunWith(AndroidJUnit4::class)
class LabSafetyTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String = "") = args.getString(k) ?: d
    private fun appCommit(): String = BuildConfig::class.java.getField("GIT_COMMIT").get(null) as String

    @Test
    fun safety() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty())
        val expect = arg("labCommit")
        if (expect.isNotEmpty()) assertEquals("installed build is not master HEAD", expect, appCommit())
        val port = arg("labPort").toInt()
        val run = arg("labRun", "safety")
        val box = arg("labFolder", "LiveIMAP-Test")
        val target = "LiveIMAP-Target"
        val scenario = arg("labScenario")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
        TrafficRecorder.start(
            File(dir, "$run.jsonl"),
            TrafficRecorder.RunHeader(BuildConfig.VERSION_NAME, appCommit(), "app", arg("labServer"), host, port, "None", "imap", "safety-$scenario"),
        )
        val steps = StringBuilder("step\tresult\tdetail\n")
        LabTestHooks.set(host, arg("labPass"))
        val session = LibetpanMailSession()
        session.setCertConfirmer { false }
        session.setPlaintextConfirmer { true }
        var failed = 0
        suspend fun step(name: String, body: suspend () -> String) {
            TrafficRecorder.event("main", "step", name)
            val (ok, detail) = try { "ok" to body() } catch (e: Throwable) { "fail" to (e.message ?: e.javaClass.simpleName) }
            if (ok == "fail") failed++
            steps.append(name).append('\t').append(ok).append('\t').append(detail.replace('\t', ' ').replace('\n', ' ').take(300)).append('\n')
        }
        val account = AccountSettings(
            imapHost = host, imapPort = port, username = arg("labUser"), tlsMode = TlsMode.None,
            logImapTraffic = true, authMechanism = "IMAP-LOGIN",
            autoExpunge = arg("labAutoExpunge") == "true",
            moveMethod = MoveMethod.valueOf(arg("labMoveMethod", "CopyThenMarkDeleted")),
            deletePolicy = DeletePolicy.valueOf(arg("labDeletePolicy", "MarkDeleted")),
        )
        try {
            step("open") {
                val r = session.open(account)
                check(r is OpenResult.Connected) { r.toString() }
                session.capabilities.sorted().joinToString(" ")
            }
            var uids = emptyList<Long>()
            step("select") { session.select(box).toString() }
            step("list") { uids = session.searchCriterion("NotDeleted", ""); uids.joinToString(",") }
            suspend fun delete(uid: Long) {
                // As IndexModel.performSwipe(Delete): the effective policy, then the matching session calls.
                val trash = session.knownTrash()
                when (effectiveDeletePolicy(account.deletePolicy, false, session.featureCaps.uidPlus, trash.isNotEmpty())) {
                    DeletePolicy.MarkDeleted -> session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                    DeletePolicy.MoveToTrash -> session.copyThenDelete(listOf(uid), trash)
                    DeletePolicy.DeletePermanently -> {
                        session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        session.uidExpunge(listOf(uid))
                    }
                }
            }
            when (scenario) {
                "view" -> {
                    step("flagOn") { session.storeFlags(listOf(uids[0]), setOf("\\Flagged"), emptySet()); "" }
                    step("flagOff") { session.storeFlags(listOf(uids[0]), emptySet(), setOf("\\Flagged")); "" }
                    step("seen") { session.storeFlags(listOf(uids[1]), setOf("\\Seen"), emptySet()); "" }
                }
                "delete" -> step("delete") { delete(uids[1]); session.knownTrash() }
                "move" -> step("move") { session.copyThenDelete(listOf(uids[2]), target); session.takeCopiedUids().toString() }
                "leave" -> {
                    step("delete") { session.storeFlags(listOf(uids[3]), setOf("\\Deleted"), emptySet()); "" }
                    step("switch") { session.select("INBOX").toString() }
                    step("back") { session.select(box).toString() }
                }
                "outside" -> {
                    // The fake expunges UID 5 on the first NOOP and adds one message on the second.
                    var changes = mutableListOf<String>()
                    step("noopExpunge") { session.noop(); session.selectedInfo().exists.toString() }
                    step("afterExpunge") { session.searchCriterion("NotDeleted", "").joinToString(",") }
                    step("flagVanished") {
                        try { session.storeFlags(listOf(5L), setOf("\\Flagged"), emptySet()); "no error" }
                        catch (e: Exception) { "error: ${e.message}" }
                    }
                    step("noopAppend") { session.noop(); session.selectedInfo().exists.toString() }
                    step("afterAppend") { session.searchCriterion("NotDeleted", "").joinToString(",") }
                    step("deleteAfter") { delete(uids[1]); "" }
                }
                else -> error("unknown scenario $scenario")
            }
            step("final") { session.searchCriterion("NotDeleted", "").joinToString(",") }
            step("unselect") { session.unselect(); "" }
            step("leaveFolder") { session.expungeOnLeave(box); "" }
        } finally {
            session.close()
            TrafficRecorder.stop()
            LabTestHooks.clear()
            File(dir, "$run.steps.tsv").writeText(steps.toString())
        }
        assertEquals("failed steps:\n$steps", 0, failed)
    }
}
