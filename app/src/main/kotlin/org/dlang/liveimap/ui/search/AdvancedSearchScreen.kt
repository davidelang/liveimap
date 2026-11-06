package org.dlang.liveimap.ui.search
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.i18n.P
import org.dlang.liveimap.i18n.Phrase
import org.dlang.liveimap.i18n.phrase

import org.dlang.liveimap.session.folderInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.ObscuredTouchFilter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.dlang.liveimap.ui.help.HelpDropdownMenuItem
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.overAskThreshold
import org.dlang.liveimap.ui.UpTopAppBar
import org.dlang.liveimap.ui.index.AdvancedCombiner
import org.dlang.liveimap.ui.index.AdvancedStep
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.advancedHasBody
import org.dlang.liveimap.ui.index.countHits
import org.dlang.liveimap.ui.index.deleteAdvanced
import org.dlang.liveimap.ui.index.encodeAdvancedQuery
import org.dlang.liveimap.ui.index.mailboxesFor
import org.dlang.liveimap.ui.index.saveAdvanced
import org.dlang.liveimap.ui.LocalScreenAccounts
import org.dlang.liveimap.ui.mailScreenInsets

private data class AdvancedField(
    val kind: String,
    val labelRes: Phrase,
)

private val advancedFields = listOf(
    AdvancedField("New", phrase("New")),
    AdvancedField("Deleted", phrase("Deleted")),
    AdvancedField("Answered", phrase("Answered")),
    AdvancedField("Important", phrase("Important")),
    AdvancedField("Forwarded", phrase("Forwarded")),
    AdvancedField("From", phrase("From")),
    AdvancedField("To", phrase("To")),
    AdvancedField("Cc", phrase("Cc")),
    AdvancedField("Subject", phrase("Subject")),
    AdvancedField("Recipient", phrase("Recipient")),
    AdvancedField("Participant", phrase("Participating")),
    AdvancedField("Since", phrase("Since")),
    AdvancedField("Before", phrase("Before")),
    AdvancedField("On", phrase("On", name = "index_filter_on")),
    AdvancedField("Age", phrase("Age")),
    AdvancedField("Larger", phrase("Larger")),
    AdvancedField("Smaller", phrase("Smaller")),
    AdvancedField("Keyword", phrase("Keyword")),
    AdvancedField("Body", phrase("Body")),
    AdvancedField("Text", phrase("Full text")),
)

private val valuedAdvancedKinds = setOf(
    "From",
    "To",
    "Cc",
    "Subject",
    "Text",
    "Body",
    "Recipient",
    "Participant",
    "Since",
    "Before",
    "On",
    "Age",
    "Larger",
    "Smaller",
    "Keyword",
)

private fun positiveAdvancedKind(kind: String): String = when (kind) {
    "NotNew" -> "New"
    "NotDeleted" -> "Deleted"
    "NotAnswered" -> "Answered"
    "NotImportant" -> "Important"
    "NotForwarded" -> "Forwarded"
    "NotKeyword" -> "Keyword"
    else -> kind
}

private data class AdvancedDraft(
    val kind: String,
    val value: String,
    val negated: Boolean,
)

private val defaultDraftRows = listOf(AdvancedDraft("Subject", "", false))

private val combinerSaver = Saver<AdvancedCombiner, String>(
    save = { value -> value.name },
    restore = { name ->
        AdvancedCombiner.entries.firstOrNull { entry -> entry.name == name } ?: AdvancedCombiner.And
    },
)

private val scopeSaver = Saver<SearchScope, String>(
    save = { value -> value.name },
    restore = { name ->
        SearchScope.entries.firstOrNull { entry -> entry.name == name } ?: SearchScope.Current
    },
)

private val draftRowSaver = Saver<List<AdvancedDraft>, String>(
    save = { rows ->
        rows.joinToString("\n") { row ->
            val negated = if (row.negated) "1" else "0"
            "${row.kind}\t${row.value}\t$negated"
        }
    },
    restore = { saved -> restoreDraftRows(saved) },
)

private fun restoreDraftRows(saved: String): List<AdvancedDraft> {
    if (saved.isEmpty()) return defaultDraftRows
    val known = HashSet<String>()
    for (field in advancedFields) known.add(field.kind)
    val rows = ArrayList<AdvancedDraft>()
    for (line in saved.split('\n')) {
        val parts = line.split('\t')
        if (parts.size != 3) return defaultDraftRows
        val storedKind = parts[0]
        val kind = positiveAdvancedKind(storedKind)
        if (kind !in known) return defaultDraftRows
        val negated = when (parts[2]) {
            "0" -> false
            "1" -> true
            else -> return defaultDraftRows
        }
        val notOn = if (storedKind != kind) true else negated
        rows.add(AdvancedDraft(kind, parts[1], notOn))
    }
    if (rows.isEmpty()) return defaultDraftRows
    return rows
}

internal fun bodyPromptExists(scope: SearchScope, currentExists: Int, scopeExists: Int): Int =
    if (scope == SearchScope.Current) currentExists else scopeExists

internal fun bodyPromptSkipped(scope: SearchScope, mailbox: String, skip: Collection<String>): Boolean =
    scope == SearchScope.Current && mailbox in skip

@Composable
private fun advancedFieldLabel(kind: String): String {
    val field = advancedFields.first { it.kind == positiveAdvancedKind(kind) }
    return S(field.labelRes)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdvancedSearchScreen(
    mailbox: String,
    onSearch: (String, SearchScope) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (accountId == null) accountId = store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    AdvancedSearchLoaded(
        accountId = id,
        mailbox = mailbox,
        store = remember(id) { store.forAccount(id) },
        onSearch = onSearch,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSearchLoaded(
    accountId: String,
    mailbox: String,
    store: SettingsStore,
    onSearch: (String, SearchScope) -> Unit,
    onBack: () -> Unit,
) {
    LocalScreenAccounts.current.Register(accountId)
    var combiner by rememberSaveable(stateSaver = combinerSaver) { mutableStateOf(AdvancedCombiner.And) }
    var fuzzyOn by rememberSaveable { mutableStateOf(false) }
    var rows by rememberSaveable(stateSaver = draftRowSaver) { mutableStateOf(defaultDraftRows) }
    var openRow by remember { mutableIntStateOf(-1) }
    var scope by rememberSaveable(stateSaver = scopeSaver) { mutableStateOf(SearchScope.Current) }
    var counting by remember { mutableStateOf(false) }
    var countProgress by remember { mutableStateOf<String?>(null) }
    var countNumber by remember { mutableStateOf<Int?>(null) }
    var skippedLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var countError by remember { mutableStateOf<String?>(null) }
    var countAsk by remember { mutableStateOf(false) }
    var countExists by remember { mutableIntStateOf(0) }
    var countBodyAllowed by remember { mutableStateOf(false) }
    val countChoice = remember { Channel<BodyAsk>(Channel.CONFLATED) }
    val cancelCount = remember { mutableStateOf(false) }
    val scopeRunner = rememberCoroutineScope()
    val settingsScope = rememberCoroutineScope()
    var saveNameOpen by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var savedOpen by remember { mutableStateOf(false) }
    var savedItems by remember { mutableStateOf<List<SavedAdvanced>>(emptyList()) }
    val session = remember(accountId) { mailSession(accountId) }
    val fuzzyAdvertised = session.featureCaps.searchFuzzy
    val context = LocalContext.current
    fun encodedQuery(): String? {
        val kept = ArrayList<AdvancedStep>()
        for (row in rows) {
            if (row.kind in valuedAdvancedKinds) {
                if (row.value.isBlank()) continue
                kept.add(AdvancedStep(row.negated, row.kind, row.value))
            } else {
                kept.add(AdvancedStep(row.negated, row.kind, ""))
            }
        }
        if (kept.isEmpty()) return null
        return encodeAdvancedQuery(combiner, kept, fuzzyAdvertised && fuzzyOn)
    }
    Scaffold(topBar = { UpTopAppBar(S("Advanced", name = "index_search_advanced"), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .windowInsetsPadding(
                    mailScreenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = combiner == AdvancedCombiner.And,
                    onClick = { combiner = AdvancedCombiner.And },
                    label = { Text(S("And")) },
                )
                FilterChip(
                    selected = combiner == AdvancedCombiner.Or,
                    onClick = { combiner = AdvancedCombiner.Or },
                    label = { Text(S("Or")) },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(S("Fuzzy"))
                Switch(
                    checked = fuzzyAdvertised && fuzzyOn,
                    onCheckedChange = { checked ->
                        if (fuzzyAdvertised) fuzzyOn = checked
                    },
                    enabled = fuzzyAdvertised,
                )
            }
            if (!fuzzyAdvertised) {
                Text(S("SEARCH=FUZZY is not advertised."))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scope == SearchScope.Current,
                    onClick = { scope = SearchScope.Current },
                    enabled = !counting,
                    label = { Text(S("This folder")) },
                )
                FilterChip(
                    selected = scope == SearchScope.Subtree,
                    onClick = { scope = SearchScope.Subtree },
                    enabled = !counting,
                    label = { Text(S("This folder and below")) },
                )
                FilterChip(
                    selected = scope == SearchScope.Subscribed,
                    onClick = { scope = SearchScope.Subscribed },
                    enabled = !counting,
                    label = { Text(S("Subscribed")) },
                )
                FilterChip(
                    selected = scope == SearchScope.All,
                    onClick = { scope = SearchScope.All },
                    enabled = !counting,
                    label = { Text(S("All folders")) },
                )
            }
            rows.forEachIndexed { index, row ->
                val needsValue = row.kind in valuedAdvancedKinds
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        TextButton(onClick = { openRow = index }) {
                            Text(advancedFieldLabel(row.kind))
                        }
                        DropdownMenu(
                            expanded = openRow == index,
                            onDismissRequest = { openRow = -1 },
                        ) {
                            ObscuredTouchFilter()
                            for (field in advancedFields) {
                                val fieldLabel = S(field.labelRes)
                                HelpDropdownMenuItem(
                                    label = fieldLabel,
                                    help = S("Uses this field in the query."),
                                    page = "messages.html",
                                    text = { Text(fieldLabel) },
                                    onClick = {
                                        openRow = -1
                                        rows = rows.mapIndexed { rowIndex, current ->
                                            if (rowIndex == index) current.copy(kind = field.kind) else current
                                        }
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = if (needsValue) row.value else "",
                        onValueChange = { next ->
                            if (!needsValue) return@OutlinedTextField
                            rows = rows.mapIndexed { rowIndex, current ->
                                if (rowIndex == index) current.copy(value = next) else current
                            }
                        },
                        enabled = needsValue,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(S("Not"))
                    Switch(
                        checked = row.negated,
                        onCheckedChange = { checked ->
                            rows = rows.mapIndexed { rowIndex, current ->
                                if (rowIndex == index) current.copy(negated = checked) else current
                            }
                        },
                    )
                    if (rows.size > 1) {
                        TextButton(
                            onClick = {
                                openRow = -1
                                rows = rows.filterIndexed { rowIndex, _ -> rowIndex != index }
                            },
                        ) { Text(S("Remove", name = "index_search_remove_term")) }
                    }
                }
                if (row.kind == "Body" || row.kind == "Text") {
                    Text(S("Searches every message body; slow on large folders."))
                }
            }
            TextButton(
                onClick = {
                    rows = rows + AdvancedDraft("Subject", "", false)
                },
            ) { Text(S("Add term")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !counting,
                    onClick = {
                        val text = encodedQuery() ?: return@TextButton
                        onSearch(text, scope)
                    },
                ) { Text(S("Search")) }
                TextButton(
                    enabled = !counting,
                    onClick = {
                        val text = encodedQuery() ?: return@TextButton
                        val chosen = scope
                        cancelCount.value = false
                        counting = true
                        countProgress = null
                        countNumber = null
                        skippedLines = emptyList()
                        countError = null
                        countBodyAllowed = false
                        scopeRunner.launch {
                            try {
                                if (advancedHasBody(text) && !countBodyAllowed) {
                                    val settings = try {
                                        store.load()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        countError = error.message ?: "not connected"
                                        return@launch
                                    }
                                    if (!bodyPromptSkipped(chosen, mailbox, settings.searchAskSkip)) {
                                        val exists = try {
                                            if (chosen == SearchScope.Current) {
                                                // F123: the count of the folder being searched, not the session's
                                                val currentExists = session.folderInfo(
                                                    org.dlang.liveimap.session.FolderAt(mailbox),
                                                ).exists
                                                bodyPromptExists(chosen, currentExists, 0)
                                            } else {
                                                var sum = 0
                                                for (box in mailboxesFor(chosen, mailbox, session)) {
                                                    try {
                                                        sum += session.folderInfo(
                                                            org.dlang.liveimap.session.FolderAt(box),
                                                        ).exists
                                                    } catch (error: CancellationException) {
                                                        throw error
                                                    } catch (_: MailFailure) {
                                                    }
                                                }
                                                bodyPromptExists(chosen, 0, sum)
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: MailFailure) {
                                            countError = error.text
                                            return@launch
                                        }
                                        if (overAskThreshold(exists, settings.askSearch)) {
                                            countExists = exists
                                            countAsk = true
                                            val answer = countChoice.receive()
                                            if (answer == BodyAsk.Cancel) return@launch
                                            if (answer == BodyAsk.Always) {
                                                try {
                                                    store.update { it.copy(searchAskSkip = it.searchAskSkip + mailbox) }
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (error: Exception) {
                                                    countError = error.message ?: "not connected"
                                                    return@launch
                                                }
                                            }
                                            countBodyAllowed = true
                                        }
                                    }
                                }
                                val result = countHits(
                                    session,
                                    mailbox,
                                    text,
                                    chosen,
                                    cancelled = { cancelCount.value },
                                ) { current, total ->
                                    countProgress = S(context, "Folder %1\$d of %2\$d",
                                        current,
                                        total,
                                    )
                                }
                                countNumber = result.count
                                skippedLines = result.skipped.map { name ->
                                    S(context, "Skipped %1\$s.", name)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                countError = error.text
                            } finally {
                                counting = false
                                countProgress = null
                                countBodyAllowed = false
                            }
                        }
                    },
                ) { Text(S("Count")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (encodedQuery() == null) return@TextButton
                        saveName = ""
                        saveNameOpen = true
                    },
                ) { Text(S("Save", name = "index_search_save")) }
                Box {
                    TextButton(
                        onClick = {
                            settingsScope.launch {
                                val stored = try {
                                    store.load()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    return@launch
                                }
                                savedItems = stored.savedAdvanced
                                savedOpen = true
                            }
                        },
                    ) { Text(S("Saved")) }
                    DropdownMenu(
                        expanded = savedOpen,
                        onDismissRequest = { savedOpen = false },
                    ) {
                        ObscuredTouchFilter()
                        if (savedItems.isEmpty()) {
                            val none = S("None", name = "index_search_none")
                            HelpDropdownMenuItem(
                                label = none,
                                help = S("Clears the saved search."),
                                page = "messages.html",
                                text = { Text(none) },
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            savedItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HelpDropdownMenuItem(
                                        label = item.name,
                                        help = S("Runs this saved search."),
                                        page = "messages.html",
                                        text = { Text(item.name) },
                                        onClick = {
                                            savedOpen = false
                                            onSearch(item.text, item.scope)
                                        },
                                    )
                                    TextButton(
                                        onClick = {
                                            settingsScope.launch {
                                                val stored = try {
                                                    store.load()
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (_: Exception) {
                                                    return@launch
                                                }
                                                val next = deleteAdvanced(stored.savedAdvanced, item.name)
                                                if (next != stored.savedAdvanced) {
                                                    try {
                                                        store.update { it.copy(savedAdvanced = next) }
                                                    } catch (error: CancellationException) {
                                                        throw error
                                                    } catch (_: Exception) {
                                                        return@launch
                                                    }
                                                    savedItems = next
                                                }
                                            }
                                        },
                                    ) { Text(S("Remove")) }
                                }
                            }
                        }
                    }
                }
            }
            val progress = countProgress
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = progress, modifier = Modifier.weight(1f))
                    TextButton(onClick = { cancelCount.value = true }) {
                        Text(S("Cancel", name = "index_search_cancel"))
                    }
                }
            }
            val shownCount = countNumber
            if (shownCount != null) {
                Text(text = shownCount.toString())
            }
            val shownError = countError
            if (shownError != null) {
                Text(text = shownError)
            }
            for (line in skippedLines) {
                Text(text = line)
            }
        }
    }
    if (saveNameOpen) {
        AlertDialog(
            onDismissRequest = { saveNameOpen = false },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text(S("Name")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = saveName
                        if (name.isBlank()) return@TextButton
                        val text = encodedQuery()
                        val chosen = scope
                        saveNameOpen = false
                        if (text == null) return@TextButton
                        settingsScope.launch {
                            val stored = try {
                                store.load()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                return@launch
                            }
                            val next = saveAdvanced(stored.savedAdvanced, name, text, chosen)
                            if (next != stored.savedAdvanced) {
                                try {
                                    store.update { it.copy(savedAdvanced = next) }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    return@launch
                                }
                            }
                        }
                    },
                ) { Text(S("OK")) }
            },
            dismissButton = {
                TextButton(onClick = { saveNameOpen = false }) {
                    Text(S("Cancel", name = "index_search_cancel"))
                }
            },
        )
    }
    if (countAsk) {
        AlertDialog(
            onDismissRequest = {
                countAsk = false
                countChoice.trySend(BodyAsk.Cancel)
            },
            title = { Text(S("Search message text?")) },
            text = {
                Text(P(countExists, countExists, one = "This folder has %d message. A body or full-text search reads every message.", other = "This folder has %d messages. A body or full-text search reads every message."))
            },
            confirmButton = {
                TextButton(onClick = {
                    countAsk = false
                    countChoice.trySend(BodyAsk.Once)
                }) { Text(S("Just this once")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        countAsk = false
                        countChoice.trySend(BodyAsk.Always)
                    }) { Text(S("Always for this folder")) }
                    TextButton(onClick = {
                        countAsk = false
                        countChoice.trySend(BodyAsk.Cancel)
                    }) { Text(S("Cancel", name = "index_cancel")) }
                }
            },
        )
    }
}

private enum class BodyAsk {
    Once,
    Always,
    Cancel,
}
