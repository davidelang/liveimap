package org.dlang.liveimap.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun DebugReportReview(
    lines: List<String>,
    onLines: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.navigationBars)
                            .union(WindowInsets.displayCutout),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Debug report", style = MaterialTheme.typography.titleLarge)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    lines.forEachIndexed { index, line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = line.ifEmpty { " " },
                                modifier = Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = {
                                onLines(lines.filterIndexed { i, _ -> i != index })
                            }) { Text("Remove line") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        copyDebugReport(context, lines)
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        shareDebugReport(context, lines)
                    }) { Text("Share") }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun copyDebugReport(context: Context, lines: List<String>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("LiveIMAP debug report", lines.joinToString("\n")))
}

private fun shareDebugReport(context: Context, lines: List<String>) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    }
    context.startActivity(Intent.createChooser(send, "Share debug report"))
}
