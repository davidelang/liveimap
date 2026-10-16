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
import org.dlang.liveimap.ui.ObscuredTouchFilter
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.dlang.liveimap.R

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
        ObscuredTouchFilter()
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
                Text(stringResource(R.string.debug_report_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.traffic_share_warning), color = MaterialTheme.colorScheme.error)
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
                            }) { Text(stringResource(R.string.debug_remove_line)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        copyDebugReport(context, lines)
                    }) { Text(stringResource(R.string.debug_copy)) }
                    TextButton(onClick = {
                        shareDebugReport(context, lines)
                    }) { Text(stringResource(R.string.debug_share)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.debug_dismiss)) }
                }
            }
        }
    }
}

private fun copyDebugReport(context: Context, lines: List<String>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.debug_clipboard), lines.joinToString("\n"))
    // F56: keep the report out of clipboard previews and keyboard suggestions.
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean(if (android.os.Build.VERSION.SDK_INT >= 33) android.content.ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
}

private fun shareDebugReport(context: Context, lines: List<String>) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.debug_share_title)))
}
