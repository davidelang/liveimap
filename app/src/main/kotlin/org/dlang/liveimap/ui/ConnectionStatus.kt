package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dlang.liveimap.R
import org.dlang.liveimap.session.ConnectionState

@Composable
fun ConnectionStatusStrip(state: ConnectionState, onRetry: () -> Unit) {
    when (state) {
        ConnectionState.Connected,
        ConnectionState.Suspended,
        -> Unit
        ConnectionState.Reconnecting -> {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Text(
                    text = stringResource(R.string.status_reconnecting),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        is ConnectionState.Lost -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.reader_retry))
                }
            }
        }
    }
}

@Composable
fun DebugStatusIcon(text: String) {
    if (text.isEmpty()) return
    Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = text,
        modifier = Modifier.size(24.dp),
    )
}
