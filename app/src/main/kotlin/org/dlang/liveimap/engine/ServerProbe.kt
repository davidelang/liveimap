package org.dlang.liveimap.engine

import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey

suspend fun probeServer(session: MailSession, account: AccountSettings): List<String> {
    val loginFailure = loginLine(session, account)
    if (loginFailure != null) return listOf(loginFailure)
    val lines = mutableListOf(okLine("login", "connected"))
    lines += okLine("capability", session.capabilities.joinToString(" "))
    for (name in requiredCapabilities) {
        lines += if (advertised(session, name)) {
            okLine(name, "advertised")
        } else {
            failLine(name, "missing")
        }
    }
    addNamespaces(session, lines)
    addList(session, lines)
    val selected = selectInbox(session, lines)
    if (selected != null) {
        addSort(session, lines)
        addThread(session, lines)
        addSearch(session, lines)
        addPreview(session, selected, lines)
        addUnselect(session, lines)
    }
    addIdle(session, lines)
    addRemaining(session, lines)
    return lines
}

private suspend fun loginLine(session: MailSession, account: AccountSettings): String? {
    val opened = try {
        session.open(account)
    } catch (error: MailFailure) {
        return failLine("login", error.text)
    }
    return when (opened) {
        is OpenResult.Failed -> failLine("login", opened.text)
        is OpenResult.Rejected -> failLine("login", opened.capabilities)
        OpenResult.Connected -> null
    }
}

private suspend fun addNamespaces(session: MailSession, lines: MutableList<String>) {
    lines += try {
        okLine("NAMESPACE", "${session.namespaces().size} namespaces")
    } catch (error: MailFailure) {
        failLine("NAMESPACE", error.text)
    }
}

private suspend fun addList(session: MailSession, lines: MutableList<String>) {
    val (passed, detail) = try {
        true to "${session.listLevel("", null, true).size} mailboxes"
    } catch (error: MailFailure) {
        false to error.text
    }
    lines += mark(passed, "LIST", detail)
    for (name in listOf("CHILDREN", "LIST-EXTENDED", "LIST-STATUS", "SPECIAL-USE")) {
        if (advertised(session, name)) {
            lines += mark(passed, name, detail)
        }
    }
}

private suspend fun selectInbox(session: MailSession, lines: MutableList<String>): SelectResult? {
    return try {
        session.select("INBOX")
    } catch (error: MailFailure) {
        lines += failLine("SELECT INBOX", error.text)
        lines += skipLine("UNSELECT", "SELECT INBOX failed")
        lines += skipLine("SORT", "SELECT INBOX failed")
        lines += skipLine("SEARCH", "SELECT INBOX failed")
        lines += skipLine("PREVIEW", "SELECT INBOX failed")
        lines += skipLine("BINARY", "SELECT INBOX failed")
        lines += skipLine("THREAD=REFERENCES", "SELECT INBOX failed")
        if (advertised(session, "THREAD=ORDEREDSUBJECT")) {
            lines += skipLine("THREAD=ORDEREDSUBJECT", "SELECT INBOX failed")
        }
        null
    }
}

private suspend fun addSort(session: MailSession, lines: MutableList<String>) {
    val (passed, detail) = try {
        session.sort(SortKey.From, true)
        true to "From newest"
    } catch (error: MailFailure) {
        false to error.text
    }
    lines += mark(passed, "SORT", detail)
    if (advertised(session, "ESORT")) lines += mark(passed, "ESORT", detail)
    if (advertised(session, "SORT=DISPLAY")) lines += mark(passed, "SORT=DISPLAY", detail)
}

private suspend fun addThread(session: MailSession, lines: MutableList<String>) {
    lines += threadLine(session, "THREAD=REFERENCES", SortKey.ThreadReferences)
    if (advertised(session, "THREAD=ORDEREDSUBJECT")) {
        lines += threadLine(session, "THREAD=ORDEREDSUBJECT", SortKey.ThreadOrderedSubject)
    }
}

private suspend fun threadLine(session: MailSession, label: String, key: SortKey): String {
    return try {
        okLine(label, "${countMessages(session.thread(key))} messages")
    } catch (error: MailFailure) {
        failLine(label, error.text)
    }
}

private fun countMessages(node: ThreadNode): Int {
    val here = if (node.uid != null) 1 else 0
    var total = here
    for (child in node.children) {
        total += countMessages(child)
    }
    return total
}

private suspend fun addSearch(session: MailSession, lines: MutableList<String>) {
    val label = if (advertised(session, "ESEARCH")) "ESEARCH" else "UID SEARCH"
    lines += try {
        okLine(label, session.searchText("x").size.toString())
    } catch (error: MailFailure) {
        failLine(label, error.text)
    }
}

private suspend fun addPreview(session: MailSession, selected: SelectResult, lines: MutableList<String>) {
    val preview = advertised(session, "PREVIEW")
    val binary = advertised(session, "BINARY")
    if (!preview && !binary) return
    if (selected.exists <= 0) {
        if (preview) lines += skipLine("PREVIEW", "INBOX is empty")
        if (binary) lines += skipLine("BINARY", "INBOX is empty")
        return
    }
    val rows = try {
        session.fetchIndex(
            IndexRequest(
                mailbox = "INBOX",
                mode = IndexMode.ArrivalNewest,
                limit = 1,
                includePreview = true,
            ),
        )
    } catch (error: MailFailure) {
        if (preview) lines += failLine("PREVIEW", error.text)
        if (binary) lines += failLine("BINARY", error.text)
        return
    }
    if (preview) lines += okLine("PREVIEW", "${rows.size} rows")
    if (!binary) return
    if (rows.isEmpty()) {
        lines += skipLine("BINARY", "no index row")
        return
    }
    lines += binaryLine(session, rows.first().uid)
}

private suspend fun binaryLine(session: MailSession, uid: Long): String {
    return try {
        val part = leafPart(session.fetchStructure(uid))
        val section = if (part.section.isEmpty()) "1" else part.section
        val bytes = session.peekPart(uid, section, 0, 1)
        okLine("BINARY", "${bytes.size} bytes")
    } catch (error: MailFailure) {
        failLine("BINARY", error.text)
    }
}

private fun leafPart(part: MimePart): MimePart {
    var current = part
    while (current.children.isNotEmpty()) {
        current = current.children.first()
    }
    return current
}

private suspend fun addUnselect(session: MailSession, lines: MutableList<String>) {
    if (!advertised(session, "UNSELECT")) {
        lines += skipLine("UNSELECT", "not advertised")
        return
    }
    lines += try {
        session.unselect()
        okLine("UNSELECT", "unselected")
    } catch (error: MailFailure) {
        failLine("UNSELECT", error.text)
    }
}

private suspend fun addIdle(session: MailSession, lines: MutableList<String>) {
    var failure: String? = null
    try {
        session.watch("INBOX") { }
    } catch (error: MailFailure) {
        failure = error.text
    } finally {
        try {
            session.stopWatch()
        } catch (error: MailFailure) {
            if (failure == null) failure = error.text
        }
    }
    val text = failure
    lines += if (text == null) okLine("IDLE", "watched") else failLine("IDLE", text)
}

private fun addRemaining(session: MailSession, lines: MutableList<String>) {
    val covered = HashSet<String>()
    for (line in lines) {
        covered += labelOf(line).uppercase()
    }
    for (token in session.capabilities) {
        if (token.uppercase() in covered) continue
        val detail = if (openContinues(token)) {
            "open sends it and continues when the server says NO"
        } else {
            "no read-only app command"
        }
        lines += skipLine(token, detail)
    }
}

private fun openContinues(token: String): Boolean =
    token.equals("CONDSTORE", ignoreCase = true) ||
        token.equals("QRESYNC", ignoreCase = true) ||
        token.equals("COMPRESS=DEFLATE", ignoreCase = true)

private fun labelOf(line: String): String {
    val space = line.indexOf(' ')
    if (space < 0 || space >= line.lastIndex) return ""
    val rest = line.substring(space + 1)
    val colon = rest.indexOf(':')
    return if (colon < 0) rest.trim() else rest.substring(0, colon).trim()
}

private fun advertised(session: MailSession, name: String): Boolean =
    session.capabilities.any { it.equals(name, ignoreCase = true) }

private fun mark(passed: Boolean, label: String, detail: String): String =
    if (passed) okLine(label, detail) else failLine(label, detail)

private fun okLine(label: String, detail: String) = "OK $label: $detail"

private fun failLine(label: String, detail: String) = "FAIL $label: $detail"

private fun skipLine(label: String, detail: String) = "SKIP $label: $detail"
