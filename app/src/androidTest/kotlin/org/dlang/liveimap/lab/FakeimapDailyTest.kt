package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.oauthTokenSlot
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.jmap.platformJmapMailSession
import org.dlang.liveimap.oauth.OauthTokenState
import org.dlang.liveimap.oauth.encodeTokenState
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.TlsMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            control(mapOf("op" to "arm-fault", "do" to "close", "command" to "SELECT", "times" to 1))
            assertTrue(attempt(ctx, labId, account, box).startsWith("fail"))
            assertEquals(before, uids(box))
            control(mapOf("op" to "clear-faults"))
            assertTrue(attempt(ctx, labId, account, box).startsWith("ok"))

            val rst = openSelect(ctx, labId, account, box)
            control(mapOf("op" to "arm-fault", "do" to "rst", "command" to "FETCH", "times" to 1))
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
                "op" to "arm-fault", "do" to "reply", "command" to "SELECT",
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
