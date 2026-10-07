package org.dlang.liveimap.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dlang.liveimap.R
import org.dlang.liveimap.ui.UpTopAppBar
import org.dlang.liveimap.ui.index.AdvancedCombiner
import org.dlang.liveimap.ui.index.AdvancedStep
import org.dlang.liveimap.ui.index.encodeAdvancedQuery
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSearchScreen(
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
) {
    var combiner by remember { mutableStateOf(AdvancedCombiner.And) }
    var rows by remember { mutableStateOf(listOf(AdvancedDraft("Subject", "", false))) }
    var openRow by remember { mutableIntStateOf(-1) }
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
            TextButton(
                onClick = {
                    val kept = ArrayList<AdvancedStep>()
                    for (row in rows) {
                        if (row.kind in valuedAdvancedKinds) {
                            if (row.value.isBlank()) continue
                            kept.add(AdvancedStep(row.negated, row.kind, row.value))
                        } else {
                            kept.add(AdvancedStep(row.negated, row.kind, ""))
                        }
                    }
                    if (kept.isEmpty()) return@TextButton
                    val text = encodeAdvancedQuery(combiner, kept) ?: return@TextButton
                    onSearch(text)
                },
            ) { Text(stringResource(R.string.index_search)) }
        }
    }
}
