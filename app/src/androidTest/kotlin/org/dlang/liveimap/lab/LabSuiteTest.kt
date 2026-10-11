package org.dlang.liveimap.lab

import org.dlang.liveimap.ui.compose.attachmentParts
import org.dlang.liveimap.ui.reader.typeMismatch
import org.dlang.liveimap.ui.reader.SniffBytes
import org.dlang.liveimap.session.TooDeepSubtype
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
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
        // labSteps (lab.py --steps): run only these, plus open/select which the others need.
        val onlySteps = arg("labSteps").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        suspend fun step(name: String, body: suspend () -> String) {
            if (onlySteps.isNotEmpty() && name !in onlySteps && name != "open" && name != "select") return
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
            // Lab client certificate (lab.py LAB_CLIENT_CERT=1): PKCS#12 passed base64, imported like the UI does.
            // labClientCertKind=keystore: the same p12 goes into this app's AndroidKeyStore (non-exportable),
            // so OpenSSL signs through the JNI KeyChain path instead of holding the key.
            clientCertAlias = arg("labClientCertB64").takeIf { it.isNotEmpty() && arg("labClientCertKind") == "keystore" }?.let {
                org.dlang.liveimap.engine.KeyChainCerts.appContext = ctx.applicationContext
                org.dlang.liveimap.engine.KeyChainCerts.importLab(
                    "lab-client", android.util.Base64.decode(it, android.util.Base64.DEFAULT), arg("labClientCertPass"),
                )
            } ?: "",
            clientCert = arg("labClientCertB64").takeIf { it.isNotEmpty() && arg("labClientCertKind") != "keystore" }?.let {
                org.dlang.liveimap.engine.ClientCertStore.import(
                    java.io.File(ctx.filesDir, "client-certs"),
                    android.util.Base64.decode(it, android.util.Base64.DEFAULT),
                    arg("labClientCertPass"),
                )
            } ?: "",
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
                if ("PARTIAL" in session.capabilities && "ESORT" in session.capabilities) step("sortWindow") {
                    val full = session.sort(SortKey.Date, true)
                    val w = session.sortWindow(SortKey.Date, true, 1, 5)
                        ?: return@step "fallback: server sent COUNT without the PARTIAL window (Cyrus)"
                    check(w.total == full.size) { "COUNT ${w.total} != full ${full.size}" }
                    check(w.uids == full.take(5)) { "window ${w.uids} != head ${full.take(5)}" }
                    "${w.total} ${w.uids.size}"
                }
                if ("PARTIAL" in session.capabilities && "ESEARCH" in session.capabilities) step("searchWindow") {
                    val full = session.searchCriterion("NotDeleted", "")
                    val w = session.searchWindow("NotDeleted", "", -3, -1) ?: error("no window despite PARTIAL")
                    check(w.total == full.size) { "COUNT ${w.total} != full ${full.size}" }
                    check(w.uids.sorted() == full.sorted().takeLast(3)) { "window ${w.uids} != tail ${full.sorted().takeLast(3)}" }
                    "${w.total} ${w.uids.size}"
                }
                step("clientSortSize") {
                    // Chunked client sort (sequence-range FETCH) against the server's SORT, by size.
                    var reports = 0
                    val client = session.clientOrder(SortKey.Size, false, { _, _ -> reports++ })
                    if ("SORT" in session.capabilities) {
                        val server = session.sort(SortKey.Size, false)
                        check(client.size == server.size) { "client ${client.size} != server ${server.size}" }
                    }
                    check(reports >= 1) { "no progress" }
                    "${client.size} in $reports chunk(s)"
                }
                step("longSearch") {
                    // Body search on a side connection: same hits as the main session, which stays usable.
                    val main = session.searchCriterion("Body", "message").sorted()
                    val link = org.dlang.liveimap.engine.LibetpanLongSearchLink(account, box, { false }, { true })
                    val r = org.dlang.liveimap.session.LongSearch(link, 50).run("Body", "message") {}
                    check(r == org.dlang.liveimap.session.LongSearchResult.Hits(main) ||
                        (r is org.dlang.liveimap.session.LongSearchResult.Hits && r.uids.sorted() == main)) { "side $r != main $main" }
                    "${main.size}"
                }
                step("longSearchParallel") {
                    // Side search and main-session work at the same time: neither waits for the other.
                    val link = org.dlang.liveimap.engine.LibetpanLongSearchLink(account, box, { false }, { true })
                    val side = async(kotlinx.coroutines.Dispatchers.IO) {
                        org.dlang.liveimap.session.LongSearch(link, 50).run("Body", "message") {}
                    }
                    var mainOps = 0
                    while (!side.isCompleted && mainOps < 50) {
                        session.searchCriterion("Subject", "LiveIMAP"); mainOps++
                    }
                    val r = side.await()
                    check(r is org.dlang.liveimap.session.LongSearchResult.Hits) { "side $r" }
                    "side ${r.uids.size} hits; main ops meanwhile $mainOps"
                }
                step("longSearchCancel") {
                    val link = org.dlang.liveimap.engine.LibetpanLongSearchLink(account, box, { false }, { true })
                    val search = org.dlang.liveimap.session.LongSearch(link, 50)
                    val r = kotlinx.coroutines.coroutineScope {
                        val job = async { search.run("Text", "LiveIMAP") {} }
                        search.cancel()
                        job.await()
                    }
                    check(r == org.dlang.liveimap.session.LongSearchResult.Cancelled) { "expected Cancelled, got $r" }
                    val after = session.searchCriterion("Subject", "LiveIMAP").size
                    "cancelled; main session ok ($after)"
                }
                step("batchedMove") {
                    // "Move entire folder" with 2-UID batches, Sub -> Trash, both settings, copy-then-mark, no EXPUNGE.
                    // Pass 1 skips \Deleted (default): one Sub message is pre-marked and must stay. Pass 2 includes
                    // \Deleted: everything in Sub (now all marked) is moved again.
                    val sep = listOf("/", ".").firstOrNull { runCatching { session.mailboxListed(box + it + "Sub") }.getOrDefault(false) }
                        ?: error("no Sub folder")
                    val sub = box + sep + "Sub"
                    val trash = box + sep + "Trash"
                    org.dlang.liveimap.engine.LabTestHooks.labMoveChunk = 2
                    try {
                        session.select(sub)
                        val before = session.selectedExists()
                        // Start clean: an earlier run may have left Sub all \Deleted (nothing is ever expunged).
                        session.storeFlags(session.searchCriterion("All", ""), emptySet(), setOf("\\Deleted"))
                        val first = session.searchCriterion("NotDeleted", "").min()
                        session.storeFlags(listOf(first), setOf("\\Deleted"), emptySet())
                        val report = StringBuilder()
                        for (includeDeleted in listOf(false, true)) {
                            val kind = org.dlang.liveimap.ui.index.moveAllKind(account.copy(moveAllIncludesDeleted = includeDeleted))
                            session.select(trash)
                            val trashBefore = session.selectedExists()
                            session.select(sub)
                            val all = org.dlang.liveimap.ui.index.allUidsInWindows(session, 3, kind)
                            val expected = if (includeDeleted) before else before - 1
                            check(all.size == expected) { "$kind found ${all.size}, expected $expected" }
                            check(includeDeleted || first !in all) { "UNDELETED returned the deleted message" }
                            var batches = 0
                            val out = org.dlang.liveimap.ui.index.moveInChunks(
                                all, org.dlang.liveimap.engine.LabTestHooks.moveChunk()!!, { false }, { _, _ -> batches++ },
                            ) { part -> session.copyThenDelete(part, trash); session.takeCopiedUids() }
                            check(out.failure == null) { "batch failed: ${out.failure?.text}" }
                            check(out.moved == all) { "moved ${out.moved.size} of ${all.size}" }
                            session.select(sub)
                            check(session.selectedExists() == before) { "source shrank: something expunged" }
                            check(session.searchCriterion("NotDeleted", "").isEmpty()) { "source not all \\Deleted" }
                            session.select(trash)
                            check(session.selectedExists() == trashBefore + all.size) { "Trash got ${session.selectedExists() - trashBefore}" }
                            report.append("$kind ${all.size} in $batches; ")
                        }
                        session.select(box)
                        report.append("no expunge").toString()
                    } finally {
                        org.dlang.liveimap.engine.LabTestHooks.labMoveChunk = null
                    }
                }
                if ("PARTIAL" in session.capabilities && "ESEARCH" in session.capabilities) step("windowedOrder") {
                    // The index model's window loader, with 7-message windows, newest first, against the full search.
                    val full = session.searchCriterion("NotDeleted", "").sortedDescending()
                    val total = full.size
                    val order = org.dlang.liveimap.ui.index.WindowedOrder(total, 7) { lo, hi ->
                        val (a, b) = org.dlang.liveimap.ui.index.searchWindowRange(lo, hi, total, true)
                        session.searchWindow("NotDeleted", "", a, b)!!.uids.sorted().reversed()
                    }
                    order.ensure(0, total)
                    check(order.toList() == full) { "windowed order differs from full search" }
                    "$total ${order.loadedWindows()}"
                }
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
                            // Only the plaintext strict fake (fake_run.py, labSievePlain) may see plaintext auth.
                            allowPlaintextAuth = arg("labSievePlain") == "true",
                        )
                    }
                }
                if ("IDLE" in session.capabilities) step("idle") {
                    // Watch connection (EXAMINE + IDLE) on INBOX; the main connection appends one message there,
                    // and the watch must report it. The message is removed again with UID EXPUNGE of exactly its
                    // UID where UIDPLUS allows (lab account only), else it stays marked \Deleted.
                    val seen = kotlinx.coroutines.CompletableDeferred<String>()
                    session.watch("INBOX") { change -> if (change is org.dlang.liveimap.session.MailboxChange.Exists) seen.complete("exists=${change.exists}") }
                    kotlinx.coroutines.delay(1500)
                    val msg = ("From: lab@liveimap.lab\r\nTo: lab@liveimap.lab\r\nSubject: LiveIMAP IDLE $run\r\n" +
                        "Message-ID: <idle-$run@liveimap.lab>\r\n\r\nIDLE lab test.\r\n").toByteArray()
                    val uid = session.appendReturningUid("INBOX", msg, emptySet())
                    val got = kotlinx.coroutines.withTimeoutOrNull(75_000) { seen.await() }  // Cyrus without idled polls every 60 s (imapidlepoll)
                    session.stopWatch()
                    if (uid > 0) {
                        session.select("INBOX")
                        session.storeFlags(listOf(uid), setOf("\\Deleted"), emptySet())
                        if (session.featureCaps.uidPlus) session.uidExpunge(listOf(uid))
                        session.select(box)
                    }
                    got ?: throw IllegalStateException("no EXISTS from the watch connection within 75 s")
                }
                // LAB_DEEP=1 seeds <box>/Deep with 100- and 5000-level MIME messages. Walk each one the way the
                // reader does: follow the first child; a "nested part" entry is opened with fetchStructureAt (the
                // reader's tap), until a real leaf or libetpan's x-libetpan-too-deep placeholder.
                val deepBox = listOf("/", ".").map { "$box${it}Deep" }
                    .firstOrNull { runCatching { session.mailboxListed(it) }.getOrDefault(false) }
                if (deepBox != null) step("deepNesting") {
                    session.select(deepBox)
                    val deepUids = session.fetchIndex(IndexRequest(deepBox, IndexMode.ArrivalNewest, limit = 10)).map { it.uid }
                    val out = ArrayList<String>()
                    for (uid in deepUids.sorted()) {
                        var tree = session.fetchStructure(uid)
                        var taps = 0
                        var levels = 0
                        while (true) {
                            var p = tree
                            var d = 0
                            while (p.children.isNotEmpty()) { p = p.children.first(); d++ }
                            levels += d
                            if (p.subtype.equals(TooDeepSubtype, ignoreCase = true) && taps < 200) {
                                tree = session.fetchStructureAt(uid, p.section)
                                taps++
                                continue
                            }
                            out.add("uid $uid: levels=$levels taps=$taps leaf=${p.type}/${p.subtype} section-depth=${p.section.count { it == '.' } + 1}")
                            break
                        }
                    }
                    session.select(box)
                    if (out.isEmpty()) throw IllegalStateException("no messages in $deepBox")
                    out.joinToString("; ")
                }
                // LAB_SPOOF=1 seeds <box>/Spoof: one honest PDF and attachments whose type and filename lie about
                // the content. Sniff each attachment exactly as the reader does before open/save/share
                // (BODY.PEEK partial of the first 4 KB) and check that only the spoofed ones are flagged.
                val spoofBox = listOf("/", ".").map { "$box${it}Spoof" }
                    .firstOrNull { runCatching { session.mailboxListed(it) }.getOrDefault(false) }
                if (spoofBox != null) step("typeSniff") {
                    session.select(spoofBox)
                    val spoofUids = session.fetchIndex(IndexRequest(spoofBox, IndexMode.ArrivalNewest, limit = 10)).map { it.uid }
                    val out = ArrayList<String>()
                    val wrong = ArrayList<String>()
                    for (uid in spoofUids.sorted()) {
                        for (part in attachmentParts(session.fetchStructure(uid))) {
                            val head = session.peekPart(uid, part.section, 0, SniffBytes)
                            val mismatch = typeMismatch("${part.type}/${part.subtype}", part.filename, head)
                            val spoofed = part.filename != "report.pdf"
                            if ((mismatch != null) != spoofed) wrong.add(part.filename ?: part.section)
                            out.add("${part.filename}: " + (mismatch?.let { "warn ${it.claimed} vs ${it.detected.mime}" } ?: "ok"))
                        }
                    }
                    session.select(box)
                    if (out.isEmpty()) throw IllegalStateException("no attachments in $spoofBox")
                    if (wrong.isNotEmpty()) throw IllegalStateException("wrong verdict for $wrong: $out")
                    out.joinToString("; ")
                }
                // Filter chain: one server search with every criterion (UID set, NotDeleted, a nested Or group).
                step("filterChain") {
                    val all = session.fetchIndex(IndexRequest(box, IndexMode.ArrivalNewest, limit = 5)).map { it.uid }.sorted()
                    if (all.size < 3) throw IllegalStateException("need 3 messages, have ${all.size}")
                    val pick = all.take(3)
                    val chain = listOf(
                        org.dlang.liveimap.ui.index.AppliedFilter("t", org.dlang.liveimap.ui.index.uidSetText(pick), "Uid"),
                        org.dlang.liveimap.ui.index.AppliedFilter("n", "", "NotDeleted"),
                        org.dlang.liveimap.ui.index.AppliedFilter(
                            "g",
                            org.dlang.liveimap.ui.index.encodeAdvancedQuery(
                                org.dlang.liveimap.ui.index.AdvancedCombiner.Or,
                                listOf(
                                    org.dlang.liveimap.ui.index.AdvancedStep(false, "Uid", pick[0].toString()),
                                    org.dlang.liveimap.ui.index.AdvancedStep(false, "Uid", pick[2].toString()),
                                ),
                            )!!,
                            "Advanced",
                        ),
                    )
                    val (kind, arg) = org.dlang.liveimap.ui.index.combinedFilterSearch(chain)!!
                    val got = session.searchCriterion(kind, arg).sorted()
                    val want = listOf(pick[0], pick[2])
                    if (got != want) throw IllegalStateException("got $got want $want")
                    // The same criteria sent with SORT, ESORT PARTIAL and THREAD must select the same messages.
                    val group = org.dlang.liveimap.ui.index.filterChainGroup(chain)!!
                    val key = org.dlang.liveimap.settings.SortKey.Subject
                    val notes = ArrayList<String>()
                    if (session.featureCaps.sort) {
                        val sorted = session.sortMatching(key, false, group)?.sorted()
                        if (sorted != want) throw IllegalStateException("SORT got $sorted want $want")
                        notes.add("sort")
                        val window = session.sortWindowMatching(key, false, 1, 10, group)
                        if (window != null) {
                            if (window.uids.sorted() != want) throw IllegalStateException("ESORT window ${window.uids}")
                            notes.add("esort-partial")
                        }
                    }
                    if (session.featureCaps.threadReferences) {
                        val tree = session.threadMatching(org.dlang.liveimap.settings.SortKey.ThreadReferences, group)
                        val inTree = ArrayList<Long>()
                        tree?.forEachPreorder { n -> n.uid?.let { inTree.add(it) } }
                        if (inTree.sorted() != want) throw IllegalStateException("THREAD got $inTree want $want")
                        notes.add("thread")
                    }
                    "uids $got " + notes.joinToString(",")
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
