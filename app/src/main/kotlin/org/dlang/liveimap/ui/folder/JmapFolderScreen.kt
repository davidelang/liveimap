package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.dlang.liveimap.jmap.JmapFolderRow
import org.dlang.liveimap.jmap.jmapFolderLabel

@Composable
fun JmapFolderList(
    rows: List<JmapFolderRow>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column {
        rows.forEachIndexed { index, row ->
            Button(
                onClick = {
                    if (row.hasChildren) onToggle(row.id) else onOpen(row.id)
                },
            ) {
                Text(jmapFolderLabel(rows, index))
            }
        }
    }
}
