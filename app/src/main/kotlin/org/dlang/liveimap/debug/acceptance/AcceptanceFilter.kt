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
)

data class AcceptanceReport(
    val suite: AcceptanceSuite,
    val rows: List<ReportRow>,
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
            "\"reason\":${jsonString(row.reason)}}"
    }
    return "{\"suite\":${jsonString(report.suite.name)},\"rows\":$rows}"
}

private fun reportText(report: AcceptanceReport): String {
    val text = StringBuilder()
    for (row in report.rows) {
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
