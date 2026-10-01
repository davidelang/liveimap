package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun FlagMarks(flags: Set<String>) {
    val marks = if (flags.isEmpty()) {
        emptyList()
    } else {
        buildList {
            if ("\\Seen" !in flags) add("\\Seen absent")
            if ("\\Flagged" in flags) add("\\Flagged")
            if ("\\Answered" in flags) add("\\Answered")
            if ("\\Deleted" in flags) add("\\Deleted")
        }
    }
    if (marks.isEmpty()) {
        Box(Modifier.requiredSize(0.dp))
    } else {
        Text(
            text = marks.joinToString(" "),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
