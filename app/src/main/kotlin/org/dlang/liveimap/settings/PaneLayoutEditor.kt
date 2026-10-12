package org.dlang.liveimap.settings

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
import org.dlang.liveimap.R

@Composable
fun paneContentLabel(content: PaneContent): String = stringResource(
    when (content) {
        PaneContent.Folders -> R.string.layout_panel_folders
        PaneContent.MessageList -> R.string.layout_panel_list
        PaneContent.Message -> R.string.layout_panel_message
        PaneContent.AccountDrawer -> R.string.layout_panel_drawer
    },
)

/** Settings editor for the custom panel layout. [onSave] gets "" for Auto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaneLayoutEditor(saved: String, textZoom: Int, onSave: (String) -> Unit) {
    val configuration = LocalConfiguration.current
    val auto = autoPaneLayout(planLayout(configuration.screenWidthDp, configuration.screenHeightDp, configuration.fontScale * textZoom / 100f))
    var tree by remember(saved) { mutableStateOf(decodePaneLayout(saved)) }
    var selected by remember(saved) { mutableStateOf<List<Int>?>(null) }
    val custom = tree != null
    fun commit(next: PaneNode?) {
        tree = next
        if (next == null) onSave("") else if (paneLayoutProblem(next) == null) onSave(next.encode())
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layout_custom), Modifier.weight(1f))
            Switch(
                checked = custom,
                onCheckedChange = { on -> selected = null; commit(if (on) auto else null) },
                modifier = Modifier.testTag("layout-custom-switch"),
            )
        }
        val shown = tree ?: auto
        Text(
            if (custom) stringResource(R.string.layout_custom_hint) else stringResource(R.string.layout_auto_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)),
        ) {
            LayoutPreview(shown, emptyList(), if (custom) selected else null) { path -> if (custom) selected = path }
        }
        val current = tree
        val sel = selected
        if (current != null) {
            paneLayoutProblem(current)?.let { problem ->
                Text(
                    stringResource(
                        when (problem) {
                            PaneLayoutProblem.NoMessageList -> R.string.layout_problem_no_list
                            PaneLayoutProblem.Duplicate -> R.string.layout_problem_duplicate
                            PaneLayoutProblem.MessageNotBesideList -> R.string.layout_problem_message
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (sel != null) {
                val used = current.leaves()
                val unused = PaneContent.entries.firstOrNull { it !in used } ?: PaneContent.Folders
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { commit(current.splitAt(sel, SplitAxis.Horizontal, unused)) }) {
                        Text(stringResource(R.string.layout_split_side))
                    }
                    OutlinedButton(onClick = { commit(current.splitAt(sel, SplitAxis.Vertical, unused)) }) {
                        Text(stringResource(R.string.layout_split_stack))
                    }
                    if (sel.isNotEmpty()) {
                        OutlinedButton(onClick = { selected = null; commit(current.removeAt(sel)) }) {
                            Text(stringResource(R.string.layout_remove))
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
            OutlinedButton(onClick = { selected = null; commit(auto) }) { Text(stringResource(R.string.layout_reset_auto)) }
        }
    }
}

@Composable
private fun LayoutPreview(node: PaneNode, path: List<Int>, selected: List<Int>?, onPick: (List<Int>) -> Unit) {
    when (node) {
        is PaneNode.Leaf -> {
            val label = paneContentLabel(node.content)
            val chosen = selected == path
            Box(
                Modifier.fillMaxSize().padding(2.dp)
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(path) }
                    .semantics { contentDescription = "layout-panel-${path.joinToString("")}-$label" },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
        is PaneNode.Split -> if (node.axis == SplitAxis.Horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.ratio).fillMaxSize()) { LayoutPreview(node.first, path + 0, selected, onPick) }
                Box(Modifier.weight(1f - node.ratio).fillMaxSize()) { LayoutPreview(node.second, path + 1, selected, onPick) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(node.ratio).fillMaxWidth()) { LayoutPreview(node.first, path + 0, selected, onPick) }
                Box(Modifier.weight(1f - node.ratio).fillMaxWidth()) { LayoutPreview(node.second, path + 1, selected, onPick) }
            }
        }
    }
}
