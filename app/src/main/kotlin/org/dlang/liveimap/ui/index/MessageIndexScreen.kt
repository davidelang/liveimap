package org.dlang.liveimap.ui.index

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.dlang.liveimap.session.ComposeSeed

@Suppress("UNUSED_PARAMETER")
@Composable
fun MessageIndexScreen(
    mailbox: String,
    onOpen: (Long) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
) {
    Text("Index")
}
