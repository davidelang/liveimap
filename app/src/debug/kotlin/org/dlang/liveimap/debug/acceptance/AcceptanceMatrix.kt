package org.dlang.liveimap.debug.acceptance

import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.settings.AccountSettings

/**
 * One acceptance pass: capabilities hidden by the capability override and/or the slow fallbacks forced on.
 * Nothing here is persisted: the override wraps the run's own session and the forced fallbacks live on a copy of
 * the account, so the user's settings cannot be left changed by a cancel or crash (restored by construction).
 */
data class AcceptanceVariant(val name: String, val hide: Set<String> = emptySet(), val forceFallbacks: Boolean = false)

val plainVariant = AcceptanceVariant("plain")

/** acceptance-testing.md "degraded behaviour" and "slow fallbacks forced on". */
val acceptanceVariants: List<AcceptanceVariant> = listOf(
    plainVariant,
    AcceptanceVariant("no SORT", setOf("SORT")),
    AcceptanceVariant("no THREAD", setOf("THREAD")),
    AcceptanceVariant("no ESEARCH", setOf("ESEARCH")),
    AcceptanceVariant("no LIST-STATUS", setOf("LIST-STATUS")),
    AcceptanceVariant("no UIDPLUS", setOf("UIDPLUS")),
    AcceptanceVariant("no IDLE", setOf("IDLE")),
    AcceptanceVariant("no UNSELECT", setOf("UNSELECT")),
    AcceptanceVariant("no SPECIAL-USE", setOf("SPECIAL-USE")),
    AcceptanceVariant("no CONDSTORE/QRESYNC", setOf("CONDSTORE", "QRESYNC")),
    AcceptanceVariant("slow fallbacks forced", forceFallbacks = true),
)

/** True when [token] is [name] or one of its parameterised forms (SORT=DISPLAY, THREAD=REFERENCES). */
fun capabilityHidden(token: String, hide: Set<String>): Boolean =
    hide.any { token.equals(it, ignoreCase = true) || token.startsWith("$it=", ignoreCase = true) }

/** The capability override: [inner] with [hide] removed from what callers see. */
class HidingSession(private val inner: MailSession, private val hide: Set<String>) : MailSession by inner {
    override val capabilities: Set<String>
        get() = inner.capabilities.filterNot { capabilityHidden(it, hide) }.toSet()
    override val featureCaps: Capabilities
        get() = Capabilities.parse(capabilities.joinToString(" "))
}

fun variantSession(session: MailSession, variant: AcceptanceVariant): MailSession =
    if (variant.hide.isEmpty()) session else HidingSession(session, variant.hide)

fun variantAccount(account: AccountSettings, variant: AcceptanceVariant): AccountSettings =
    if (variant.forceFallbacks) account.copy(forceSlowerFallbacks = true) else account

/** One server of a matrix run: a label and how to open a context on it (null: could not connect, with why). */
class MatrixServer(val label: String, val open: suspend (AcceptanceVariant) -> Pair<AcceptanceContext?, String>, val close: suspend () -> Unit = {})

/** Runs [tests] once per server and variant; rows carry server and variant. Each server is closed in finally. */
suspend fun runMatrix(
    servers: List<MatrixServer>,
    variants: List<AcceptanceVariant>,
    tests: List<AcceptanceTest>,
    readOnly: (String) -> Boolean = { false },
    progress: (String) -> Unit = {},
    writeHosts: Set<String> = emptySet(),
): List<ReportRow> {
    val rows = ArrayList<ReportRow>()
    for (server in servers) {
        try {
            for (variant in variants) {
                progress("${server.label} ${variant.name}")
                val (context, why) = server.open(variant)
                if (context == null) {
                    rows += ReportRow("login", "tls-auth", listOf(RunnerPlan), "fail", why, server = server.label, variant = variant.name)
                    continue
                }
                rows += runAcceptance(context, tests, readOnly(server.label), writeHosts = writeHosts).map { it.copy(server = server.label, variant = variant.name) }
            }
        } finally {
            server.close()
        }
    }
    return rows
}

/** Server x test grid for the text report: one line per test, one P/F/S cell per server/variant column. */
fun matrixGrid(rows: List<ReportRow>): String {
    val columns = rows.map { it.server to it.variant }.distinct()
    val tests = rows.map { it.name }.distinct()
    val cell = rows.associate { (Triple(it.server, it.variant, it.name)) to it.result }
    val out = StringBuilder()
    columns.forEachIndexed { i, (s, v) -> out.append("# col ").append(i + 1).append(": ").append(s).append(" / ").append(v).append('\n') }
    val width = tests.maxOfOrNull { it.length } ?: 0
    out.append("test".padEnd(width)).append(columns.indices.joinToString("") { " %3d".format(it + 1) }).append('\n')
    for (t in tests) {
        out.append(t.padEnd(width))
        for ((s, v) in columns) {
            val r = cell[Triple(s, v, t)]
            out.append("   ").append(when (r) { "pass" -> "P"; "fail" -> "F"; "skip" -> "S"; else -> "-" })
        }
        out.append('\n')
    }
    return out.toString()
}
