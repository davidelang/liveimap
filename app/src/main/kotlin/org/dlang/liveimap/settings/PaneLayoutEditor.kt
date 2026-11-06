package org.dlang.liveimap.settings
import org.dlang.liveimap.i18n.S

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dlang.liveimap.window.addRule
import org.dlang.liveimap.window.delete
import org.dlang.liveimap.window.editFactory
import org.dlang.liveimap.window.LayoutAutomatic
import org.dlang.liveimap.window.LayoutCatalog
import org.dlang.liveimap.window.LayoutCriterionExternal
import org.dlang.liveimap.window.LayoutCriterionLandscape
import org.dlang.liveimap.window.LayoutCriterionLeft
import org.dlang.liveimap.window.LayoutCriterionPortrait
import org.dlang.liveimap.window.LayoutCriterionRight
import org.dlang.liveimap.window.LayoutCriterionUpright
import org.dlang.liveimap.window.LayoutCriterionUpside
import org.dlang.liveimap.window.LayoutDrawerPinned
import org.dlang.liveimap.window.LayoutFolderTreePinned
import org.dlang.liveimap.window.LayoutIndexBesideMessage
import org.dlang.liveimap.window.LayoutListAboveMessage
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutPanel
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.UnusedPanel
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.decodeLayoutCatalog
import org.dlang.liveimap.window.encodeLayoutCatalog
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.moveRuleEarlier
import org.dlang.liveimap.window.offeredIds
import org.dlang.liveimap.window.removeRule
import org.dlang.liveimap.window.replaceCustom
import org.dlang.liveimap.window.resolve
import org.dlang.liveimap.window.restoreDefaults
import org.dlang.liveimap.window.freshPanelId
import org.dlang.liveimap.window.layoutRecordProblem
import org.dlang.liveimap.window.removePanel
import org.dlang.liveimap.window.splitPanel
import org.dlang.liveimap.window.withPin

/** Settings builder for the layout catalog. [onSave] gets the encoded catalog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaneLayoutEditor(saved: String, onSave: (String) -> Unit) {
    var catalog by remember(saved) { mutableStateOf(decodeLayoutCatalog(saved)) }
    var picked by remember(saved) { mutableStateOf<String?>(null) }
    var rejected by remember(saved) { mutableStateOf(false) }
    var addingRule by remember(saved) { mutableStateOf(false) }
    var ruleCriterion by remember(saved) { mutableStateOf<String?>(null) }
    fun commit(next: LayoutCatalog) {
        catalog = next
        onSave(encodeLayoutCatalog(next))
    }
    fun edit(transform: (LayoutRecord) -> LayoutRecord) {
        val base = catalog.custom.firstOrNull { it.id == catalog.selectedId }?.record ?: catalog.resolve()
        val updated = transform(base)
        if (layoutRecordProblem(updated) != null) {
            rejected = true
            return
        }
        rejected = false
        var next = catalog
        val factoryId = next.selectedId.ifEmpty { LayoutAutomatic }
        if (factoryRecord(factoryId) != null && next.custom.none { it.id == next.selectedId }) {
            next = next.editFactory(factoryId)
        }
        if (next.custom.any { it.id == next.selectedId }) {
            next = next.replaceCustom(next.selectedId, updated)
        }
        commit(next)
    }
    val selected = catalog.selectedId.ifEmpty { LayoutAutomatic }
    val record = catalog.resolve()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            S("Tap a panel, then split it side by side or top and bottom, remove it, or choose what it shows."),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in catalog.offeredIds()) {
                FilterChip(
                    selected = id == selected || (catalog.selectedId.isEmpty() && id == LayoutAutomatic),
                    onClick = {
                        picked = null
                        rejected = false
                        commit(catalog.copy(selectedId = id))
                    },
                    label = { Text(layoutTitle(id)) },
                )
            }
        }
        for ((index, rule) in catalog.rules.withIndex()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(criterionLabel(rule.criterion), Modifier.weight(1f))
                Text(layoutTitle(rule.layoutId), Modifier.weight(1f))
                if (index > 0) {
                    OutlinedButton(onClick = { commit(catalog.moveRuleEarlier(index)) }) {
                        Text(S("Move earlier"))
                    }
                }
                OutlinedButton(onClick = { commit(catalog.removeRule(index)) }) {
                    Text(S("Remove rule"))
                }
            }
        }
        OutlinedButton(
            onClick = {
                addingRule = !addingRule
                ruleCriterion = null
            },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("+") }
        val pendingCriterion = ruleCriterion
        if (addingRule && pendingCriterion == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (token in layoutCriteria) {
                    FilterChip(
                        selected = false,
                        onClick = { ruleCriterion = token },
                        label = { Text(criterionLabel(token)) },
                    )
                }
            }
        }
        if (addingRule && pendingCriterion != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (id in catalog.offeredIds()) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            commit(catalog.addRule(pendingCriterion, id))
                            addingRule = false
                            ruleCriterion = null
                        },
                        label = { Text(layoutTitle(id)) },
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
        ) {
            val root = record.root
            if (root == null) {
                Text(S("Message"), Modifier.align(Alignment.Center))
            } else {
                RecordPreview(root, picked) { picked = it }
            }
        }
        if (rejected) {
            Text(
                S("Each panel type can appear only once. Not saved yet."),
                color = MaterialTheme.colorScheme.error,
            )
        }
        val panelId = picked
        if (panelId != null && record.panels.any { it.id == panelId }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    edit { current ->
                        val root = current.root ?: return@edit current
                        val added = LayoutPanel(freshPanelId(current))
                        current.copy(root = root.splitPanel(panelId, SplitDirection.Beside, added))
                    }
                }) { Text(S("Split side by side")) }
                OutlinedButton(onClick = {
                    edit { current ->
                        val root = current.root ?: return@edit current
                        val added = LayoutPanel(freshPanelId(current))
                        current.copy(root = root.splitPanel(panelId, SplitDirection.Above, added))
                    }
                }) { Text(S("Split top and bottom")) }
                if (record.panels.size > 1) {
                    OutlinedButton(onClick = {
                        picked = null
                        edit { current ->
                            val root = current.root ?: return@edit current
                            current.copy(root = root.removePanel(panelId))
                        }
                    }) { Text(S("Remove panel")) }
                }
                OutlinedButton(onClick = {
                    edit { current ->
                        val root = current.root ?: return@edit current
                        current.copy(root = root.nudge(panelId, 0.05f))
                    }
                }) { Text(S("Wider")) }
                OutlinedButton(onClick = {
                    edit { current ->
                        val root = current.root ?: return@edit current
                        current.copy(root = root.nudge(panelId, -0.05f))
                    }
                }) { Text(S("Narrower")) }
            }
            val pin = record.panels.firstOrNull { it.id == panelId }?.pin
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pin == null,
                    onClick = {
                        edit { current ->
                            val root = current.root ?: return@edit current
                            current.copy(root = root.withPin(panelId, null))
                        }
                    },
                    label = { Text(S("Unpinned")) },
                )
                for (kind in WindowLayerKind.entries) {
                    if (kind == WindowLayerKind.Screen) continue
                    FilterChip(
                        selected = pin == kind,
                        onClick = {
                            edit { current ->
                                val root = current.root ?: return@edit current
                                current.copy(root = root.withPin(panelId, kind))
                            }
                        },
                        label = { Text(layerTitle(kind)) },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { edit { it.copy(unpinnedKeep = (it.unpinnedKeep - 1).coerceAtLeast(0)) } }) {
                Text(S("Fewer layers"))
            }
            Text(S("Layers kept") + " ${record.unpinnedKeep}", Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { edit { it.copy(unpinnedKeep = it.unpinnedKeep + 1) } }) {
                Text(S("More layers"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { edit { it.copy(coverPanels = (it.coverPanels - 1).coerceAtLeast(0)) } }) {
                Text(S("Fewer cover panels"))
            }
            Text("${record.coverPanels}", Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { edit { it.copy(coverPanels = it.coverPanels + 1) } }) {
                Text(S("More cover panels"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(S("Collapse unused panels"), Modifier.weight(1f))
            Switch(
                checked = record.unused == UnusedPanel.Collapse,
                onCheckedChange = { on ->
                    edit { it.copy(unused = if (on) UnusedPanel.Collapse else UnusedPanel.StayEmpty) }
                },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (catalog.selectedId.isNotEmpty()) {
                OutlinedButton(onClick = {
                    picked = null
                    rejected = false
                    commit(catalog.delete(catalog.selectedId.ifEmpty { selected }))
                }) { Text(S("Delete")) }
            }
            OutlinedButton(onClick = {
                picked = null
                rejected = false
                commit(catalog.restoreDefaults())
            }) { Text(S("Restore defaults")) }
        }
    }
}

private val layoutCriteria = listOf(
    LayoutCriterionPortrait,
    LayoutCriterionLandscape,
    LayoutCriterionUpright,
    LayoutCriterionLeft,
    LayoutCriterionUpside,
    LayoutCriterionRight,
    LayoutCriterionExternal,
)

@Composable
private fun criterionLabel(token: String): String = when (token) {
    LayoutCriterionPortrait -> S("Portrait")
    LayoutCriterionLandscape -> S("Landscape")
    LayoutCriterionUpright -> S("Upright")
    LayoutCriterionLeft -> S("Turned left")
    LayoutCriterionUpside -> S("Upside down")
    LayoutCriterionRight -> S("Turned right")
    LayoutCriterionExternal -> S("External display")
    else -> token
}

@Composable
private fun layoutTitle(id: String): String = when (id) {
    LayoutAutomatic -> stringResource(SettingsTreeGenerated.resource("settings_pane_auto"))
    LayoutIndexBesideMessage -> S("Index beside message")
    LayoutListAboveMessage -> S("List above message")
    LayoutFolderTreePinned -> S("Folder tree pinned")
    LayoutDrawerPinned -> S("Drawer pinned")
    else -> id
}

@Composable
private fun layerTitle(kind: WindowLayerKind): String = when (kind) {
    WindowLayerKind.Drawer -> S("Accounts drawer")
    WindowLayerKind.FolderTree -> S("Folders")
    WindowLayerKind.FolderIndex -> S("Message list")
    WindowLayerKind.SearchIndex -> S("Search")
    WindowLayerKind.Message -> S("Message")
    WindowLayerKind.Screen -> kind.name
}

@Composable
private fun RecordPreview(node: LayoutNode, picked: String?, onPick: (String) -> Unit) {
    when (node) {
        is LayoutNode.Panel -> {
            val chosen = picked == node.panel.id
            Box(
                Modifier.fillMaxSize().padding(2.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(node.panel.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    node.panel.pin?.let { layerTitle(it) } ?: S("Unpinned"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        is LayoutNode.Split -> if (node.direction == SplitDirection.Beside) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.share).fillMaxSize()) { RecordPreview(node.first, picked, onPick) }
                Box(Modifier.weight(1f - node.share).fillMaxSize()) { RecordPreview(node.second, picked, onPick) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.share).fillMaxSize()) { RecordPreview(node.first, picked, onPick) }
                Box(Modifier.weight(1f - node.share).fillMaxSize()) { RecordPreview(node.second, picked, onPick) }
            }
        }
    }
}

private fun LayoutNode.nudge(panelId: String, delta: Float): LayoutNode = when (this) {
    is LayoutNode.Panel -> this
    is LayoutNode.Split -> when {
        first is LayoutNode.Panel && first.panel.id == panelId -> copy(share = (share + delta).coerceIn(0.1f, 0.9f))
        second is LayoutNode.Panel && second.panel.id == panelId -> copy(share = (share - delta).coerceIn(0.1f, 0.9f))
        else -> copy(first = first.nudge(panelId, delta), second = second.nudge(panelId, delta))
    }
}
