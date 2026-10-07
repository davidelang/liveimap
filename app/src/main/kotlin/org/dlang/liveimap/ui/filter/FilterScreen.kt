package org.dlang.liveimap.ui.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.engine.sieve.RuleCriterion
import org.dlang.liveimap.engine.sieve.RuleField
import org.dlang.liveimap.engine.sieve.SystemFlag
import org.dlang.liveimap.engine.sieve.emitSieve
import org.dlang.liveimap.engine.sieve.seedCriteria
import org.dlang.liveimap.settings.DataStoreSettingsStore

@Composable
fun FilterListScreen(onOpen: (Int) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<InboundRule>?>(null) }
    LifecycleStartEffect(store) {
        val job = scope.launch {
            val loaded = try {
                store.load().inboundRules
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (loaded != null) rules = loaded
        }
        onStopOrDispose { job.cancel() }
    }
    val loaded = rules ?: return
    val script = emitSieve(loaded)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (loaded.isEmpty()) {
            Text(
                text = stringResource(R.string.filter_none),
                modifier = Modifier.padding(16.dp),
            )
        }
        loaded.forEachIndexed { index, rule ->
            ListItem(
                headlineContent = { Text(criterionLabel(rule)) },
                supportingContent = { Text(actionLabel(rule)) },
                modifier = Modifier.clickable { onOpen(index) },
            )
        }
        HorizontalDivider()
        Text(
            text = stringResource(R.string.filter_script),
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        Surface(modifier = Modifier.padding(16.dp).fillMaxWidth(), tonalElevation = 1.dp) {
            Text(
                text = script,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun FilterEditorScreen(
    index: Int,
    seed: List<RuleCriterion>,
    onDone: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    var from by rememberSaveable { mutableStateOf(seedValue(seed, RuleField.From)) }
    var to by rememberSaveable { mutableStateOf(seedValue(seed, RuleField.To)) }
    var listId by rememberSaveable { mutableStateOf(seedValue(seed, RuleField.ListId)) }
    var subject by rememberSaveable { mutableStateOf(seedValue(seed, RuleField.Subject)) }
    var fileInto by rememberSaveable { mutableStateOf("") }
    var markRead by rememberSaveable { mutableStateOf(false) }
    var flagged by rememberSaveable { mutableStateOf(false) }
    var redirectTo by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    var ready by rememberSaveable { mutableStateOf(index < 0) }
    LifecycleStartEffect(store, index) {
        val job = scope.launch {
            if (ready || index < 0) return@launch
            val rule = try {
                store.load().inboundRules.getOrNull(index)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (rule == null) {
                onDone()
                return@launch
            }
            from = fieldValue(rule, RuleField.From)
            to = fieldValue(rule, RuleField.To)
            listId = fieldValue(rule, RuleField.ListId)
            subject = fieldValue(rule, RuleField.Subject)
            fileInto = rule.fileInto
            markRead = SystemFlag.Seen in rule.flags
            flagged = SystemFlag.Flagged in rule.flags
            redirectTo = rule.redirectTo
            discard = rule.discard
            ready = true
        }
        onStopOrDispose { job.cancel() }
    }
    val draft = draftRule(from, to, listId, subject, fileInto, markRead, flagged, redirectTo, discard)
    val canSave = draft.criteria.isNotEmpty() && hasFilterAction(draft)
    fun persist(nextIndex: Int, remove: Boolean) {
        if (!ready) return
        scope.launch {
            gate.withLock {
                if (!remove && !canSave) return@withLock
                val loaded = try {
                    store.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withLock
                }
                val next = loaded.inboundRules.toMutableList()
                if (remove) {
                    if (nextIndex !in next.indices) {
                        onDone()
                        return@withLock
                    }
                    next.removeAt(nextIndex)
                } else if (nextIndex in next.indices) {
                    next[nextIndex] = draft
                } else if (nextIndex < 0) {
                    next.add(draft)
                } else {
                    return@withLock
                }
                try {
                    store.save(loaded.copy(inboundRules = next))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withLock
                }
                onDone()
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        FilterField(stringResource(R.string.filter_from), from, ready) { from = it }
        FilterField(stringResource(R.string.filter_to), to, ready) { to = it }
        FilterField(stringResource(R.string.filter_list_id), listId, ready) { listId = it }
        FilterField(stringResource(R.string.filter_subject), subject, ready) { subject = it }
        FilterField(stringResource(R.string.filter_file_into), fileInto, ready) { fileInto = it }
        FilterCheck(stringResource(R.string.filter_mark_read), markRead, ready) { markRead = it }
        FilterCheck(stringResource(R.string.filter_flag), flagged, ready) { flagged = it }
        FilterField(stringResource(R.string.filter_redirect), redirectTo, ready) { redirectTo = it }
        FilterCheck(stringResource(R.string.filter_discard), discard, ready) { discard = it }
        Row {
            TextButton(onClick = { persist(index, remove = false) }, enabled = ready && canSave) {
                Text(stringResource(R.string.filter_save))
            }
            if (index >= 0) {
                TextButton(onClick = { persist(index, remove = true) }, enabled = ready) {
                    Text(stringResource(R.string.filter_delete))
                }
            }
        }
    }
}

@Composable
private fun FilterField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = true,
    )
}

@Composable
private fun FilterCheck(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChecked,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun criterionLabel(rule: InboundRule): String {
    val first = rule.criteria.firstOrNull() ?: return ""
    val name = when (first.field) {
        RuleField.From -> stringResource(R.string.filter_from)
        RuleField.To -> stringResource(R.string.filter_to)
        RuleField.ListId -> stringResource(R.string.filter_list_id)
        RuleField.Subject -> stringResource(R.string.filter_subject)
    }
    return "$name ${first.value}"
}

@Composable
private fun actionLabel(rule: InboundRule): String {
    val parts = ArrayList<String>(5)
    if (rule.fileInto.isNotBlank()) parts.add(stringResource(R.string.filter_file_into_value, rule.fileInto))
    if (SystemFlag.Seen in rule.flags) parts.add(stringResource(R.string.filter_mark_read))
    if (SystemFlag.Flagged in rule.flags) parts.add(stringResource(R.string.filter_flag))
    if (rule.redirectTo.isNotBlank()) parts.add(stringResource(R.string.filter_redirect_value, rule.redirectTo))
    if (rule.discard) parts.add(stringResource(R.string.filter_discard))
    return parts.joinToString(", ")
}

private fun seedValue(seed: List<RuleCriterion>, field: RuleField): String =
    seed.firstOrNull { it.field == field }?.value.orEmpty()

private fun fieldValue(rule: InboundRule, field: RuleField): String =
    rule.criteria.firstOrNull { it.field == field }?.value.orEmpty()

private fun draftRule(
    from: String,
    to: String,
    listId: String,
    subject: String,
    fileInto: String,
    markRead: Boolean,
    flagged: Boolean,
    redirectTo: String,
    discard: Boolean,
): InboundRule {
    val flags = linkedSetOf<SystemFlag>()
    if (markRead) flags.add(SystemFlag.Seen)
    if (flagged) flags.add(SystemFlag.Flagged)
    return InboundRule(
        criteria = seedCriteria(from, to, listId, subject),
        fileInto = fileInto,
        flags = flags,
        redirectTo = redirectTo,
        discard = discard,
    )
}

private fun hasFilterAction(rule: InboundRule): Boolean {
    return rule.fileInto.isNotBlank() ||
        rule.flags.isNotEmpty() ||
        rule.redirectTo.isNotBlank() ||
        rule.discard
}
