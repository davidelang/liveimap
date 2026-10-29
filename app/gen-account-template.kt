private val ignoredAccountToolbarKeys = setOf(
    "indexBar",
    "selectionBar",
    "folderBar",
    "readerToolbar",
    "composeBar",
    "toolbarRows",
    "readerTextZoom",
)

data class AccountSettings(
/* @gen-fields */
    val unknownSettings: List<String> = emptyList(),
) {
    val preferHtml: Boolean
        get() = bodyView == BodyView.PlainOrHtml

    init {
        if (composerWrapColumn !in 0..998) throw IllegalArgumentException("bad composerWrapColumn")
        if (pollSeconds !in 15..600) throw IllegalArgumentException("pollSeconds")
        if (idleFallbackSeconds < 1) throw IllegalArgumentException("idleFallbackSeconds")
        if (keepAliveMinutes < 1) throw IllegalArgumentException("keepAliveMinutes")
        if (idleRenewMinutes !in 1..29) throw IllegalArgumentException("idleRenewMinutes")
        if (connectTimeoutSec !in 1..600) throw IllegalArgumentException("connectTimeoutSec")
        if (readTimeoutSec !in 1..3600) throw IllegalArgumentException("readTimeoutSec")
        if (trafficLogMaxMb !in 1..64) throw IllegalArgumentException("trafficLogMaxMb")
        if (jmapPartCacheMessages !in 1..10000) throw IllegalArgumentException("jmapPartCacheMessages")
        if (longSearchProgressSec < 1) throw IllegalArgumentException("longSearchProgressSec")
        if (longSearchQuietSec < 1) throw IllegalArgumentException("longSearchQuietSec")
        if (maxJmapResponseBytes < 1) throw IllegalArgumentException("maxJmapResponseBytes")
        if (maxResultUids < 1) throw IllegalArgumentException("maxResultUids")
        if (sieveConnectSeconds < 1) throw IllegalArgumentException("sieveConnectSeconds")
        if (sieveReadSeconds < 1) throw IllegalArgumentException("sieveReadSeconds")
        if (!knownUnlabeledCharset(unlabeledCharset)) throw IllegalArgumentException("unlabeledCharset")
    }

    /** tlsMode, then deletePolicy, then moveMethod, when that raw line is still kept. */
    fun preservedSecurityKey(): String? {
        val present = HashSet<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0) present.add(first.substring(0, eq))
        }
        if ("tlsMode" in present) return "tlsMode"
        if ("deletePolicy" in present) return "deletePolicy"
        if ("moveMethod" in present) return "moveMethod"
        return null
    }

    /** The raw sieveTlsMode line, when it is still kept. Not a preservedSecurityKey. */
    fun preservedSieveTlsKey(): String? {
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == "sieveTlsMode") return "sieveTlsMode"
        }
        return null
    }

    /** A chosen value, including the default, drops every kept line for [key]. */
    fun withoutPreserved(key: String): AccountSettings {
        val kept = ArrayList<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == key) continue
            kept.add(entry)
        }
        return copy(unknownSettings = kept)
    }
}

private val fieldNames = listOf(
/* @gen-names */
)

// Lines a fresh account writes. A preserved key still at that line stays omitted.
private fun failureEncodedLine(key: String): String? = if (key == "tlsMode") "tlsMode=None" else null

private val defaultEncodedLines: Map<String, String> by lazy {
    AccountSettings().encode().lineSequence().filter { it.isNotEmpty() }.associate { line ->
        val eq = line.indexOf('=')
        line.substring(0, eq) to line
    }
}

fun AccountSettings.encode(): String = buildString {
    val preserved = HashSet<String>()
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) preserved.add(first.substring(0, eq))
    }
    val written = HashSet<String>()
    fun put(line: String) {
        val first = line.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) {
            val key = first.substring(0, eq)
            // A later edit replaces the raw line. The failed-decode default does not.
            if (key in preserved && (defaultEncodedLines[key] == first || failureEncodedLine(key) == first)) return
            written.add(key)
        }
        appendLine(line)
    }
/* @gen-encode */
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0 && first.substring(0, eq) in written) continue
        appendLine(entry)
    }
}

fun decodeAccountSettings(text: String): AccountSettings {
    val values = linkedMapOf<String, String>()
    val unknown = ArrayList<String>()
    var openKey: String? = null
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        val key = if (eq > 0) line.substring(0, eq) else ""
        if (eq > 0 && key in fieldNames) {
            values[key] = line.substring(eq + 1) // F69: a duplicate key: the last one wins
            openKey = key
        } else if (eq > 0 && key in ignoredAccountToolbarKeys) {
            openKey = null
        } else if (openKey == "inboundRules" && line.contains('\t')) {
            values[openKey] = values.getValue(openKey) + "\n" + line // rule lines always carry a TAB
        } else if (eq > 0) {
            // F69: a newer build's key: kept for round-tripping, not an error. Never keep anything credential-like
            // (secrets live in the keystore; a stray one here must not be carried along and re-saved).
            val lower = key.lowercase()
            if ("password" !in lower && "secret" !in lower && "token" !in lower) unknown += line
            openKey = null
        } else {
            openKey = null // F69: a malformed line is skipped
        }
    }
    val d = AccountSettings()
    fun listDroppedAll(key: String, value: Any?): Boolean = when (key) {
        "inboundRules", "favorites" -> value is List<*> && value.isEmpty()
        "folderViews", "folderStarts" -> value is Map<*, *> && value.isEmpty()
        "expandedFolders" -> value is Set<*> && value.isEmpty()
        else -> false
    }
    // A throw keeps the raw line. A list that skips every element of a non-empty value does too.
    fun <T> field(key: String, default: T, read: () -> T): T {
        val raw = values[key]
        if (raw == null && key != "bodyView") return default
        return try {
            val value = read()
            if (raw != null && raw.isNotEmpty() && listDroppedAll(key, value)) unknown += "$key=$raw"
            value
        } catch (_: Exception) {
            if (raw != null) unknown += "$key=$raw"
            default
        }
    }
    val decodedTlsMode = field("tlsMode", TlsMode.None) { enumValueOf<TlsMode>(values.getValue("tlsMode")) }
    val decodedSmtpTls = if ("smtpTlsMode" !in values) {
        decodedTlsMode
    } else {
        field("smtpTlsMode", decodedTlsMode) { enumValueOf<TlsMode>(values.getValue("smtpTlsMode")) }
    }
    return AccountSettings(
/* @gen-decode */
        unknownSettings = unknown,
    )
}

