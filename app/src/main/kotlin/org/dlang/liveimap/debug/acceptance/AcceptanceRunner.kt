package org.dlang.liveimap.debug.acceptance

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey

/** Plan tag of the tests added with the runner (sandbox/roadmap/acceptance-testing.md). */
const val RunnerPlan = "acceptance-runner-20261012-1420"

/** Mail-changing tests work only in this folder tree, so the seeded folders stay identical. */
const val AcceptanceFolder = "LiveIMAP-Test"

class AcceptanceSkip(reason: String) : Exception(reason)

class AcceptanceContext(
    val session: MailSession,
    val account: AccountSettings,
    /** Re-opens the session (reconnect test). */
    val reopen: suspend () -> OpenResult,
) {
    var inboxUid: Long = 0
    var appendedUid: Long = 0
    fun has(name: String) = session.capabilities.any { it.equals(name, ignoreCase = true) }
}

class AcceptanceTest(val case: AcceptanceCase, val run: suspend AcceptanceContext.() -> String)

private fun t(name: String, feature: String, writes: Boolean = false, run: suspend AcceptanceContext.() -> String) =
    AcceptanceTest(AcceptanceCase(name, feature, listOf(RunnerPlan), writes, scale = false), run)

private fun testMessage(account: AccountSettings, subject: String): ByteArray {
    val from = account.email.ifBlank { account.username }
    return ("From: $from\r\nTo: $from\r\nSubject: $subject\r\nMessage-ID: <acc-${System.nanoTime()}@liveimap.test>\r\n" +
        "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nLiveIMAP acceptance run.\r\n").toByteArray()
}

/** The registry: every acceptance test, in run order (later tests use state set by earlier ones). */
val acceptanceTests: List<AcceptanceTest> = listOf(
    t("capability line", "capability-model") {
        if (session.capabilities.isEmpty()) throw MailFailure("no capabilities") else "${session.capabilities.size} tokens"
    },
    t("namespaces", "folder-list") { "${session.namespaces().size} namespaces" },
    t("list top level", "folder-list") { "${session.listLevel("", null, false).size} mailboxes" },
    t("select INBOX", "session-select-examine") { "${session.select("INBOX").exists} messages" },
    t("index window newest 20", "index-layout-and-sort") {
        val rows = session.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 20, prefetch = 0))
        inboxUid = rows.firstOrNull()?.uid ?: 0
        "${rows.size} rows"
    },
    t("sort from", "index-layout-and-sort") {
        if (!has("SORT")) throw AcceptanceSkip("SORT not advertised")
        "${session.sort(SortKey.From, true).size} uids"
    },
    t("thread references", "index-layout-and-sort") {
        if (!has("THREAD=REFERENCES")) throw AcceptanceSkip("THREAD=REFERENCES not advertised")
        "${session.thread(SortKey.ThreadReferences).messageCount()} messages"
    },
    t("search text", "search") { "${session.searchText("liveimap").size} hits" },
    t("structure and first part", "reader-body-decoding") {
        if (inboxUid == 0L) throw AcceptanceSkip("INBOX is empty")
        val part = session.fetchStructure(inboxUid)
        var leaf = part
        while (leaf.children.isNotEmpty()) leaf = leaf.children.first()
        "${session.peekPart(inboxUid, leaf.section.ifEmpty { "1" }, 0, 64).size} bytes"
    },
    t("idle watch", "watched-folders") {
        if (!has("IDLE")) throw AcceptanceSkip("IDLE not advertised")
        try { session.watch("INBOX") { } } finally { session.stopWatch() }
        "watched"
    },
    t("append to test folder", "smtp-submission", writes = true) {
        if (!session.mailboxListed(AcceptanceFolder)) throw AcceptanceSkip("$AcceptanceFolder missing (server not reset)")
        appendedUid = session.appendReturningUid(AcceptanceFolder, testMessage(account, "Acceptance append"))
        if (appendedUid > 0) "uid $appendedUid" else "appended (no UIDPLUS)"
    },
    t("flag and delete appended", "delete-and-expunge", writes = true) {
        if (appendedUid <= 0) throw AcceptanceSkip("no appended UID")
        session.select(AcceptanceFolder)
        session.storeFlags(listOf(appendedUid), setOf("\\Flagged"), emptySet())
        session.storeFlags(listOf(appendedUid), setOf("\\Deleted"), emptySet())
        session.uidExpunge(listOf(appendedUid))
        "flagged, deleted, expunged"
    },
    t("send to self", "smtp-submission", writes = true) {
        if (account.email.isBlank()) throw AcceptanceSkip("no test recipient (account email empty)")
        val r = session.smtpSend(testMessage(account, "Acceptance send"), listOf(account.email))
        "sent" + if (r < 0) " (no DSN)" else ""
    },
    t("reconnect", "session-select-examine") {
        session.close()
        when (val o = reopen()) {
            OpenResult.Connected -> "${session.select("INBOX").exists} messages after reconnect"
            is OpenResult.Failed -> throw MailFailure(o.text)
            is OpenResult.Rejected -> throw MailFailure(o.capabilities)
        }
    },
)

/**
 * Runs [tests] in order on an opened [context]. Mail-changing tests run only for lab allowlist users and
 * never on a read-only server. Never throws for a test failure; each row says pass/fail/skip.
 */
suspend fun runAcceptance(
    context: AcceptanceContext,
    tests: List<AcceptanceTest>,
    readOnly: Boolean,
    progress: (Int, Int, String) -> Unit = { _, _, _ -> },
    clock: () -> Long = System::currentTimeMillis,
): List<ReportRow> {
    val writer = allowWriter(context.account.username, context.account.imapHost) &&
        allowWriteHost(context.account.smtpHost.ifBlank { context.account.imapHost })
    val rows = ArrayList<ReportRow>()
    tests.forEachIndexed { index, test ->
        val c = test.case
        progress(index, tests.size, c.name)
        val outcome = caseOutcome(readOnly, scaleSeeded = false, c)
        if (outcome.step == CaseStep.Skip) {
            rows += ReportRow(c.name, c.feature, c.plans, "skip", outcome.reason); return@forEachIndexed
        }
        if (c.writesMail && !writer) {
            rows += ReportRow(c.name, c.feature, c.plans, "skip", "not a lab test user: no mail changed"); return@forEachIndexed
        }
        val start = clock()
        rows += try {
            ReportRow(c.name, c.feature, c.plans, "pass", test.run(context), clock() - start)
        } catch (error: CancellationException) {
            throw error
        } catch (skip: AcceptanceSkip) {
            ReportRow(c.name, c.feature, c.plans, "skip", skip.message.orEmpty(), clock() - start)
        } catch (error: Exception) {
            ReportRow(c.name, c.feature, c.plans, "fail", (error as? MailFailure)?.text ?: error.toString(), clock() - start)
        }
    }
    progress(tests.size, tests.size, "")
    return rows
}

fun summaryLine(rows: List<ReportRow>): String =
    "${rows.count { it.result == "pass" }} pass, ${rows.count { it.result == "fail" }} fail, ${rows.count { it.result == "skip" }} skip"
