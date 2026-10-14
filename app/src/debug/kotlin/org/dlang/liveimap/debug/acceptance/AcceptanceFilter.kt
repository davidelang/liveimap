package org.dlang.liveimap.debug.acceptance

import java.io.File

enum class AcceptanceSuite {
    Full,
    ThisTurn,
    LastN,
}

data class AcceptanceCase(
    val name: String,
    val feature: String,
    val plans: List<String>,
    val writesMail: Boolean,
    val scale: Boolean,
)

enum class CaseStep {
    Run,
    Skip,
}

data class CaseOutcome(
    val step: CaseStep,
    val reason: String,
)

data class ReportRow(
    val name: String,
    val feature: String,
    val plans: List<String>,
    val result: String,
    val reason: String,
    val millis: Long = -1,
    /** Matrix runs: which server and capability/fallback variant (empty for a single-server run). */
    val server: String = "",
    val variant: String = "",
)

data class AcceptanceReport(
    val suite: AcceptanceSuite,
    val rows: List<ReportRow>,
    /** Build, device, server, TLS, capability line... (no passwords, no bodies). */
    val header: List<Pair<String, String>> = emptyList(),
)

private val stampPattern = Regex("""\d{8}-\d{4}""")

private val allowUsers = setOf(
    "liveimap",
    "liveimap2",
    "liveimap-empty",
    "liveimap-utf8",
    "liveimap-scale",
)

fun planStamp(text: String): String {
    if (text.length < 13) return text
    val tail = text.takeLast(13)
    return if (stampPattern.matches(tail)) tail else text
}

fun selectCases(
    cases: List<AcceptanceCase>,
    suite: AcceptanceSuite,
    recentPlans: List<String>,
    lastN: Int,
    feature: String,
): List<AcceptanceCase> {
    val stamps = recentPlans.map(::planStamp).distinct().sortedDescending()
    val windowed = when (suite) {
        AcceptanceSuite.Full -> cases
        AcceptanceSuite.ThisTurn -> {
            val newest = stamps.firstOrNull()
            if (newest == null) {
                emptyList()
            } else {
                cases.filter { item -> item.plans.any { planStamp(it) == newest } }
            }
        }
        AcceptanceSuite.LastN -> {
            if (stamps.isEmpty() || lastN < 1) {
                emptyList()
            } else {
                val window = stamps.take(lastN).toSet()
                cases.filter { item -> item.plans.any { planStamp(it) in window } }
            }
        }
    }
    if (feature.isEmpty()) return windowed
    return windowed.filter { it.feature == feature }
}

fun allowHost(hosts: Set<String>, host: String, port: Int): Boolean {
    return hosts.contains("$host:$port")
}

/**
 * F46: servers where mail-changing acceptance cases (APPEND, flags/expunge, a real SMTP send) may run: the
 * emulator lab and sun (the disposable copy). asgard (authoritative) and moon (replica) are never written to.
 */
fun allowWriteHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h in setOf("lab.test", "10.0.2.2", "localhost", "127.0.0.1")) return true
    return h.substringBefore('.') == "sun"
}

/** Writes need both a lab user name and a lab host; a real account whose name happens to match is safe. */
fun allowWriter(user: String, host: String): Boolean = allowUser(user) && allowWriteHost(host)

fun allowUser(user: String): Boolean {
    return user in allowUsers
}

fun allowEntry(
    markedTestServer: Boolean,
    realServer: Boolean,
    hosts: Set<String>,
    host: String,
    port: Int,
    user: String,
): String? {
    if (!markedTestServer) return "unmarked server"
    if (!allowHost(hosts, host, port)) return "host not allowlisted"
    if (realServer && !allowUser(user)) return "user not allowlisted"
    return null
}

/** Seed version the lab corpus carries (test-servers.md "Seed markers"). */
const val SeedVersion = "corpus-v1"

data class SeedMarker(val version: String, val scale: Boolean)

/** The marker from the INBOX marker message's header block, or null when it has none. */
fun parseSeedMarker(header: String): SeedMarker? {
    fun field(name: String) = Regex("(?im)^" + name + "[ \t]*:[ \t]*([^\r\n]*)").find(header)?.groupValues?.get(1)?.trim()
    val version = field("X-LiveIMAP-Seed")?.takeIf { it.isNotEmpty() } ?: return null
    val profile = field("X-LiveIMAP-Seed-Profile").orEmpty().lowercase()
    return SeedMarker(version, profile.contains("scale"))
}

/** Null when the server is seeded with [SeedVersion]; otherwise the "not reset" reason every test is skipped with. */
fun seedGate(marker: SeedMarker?): String? = when {
    marker == null -> "not reset: no seed marker"
    marker.version != SeedVersion -> "not reset: seed ${marker.version}, want $SeedVersion"
    else -> null
}

fun caseOutcome(readOnly: Boolean, scaleSeeded: Boolean, item: AcceptanceCase): CaseOutcome {
    if (item.scale && !scaleSeeded) {
        return CaseOutcome(CaseStep.Skip, "scale content not seeded")
    }
    if (readOnly && item.writesMail) {
        return CaseOutcome(CaseStep.Skip, "read-only server")
    }
    return CaseOutcome(CaseStep.Run, "")
}

fun writeReport(dir: File, report: AcceptanceReport, stamp: String) {
    if (!dir.isDirectory) {
        dir.mkdirs()
    }
    val base = File(dir, "acceptance-$stamp-${report.suite.name}")
    File(dir, "${base.name}.json").writeText(reportJson(report))
    File(dir, "${base.name}.txt").writeText(reportText(report))
}

private fun reportJson(report: AcceptanceReport): String {
    val rows = report.rows.joinToString(prefix = "[", postfix = "]") { row ->
        "{\"name\":${jsonString(row.name)}," +
            "\"feature\":${jsonString(row.feature)}," +
            "\"plans\":${jsonArray(row.plans)}," +
            "\"result\":${jsonString(row.result)}," +
            "\"reason\":${jsonString(row.reason)}" +
            (if (row.server.isNotEmpty()) ",\"server\":${jsonString(row.server)}" else "") +
            (if (row.variant.isNotEmpty()) ",\"variant\":${jsonString(row.variant)}" else "") +
            (if (row.millis >= 0) ",\"ms\":${row.millis}" else "") + "}"
    }
    val header = report.header.joinToString("") { (k, v) -> "${jsonString(k)}:${jsonString(v)}," }
    return "{$header\"suite\":${jsonString(report.suite.name)},\"rows\":$rows}"
}

private fun reportText(report: AcceptanceReport): String {
    val text = StringBuilder()
    for ((k, v) in report.header) text.append("# ").append(k).append(": ").append(v).append('\n')
    if (report.rows.any { it.server.isNotEmpty() || it.variant.isNotEmpty() }) text.append(matrixGrid(report.rows))
    for (row in report.rows) {
        if (row.server.isNotEmpty() || row.variant.isNotEmpty()) text.append(row.server).append('\t').append(row.variant).append('\t')
        text.append(row.name)
        text.append('\t')
        text.append(row.result)
        text.append('\t')
        text.append(row.reason)
        text.append('\n')
    }
    return text.toString()
}

private fun jsonArray(values: List<String>): String {
    return values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
}

private fun jsonString(text: String): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    for (ch in text) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
