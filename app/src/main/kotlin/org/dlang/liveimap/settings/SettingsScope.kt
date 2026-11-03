package org.dlang.liveimap.settings

import android.content.Context
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.accounts.parseAccountId
import org.dlang.liveimap.ui.toolbar.ToolbarStore

/** Picks from the save dialog. Save anyway leaves this empty, so disagreeing copies stay. */
internal object ScopePicks {
    @Volatile var chosen: Map<String, String> = emptyMap()
}

internal fun Settings.scopeToken(key: String): String? {
    if (key !in storedKeys) return null
    val side = unknownSettings.firstOrNull { it.substringBefore('\n') == key }
    if (side != null) return "raw\n" + side.substringAfter('\n')
    return "field\n" + getSetting(key)
}

internal fun Settings.putScopeToken(key: String, token: String): Settings {
    val body = token.substringAfter('\n')
    val cleared = withoutPreserved(key)
    return if (token.startsWith("raw\n")) {
        cleared.copy(
            unknownSettings = cleared.unknownSettings + (key + "\n" + body),
            storedKeys = cleared.storedKeys + key,
        )
    } else {
        val set = cleared.withSetting(key, SettingValue(body))
        set.copy(storedKeys = set.storedKeys + key)
    }
}

internal fun Settings.clearScopeKey(key: String): Settings =
    withoutPreserved(key).copy(storedKeys = storedKeys - key)

/**
 * Agreed values move into the record the tree names. Disagreeing values stay, and come back as dialog lines.
 * A corrupt document is not passed in here. This does not write.
 */
internal fun reconcileScope(
    ui: Settings,
    accounts: Map<String, Settings>,
    names: Map<String, String>,
    picks: Map<String, String>,
): Pair<Pair<Settings, Map<String, Settings>>, List<PolicyLine>> {
    var nextUi = ui
    val nextAccounts = LinkedHashMap(accounts)
    val lines = ArrayList<PolicyLine>()
    for ((key, spec) in settingsRegistry) {
        if (spec.leaf.derived) continue
        val inUi = nextUi.scopeToken(key)
        val inAccounts = nextAccounts.mapNotNull { (id, settings) ->
            settings.scopeToken(key)?.let { id to it }
        }
        if (spec.scope == SettingScope.Ui) {
            if (inUi != null) {
                for ((id, _) in inAccounts) nextAccounts[id] = nextAccounts.getValue(id).clearScopeKey(key)
            } else if (inAccounts.isNotEmpty()) {
                val distinct = inAccounts.map { it.second }.distinct()
                val pick = picks[key]
                val value = when {
                    distinct.size == 1 -> distinct[0]
                    pick != null && pick in distinct -> pick
                    else -> null
                }
                if (value != null) {
                    nextUi = nextUi.putScopeToken(key, value)
                    for ((id, _) in inAccounts) nextAccounts[id] = nextAccounts.getValue(id).clearScopeKey(key)
                } else {
                    lines.add(scopeDisagreeLine(key, inAccounts, names))
                }
            }
        } else if (nextAccounts.isNotEmpty() && inUi != null) {
            val found = listOf("ui" to inUi) + inAccounts
            val distinct = found.map { it.second }.distinct()
            val pick = picks[key]
            val value = when {
                distinct.size == 1 -> distinct[0]
                pick != null && pick in distinct -> pick
                else -> null
            }
            if (value != null) {
                for (id in nextAccounts.keys) nextAccounts[id] = nextAccounts.getValue(id).putScopeToken(key, value)
                nextUi = nextUi.clearScopeKey(key)
            } else {
                lines.add(scopeDisagreeLine(key, found, names))
            }
        }
    }
    return (nextUi to nextAccounts) to lines
}

private fun scopeDisagreeLine(
    key: String,
    found: List<Pair<String, String>>,
    names: Map<String, String>,
): PolicyLine {
    val options = found.groupBy { it.second }.map { (value, holders) ->
        val who = holders.joinToString(", ") { (blob, _) ->
            if (blob == "ui") "UI settings" else names[blob] ?: blob
        }
        val shown = value.substringAfter('\n').let { if (it.length > 80) it.take(80) else it }
        "$who: $shown" to value
    }
    val summary = options.joinToString("; ") { it.first }
    return PolicyLine(
        key,
        "$key has different values: $summary. Clean stores the selected value and removes the old copies.",
        needsPick = true,
        options = options,
    ) { current, _ -> current }
}

internal fun scopeDisagreementLines(draft: Settings, accountId: String?): List<PolicyLine> {
    if (settingsRegistry.isEmpty()) installGeneratedSettings()
    val accounts = LinkedHashMap(SettingsHub.accounts.value)
    val names = HashMap<String, String>()
    val ui = if (accountId.isNullOrEmpty()) draft else SettingsHub.uiNow()
    if (!accountId.isNullOrEmpty()) {
        val id = parseAccountId(accountId) ?: return emptyList()
        accounts[id] = draft
        names[id] = id
    }
    for (id in accounts.keys) names.putIfAbsent(id, id)
    names["ui"] = "UI settings"
    return reconcileScope(ui, accounts, names, emptyMap()).second
}

/** Writes agreed moves, and a Clean pick, into the other records. Skips a document that does not parse. */
internal suspend fun settleScope(context: Context) {
    val store = AccountStore.shared(context)
    val uiText = readGlobalRecord(context)
    if (documentError(uiText) != null) {
        ScopePicks.chosen = emptyMap()
        return
    }
    val ui = decodeSettings(uiText)
    val accounts = LinkedHashMap<String, Settings>()
    val names = HashMap<String, String>()
    for ((rawId, text) in store.storedBlobs()) {
        val id = parseAccountId(rawId) ?: continue
        if (documentError(text) != null) continue
        val user = store.accountFields(id) ?: emptyMap()
        accounts[id] = overlayAccountFields(decodeSettings(text), user)
        names[id] = id
    }
    names["ui"] = "UI settings"
    val picks = ScopePicks.chosen
    ScopePicks.chosen = emptyMap()
    val (docs, _) = reconcileScope(ui, accounts, names, picks)
    val (nextUi, nextAccounts) = docs
    if (nextUi.encodeUi() != ui.encodeUi()) {
        val text = nextUi.encodeUi()
        writeGlobalRecord(context, text)
        SettingsHub.adoptRecord(text)
        ToolbarStore.install(nextUi)
    }
    for ((id, settings) in nextAccounts) {
        val previous = accounts[id] ?: continue
        val encoded = settings.encode()
        if (encoded == previous.encode()) continue
        store.putBlob(id, encoded)
        SettingsHub.replaceAccount(id, settings)
    }
}
