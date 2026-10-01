package org.dlang.liveimap.ui.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.dlang.liveimap.session.ComposeSeed

@Suppress("UNUSED_PARAMETER")
@Composable
fun MessageReaderScreen(
    mailbox: String,
    uid: Long,
    onCompose: (ComposeSeed) -> Unit,
) {
    Text("Reader")
}
