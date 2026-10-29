package org.dlang.liveimap.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.parseAccountId
import org.dlang.liveimap.ui.toolbar.ToolbarStore

data class ExtraStoredKey(val blob: String, val key: String)

data class MoveOption(val label: String, val value: String)

data class MoveQuestion(val key: String, val options: List<MoveOption>)

data class LoadQuestions(
    val extras: List<ExtraStoredKey>,
    val moves: List<MoveQuestion>,
    val warning: String,
)

/**
 * Decoded UI blob and each existing account. Snapshots are replaced whole.
 * Nothing is published until the load check has one answer.
 */
object SettingsHub {
    private val uiState = MutableStateFlow(Settings())
    private val accountState = MutableStateFlow<Map<String, Settings>>(emptyMap())
    private val openState = MutableStateFlow<String?>(null)
    private val questionState = MutableStateFlow<LoadQuestions?>(null)
    private val noticeState = MutableStateFlow<String?>(null)

    val ui: StateFlow<Settings> = uiState
    val accounts: StateFlow<Map<String, Settings>> = accountState
    val openId: StateFlow<String?> = openState
    val questions: StateFlow<LoadQuestions?> = questionState
    val notice: StateFlow<String?> = noticeState

    @Volatile private var phase = 0
    private val prepareGate = Mutex()
    private var pendingUi: LinkedHashMap<String, String>? = null
    private var pendingAccounts: LinkedHashMap<String, LinkedHashMap<String, String>>? = null
    private var pendingNames: Map<String, String> = emptyMap()

    fun uiNow(): Settings = uiState.value

    fun accountNow(accountId: String): Settings? = accountState.value[parseAccountId(accountId)]

    fun openAccount(): Settings? {
        val id = parseAccountId(openState.value) ?: return null
        return accountState.value[id]
    }

    fun selectOpen(accountId: String) {
        val id = parseAccountId(accountId) ?: return
        if (accountState.value.containsKey(id)) openState.value = id
    }

    fun replaceAccount(accountId: String, settings: Settings) {
        val id = parseAccountId(accountId) ?: return
        val ui = uiState.value
        val next = settings.withUiOf(ui)
        accountState.value = accountState.value + (id to next)
        if (accountState.value.size == 1) openState.value = id
    }

    fun clearNotice() {
        noticeState.value = null
    }

    suspend fun prepare(context: Context) {
        prepareGate.withLock {
        if (phase != 0) return@withLock
        installGeneratedSettings()
        val store = AccountStore.shared(context)
        val blobs = store.storedBlobs()
        val uiMap = linesOf(readGlobalRecord(context))
        val accounts = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((id, text) in blobs) {
            val parsed = parseAccountId(id) ?: continue
            accounts[parsed] = linesOf(text)
        }
        val names = store.listAccounts().associate { it.id to it.name }
        val proposal = propose(uiMap, accounts, names)
        if (proposal.questions.extras.isEmpty() && proposal.questions.moves.isEmpty()) {
            finish(context, proposal.ui, proposal.accounts, proposal.warning)
        } else {
            pendingUi = proposal.ui
            pendingAccounts = proposal.accounts
            pendingNames = names
            questionState.value = proposal.questions
            phase = 1
        }
        }
    }

    suspend fun answer(context: Context, removeExtras: Boolean, picks: Map<String, String>) {
        val ui = pendingUi ?: return
        val accounts = pendingAccounts ?: return
        val questions = questionState.value
        if (removeExtras && questions != null) {
            for (extra in questions.extras) {
                if (extra.blob == "ui") ui.remove(extra.key) else accounts[extra.blob]?.remove(extra.key)
            }
        }
        if (questions != null) {
            for (move in questions.moves) {
                val value = picks[move.key] ?: move.options.firstOrNull()?.value ?: continue
                applyMove(move.key, value, ui, accounts)
            }
        }
        questionState.value = null
        pendingUi = null
        pendingAccounts = null
        finish(context, ui, accounts, "")
    }

    suspend fun saveUiKeys(context: Context, draft: Settings, keys: Set<String>) {
        if (keys.isEmpty()) return
        var next = decodeSettings(readGlobalRecord(context))
        for (key in keys) next = next.withSetting(key, SettingValue(draft.getSetting(key)))
        val text = next.encodeUi()
        writeGlobalRecord(context, text)
        publishUi(next)
        if ("toolbars" in keys) ToolbarStore.install(next.toolbars)
    }

    suspend fun noteAccountWritten(context: Context, accountId: String, settings: Settings, first: Boolean) {
        val id = parseAccountId(accountId) ?: return
        if (first) {
            var ui = decodeSettings(readGlobalRecord(context))
            ui = ui.withSetting("defaultAccount", SettingValue(id))
            writeGlobalRecord(context, ui.encodeUi())
            publishUi(ui)
        }
        replaceAccount(id, settings)
        openState.value = resolveOpen(accountState.value.keys, uiState.value.defaultAccount)
    }

    suspend fun afterAccountRemoved(context: Context, accountId: String) {
        val id = parseAccountId(accountId) ?: return
        val left = accountState.value - id
        accountState.value = left
        org.dlang.liveimap.engine.TrafficLog.close(id)
        var ui = decodeSettings(readGlobalRecord(context))
        val wasDefault = ui.defaultAccount == id
        if (left.size == 1) {
            val only = left.keys.first()
            ui = ui.withSetting("defaultAccount", SettingValue(only))
            writeGlobalRecord(context, ui.encodeUi())
            publishUi(ui)
            openState.value = only
        } else if (wasDefault) {
            ui = ui.withSetting("defaultAccount", SettingValue(""))
            writeGlobalRecord(context, ui.encodeUi())
            publishUi(ui)
            openState.value = null
            noticeState.value = "default-cleared"
        } else {
            openState.value = resolveOpen(left.keys, ui.defaultAccount)
        }
    }

    private suspend fun finish(
        context: Context,
        uiMap: LinkedHashMap<String, String>,
        accounts: LinkedHashMap<String, LinkedHashMap<String, String>>,
        warning: String,
    ) {
        val ids = accounts.keys.toList()
        if (ids.size == 1) uiMap["defaultAccount"] = ids[0]
        val uiText = textOf(uiMap)
        writeGlobalRecord(context, uiText)
        val store = AccountStore.shared(context)
        store.onAccount {
            for ((id, map) in accounts) store.putBlob(id, textOf(map))
        }
        val ui = decodeSettings(uiText)
        val published = LinkedHashMap<String, Settings>()
        for ((id, map) in accounts) {
            published[id] = decodeSettings(textOf(map)).withUiOf(ui)
        }
        uiState.value = ui
        accountState.value = published
        openState.value = resolveOpen(published.keys, ui.defaultAccount)
        if (warning.isNotEmpty()) noticeState.value = warning
        ToolbarStore.install(ui.toolbars)
        phase = 2
    }

    private fun publishUi(ui: Settings) {
        uiState.value = ui
        accountState.value = accountState.value.mapValues { (_, settings) -> settings.withUiOf(ui) }
    }

    /** A record just written to the UI blob. Publishes that snapshot and installs toolbars. */
    internal fun adoptRecord(record: String) {
        val ui = decodeSettings(record)
        publishUi(ui)
        ToolbarStore.install(ui.toolbars)
    }

    private fun resolveOpen(ids: Collection<String>, defaultAccount: String): String? {
        if (ids.isEmpty()) return null
        if (ids.size == 1) return ids.first()
        val id = parseAccountId(defaultAccount) ?: return null
        return if (id in ids) id else null
    }
}

internal data class Proposal(
    val ui: LinkedHashMap<String, String>,
    val accounts: LinkedHashMap<String, LinkedHashMap<String, String>>,
    val questions: LoadQuestions,
    val warning: String,
)

internal fun propose(
    uiSource: Map<String, String>,
    accountSource: Map<String, Map<String, String>>,
    names: Map<String, String>,
): Proposal {
    val fresh = Settings()
    val defaults = linesOf(fresh.encode() + "\n" + fresh.encodeUi())
    val ui = LinkedHashMap(uiSource)
    val accounts = LinkedHashMap<String, LinkedHashMap<String, String>>()
    for ((id, map) in accountSource) accounts[id] = LinkedHashMap(map)
    val warnings = ArrayList<String>()
    fun repair(blob: String, map: MutableMap<String, String>) {
        val bad = ArrayList<String>()
        for ((key, value) in map) {
            val spec = settingsRegistry[key] ?: continue
            if (!globalValueOk(spec, value)) {
                map[key] = defaults[key] ?: ""
                bad.add(key)
            }
        }
        if (bad.isNotEmpty()) warnings.add("$blob: " + bad.joinToString(", "))
    }
    repair("UI settings", ui)
    for ((id, map) in accounts) repair(names[id] ?: id, map)
    val extras = ArrayList<ExtraStoredKey>()
    fun unknowns(blob: String, map: Map<String, String>) {
        for (key in map.keys) if (settingsRegistry[key] == null) extras.add(ExtraStoredKey(blob, key))
    }
    unknowns("ui", ui)
    for ((id, map) in accounts) unknowns(id, map)
    val moves = ArrayList<MoveQuestion>()
    for ((key, spec) in settingsRegistry) {
        if (spec.leaf.derived) continue
        val inUi = ui[key]
        val inAccounts = accounts.mapNotNull { (id, map) -> map[key]?.let { id to it } }
        if (spec.scope == SettingScope.Ui) {
            if (inUi != null) {
                for ((id, _) in inAccounts) accounts[id]?.remove(key)
            } else if (inAccounts.isNotEmpty()) {
                val distinct = inAccounts.map { it.second }.distinct()
                if (distinct.size == 1) {
                    ui[key] = distinct[0]
                    for ((id, _) in inAccounts) accounts[id]?.remove(key)
                } else {
                    moves.add(questionFor(key, inAccounts, names))
                }
            }
        } else if (accounts.isNotEmpty() && inUi != null) {
            val found = ArrayList<Pair<String, String>>()
            found.add("ui" to inUi)
            found.addAll(inAccounts)
            val distinct = found.map { it.second }.distinct()
            if (distinct.size == 1) {
                val value = distinct[0]
                for (id in accounts.keys) accounts.getValue(id)[key] = value
                ui.remove(key)
            } else if (distinct.size > 1) {
                moves.add(questionFor(key, found, names))
            }
        }
    }
    val warning = warnings.joinToString("\n")
    return Proposal(ui, accounts, LoadQuestions(extras, moves, warning), warning)
}

private fun questionFor(key: String, found: List<Pair<String, String>>, names: Map<String, String>): MoveQuestion {
    val options = found.groupBy { it.second }.map { (value, holders) ->
        val label = holders.joinToString(", ") { (blob, _) -> if (blob == "ui") "UI settings" else names[blob] ?: blob }
        MoveOption(label, value)
    }
    return MoveQuestion(key, options)
}

internal fun applyMove(
    key: String,
    value: String,
    ui: MutableMap<String, String>,
    accounts: Map<String, MutableMap<String, String>>,
) {
    val spec = settingsRegistry[key] ?: return
    if (spec.scope == SettingScope.Ui) {
        ui[key] = value
        for (map in accounts.values) map.remove(key)
    } else {
        val fromUi = ui[key]
        ui.remove(key)
        if (fromUi != null && value == fromUi) {
            for (map in accounts.values) map[key] = value
        }
    }
}

internal fun linesOf(text: String): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    var openKey: String? = null
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        if (openKey == "inboundRules" && '\t' in line) {
            map[openKey] = map.getValue(openKey) + "\n" + line
            continue
        }
        val eq = line.indexOf('=')
        if (eq <= 0) {
            openKey = null
            continue
        }
        val key = line.substring(0, eq)
        map[key] = line.substring(eq + 1)
        openKey = key
    }
    return map
}

private fun textOf(map: Map<String, String>): String =
    map.entries.joinToString("\n") { (key, value) -> "$key=$value" }
