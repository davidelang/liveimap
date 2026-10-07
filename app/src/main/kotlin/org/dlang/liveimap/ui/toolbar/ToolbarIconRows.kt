package org.dlang.liveimap.ui.toolbar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ToolbarIconSlot = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
fun toolbarExpandedHeight(rows: Int) =
    if (rows <= 1) {
        TopAppBarDefaults.TopAppBarExpandedHeight
    } else {
        TopAppBarDefaults.TopAppBarExpandedHeight + ToolbarIconSlot * (rows - 1)
    }

@Composable
fun RowScope.ToolbarIconRows(
    maxRows: Int,
    onRows: (Int) -> Unit,
    icons: List<@Composable () -> Unit>,
) {
    val scrollState = rememberScrollState()
    if (icons.isEmpty()) {
        SideEffect { onRows(1) }
        return
    }
    BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
        val slots = if (constraints.hasBoundedWidth) {
            (maxWidth / ToolbarIconSlot).toInt().coerceAtLeast(1)
        } else {
            icons.size
        }
        val pack = packToolbar(icons.size, slots, maxRows)
        val rowCount = if (pack.rowSizes.isEmpty()) 1 else pack.rowSizes.size
        SideEffect { onRows(rowCount) }
        Column(modifier = Modifier.height(ToolbarIconSlot * rowCount)) {
            var cursor = 0
            for (rowIndex in pack.rowSizes.indices) {
                val size = pack.rowSizes[rowIndex]
                val from = cursor
                cursor += size
                val rowIcons = icons.subList(from, cursor)
                val scroll = rowIndex == pack.rowSizes.lastIndex && pack.overflowsCap
                Row(
                    modifier = if (scroll) {
                        Modifier
                            .height(ToolbarIconSlot)
                            .width(ToolbarIconSlot * slots)
                            .horizontalScroll(scrollState)
                    } else {
                        Modifier.height(ToolbarIconSlot)
                    },
                ) {
                    rowIcons.forEachIndexed { index, icon ->
                        key(from + index) { icon() }
                    }
                }
            }
        }
    }
}
