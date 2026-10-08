package org.dlang.liveimap.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.dlang.liveimap.R
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.ui.UpTopAppBar
import org.dlang.liveimap.ui.index.AdvancedCombiner
import org.dlang.liveimap.ui.index.AdvancedStep
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.countHits
import org.dlang.liveimap.ui.index.deleteAdvanced
import org.dlang.liveimap.ui.index.encodeAdvancedQuery
import org.dlang.liveimap.ui.index.saveAdvanced
import org.dlang.liveimap.ui.mailScreenInsets

private data class AdvancedField(
    val kind: String,
    val labelRes: Int,
)

private val advancedFields = listOf(
    AdvancedField("New", R.string.index_filter_new),
    AdvancedField("NotNew", R.string.index_filter_not_new),
    AdvancedField("Deleted", R.string.index_filter_deleted),
    AdvancedField("NotDeleted", R.string.index_filter_not_deleted),
    AdvancedField("Answered", R.string.index_filter_answered),
    AdvancedField("NotAnswered", R.string.index_filter_not_answered),
    AdvancedField("Important", R.string.index_filter_important),
    AdvancedField("NotImportant", R.string.index_filter_not_important),
    AdvancedField("Forwarded", R.string.index_filter_forwarded),
    AdvancedField("NotForwarded", R.string.index_filter_not_forwarded),
    AdvancedField("From", R.string.index_filter_from),
    AdvancedField("To", R.string.index_filter_to),
    AdvancedField("Cc", R.string.index_filter_cc),
    AdvancedField("Subject", R.string.index_filter_subject),
    AdvancedField("Recipient", R.string.index_filter_recipient),
    AdvancedField("Participant", R.string.index_search_participating),
    AdvancedField("Since", R.string.index_filter_since),
    AdvancedField("Before", R.string.index_filter_before),
    AdvancedField("On", R.string.index_filter_on),
    AdvancedField("Age", R.string.index_filter_age),
    AdvancedField("Larger", R.string.index_filter_larger),
    AdvancedField("Smaller", R.string.index_filter_smaller),
    AdvancedField("Keyword", R.string.index_filter_keyword),
    AdvancedField("NotKeyword", R.string.index_filter_not_keyword),
    AdvancedField("Body", R.string.index_search_body),
    AdvancedField("Text", R.string.index_search_full_text),
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
    "NotKeyword",
)

private data class AdvancedDraft(
    val kind: String,
    val value: String,
    val negated: Boolean,
)

@Composable
private fun advancedFieldLabel(kind: String): String {
    val field = advancedFields.first { it.kind == kind }
    return stringResource(field.labelRes)
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
    var accountId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        accountId = store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    AdvancedSearchLoaded(
        accountId = id,
        mailbox = mailbox,
        store = store,
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
    var combiner by remember { mutableStateOf(AdvancedCombiner.And) }
    var rows by remember { mutableStateOf(listOf(AdvancedDraft("Subject", "", false))) }
    var openRow by remember { mutableIntStateOf(-1) }
    var scope by remember { mutableStateOf(SearchScope.Current) }
    var counting by remember { mutableStateOf(false) }
    var countProgress by remember { mutableStateOf<String?>(null) }
    var countNumber by remember { mutableStateOf<Int?>(null) }
    var skippedLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var countError by remember { mutableStateOf<String?>(null) }
    val cancelCount = remember { mutableStateOf(false) }
    val scopeRunner = rememberCoroutineScope()
    val settingsScope = rememberCoroutineScope()
    var saveNameOpen by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var savedOpen by remember { mutableStateOf(false) }
    var savedItems by remember { mutableStateOf<List<SavedAdvanced>>(emptyList()) }
    val session = remember(accountId) { mailSession(accountId) }
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
        return encodeAdvancedQuery(combiner, kept)
    }
    Scaffold(topBar = { UpTopAppBar(stringResource(R.string.index_search_advanced), onBack) }) { innerPadding ->
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
                    label = { Text(stringResource(R.string.index_search_and)) },
                )
                FilterChip(
                    selected = combiner == AdvancedCombiner.Or,
                    onClick = { combiner = AdvancedCombiner.Or },
                    label = { Text(stringResource(R.string.index_search_or)) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scope == SearchScope.Current,
                    onClick = { scope = SearchScope.Current },
                    enabled = !counting,
                    label = { Text(stringResource(R.string.index_search_scope_folder)) },
                )
                FilterChip(
                    selected = scope == SearchScope.Subtree,
                    onClick = { scope = SearchScope.Subtree },
                    enabled = !counting,
                    label = { Text(stringResource(R.string.index_search_scope_below)) },
                )
                FilterChip(
                    selected = scope == SearchScope.Subscribed,
                    onClick = { scope = SearchScope.Subscribed },
                    enabled = !counting,
                    label = { Text(stringResource(R.string.index_search_scope_subscribed)) },
                )
                FilterChip(
                    selected = scope == SearchScope.All,
                    onClick = { scope = SearchScope.All },
                    enabled = !counting,
                    label = { Text(stringResource(R.string.index_search_scope_all)) },
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
                        Text(
                            text = advancedFieldLabel(row.kind),
                            modifier = Modifier.clickable { openRow = index },
                        )
                        DropdownMenu(
                            expanded = openRow == index,
                            onDismissRequest = { openRow = -1 },
                        ) {
                            for (field in advancedFields) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(field.labelRes)) },
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
                    Text(stringResource(R.string.index_search_not))
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
                        ) { Text(stringResource(R.string.index_search_remove_term)) }
                    }
                }
                if (row.kind == "Body" || row.kind == "Text") {
                    Text(stringResource(R.string.index_search_body_cost))
                }
            }
            TextButton(
                onClick = {
                    rows = rows + AdvancedDraft("Subject", "", false)
                },
            ) { Text(stringResource(R.string.index_search_add_term)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !counting,
                    onClick = {
                        val text = encodedQuery() ?: return@TextButton
                        onSearch(text, scope)
                    },
                ) { Text(stringResource(R.string.index_search)) }
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
                        scopeRunner.launch {
                            try {
                                val result = countHits(
                                    session,
                                    mailbox,
                                    text,
                                    chosen,
                                    cancelled = { cancelCount.value },
                                ) { current, total ->
                                    countProgress = context.getString(
                                        R.string.index_search_folder_progress,
                                        current,
                                        total,
                                    )
                                }
                                countNumber = result.count
                                skippedLines = result.skipped.map { name ->
                                    context.getString(R.string.index_search_skipped, name)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: MailFailure) {
                                countError = error.text
                            } finally {
                                counting = false
                                countProgress = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.index_search_count)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (encodedQuery() == null) return@TextButton
                        saveName = ""
                        saveNameOpen = true
                    },
                ) { Text(stringResource(R.string.index_search_save)) }
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
                    ) { Text(stringResource(R.string.index_search_saved)) }
                    DropdownMenu(
                        expanded = savedOpen,
                        onDismissRequest = { savedOpen = false },
                    ) {
                        if (savedItems.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.index_search_none)) },
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            savedItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DropdownMenuItem(
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
                                                        store.save(stored.copy(savedAdvanced = next))
                                                    } catch (error: CancellationException) {
                                                        throw error
                                                    } catch (_: Exception) {
                                                        return@launch
                                                    }
                                                    savedItems = next
                                                }
                                            }
                                        },
                                    ) { Text(stringResource(R.string.index_search_remove)) }
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
                        Text(stringResource(R.string.index_search_cancel))
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
                    label = { Text(stringResource(R.string.index_search_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = saveName
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
                                    store.save(stored.copy(savedAdvanced = next))
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    return@launch
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.index_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { saveNameOpen = false }) {
                    Text(stringResource(R.string.index_search_cancel))
                }
            },
        )
    }
}
