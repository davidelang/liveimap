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
 * Agreed values move into the record the tree names. Two copies that disagree do not open a review line.
 * A shared setting missing from the shared document is left there, and the old account copies are dropped.
 * An account setting that is also stored on the shared document moves back only when every copy agrees.
 * A disagreement on that account setting overwrites neither side.
 * A corrupt document is not passed in here. This does not write.
 */
internal fun reconcileScope(
    ui: Settings,
    accounts: Map<String, Settings>,
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
            if (inUi == null && inAccounts.isNotEmpty()) {
                val distinct = inAccounts.map { it.second }.distinct()
                if (distinct.size == 1) nextUi = nextUi.putScopeToken(key, distinct[0])
            }
            for ((id, _) in inAccounts) nextAccounts[id] = nextAccounts.getValue(id).clearScopeKey(key)
        } else if (nextAccounts.isNotEmpty() && inUi != null) {
            val found = listOf("ui" to inUi) + inAccounts
            val distinct = found.map { it.second }.distinct()
            if (distinct.size == 1) {
                val value = distinct[0]
                for (id in nextAccounts.keys) nextAccounts[id] = nextAccounts.getValue(id).putScopeToken(key, value)
                nextUi = nextUi.clearScopeKey(key)
            }
        }
    }
    return (nextUi to nextAccounts) to lines
}

internal fun scopeDisagreementLines(draft: Settings, accountId: String?): List<PolicyLine> {
    if (settingsRegistry.isEmpty()) installGeneratedSettings()
    val accounts = LinkedHashMap(SettingsHub.accounts.value)
    val ui = if (accountId.isNullOrEmpty()) draft else SettingsHub.uiNow()
    if (!accountId.isNullOrEmpty()) {
        val id = parseAccountId(accountId) ?: return emptyList()
        accounts[id] = draft
    }
    return reconcileScope(ui, accounts).second
}

/** Writes agreed moves into the other records. Skips a document that does not parse. */
internal suspend fun settleScope(context: Context) {
    val store = AccountStore.shared(context)
    val uiText = readGlobalRecord(context)
    if (documentError(uiText) != null) {
        ScopePicks.chosen = emptyMap()
        return
    }
    val ui = decodeSettings(uiText)
    val accounts = LinkedHashMap<String, Settings>()
    for ((rawId, text) in store.storedBlobs()) {
        val id = parseAccountId(rawId) ?: continue
        if (documentError(text) != null) continue
        val user = store.accountFields(id) ?: emptyMap()
        accounts[id] = overlayAccountFields(decodeSettings(text), user)
    }
    ScopePicks.chosen = emptyMap()
    val (docs, _) = reconcileScope(ui, accounts)
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
