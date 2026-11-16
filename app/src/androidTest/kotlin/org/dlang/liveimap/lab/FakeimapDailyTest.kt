package org.dlang.liveimap.lab

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.oauthTokenSlot
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.engine.LibetpanLongSearchLink
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.sieve.SieveFailure
import org.dlang.liveimap.engine.sieve.authenticatePlain
import org.dlang.liveimap.engine.sieve.listScripts
import org.dlang.liveimap.engine.sieve.openPlainSieve
import org.dlang.liveimap.engine.sieve.putScript
import org.dlang.liveimap.engine.sieve.readGreeting
import org.dlang.liveimap.jmap.platformJmapMailSession
import org.dlang.liveimap.oauth.OauthTokenState
import org.dlang.liveimap.oauth.encodeTokenState
import org.dlang.liveimap.session.LongSearch
import org.dlang.liveimap.session.LongSearchResult
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.TlsMode
import org.dlang.liveimap.ui.compose.DsnMode
import org.dlang.liveimap.ui.compose.dsnMask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented cases for the pinned fakeimap daily backend. The host runner
 * (tools/test-servers/fakeimap_daily.py) starts the process and passes -e labHost.
 * Without labHost the test is skipped. The device does not open its own IMAP,
 * SMTP, or JMAP socket: control and the command log come from the runner's bridge.
 */
@RunWith(AndroidJUnit4::class)
class FakeimapDailyTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String = "") = args.getString(k) ?: d

    @Test
    fun generatedMailboxStatusAndOneBody() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == "generated")
        val count = arg("labCount").toInt()
        val maxSeed = arg("labMaxSeedCount").toInt()
        val box = arg("labFolder", "Generated")
        assertTrue("count $count is not above seed/folders.tsv max $maxSeed", count > maxSeed)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser"))
        val labId = createLabAccount(ctx, account, arg("labPass"))
        val session = LibetpanMailSession(ctx, labId)
        try {
            val opened = session.open(account)
            assertTrue("open: $opened", opened is OpenResult.Connected)
            session.select(box)
            val status = session.statusMessages(listOf(box))
            assertEquals(count, status[box] ?: -1)
            val body = session.fetchRfc822(1)
            assertTrue("opened message has no body", body.isNotEmpty())
            val bodies = commandTexts().count { isBodyFetch(it) }
            assertTrue("body fetches $bodies for $count messages", bodies in 1 until count)
        } finally {
            runCatching { session.close() }
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun faultsLeaveTheMailbox() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == "faults")
        val box = arg("labFolder", "LiveIMAP-Test")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser"))
        val labId = createLabAccount(ctx, account, arg("labPass"))
        try {
            val before = uids(box)
            assertTrue(before.isNotEmpty())
            // select() sends EXAMINE. A dropped connection is retried once, so close stays armed.
            control(mapOf("op" to "arm-fault", "do" to "close", "command" to "EXAMINE", "times" to 0))
            assertTrue(attempt(ctx, labId, account, box).startsWith("fail"))
            assertEquals(before, uids(box))
            control(mapOf("op" to "clear-faults"))
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))

            val rst = openSelect(ctx, labId, account, box)
            // The fetch retry is a second FETCH. times 0 keeps the reset armed until clear-faults.
            control(mapOf("op" to "arm-fault", "do" to "rst", "command" to "FETCH", "times" to 0))
            val fetched = try {
                rst.session.fetchRfc822(before.first().toLong())
                "ok"
            } catch (e: Throwable) {
                "fail: ${e.message}"
            }
            runCatching { rst.session.close() }
            assertTrue(fetched, fetched.startsWith("fail"))
            assertEquals(before, uids(box))
            control(mapOf("op" to "clear-faults"))
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))

            control(mapOf(
                "op" to "arm-fault", "do" to "reply", "command" to "EXAMINE",
                "status" to "NO", "text" to "no", "times" to 1,
            ))
            assertTrue(attempt(ctx, labId, account, box).startsWith("fail"))
            assertEquals(before, uids(box))
            control(mapOf("op" to "clear-faults"))
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))

            control(mapOf(
                "op" to "set-referral", "kind" to "mailbox", "mailbox" to box,
                "url" to "imap://referral.invalid/box",
            ))
            val referred = attempt(ctx, labId, account, box)
            assertTrue(referred, referred.startsWith("fail"))
            assertEquals(before, uids(box))
            control(mapOf("op" to "clear-referral"))
            val again = attempt(ctx, labId, account, box)
            assertTrue(again, again.startsWith("ok"))
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun bearerFromIssueOpensThroughTheApp() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == "bearer")
        val issued = control(mapOf("op" to "issue", "username" to arg("labUser")))
        val access = issued.getString("access_token")
        val refresh = issued.getString("refresh_token")
        assertTrue(access.isNotEmpty() && refresh.isNotEmpty())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser")).copy(
            accountAuth = "oauth2",
            oauthMechanism = "OAUTHBEARER",
            smtpHost = host,
            smtpPort = arg("labSmtpPort").toInt(),
            smtpSameAsImap = true,
            email = arg("labUser") + "@lab.test",
        )
        val labId = createLabAccount(ctx, account, arg("labPass"))
        val store = AccountStore.shared(ctx)
        val hour = 60L * 60L * 1000L
        store.setOauthPlain(
            labId,
            oauthTokenSlot,
            encodeTokenState(
                OauthTokenState(
                    access = access,
                    refresh = refresh,
                    expiresAtMs = System.currentTimeMillis() + hour,
                ),
            ),
        )
        val session = LibetpanMailSession(ctx, labId)
        session.setPlaintextConfirmer { true }
        val jmap = platformJmapMailSession(ctx, labId)
        try {
            val opened = session.open(account)
            assertTrue("imap: $opened", opened is OpenResult.Connected)
            session.select("INBOX")
            val msg = ("From: ${account.email}\r\nTo: ${account.email}\r\nSubject: bearer\r\n\r\nbearer\r\n")
                .toByteArray(StandardCharsets.UTF_8)
            val sent = session.smtpSend(msg, listOf(account.email))
            assertEquals("smtp $sent", 0, sent)
            val jmapAccount = account.copy(
                imapHost = "http://$host:${arg("labJmapPort")}",
                jmapFolders = true,
            )
            val jmapOpen = jmap.open(jmapAccount)
            assertTrue("jmap: $jmapOpen", jmapOpen is OpenResult.Connected)
            assertTrue(jmap.listLevel("", null, false).isNotEmpty())
        } finally {
            runCatching { session.close() }
            runCatching { jmap.close() }
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapSilentFetch() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-silent") ?: return@runBlocking
        val slow = account.copy(readTimeoutSec = 2)
        try {
            fetchFaultLeavesMail("silent", slow, ctx, labId, box, delayMs = 0)
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapDelayFetch() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-delay") ?: return@runBlocking
        val timeoutSec = 2
        val delayMs = 8_000
        assertTrue(delayMs > timeoutSec * 1000)
        val slow = account.copy(readTimeoutSec = timeoutSec)
        try {
            fetchFaultLeavesMail("delay", slow, ctx, labId, box, delayMs)
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapHalfCloseFetch() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-half-close") ?: return@runBlocking
        try {
            fetchFaultLeavesMail("half_close", account, ctx, labId, box, delayMs = 0)
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapFetchNo() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-fetch-no") ?: return@runBlocking
        try {
            val before = uids(box)
            val held = openSelect(ctx, labId, account, box)
            arm(mapOf("do" to "reply", "command" to "FETCH", "status" to "NO", "text" to "no", "times" to 0))
            val failed = callFails { held.session.fetchRfc822(before.first().toLong()) }
            runCatching { held.session.close() }
            assertTrue(failed, failed.startsWith("fail"))
            assertEquals(before, uids(box))
            clearFaults()
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapStoreNo() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-store-no") ?: return@runBlocking
        try {
            val before = uids(box)
            val held = openSelect(ctx, labId, account, box)
            arm(mapOf("do" to "reply", "command" to "STORE", "status" to "NO", "text" to "no", "times" to 0))
            val failed = callFails {
                held.session.storeFlags(listOf(before.first().toLong()), setOf("\\Flagged"), emptySet())
            }
            runCatching { held.session.close() }
            assertTrue(failed, failed.startsWith("fail"))
            assertEquals(before, uids(box))
            clearFaults()
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun imapAppendNo() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-append-no") ?: return@runBlocking
        try {
            val before = uids(box)
            val held = openSelect(ctx, labId, account, box)
            val mark = commandTexts().size
            arm(mapOf("do" to "reply", "command" to "APPEND", "status" to "NO", "text" to "no", "times" to 0))
            val msg = "From: a@lab.test\r\nTo: a@lab.test\r\nSubject: append\r\n\r\nappend\r\n"
                .toByteArray(StandardCharsets.UTF_8)
            val failed = callFails { held.session.append(box, msg, emptySet()) }
            runCatching { held.session.close() }
            assertTrue(failed, failed.startsWith("fail"))
            assertWriteSelectBeforeAppend(box, mark)
            assertEquals(before, uids(box))
            clearFaults()
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun smtpDropAfter354() = runBlocking {
        smtpFault("smtp-drop", "drop_after_354", "DATA", uncertain = true)
    }

    @Test
    fun smtpBounce() = runBlocking {
        // Pin bounce answers DATA with 250. dsnNotify 0 is the success return.
        // FAILURE against a listener that does not offer DSN returns -1.
        smtpFault("smtp-bounce", "bounce", "DATA", uncertain = false, dsnNotify = dsnMask(DsnMode.Failure))
    }

    @Test
    fun smtpOverquota() = runBlocking {
        smtpFault("smtp-overquota", "overquota", "MAIL", uncertain = false)
    }

    @Test
    fun jmapServerFail() = runBlocking {
        jmapCallFails("jmap-server-fail", "server_fail")
    }

    @Test
    fun jmapUnauthorized() = runBlocking {
        jmapCallFails("jmap-unauthorized", "unauthorized")
    }

    @Test
    fun jmapDropEventSource() = runBlocking {
        val (ctx, labId, account, _) = prepare("jmap-drop-eventsource") ?: return@runBlocking
        val jmapAccount = jmapAccount(account)
        val jmap = platformJmapMailSession(ctx, labId)
        try {
            val opened = jmap.open(jmapAccount)
            assertTrue("jmap: $opened", opened is OpenResult.Connected)
            val listed = jmap.listLevel("", null, false)
            assertTrue(listed.isNotEmpty())
            val lost = CompletableDeferred<Unit>()
            jmap.watch(listed.first().mailbox) { change ->
                if (change == MailboxChange.WatchLost) lost.complete(Unit)
            }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!lost.isCompleted && System.nanoTime() < deadline) {
                arm(mapOf("do" to "drop_eventsource", "proto" to "jmap", "times" to 0))
                delay(1_000)
            }
            assertTrue(lost.isCompleted)
            clearFaults()
            runCatching { jmap.close() }
            val again = platformJmapMailSession(ctx, labId)
            try {
                val next = again.open(jmapAccount)
                assertTrue("jmap after clear: $next", next is OpenResult.Connected)
                assertTrue(again.listLevel("", null, false).isNotEmpty())
            } finally {
                runCatching { again.close() }
            }
        } finally {
            runCatching { jmap.close() }
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun idleCloseEndsWatch() = runBlocking {
        val (ctx, labId, account, box) = prepare("idle-close") ?: return@runBlocking
        try {
            arm(mapOf("do" to "close", "command" to "IDLE", "times" to 0))
            val held = openSelect(ctx, labId, account, box)
            assertTrue("IDLE was not advertised", held.session.featureCaps.idle)
            val mark = commandTexts().size
            held.session.watch(box) { }
            val idle = commandTexts().drop(mark).any { it.uppercase().contains(" IDLE") }
            runCatching { held.session.close() }
            assertTrue("watch ended without IDLE", idle)
            clearFaults()
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun bearerAuthenticateNo() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == "bearer-auth-no")
        val issued = control(mapOf("op" to "issue", "username" to arg("labUser")))
        assertTrue(issued.toString(), issued.optBoolean("ok", true) || issued.has("access_token"))
        val access = issued.getString("access_token")
        val refresh = issued.getString("refresh_token")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser")).copy(
            accountAuth = "oauth2",
            oauthMechanism = "OAUTHBEARER",
            email = arg("labUser") + "@lab.test",
        )
        val labId = createLabAccount(ctx, account, arg("labPass"))
        val store = AccountStore.shared(ctx)
        val hour = 60L * 60L * 1000L
        store.setOauthPlain(
            labId,
            oauthTokenSlot,
            encodeTokenState(
                OauthTokenState(
                    access = access,
                    refresh = refresh,
                    expiresAtMs = System.currentTimeMillis() + hour,
                ),
            ),
        )
        try {
            arm(mapOf("do" to "reply", "command" to "AUTHENTICATE", "status" to "NO", "text" to "no", "times" to 0))
            val denied = openResult(ctx, labId, account)
            assertTrue("open $denied", denied is OpenResult.Failed)
            clearFaults()
            val opened = openResult(ctx, labId, account)
            assertTrue("open after clear $opened", opened is OpenResult.Connected)
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun tlsCertFaultsFailBeforeLogin() = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == "tls-certs")
        val der = Base64.decode(arg("labCa"), Base64.DEFAULT)
        assertTrue(der.isNotEmpty())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser")).copy(tlsMode = TlsMode.Implicit)
        LabTestHooks.setLabCa(der)
        val labId = createLabAccount(ctx, account, arg("labPass"))
        try {
            for (fault in listOf("wrong_name", "expired", "self_signed")) {
                clearFaults()
                arm(mapOf("do" to fault, "times" to 0))
                val mark = commandTexts().size
                val opened = openResult(ctx, labId, account)
                assertTrue("$fault $opened", opened is OpenResult.Failed)
                val added = commandTexts().drop(mark)
                assertFalse(
                    added.joinToString("\n"),
                    added.any { hasImapCommand(it, "LOGIN") || hasImapCommand(it, "AUTHENTICATE") },
                )
            }
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun longSearchSilent() = runBlocking {
        longSearchFault("search-silent", "silent")
    }

    @Test
    fun longSearchClose() = runBlocking {
        longSearchFault("search-close", "close")
    }

    @Test
    fun clientSortExpungeMid() = runBlocking {
        val (ctx, labId, account, box) = prepare("sort-expunge") ?: return@runBlocking
        try {
            val before = uids(box)
            val victim = before.first()
            val held = openSelect(ctx, labId, account, box)
            held.session.storeFlags(listOf(victim.toLong()), setOf("\\Flagged"), emptySet())
            arm(mapOf(
                "do" to "expunge_mid", "command" to "FETCH", "uid" to victim, "after" to 1, "times" to 1,
            ))
            var reported = ""
            try {
                held.session.clientOrder(SortKey.Size, false, { _, _ -> }, { false })
            } catch (e: Throwable) {
                reported = e.message ?: e.javaClass.simpleName
            }
            runCatching { held.session.close() }
            assertFalse(reported, reported == "sort failed")
            val listed = uids(box)
            assertFalse(listed.contains(victim))
            assertEquals(listed.sorted(), openUids(ctx, labId, account, box).sorted())
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun expungeMidMatchesList() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-expunge-mid") ?: return@runBlocking
        try {
            val before = uids(box)
            val victim = before.first()
            val held = openSelect(ctx, labId, account, box)
            held.session.storeFlags(listOf(victim.toLong()), setOf("\\Flagged"), emptySet())
            arm(mapOf("do" to "expunge_mid", "command" to "NOOP", "uid" to victim, "times" to 1))
            held.session.noop()
            runCatching { held.session.close() }
            val listed = uids(box)
            assertFalse(listed.contains(victim))
            assertEquals(listed.sorted(), openUids(ctx, labId, account, box).sorted())
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun armInjectMatchesList() = runBlocking {
        val (ctx, labId, account, box) = prepare("imap-inject") ?: return@runBlocking
        try {
            val before = uids(box)
            val raw = "From: a@lab.test\r\nTo: a@lab.test\r\nSubject: inject\r\n\r\ninject\r\n"
            val armed = control(mapOf(
                "op" to "arm-inject",
                "command" to "EXAMINE",
                "mailbox" to box,
                "raw" to raw,
                "user" to arg("labUser"),
                "times" to 1,
            ))
            assertTrue(armed.toString(), armed.optBoolean("ok"))
            val held = openSelect(ctx, labId, account, box)
            runCatching { held.session.close() }
            assertTrue(held.text, held.text.startsWith("ok"))
            val listed = uids(box)
            assertTrue("inject did not change $before -> $listed", listed.size > before.size)
            assertEquals(listed.sorted(), openUids(ctx, labId, account, box).sorted())
        } finally {
            deleteLabAccount(ctx, labId)
        }
    }

    @Test
    fun sievePutNoAndClose() = runBlocking {
        val (ctx, labId, _, _) = prepare("sieve-put") ?: return@runBlocking
        val host = arg("labHost")
        val port = arg("labSievePort").toInt()
        try {
            val refused = authedSieve(host, port)
            try {
                arm(mapOf("do" to "reply", "proto" to "sieve", "command" to "PUTSCRIPT", "times" to 1))
                var text = ""
                val failed = try {
                    putScript(refused, "faulted", "keep;\n")
                    false
                } catch (e: SieveFailure) {
                    text = e.text
                    true
                }
                assertTrue(text, failed)
                assertEquals("fault", text)
            } finally {
                runCatching { refused.close() }
            }
            clearFaults()
            val afterNo = authedSieve(host, port)
            try {
                assertFalse(listScripts(afterNo).any { it.name == "faulted" })
            } finally {
                runCatching { afterNo.close() }
            }
            val huge = authedSieve(host, port)
            try {
                val script = "keep;\n" + " ".repeat(1 shl 20)
                var closed = false
                try {
                    putScript(huge, "closed", script)
                } catch (_: Throwable) {
                    closed = true
                }
                assertTrue(closed)
            } finally {
                runCatching { huge.close() }
            }
            val afterClose = authedSieve(host, port)
            try {
                assertFalse(listScripts(afterClose).any { it.name == "closed" })
            } finally {
                runCatching { afterClose.close() }
            }
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    private data class Ready(
        val ctx: android.content.Context,
        val labId: String,
        val account: Settings,
        val box: String,
    )

    private suspend fun prepare(case: String): Ready? {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty() && arg("labCase") == case)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val account = labAccount(host, arg("labPort").toInt(), arg("labUser"))
        val labId = createLabAccount(ctx, account, arg("labPass"))
        return Ready(ctx, labId, account, arg("labFolder", "LiveIMAP-Test"))
    }

    private suspend fun fetchFaultLeavesMail(
        fault: String,
        account: Settings,
        ctx: android.content.Context,
        labId: String,
        box: String,
        delayMs: Int,
    ) {
        val before = uids(box)
        assertTrue(before.isNotEmpty())
        val held = openSelect(ctx, labId, account, box)
        val fields = mutableMapOf<String, Any?>("do" to fault, "command" to "FETCH", "times" to 0)
        if (fault == "delay") fields["delay_ms"] = delayMs
        val started = System.nanoTime()
        arm(fields)
        val failed = callFails { held.session.fetchRfc822(before.first().toLong()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        runCatching { held.session.close() }
        assertTrue(failed, failed.startsWith("fail"))
        if (fault == "delay") assertTrue("elapsed ${elapsedMs}ms delay $delayMs", elapsedMs < delayMs)
        assertEquals(before, uids(box))
        clearFaults()
        assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))
    }

    private suspend fun smtpFault(case: String, fault: String, command: String, uncertain: Boolean, dsnNotify: Int = 0) {
        val (ctx, labId, account, _) = prepare(case) ?: return
        val smtp = account.copy(
            smtpHost = arg("labHost"),
            smtpPort = arg("labSmtpPort").toInt(),
            smtpTlsMode = TlsMode.None,
            smtpSameAsImap = true,
            email = arg("labUser") + "@lab.test",
        )
        val session = LibetpanMailSession(ctx, labId)
        session.setPlaintextConfirmer { true }
        try {
            val opened = session.open(smtp)
            assertTrue("open: $opened", opened is OpenResult.Connected)
            val beforeInbox = if (fault == "bounce") uids("INBOX") else emptyList()
            arm(mapOf("do" to fault, "proto" to "smtp", "command" to command, "times" to 1))
            val msg = "From: ${smtp.email}\r\nTo: ${smtp.email}\r\nSubject: $fault\r\n\r\n$fault\r\n"
                .toByteArray(StandardCharsets.UTF_8)
            var code: Int? = null
            var text = ""
            try {
                code = session.smtpSend(msg, listOf(smtp.email), dsnNotify)
            } catch (e: MailFailure) {
                text = e.text
            }
            if (fault == "bounce" && text.isEmpty()) {
                val added = uids("INBOX").filter { it !in beforeInbox }
                assertTrue("bounce stored no failure report", added.isNotEmpty())
                session.select("INBOX")
                val report = session.fetchRfc822(added.last().toLong()).toString(StandardCharsets.UTF_8)
                text = report.lineSequence().firstOrNull { it.startsWith("Action:") } ?: report
            }
            assertTrue("smtp returned $code text=$text", code == null || code != 0)
            if (uncertain) assertTrue(text, text.startsWith("smtp-uncertain:"))
            else assertFalse(text, text.startsWith("smtp-uncertain:"))
            if (dsnNotify != 0) assertTrue("smtp returned $code text=$text", code != null && code != 0)
        } finally {
            runCatching { session.close() }
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    private suspend fun jmapCallFails(case: String, fault: String) {
        val (ctx, labId, account, _) = prepare(case) ?: return
        val jmapAccount = jmapAccount(account)
        val jmap = platformJmapMailSession(ctx, labId)
        try {
            arm(mapOf("do" to fault, "proto" to "jmap", "times" to 0))
            val failed = try {
                val opened = jmap.open(jmapAccount)
                if (opened !is OpenResult.Connected) true else {
                    jmap.listLevel("", null, false)
                    false
                }
            } catch (_: Throwable) {
                true
            }
            assertTrue(failed)
            clearFaults()
            runCatching { jmap.close() }
            val again = platformJmapMailSession(ctx, labId)
            try {
                val opened = again.open(jmapAccount)
                assertTrue("jmap after clear: $opened", opened is OpenResult.Connected)
                assertTrue(again.listLevel("", null, false).isNotEmpty())
            } finally {
                runCatching { again.close() }
            }
        } finally {
            runCatching { jmap.close() }
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    private fun jmapAccount(account: Settings): Settings = account.copy(
        imapHost = "http://${arg("labHost")}:${arg("labJmapPort")}",
        jmapFolders = true,
    )

    private suspend fun longSearchFault(case: String, fault: String) {
        val (ctx, labId, account, box) = prepare(case) ?: return
        val quiet = account.copy(longSearchQuietSec = 3L)
        try {
            val first = searchBody(quiet, labId, box)
            val firstHits = (first as? LongSearchResult.Hits)?.uids
            assertTrue("$first", !firstHits.isNullOrEmpty())
            arm(mapOf("do" to fault, "command" to "SEARCH", "times" to 0))
            val second = searchBody(quiet, labId, box)
            val secondHits = (second as? LongSearchResult.Hits)?.uids
            assertFalse("$second", secondHits == firstHits)
        } finally {
            clearFaults()
            deleteLabAccount(ctx, labId)
        }
    }

    private suspend fun searchBody(account: Settings, labId: String, box: String): LongSearchResult {
        val link = LibetpanLongSearchLink(account, labId, box, { false }, { true })
        return LongSearch(link, 50).run("Body", "seed") {}
    }

    private suspend fun openUids(
        ctx: android.content.Context,
        labId: String,
        account: Settings,
        box: String,
    ): List<Int> {
        val held = openSelect(ctx, labId, account, box)
        assertTrue(held.text, held.text.startsWith("ok"))
        return try {
            held.session.searchCriterion("All", "").map { it.toInt() }
        } finally {
            runCatching { held.session.close() }
        }
    }

    private suspend fun openResult(
        ctx: android.content.Context,
        labId: String,
        account: Settings,
    ): OpenResult {
        val session = LibetpanMailSession(ctx, labId)
        session.setPlaintextConfirmer { true }
        return try {
            session.open(account)
        } catch (e: Throwable) {
            OpenResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun authedSieve(host: String, port: Int) =
        openPlainSieve(host, port, null).also { transport ->
            readGreeting(transport)
            authenticatePlain(transport, arg("labUser"), arg("labPass"))
        }

    private suspend fun callFails(body: suspend () -> Unit): String = try {
        body()
        "ok"
    } catch (e: Throwable) {
        "fail: ${e.message}"
    }

    private fun arm(fields: Map<String, Any?>) {
        val reply = control(mapOf<String, Any?>("op" to "arm-fault") + fields)
        assertTrue(reply.toString(), reply.optBoolean("ok"))
    }

    private fun clearFaults() {
        val reply = control(mapOf("op" to "clear-faults"))
        assertTrue(reply.toString(), reply.optBoolean("ok"))
    }

    private fun assertWriteSelectBeforeAppend(box: String, from: Int) {
        val lines = commandTexts().drop(from)
        val appendAt = lines.indexOfLast { hasImapCommand(it, "APPEND") }
        assertTrue(lines.joinToString("\n"), appendAt >= 0)
        val select = lines.subList(0, appendAt).lastOrNull { line ->
            hasImapCommand(line, "SELECT") && line.uppercase().contains(box.uppercase())
        }
        assertTrue(lines.joinToString("\n"), select != null)
    }

    private fun hasImapCommand(line: String, name: String): Boolean {
        val upper = line.uppercase()
        return upper.contains(" $name ") || upper.endsWith(" $name")
    }

    private fun labAccount(host: String, port: Int, user: String): Settings {
        LabTestHooks.setLabHost(host)
        return Settings(
            imapHost = host,
            imapPort = port,
            username = user,
            tlsMode = TlsMode.None,
            allowPlaintextAuth = true,
            logImapTraffic = true,
        )
    }

    private suspend fun attempt(ctx: android.content.Context, labId: String, account: Settings, box: String): String {
        val held = openSelect(ctx, labId, account, box)
        val text = held.text
        runCatching { held.session.close() }
        return text
    }

    private data class Held(val session: LibetpanMailSession, val text: String)

    private suspend fun openSelect(ctx: android.content.Context, labId: String, account: Settings, box: String): Held {
        val session = LibetpanMailSession(ctx, labId)
        session.setPlaintextConfirmer { true }
        return try {
            val opened = session.open(account)
            if (opened !is OpenResult.Connected) {
                Held(session, "fail: $opened")
            } else {
                val selected = session.select(box)
                Held(session, "ok exists=${selected.exists}")
            }
        } catch (e: Throwable) {
            Held(session, "fail: ${e.message}")
        }
    }

    private fun uids(box: String): List<Int> {
        val reply = control(mapOf("op" to "list-mail", "mailbox" to box, "user" to arg("labUser")))
        assertTrue(reply.toString(), reply.optBoolean("ok"))
        val messages = reply.optJSONArray("messages") ?: return emptyList()
        return (0 until messages.length()).map { messages.getJSONObject(it).getInt("uid") }
    }

    private fun control(fields: Map<String, Any?>): JSONObject {
        val body = JSONObject()
        for ((key, value) in fields) {
            when (value) {
                null -> body.put(key, JSONObject.NULL)
                is Int -> body.put(key, value)
                is Boolean -> body.put(key, value)
                else -> body.put(key, value.toString())
            }
        }
        val conn = bridge("control")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(text)
    }

    private fun commandTexts(): List<String> {
        val conn = bridge("log")
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return@mapNotNull null
            val rec = JSONObject(trimmed)
            if (rec.optString("kind") == "command") rec.optString("text") else null
        }.toList()
    }

    private fun bridge(path: String): HttpURLConnection {
        val conn = java.net.URI(arg("labBridge").trimEnd('/') + "/" + path).toURL()
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return conn
    }

    private fun isBodyFetch(line: String): Boolean {
        val upper = line.uppercase()
        if (upper.contains("RFC822.SIZE")) {
            val rest = upper.replace("RFC822.SIZE", "")
            if (!rest.contains("RFC822") && !Regex("""BODY(?:\.PEEK)?\[\]""").containsMatchIn(rest)) return false
        }
        return upper.contains("RFC822") || Regex("""BODY(?:\.PEEK)?\[\]""").containsMatchIn(upper)
    }
}
