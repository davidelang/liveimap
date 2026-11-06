package org.dlang.liveimap.settings
import org.dlang.liveimap.i18n.S

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun paneContentLabel(content: PaneContent): String = when (content) {
        PaneContent.Folders -> S("Folders")
        PaneContent.MessageList -> S("Message list")
        PaneContent.Message -> S("Message")
        PaneContent.AccountDrawer -> S("Accounts drawer")
    }

/** Settings editor for the custom panel layout. [onSave] gets "" for Auto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaneLayoutEditor(saved: String, persist: Boolean = true, onSave: (String) -> Unit) {
    val configuration = LocalConfiguration.current
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val auto = autoPaneLayout(planLayout(configuration.screenWidthDp, configuration.screenHeightDp, fontScale))
    var tree by remember(saved, persist) { mutableStateOf(decodePaneLayout(saved)) }
    var selected by remember(saved, persist) { mutableStateOf<List<Int>?>(null) }
    val custom = tree != null
    fun commit(next: PaneNode?) {
        tree = next
        if (persist) {
            if (next == null) onSave("") else if (paneLayoutProblem(next) == null) onSave(next.encode())
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(SettingsTreeGenerated.resource("layout_custom")), Modifier.weight(1f))
            Switch(
                checked = custom,
                onCheckedChange = { on -> selected = null; commit(if (on) auto else null) },
                modifier = Modifier.testTag("layout-custom-switch"),
            )
        }
        val shown = tree ?: auto
        Text(
            if (custom) S("Tap a panel, then split it side by side or top and bottom, remove it, or choose what it shows.") else S("Off: panels follow the screen size (Auto). This is the Auto arrangement for this screen."),
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
        ) {
            LayoutPreview(shown, emptyList(), if (custom) selected else null, custom) { path -> if (custom) selected = path }
        }
        val current = tree
        val sel = selected
        if (current != null) {
            paneLayoutProblem(current)?.let { problem ->
                Text(
                    when (problem) {
                            PaneLayoutProblem.NoMessageList -> S("The layout needs a message list. Not saved yet.")
                            PaneLayoutProblem.Duplicate -> S("Each panel type can appear only once. Not saved yet.")
                            PaneLayoutProblem.MessageNotBesideList -> S("The message panel must share a split with the message list. Not saved yet.")
                        },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (sel != null) {
                val used = current.leaves()
                val unused = PaneContent.entries.firstOrNull { it !in used } ?: PaneContent.Folders
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { commit(current.splitAt(sel, SplitAxis.Horizontal, unused)) }) {
                        Text(S("Split side by side"))
                    }
                    OutlinedButton(onClick = { commit(current.splitAt(sel, SplitAxis.Vertical, unused)) }) {
                        Text(S("Split top and bottom"))
                    }
                    if (sel.isNotEmpty()) {
                        OutlinedButton(onClick = { selected = null; commit(current.removeAt(sel)) }) {
                            Text(S("Remove panel"))
                        }
                    }
                }
                val leaf = current.leafPaths().firstOrNull { it.first == sel }?.second
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (content in PaneContent.entries) {
                        FilterChip(
                            selected = leaf == content,
                            onClick = { commit(current.setContent(sel, content)) },
                            label = { Text(paneContentLabel(content)) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { selected = null; commit(auto) }) { Text(S("Start from Auto")) }
        }
    }
}

@Composable
private fun LayoutPreview(
    node: PaneNode,
    path: List<Int>,
    selected: List<Int>?,
    pickable: Boolean,
    onPick: (List<Int>) -> Unit,
) {
    when (node) {
        is PaneNode.Leaf -> {
            val label = paneContentLabel(node.content)
            val chosen = selected == path
            Box(
                Modifier.fillMaxSize().padding(2.dp)
                    .then(
                        if (pickable) {
                            Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
                        } else {
                            Modifier
                        },
                    )
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(path) }
                    .semantics { contentDescription = "layout-panel-${path.joinToString("")}-$label" },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
        is PaneNode.Split -> if (node.axis == SplitAxis.Horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.ratio).fillMaxSize()) {
                    LayoutPreview(node.first, path + 0, selected, pickable, onPick)
                }
                Box(Modifier.weight(1f - node.ratio).fillMaxSize()) {
                    LayoutPreview(node.second, path + 1, selected, pickable, onPick)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.ratio).fillMaxWidth()) {
                    LayoutPreview(node.first, path + 0, selected, pickable, onPick)
                }
                Box(Modifier.weight(1f - node.ratio).fillMaxWidth()) {
                    LayoutPreview(node.second, path + 1, selected, pickable, onPick)
                }
            }
        }
    }
}
